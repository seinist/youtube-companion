@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaDescriptionCompat
import android.view.View
import android.widget.Button
import androidx.annotation.Keep
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.gms.car.CarActivityHost
import com.google.android.apps.auto.sdk.SearchCallback
import com.google.android.apps.auto.sdk.SearchItem
import de.uni.youtubeaa.car.hybrid.BrowseFacade
import de.uni.youtubeaa.car.hybrid.BrowseOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Host contract for projection fragments (CarActivity is not a FragmentActivity). */
interface VideoHost {
    fun playVideo(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat> = listOf(video))
    fun switchToWebViewFallback(video: MediaDescriptionCompat)
    fun onHeadUnitPlayerReady(videoId: String, requestId: Long)
    fun leavePlayback(stopSession: Boolean = false)
    fun showBrowse()
    fun showSettings()
    fun showSearch()
    /** Re-run a search from the browse screen's refresh action, bypassing the cache. */
    fun refreshSearch(query: String)
    fun exitBrowse()
}

/**
 * Projected AA UI host (aauto-sdk [CarActivity]).
 * Flow: park gate → browse (+ AA search box) → ExoPlayer / WebView.
 * Registriert sich bei [RemoteCastBus], damit die Smartphone-App fernsteuern kann, welches
 * Video auf dem Head-Unit-Display gezeigt wird.
 */
@Keep
class VideoCarActivity : CarActivity(), VideoHost, RemoteCastBus.Receiver {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settings: SettingsRepository
    private lateinit var browseFacade: BrowseFacade
    private var warningAccepted = false
    private var parkGate: ParkGate? = null
    private var parked: Boolean? = null
    private var searchDebounce: Runnable? = null
    /** True while the AA search box/input session should accept suggestion updates. */
    private var searchUiOpen = false
    private var pendingCastVideoId: String? = null
    private var pendingCastRequestId: Long? = null
    private var pendingCastStarted: (() -> Unit)? = null
    private var pendingPhoneHandoff: PlaybackEngine.ProjectionHandoff? = null
    private var activityDestroyed = false
    private var projectionHost: CarActivityHost? = null
    /** True when playback was opened from phone/native AA rather than this projection browser. */
    private var playbackOpenedExternally = false
    /** Natural end/explicit stop must not create a paused phone handoff while leaving projection. */
    private var suppressPhoneHandoffOnDestroy = false

    override fun attach(host: CarActivityHost) {
        super.attach(host)
        projectionHost = host
        refreshProjectionHostState("attach")
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        refreshProjectionHostState("onCreate")
        HeadUnitDiagnostics.event("VideoCarActivity", "onCreate savedState=${bundle != null}")
        // The projection can be launched directly by Android Auto, without any phone Activity
        // having initialized the process-wide playback engine first. Initialise it before any
        // optional phone -> HU handoff touches the shared ExoPlayer.
        runCatching { PlaybackEngine.ensureInit(this) }
            .onFailure { HeadUnitDiagnostics.error("VideoCarActivity", "PlaybackEngine initialization failed", it) }
            .getOrThrow()
        setContentView(R.layout.activity_car_video)
        settings = SettingsRepository(this)
        browseFacade = BrowseFacade(this)

        carUiController.statusBarController.apply {
            hideAppHeader()
            hideConnectivityLevel()
            setTitle(getString(R.string.car_projection_label))
        }
        carUiController.menuController.hideMenuButton()
        setupSearch()

        // Reverse handoff (phone -> Android Auto): pause the phone output, carry the exact
        // queue/position into the projected player, and continue immediately on the head unit.
        // An explicit cast request already waiting in RemoteCastBus keeps its existing behavior.
        if (!RemoteCastBus.hasPendingCast() && (PlaybackEngine.hasSession() || VideoSessionBridge.controller?.snapshot() != null)) {
            pendingPhoneHandoff = PlaybackEngine.prepareProjectionHandoff()
        }
        RemoteCastBus.register(this)

        (findViewById(R.id.park_warning_continue) as Button).setOnClickListener {
            if (settings.isCarAudioModeEnabled() || !settings.isRequireParked() || parked != false) {
                warningAccepted = true
                showContent()
            }
        }

        if (settings.isRequireParked() && !settings.isCarAudioModeEnabled()) {
            parkGate = ParkGate(this) { isParked ->
                parked = isParked
                mainHandler.post { refreshParkGate() }
            }.also { it.start() }
            // Soft gate until sensor reports; if already parked, skip warning.
            refreshParkGate()
        } else {
            showContent()
        }
    }

