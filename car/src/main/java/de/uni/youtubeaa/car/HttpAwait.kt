package de.uni.youtubeaa.car

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpBytes(val code: Int, val bytes: ByteArray) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Cancelling a UI request closes its socket as well as cancelling its coroutine. */
internal suspend fun Call.awaitBytes(maxBytes: Int = 12 * 1024 * 1024): HttpBytes =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = it.body
                        if (body.contentLength() > maxBytes) throw IOException("Response too large")
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive) return
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > maxBytes) throw IOException("Response too large")
                                output.write(buffer, 0, count)
                            }
                        }
                        HttpBytes(it.code, output.toByteArray())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
