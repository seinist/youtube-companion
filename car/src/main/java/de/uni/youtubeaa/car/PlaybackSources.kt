package de.uni.youtubeaa.car

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds ExoPlayer [MediaSource]s for YouTube CDN URLs.
 * Sends the User-Agent of the InnerTube client that issued the URL (see [requestHeadersFor]) and
 * uses [DefaultMediaSourceFactory] so progressive / DASH / HLS are picked automatically.
 * Live streams often use HLS/DASH URLs without a clear file extension — mime type hints help.
 */
@OptIn(markerClass = [UnstableApi::class])
object PlaybackSources {

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to BROWSER_USER_AGENT,
        "Referer" to "https://www.youtube.com/",
        "Origin" to "https://www.youtube.com"
    )

    /**
     * NewPipeExtractor takes stream URLs from the visionOS/Android/iOS InnerTube clients (`c=`).
     * googlevideo checks that the downloader looks like that client; the fixed Chrome UA used
     * before is a likely cause of the 403 on the first source load seen in head-unit logs.
     * Mirrors NewPipe's own YoutubeHttpDataSource: client UA for app clients, browser UA plus
     * Referer/Origin for web and manifest URLs.
     */
    /** Headers the players send for [url]; also used for the resolver's own manifest reads. */
    internal fun requestHeaders(url: String): Map<String, String> = requestHeadersFor(url)

    private fun requestHeadersFor(url: String): Map<String, String> {
        val userAgent = when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
            else -> return browserHeaders
        }
        return mapOf("User-Agent" to userAgent)
    }

    /** One factory per distinct header set (a handful at most), shared by all players. */
    private val mediaSourceFactories = ConcurrentHashMap<Map<String, String>, DefaultMediaSourceFactory>()

    private fun mediaSourceFactory(headers: Map<String, String>): DefaultMediaSourceFactory =
        mediaSourceFactories.getOrPut(headers) {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                // The UA travels in the headers: a factory-level UA would override them.
                .setUserAgent(null)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                // Per read, not per request: a slow but flowing download never hits it, while a
                // socket that stopped delivering fails after 10 s and is retried at once instead
                // of hanging for 30 s (head-unit log: 39 s stuck at 327 ms buffered).
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
            DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(SignedUrlErrorPolicy)
        }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * A signed googlevideo URL that answers 401/403/404/410 stays rejected; the default policy
     * still retried it six times with growing back-off, so head-unit logs showed ~17 s of
     * silence before the 403 reached onPlayerError and a re-resolve fixed it in ~2 s.
     * Fail those immediately; keep the retries for network/5xx errors.
     */
    private object SignedUrlErrorPolicy : DefaultLoadErrorHandlingPolicy(6) {
        private val REJECTED_URL_CODES = setOf(401, 403, 404, 410)

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val error = loadErrorInfo.exception
            if (error is HttpDataSource.InvalidResponseCodeException && error.responseCode in REJECTED_URL_CODES) {
                return C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /** `itag` of a YouTube CDN URL that answered with an HTTP error, for diagnostics. */
    fun rejectedItag(error: Throwable?): String? {
        var cause = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.dataSpec.uri.getQueryParameter("itag") ?: "?"
            }
            cause = cause.cause
        }
        return null
    }

    fun mediaSource(url: String): MediaSource =
        mediaSourceFactory(requestHeadersFor(url)).createMediaSource(mediaItem(url))

    fun merged(videoUrl: String, audioUrl: String): MediaSource =
        MergingMediaSource(mediaSource(videoUrl), mediaSource(audioUrl))

    /**
     * Live speed limits set on the item itself, which Media3 ranks above a DASH
     * ServiceDescription. No speeding up (the audible "too fast"), but slowing down stays allowed
     * so a thin live buffer slows briefly instead of stalling. Ignored for VOD.
     */
    private val steadyLiveSpeed = MediaItem.LiveConfiguration.Builder()
        .setMinPlaybackSpeed(PlayerFactory.LIVE_MIN_SPEED)
        .setMaxPlaybackSpeed(1f)
        .build()

    private fun mediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url)).setLiveConfiguration(steadyLiveSpeed)
        mimeTypeHint(url)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private fun mimeTypeHint(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.contains("/manifest/hls") || lower.contains("hls_playlist") ->
                MimeTypes.APPLICATION_M3U8
            lower.contains(".mpd") || lower.contains("/manifest/dash") || lower.contains("dash/live") ->
                MimeTypes.APPLICATION_MPD
            // Do not force codec MIME types for YouTube /videoplayback URLs. itag 140 is an
            // MP4/M4A container, not raw AAC, and WebM/Opus is not raw Opus either. Forcing
            // AUDIO_AAC/AUDIO_OPUS can select the wrong extractor and cause SOURCE_ERROR.
            // ProgressiveMediaSource can sniff these containers correctly.
            else -> null
        }
    }
}