    /** Set by onResume/onPause; read from RemoteCastBus on the main thread. */
    @Volatile
    private var inForeground = false

    override fun isInForeground(): Boolean = inForeground && !activityDestroyed

    override fun bringToFront(): Boolean {
        if (activityDestroyed) return false
        return runCatching { startCarActivity(ProjectionLauncher.projectionIntent(this)) }
            .onSuccess { HeadUnitDiagnostics.event("VideoCarActivity", "bringToFront via host") }
            .onFailure { HeadUnitDiagnostics.error("VideoCarActivity", "bringToFront via host failed", it) }
            .isSuccess
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        refreshProjectionHostState("onResume")
        HeadUnitDiagnostics.event("VideoCarActivity", "onResume pendingCast=${RemoteCastBus.hasPendingCast()}")
        RemoteCastBus.retryPendingIfAny()
    }

    override fun onFrameRateChange(frameRate: Int) {
        super.onFrameRateChange(frameRate)
        ProjectionPerformanceState.updateFrameRate(frameRate)
    }

    override fun onPowerStateChange(powerState: Int) {
        super.onPowerStateChange(powerState)
        ProjectionPerformanceState.updatePowerState(powerState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean, secondaryFlag: Boolean) {
        super.onWindowFocusChanged(hasFocus, secondaryFlag)
        ProjectionPerformanceState.updateWindowFocus(hasFocus, secondaryFlag)
    }

    private fun refreshProjectionHostState(reason: String) {
        val host = projectionHost ?: return
        val maxFps = runCatching { host.maxFrameRate }.getOrDefault(0)
        val powerState = runCatching { host.powerState }.getOrDefault(Int.MIN_VALUE)
        val hasFocus = runCatching { host.hasWindowFocus() }.getOrDefault(true)
        ProjectionPerformanceState.updateHost(maxFps, powerState, hasFocus)
        HeadUnitDiagnostics.event(
            "VideoCarActivity",
            "projection-host reason=$reason maxFps=$maxFps powerState=$powerState windowFocus=$hasFocus"
        )
    }

    override fun onDestroy() {
        HeadUnitDiagnostics.event("VideoCarActivity", "onDestroy")
        activityDestroyed = true
        // AA going away must not drop the session. Transfer the active projected item to the
        // phone as a paused, audio-only resumable session before the HU player is released.
        if (!suppressPhoneHandoffOnDestroy && VideoSessionBridge.activeRole == VideoSessionBridge.Role.HEAD_UNIT) {
            (supportFragmentManager.findFragmentById(R.id.fragment_container) as? ExoPlayerVideoFragment)
                ?.handoffToPhonePaused()
        }
        RemoteCastBus.unregister(this)
        pendingCastVideoId = null
        pendingCastStarted = null
        pendingPhoneHandoff?.let { handoff ->
            if (VideoSessionBridge.activeRole != VideoSessionBridge.Role.HEAD_UNIT) {
                PlaybackEngine.restoreProjectionHandoffAfterFailure(handoff)
            }
        }
        pendingPhoneHandoff = null
        searchDebounce?.let { mainHandler.removeCallbacks(it) }
        parkGate?.stop()
        parkGate = null
        scope.cancel()
        projectionHost = null
        ProjectionPerformanceState.clear()
        super.onDestroy()
    }

    private fun refreshParkGate() {
        if (
            settings.isCarAudioModeEnabled() ||
            !settings.isRequireParked() ||
            parked == true ||
            (parked == null && warningAccepted)
        ) {
            showContent()
        } else {
            findViewById(R.id.park_warning).visibility = View.VISIBLE
        }
    }

    private fun showContent() {
        HeadUnitDiagnostics.event("VideoCarActivity", "showContent parked=$parked warningAccepted=$warningAccepted audioMode=${settings.isCarAudioModeEnabled()}")
        findViewById(R.id.park_warning).visibility = View.GONE
        RemoteCastBus.retryPendingIfAny()
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            val handoff = pendingPhoneHandoff
            if (handoff != null) {
                showPhoneHandoff(handoff)
            } else if (!RemoteCastBus.hasPendingCast()) {
                // A pending external cast may already have been accepted but its Fragment
                // transaction has not executed yet. Do not overwrite it with Browse.
                showBrowse()
            }
        }
    }

