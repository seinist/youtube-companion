@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaDescriptionCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment

/**
 * Fallback AA projection player: loads the YouTube watch page in a WebView.
 * Used when ExoPlayer/NewPipe extraction fails or Settings force `webview`.
 */
class WebViewFallbackFragment : Fragment() {

    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentVideo: MediaDescriptionCompat? = null
    private var readyReported = false
    private var retired = false
    private var readyAttempts = 0
    private val requestId: Long get() = arguments?.getLong("cast_request_id", 0L) ?: 0L
    private val sessionOwner = object : VideoSessionBridge.Controller {
        override fun play() { webView?.evaluateJavascript("document.querySelector('video')?.play()", null) }
        override fun pause() { webView?.evaluateJavascript("document.querySelector('video')?.pause()", null) }
        override fun stopPlayback() { pause(); (context as? VideoHost)?.leavePlayback(true) }
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun playMediaId(videoId: String) = Unit
        override fun playQueue(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) = Unit
        override fun isPlaying() = false
        override fun getPositionMs() = 0L
        override fun getDurationMs() = 0L
        override fun onOwnershipLost() {
            retired = true
            mainHandler.removeCallbacksAndMessages(null)
            pause()
            webView?.onPause()
        }
    }

    private val readyPoll = object : Runnable {
        override fun run() {
            val browser = webView ?: return
            if (retired || readyReported || ++readyAttempts > 80) return
            browser.evaluateJavascript("(document.querySelector('video')?.readyState || 0)") { result ->
                if (retired || webView !== browser || readyReported) return@evaluateJavascript
                if ((result.toIntOrNull() ?: 0) >= 2) {
                    readyReported = true
                    VideoSessionBridge.activate(sessionOwner, VideoSessionBridge.Role.HEAD_UNIT)
                    val video = currentVideo ?: return@evaluateJavascript
                    CarPlaybackState.markActive(CarPlaybackState.Owner.PROJECTION, video.mediaId)
                    publishVideo(video)
                    video.mediaId?.let { (context as? VideoHost)?.onHeadUnitPlayerReady(it, requestId) }
                    val startSeconds = (arguments?.getLong("start_position_ms", 0L) ?: 0L).coerceAtLeast(0L) / 1000.0
                    browser.settings.mediaPlaybackRequiresUserGesture = false
                    browser.evaluateJavascript("{ const v=document.querySelector('video'); if(v){ if($startSeconds>0)v.currentTime=$startSeconds; v.play(); } }", null)
                } else mainHandler.postDelayed(this, 250L)
            }
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_webview_video, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val status = view.findViewById<TextView>(R.id.webview_status)
        val root = view as FrameLayout
        view.findViewById<Button>(R.id.webview_back).setOnClickListener {
            (context as? VideoHost)?.showBrowse()
        }
        webView = view.findViewById<WebView>(R.id.web_view).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            WebView.setWebContentsDebuggingEnabled(false)
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    if (isAllowedNavigation(request.url)) return false
                    status.visibility = View.VISIBLE
                    status.setText(R.string.video_error_stream)
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    mainHandler.removeCallbacks(readyPoll)
                    mainHandler.post(readyPoll)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest,
                    error: WebResourceError?
                ) {
                    if (!request.isForMainFrame) return
                    status.visibility = View.VISIBLE
                    status.setText(R.string.video_error_stream)
                    RemoteCastBus.projectionFailed(requestId)
                    if (VideoSessionBridge.isOwner(sessionOwner)) CarPlaybackState.clear(CarPlaybackState.Owner.PROJECTION)
                    NowPlayingBus.clear(owner = sessionOwner)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    visibility = View.GONE
                    root.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                override fun onHideCustomView() {
                    customView?.let { root.removeView(it) }
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    visibility = View.VISIBLE
                }
            }
        }

        val video = arguments?.let { BundleCompat.getParcelable(it, ARG_VIDEO, MediaDescriptionCompat::class.java) }
        val videoId = video?.mediaId
        if (videoId.isNullOrBlank()) {
            status.visibility = View.VISIBLE
            status.text = getString(R.string.video_error_stream)
            return
        }
        status.visibility = View.GONE
        currentVideo = video
        webView?.loadUrl("https://www.youtube.com/watch?v=$videoId")
    }

    private fun publishVideo(video: MediaDescriptionCompat) {
        val videoId = video.mediaId ?: return
        NowPlayingBus.update(
            videoId = videoId,
            title = video.title?.toString() ?: videoId,
            channelTitle = video.subtitle?.toString(),
            thumbnailUrl = video.iconUri?.toString(),
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            controllable = false,
            isBuffering = false,
            presentation = PlaybackPresentation.VIDEO,
            owner = sessionOwner
        )
    }

    private fun isAllowedNavigation(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase().orEmpty()
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "google.com" || host.endsWith(".google.com")
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroyView() {
        retired = true
        mainHandler.removeCallbacksAndMessages(null)
        if (VideoSessionBridge.isOwner(sessionOwner)) CarPlaybackState.clear(CarPlaybackState.Owner.PROJECTION)
        NowPlayingBus.clear(owner = sessionOwner)
        VideoSessionBridge.unregister(sessionOwner)
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_VIDEO = "video"

        fun newInstance(video: MediaDescriptionCompat, requestId: Long = 0L, startPositionMs: Long = 0L) = WebViewFallbackFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_VIDEO, video)
                putLong("cast_request_id", requestId)
                putLong("start_position_ms", startPositionMs)
            }
        }
    }
}
