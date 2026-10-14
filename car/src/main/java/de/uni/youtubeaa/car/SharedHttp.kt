package de.uni.youtubeaa.car

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
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

    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
}