    private fun setupSearch() {
        val search = carUiController.searchController
        search.setSearchHint(getString(R.string.browse_search_hint))
        search.setSearchCallback(object : SearchCallback() {
            override fun onSearchItemSelected(item: SearchItem) {
                val videoId = item.extras?.getString(EXTRA_VIDEO_ID) ?: return
                dismissSearchUi()
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId(videoId)
                    .setTitle(item.title)
                    .setSubtitle(item.subtitle)
                    .build()
                playVideo(description)
            }

            override fun onSearchSubmitted(query: String): Boolean {
                if (query.isBlank()) return false
                // Close keyboard/input and the search box before loading browse results.
                // hideSearchBox alone leaves the AA startInput session open on many HUs.
                dismissSearchUi()
                runYoutubeSearch(query.trim())
                return true
            }

            override fun onSearchTextChanged(query: String) {
                searchDebounce?.let { mainHandler.removeCallbacks(it) }
                if (query.isBlank()) {
                    search.setSearchItems(emptyList())
                    return
                }
                val q = query.trim()
                val task = Runnable { runYoutubeSearch(q, suggestionsOnly = true) }
                searchDebounce = task
                mainHandler.postDelayed(task, SEARCH_DEBOUNCE_MS)
            }

            override fun onSearchStart() {
                searchUiOpen = true
            }

            override fun onSearchStop() {
                // Host closed search (X / back). Cancel pending suggestion work so a late
                // setSearchItems cannot reopen the input after dismiss.
                searchUiOpen = false
                searchDebounce?.let { mainHandler.removeCallbacks(it) }
                searchDebounce = null
                runCatching { search.setSearchItems(emptyList()) }
            }
        })
        dismissSearchUi()
    }

    /**
     * Fully tear down AA search: cancel debounce, clear suggestions, stop the input/keyboard
     * session ([SearchController.stopSearch]), then hide the search box.
     * [SearchController.hideSearchBox] alone often leaves the projected keyboard/input open.
     */
    private fun dismissSearchUi() {
        searchUiOpen = false
        searchDebounce?.let { mainHandler.removeCallbacks(it) }
        searchDebounce = null
        val search = carUiController.searchController
        runCatching { search.setSearchItems(emptyList()) }
        runCatching { search.stopSearch() }
        runCatching { search.hideSearchBox() }
    }

