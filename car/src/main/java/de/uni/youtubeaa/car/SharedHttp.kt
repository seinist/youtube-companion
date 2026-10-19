package de.uni.youtubeaa.car

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.TimeUnit

/** Shared OkHttp transport resources for the companion + AA process. */
object SharedHttp {
    private val dispatcher = Dispatcher().apply {
        // Keep enough room for playback/auth, but avoid a burst of background requests creating
        // a large thread pool while the projected decoder is already busy.
        maxRequests = 16
        maxRequestsPerHost = 6
    }
    private val connectionPool = ConnectionPool(6, 5, TimeUnit.MINUTES)

    /**
     * [subsystem] fixes the data-usage bucket for a single-purpose client (images, auth …);
     * null classifies each request by host and path (NewPipe serves search, lists and watch pages
     * through one downloader). See [DataUsageStats.subsystemOf].
     */
    fun builder(subsystem: DataUsageStats.Subsystem? = null): OkHttpClient.Builder = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        // Network interceptor: sees the bytes as transferred (still gzip-compressed), retries
        // and redirects each count once.
        .addNetworkInterceptor(ByteCountingInterceptor(subsystem))

    private class ByteCountingInterceptor(private val subsystem: DataUsageStats.Subsystem?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val bucket = DataUsageStats.subsystemOf(request.url.host, request.url.encodedPath, subsystem)
            val sent = (request.body?.contentLength()?.coerceAtLeast(0L) ?: 0L) + request.headers.byteCount()
            val response = chain.proceed(request)
            val headerBytes = response.headers.byteCount()
            val body = response.body
            return response.newBuilder()
                .body(CountingBody(body) { received -> DataUsageStats.recordNetwork(bucket, sent + headerBytes + received) })
                .build()
        }

        private fun okhttp3.Headers.byteCount(): Long {
            var total = 0L
            for (i in 0 until size) total += name(i).length + value(i).length + 4
            return total
        }
    }

    /** Reports the body bytes read once, when the body is exhausted or closed. */
    private class CountingBody(private val delegate: ResponseBody, private val onDone: (Long) -> Unit) : ResponseBody() {
        private val source = object : ForwardingSource(delegate.source()) {
            private var read = 0L
            private var reported = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0L) read += n else report()
                return n
            }

            override fun close() {
                report()
                super.close()
            }

            private fun report() {
                if (reported) return
                reported = true
                onDone(read)
            }
        }.buffer()

        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = source
    }
}