    private fun runYoutubeSearch(
        query: String,
        suggestionsOnly: Boolean = false,
        forceRefresh: Boolean = false
    ) {
        scope.launch {
            when (val result = browseFacade.search(query, forceRefresh)) {
                is BrowseOutcome.Ok -> {
                    if (suggestionsOnly) {
                        // Drop late results if the user already closed search.
                        if (!searchUiOpen) return@launch
                        val items = result.data.mapNotNull { desc ->
                            val videoId = desc.mediaId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            SearchItem.Builder()
                                .setTitle(desc.title?.toString() ?: videoId)
                                .setSubtitle(desc.subtitle?.toString())
                                .setType(SearchItem.Type.SEARCH_RESULT)
                                .setExtras(Bundle().apply { putString(EXTRA_VIDEO_ID, videoId) })
                                .build()
                        }
                        carUiController.searchController.setSearchItems(items)
                    } else {
                        // Submitted search: results live in Browse. Never setSearchItems here —
                        // that can reopen the search input after dismissSearchUi().
                        val browse = supportFragmentManager.findFragmentByTag(TAG_BROWSE) as? BrowseCarFragment
                        if (browse != null && browse.isAdded) {
                            browse.showSearchResults(query, result.data)
                        } else {
                            val frag = BrowseCarFragment()
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.fragment_container, frag, TAG_BROWSE)
                                .commitNowAllowingStateLoss()
                            frag.showSearchResults(query, result.data)
                        }
                        dismissSearchUi()
                    }
                }
                is BrowseOutcome.Error,
                is BrowseOutcome.NeedsSignIn -> {
                    if (suggestionsOnly) {
                        if (searchUiOpen) {
                            carUiController.searchController.setSearchItems(emptyList())
                        }
                    } else {
                        dismissSearchUi()
                    }
                }
            }
        }
    }


    private fun showPhoneHandoff(handoff: PlaybackEngine.ProjectionHandoff) {
        HeadUnitDiagnostics.event("VideoCarActivity", "showPhoneHandoff videoId=${handoff.video.mediaId} position=${handoff.positionMs} queue=${handoff.queue.size}")
        if (activityDestroyed || handoff.video.mediaId.isNullOrBlank()) return
        playbackOpenedExternally = true
        suppressPhoneHandoffOnDestroy = false
        dismissSearchUi()
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                ExoPlayerVideoFragment.newInstance(
                    video = handoff.video,
                    queue = handoff.queue,
                    startPositionMs = handoffStartPosition(handoff),
                    startPaused = false
                ),
                TAG_VIDEO
            )
            .commitAllowingStateLoss()
    }

    /**
     * Native AA audio keeps playing while the projected player loads. Starting the load a little
     * ahead lets the takeover land inside the fresh buffer instead of seeking out of it.
     */
    private fun handoffStartPosition(handoff: PlaybackEngine.ProjectionHandoff): Long {
        val sourceStillPlaying = VideoSessionBridge.activeRole == VideoSessionBridge.Role.NATIVE_AUDIO &&
            VideoSessionBridge.controller?.snapshot()?.playWhenReady == true
        return if (sourceStillPlaying) handoff.positionMs + ExoPlayerVideoFragment.HANDOFF_LEAD_MS else handoff.positionMs
    }

    override fun showBrowse() {
        if (activityDestroyed) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, BrowseCarFragment(), TAG_BROWSE)
            .commitAllowingStateLoss()
        carUiController.statusBarController.setTitle(getString(R.string.car_projection_label))
        dismissSearchUi()
    }

    override fun showSearch() {
        if (activityDestroyed) return
        val search = carUiController.searchController
        searchUiOpen = true
        search.setSearchItems(emptyList())
        search.showSearchBox()
        // startSearch opens the projected keyboard/input session that stopSearch closes.
        runCatching { search.startSearch("") }
    }

    override fun refreshSearch(query: String) {
        if (activityDestroyed || query.isBlank()) return
        runYoutubeSearch(query, forceRefresh = true)
    }

    override fun showSettings() {
        if (activityDestroyed) return
        dismissSearchUi()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SettingsCarFragment(), TAG_SETTINGS)
            .commitAllowingStateLoss()
        carUiController.statusBarController.setTitle(getString(R.string.car_settings_title))
    }

    override fun exitBrowse() {
        if (activityDestroyed) return
        super.onBackPressed()
    }

    override fun playVideo(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) {
        startPlayback(
            video,
            queue,
            openedExternally = false,
            startPositionMs = WatchHistoryStore.resumePositionFromDescription(video)
        )
    }

    private fun startPlayback(
        video: MediaDescriptionCompat,
        queue: List<MediaDescriptionCompat>,
        openedExternally: Boolean,
        startPositionMs: Long = 0L,
        requestId: Long = 0L
    ) {
        HeadUnitDiagnostics.event(
            "VideoCarActivity",
            "startPlayback videoId=${video.mediaId} queue=${queue.size} external=$openedExternally position=${startPositionMs.coerceAtLeast(0L)} stateSaved=${supportFragmentManager.isStateSaved}"
        )
        if (activityDestroyed || video.mediaId.isNullOrBlank()) return
        playbackOpenedExternally = openedExternally
        suppressPhoneHandoffOnDestroy = false
        // A different video supersedes an unfinished phone handoff (user left it before READY).
        // Keeping it made onDestroy "restore after failure" although the HU had taken over.
        // The HU player takes ownership like the cast path does, so the phone lease is dropped.
        pendingPhoneHandoff = null
        dismissSearchUi()
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                ExoPlayerVideoFragment.newInstance(
                    video = video,
                    queue = queue.ifEmpty { listOf(video) },
                    startPositionMs = startPositionMs.coerceAtLeast(0L),
                    requestId = requestId
                ),
                TAG_VIDEO
            )
            // A cast/search click can race onSaveInstanceState when AA switches surfaces. Losing
            // this visual transition is preferable to crashing the whole shared playback process.
            .commitAllowingStateLoss()
    }

    override fun switchToWebViewFallback(video: MediaDescriptionCompat) {
        if (activityDestroyed || video.mediaId.isNullOrBlank()) return
        dismissSearchUi()
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                WebViewFallbackFragment.newInstance(
                    video,
                    requestId = pendingCastRequestId ?: 0L,
                    startPositionMs = (supportFragmentManager.findFragmentByTag(TAG_VIDEO) as? ExoPlayerVideoFragment)
                        ?.arguments?.getLong(ExoPlayerVideoFragment.ARG_START_POSITION_MS, 0L) ?: 0L
                ),
                TAG_WEBVIEW
            )
            .commitAllowingStateLoss()
    }

    /**
     * Fernsteuerung aus der Smartphone-App: wechselt das Head-Unit-Display auf [video].
     * RemoteCastBus serialisiert Aufrufe auf den Main-Looper, bevor FragmentManager/UI berührt wird.
     */
    override fun castVideo(
        video: MediaDescriptionCompat,
        queue: List<MediaDescriptionCompat>,
        startPositionMs: Long,
        requestId: Long,
        onStarted: () -> Unit
    ): RemoteCastBus.Acceptance {
        HeadUnitDiagnostics.event("VideoCarActivity", "castVideo videoId=${video.mediaId} queue=${queue.size} position=${startPositionMs.coerceAtLeast(0L)} parked=$parked warningAccepted=$warningAccepted stateSaved=${supportFragmentManager.isStateSaved}")
        if (
            !settings.isCarAudioModeEnabled() &&
            settings.isRequireParked() &&
            parked != true &&
            !(parked == null && warningAccepted)
        ) {
            return RemoteCastBus.Acceptance.PARK_BLOCKED
        }
        if (activityDestroyed) return RemoteCastBus.Acceptance.UNAVAILABLE
        // Saved instance state is not a reason to refuse: every transaction below commits with
        // commitAllowingStateLoss(). Refusing here made "continue as video" fail silently (and
        // report a parked-video block) whenever the projection sat behind the AA media screen.
        pendingCastVideoId = video.mediaId
        pendingCastRequestId = requestId
        pendingCastStarted = onStarted
        startPlayback(video, queue, openedExternally = true, startPositionMs = startPositionMs, requestId = requestId)
        return RemoteCastBus.Acceptance.ACCEPTED
    }

    override fun cancelCast(requestId: Long) {
        if (pendingCastRequestId != requestId) return
        pendingCastRequestId = null
        pendingCastVideoId = null
        pendingCastStarted = null
        if (activityDestroyed) return
        // Queue after the already accepted replacement transaction. A timed-out fragment must
        // not start playing after the phone has already fallen back.
        val fragment = supportFragmentManager.findFragmentByTag(TAG_VIDEO) as? ExoPlayerVideoFragment
        fragment?.cancelPendingCast(requestId)
        showBrowse()
    }

    override fun leavePlayback(stopSession: Boolean) {
        if (activityDestroyed) return
        HeadUnitDiagnostics.event(
            "VideoCarActivity",
            "leavePlayback external=$playbackOpenedExternally stopSession=$stopSession"
        )
        if (stopSession) {
            suppressPhoneHandoffOnDestroy = true
            if (VideoSessionBridge.activeRole == VideoSessionBridge.Role.HEAD_UNIT) {
                NowPlayingBus.clear(owner = VideoSessionBridge.controller)
            }
        }
        // Always return to the projection browser, even when playback was opened externally
        // (phone handoff / cast). The projection is usually the root AA surface, so
        // super.onBackPressed() was a no-op there and left the user on a paused player.
        // Leaving the projection itself stays available via Back in Browse (exitBrowse()).
        playbackOpenedExternally = false
        showBrowse()
    }

    override fun onHeadUnitPlayerReady(videoId: String, requestId: Long) {
        HeadUnitDiagnostics.event(
            "VideoCarActivity",
            "onHeadUnitPlayerReady videoId=$videoId requestId=$requestId pendingCast=$pendingCastVideoId " +
                "pendingPhone=${pendingPhoneHandoff?.video?.mediaId}"
        )
        if (requestId == 0L && pendingPhoneHandoff?.video?.mediaId == videoId) {
            // Decoder lease is deliberately not restored: HEAD_UNIT ownership has now stopped and
            // cleared the phone player, so the overlap-free handoff is complete.
            pendingPhoneHandoff = null
            return
        }
        if (pendingCastVideoId != videoId || pendingCastRequestId != requestId) return
        val callback = pendingCastStarted
        pendingCastRequestId = null
        pendingCastVideoId = null
        pendingCastStarted = null
        pendingPhoneHandoff = null
        callback?.invoke()
    }

    companion object {
        private const val TAG_BROWSE = "browse"
        private const val TAG_SETTINGS = "settings"
        private const val TAG_VIDEO = "exo_video"
        private const val TAG_WEBVIEW = "webview_video"
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val SEARCH_DEBOUNCE_MS = 400L
    }
}
