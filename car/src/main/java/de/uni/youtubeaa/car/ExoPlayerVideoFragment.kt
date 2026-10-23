@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaDescriptionCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.preference.PreferenceManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AA projection player: video surface or audio-only media UI, toggled via playback mode.
 */
@OptIn(markerClass = [UnstableApi::class])
class ExoPlayerVideoFragment : Fragment(), VideoSessionBridge.Controller {

    private val streamResolver = StreamResolver()
    private val metadataRefetcher = VideoMetadataRefetcher()
    private val lipSync = CarLipSync()
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var statusText: TextView? = null
    private var playbackStatus: PlaybackStatusView? = null
    private var modeButton: ImageButton? = null
    private var titleText: TextView? = null
    private var playerView: PlayerView? = null
    private var audioPanel: View? = null
    private var audioPlayPause: ImageButton? = null
    private var audioSeek: DefaultTimeBar? = null
    private var audioPosition: TextView? = null
    private var audioDuration: TextView? = null
    private var settingsOverlay: View? = null
    private var settingsTitle: TextView? = null
    private var settingsRows: LinearLayout? = null
    private var settingsBack: ImageButton? = null
    private var currentVideoId: String? = null
    private var queue: List<MediaDescriptionCompat> = emptyList()
    private var queueIndex: Int = 0
    private var thumbJob: Job? = null
    private var loadJob: Job? = null
    private var preserveSourcePosition = false
    private var isSeeking = false
    private var knownDurationMs: Long = 0L
    /** Live streams have no fixed duration; ExoPlayer's duration grows as the DVR window does,
     *  so it must never be locked into [knownDurationMs] like a VOD estimate would be. */
    private var currentIsLive: Boolean = false
    /** True when the currently prepared source still contains a video track. */
    private var currentSourceHasVideo: Boolean = false
    /** True when video -> audio can be done by track selection without replacing the source. */
    private var currentSourceHasAudioForSeamlessSwitch: Boolean = false
    private var recoveryAttemptedForVideoId: String? = null
    private var lastRecoveryAttemptElapsedMs: Long = 0L
    private var recoveryAttemptsInWindow = 0
    /** Stall watchdog (see [checkLoadingStall]). */
    private var stallBufferedPositionMs = C.TIME_UNSET
    private var stallSinceElapsedMs = 0L
    private var bufferingSinceElapsedMs = 0L
    private var lastStallRecoveryElapsedMs = 0L
    private var stallRecoveriesWithoutReady = 0
    private var stallNetworkWaitLogged = false
    private var lastHistorySaveElapsedMs: Long = 0L
    private var requestedPlayWhenReady = true
    private var awaitingReady = false
    private var relinquished = false
    private var projectionVisible = false
    private var projectionSuspendJob: Job? = null
    private var focusSuspendJob: Job? = null
    /** True only after the focus-loss grace actually detached the surface. */
    private var focusVideoSuspended = false
    private var performanceAdaptJob: Job? = null
    private var thermalRecoveryJob: Job? = null
    private var dropPressureRecoveryJob: Job? = null
    private var fpsFallbackRelaxedForItem = false
    private var projectionSnapshot = ProjectionPerformanceState.snapshot()
    private var observedThermalStatus = -1
    private var policyThermalStatus = -1
    private var droppedFramePressureStatus = 0
    private var lastThermalCheckElapsedMs = 0L
    /** Ignore drop callbacks while Media3 rebuilds after a budget/track change (false spikes). */
    private var ignoreDroppedFramesUntilElapsedMs = 0L
    private var lastAppliedVideoBudget: CarVideoPolicy.Budget? = null
    private var manifestHardwareOverrideKey: String? = null
    private var currentResolvedStreamInfo: StreamResolver.VideoResult.StreamInfo? = null
    private var currentSourceIsManifest = false
    /** Packaging of the prepared source; muxed sources cannot drop video for free. */
    private var currentSourceLayout = SourceLayout.AUDIO_ONLY
    private var meteredWatcher: MeteredNetworkWatcher? = null
    private val hardwareRecoveryExcludedStreamIds = linkedSetOf<String>()
    private var hardwareRecoveryAttempts = 0
    private var hardwareRecoveryStreamInFlight: String? = null
    private val manifestHardwareExcludedTrackKeys = linkedSetOf<String>()
    private val manifestHardwareFailedFormatKeys = linkedSetOf<String>()
    private val projectionPerformanceListener = ProjectionPerformanceState.Listener { snapshot ->
        projectionSnapshot = snapshot
        view?.post {
            if (!isAdded || relinquished) return@post
            schedulePerformanceAdaptation("projection")
            if (snapshot.hasWindowFocus) {
                cancelFocusSuspendAndResume()
            } else {
                scheduleFocusSuspend()
            }
        }
    }
    private val castRequestId: Long get() = arguments?.getLong(ARG_CAST_REQUEST_ID, 0L) ?: 0L

    private fun publishCurrent() {
        val video = queue.getOrNull(queueIndex) ?: return
        val exo = player ?: return
        NowPlayingBus.update(
            videoId = video.mediaId ?: return, title = video.title?.toString().orEmpty(),
            channelTitle = video.subtitle?.toString(), thumbnailUrl = video.iconUri?.toString(),
            isPlaying = exo.isPlaying, positionMs = exo.currentPosition,
            durationMs = exo.duration.coerceAtLeast(0L), controllable = true,
            isBuffering = exo.playbackState == Player.STATE_BUFFERING,
            presentation = if (SettingsRepository(requireContext()).isAudioOnly()) PlaybackPresentation.AUDIO else PlaybackPresentation.VIDEO,
            queueIndex = queueIndex, queueSize = queue.size, owner = this
        )
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val surface = SettingsRepository(requireContext()).getCarVideoSurface()
        val layout = if (surface == "texture") {
            R.layout.fragment_exo_player_video_texture
        } else {
            R.layout.fragment_exo_player_video
        }
        HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "video-surface=$surface")
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        projectionSnapshot = ProjectionPerformanceState.snapshot()
        observedThermalStatus = currentThermalStatus()
        policyThermalStatus = observedThermalStatus
        ProjectionPerformanceState.register(projectionPerformanceListener)
        // Wi-Fi <-> mobile data mid-video: re-evaluate the budget (mobile-data cap).
        meteredWatcher = MeteredNetworkWatcher(view.context) {
            schedulePerformanceAdaptation("metered-change")
        }.also { it.start() }
        CarUi.bind(view)
        HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "onViewCreated savedState=${savedInstanceState != null}")
        statusText = view.findViewById(R.id.status_text)
        playbackStatus = PlaybackStatusView(view) {
            val video = queue.getOrNull(queueIndex) ?: return@PlaybackStatusView
            val id = video.mediaId ?: return@PlaybackStatusView
            streamResolver.invalidate(id)
            val position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            if (SettingsRepository(requireContext()).isAudioOnly()) loadAudio(id, position, requestedPlayWhenReady)
            else loadVideo(id, video, position, requestedPlayWhenReady)
        }
        modeButton = view.findViewById(R.id.player_mode)
        titleText = view.findViewById(R.id.player_overlay_title)
        playerView = view.findViewById(R.id.player_view)
        audioPanel = view.findViewById(R.id.audio_player_panel)
        audioPlayPause = view.findViewById(R.id.audio_play_pause)
        audioSeek = view.findViewById(R.id.audio_seek)
        audioPosition = view.findViewById(R.id.audio_position)
        audioDuration = view.findViewById(R.id.audio_duration)
        settingsOverlay = view.findViewById(R.id.player_settings_overlay)
        settingsTitle = view.findViewById(R.id.player_settings_title)
        settingsRows = view.findViewById(R.id.player_settings_rows)
        settingsBack = view.findViewById(R.id.player_settings_back)
        settingsOverlay?.setOnClickListener { hideSettingsMenu() }
        view.findViewById<View>(R.id.player_settings_panel).setOnClickListener { /* consume */ }
        view.findViewById<ImageButton>(R.id.player_settings_close).setOnClickListener { hideSettingsMenu() }
        settingsBack?.setOnClickListener { showSettingsRoot() }

        view.findViewById<ImageButton>(R.id.player_overlay_back).setOnClickListener {
            player?.pause()
            videoHost()?.leavePlayback(stopSession = false)
        }
        view.findViewById<ImageButton>(R.id.player_overlay_settings).setOnClickListener {
            showSettingsMenu(it)
        }
        view.findViewById<ImageButton>(R.id.audio_back).setOnClickListener {
            player?.pause()
            videoHost()?.leavePlayback(stopSession = false)
        }
        view.findViewById<ImageButton>(R.id.audio_settings).setOnClickListener {
            showSettingsMenu(it)
        }
        modeButton?.setOnClickListener { togglePlaybackMode() }
        view.findViewById<ImageButton>(R.id.audio_switch_video).setOnClickListener {
            if (SettingsRepository(requireContext()).isAudioOnly()) togglePlaybackMode()
        }
        audioPlayPause?.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            val playing = if (loadJob?.isActive == true || awaitingReady) requestedPlayWhenReady else exo.playWhenReady
            if (playing) pause() else play()
        }
        view.findViewById<ImageButton>(R.id.audio_prev).setOnClickListener { skipToPrevious() }
        view.findViewById<ImageButton>(R.id.audio_next).setOnClickListener { skipToNext() }
        audioSeek?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                isSeeking = true
                audioPosition?.text = formatPlaybackTime(position.coerceAtLeast(0L))
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                audioPosition?.text = formatPlaybackTime(position.coerceAtLeast(0L))
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                isSeeking = false
                if (!canceled) {
                    player?.seekTo(position.coerceAtLeast(0L))
                }
                updateAudioProgressUi()
            }
        })

        applyLipSync()
        val handle = PlayerFactory.create(
            requireContext(),
            carLipSync = lipSync,
            onVideoDecoderStarted = ::onVideoDecoderStarted,
            onDroppedVideoFrames = ::onDroppedVideoFramesForAuto,
            videoExpectationProvider = {
                currentResolvedStreamInfo?.let { info ->
                    PlaybackDiagnostics.VideoExpectation(
                        streamId = info.id,
                        itag = info.itag,
                        source = info.source,
                        codecHint = info.codecHint,
                        widthHint = info.width,
                        heightHint = info.height,
                        fpsHint = info.fps
                    )
                }
            }
        )
        player = handle.player
        trackSelector = handle.trackSelector
        PlayerFactory.applyCarVideoConstraints(handle.trackSelector, currentVideoBudget())
        lastAppliedVideoBudget = currentVideoBudget()
        val exo = handle.player
        playerView?.apply {
            player = exo
            setControllerShowTimeoutMs(3500)
            setControllerHideOnTouch(true)
            setControllerAutoShow(true)
        }
        applyPlaybackSpeed()
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!relinquished) {
                    playbackStatus?.update(player)
                    audioPlayPause?.setImageResource(if (requestedPlayWhenReady && (awaitingReady || loadJob?.isActive == true) || player.playWhenReady)
                        R.drawable.ic_pause_on_primary else R.drawable.ic_play_on_primary)
                }
                if (relinquished || !VideoSessionBridge.isOwner(this@ExoPlayerVideoFragment)) return
                NowPlayingBus.updatePlayback(
                    player.isPlaying, player.currentPosition, player.duration,
                    isBuffering = player.playbackState == Player.STATE_BUFFERING,
                    owner = this@ExoPlayerVideoFragment
                )
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    requestedPlayWhenReady = playWhenReady
                }
                if (!relinquished && !awaitingReady && loadJob?.isActive == true) {
                    requestedPlayWhenReady = playWhenReady
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (relinquished || !projectionVisible) return
                val ctx = context ?: return
                if (SettingsRepository(ctx).isAudioOnly()) return
                if (trackSelector?.parameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_VIDEO) == true) return
                // Hidden muxed source pinned to its lowest rung: no HW override until resumed.
                if (trackSelector?.parameters?.forceLowestBitrate == true) return

                val budget = currentVideoBudget()
                if (currentSourceIsManifest && applyManifestHardwareAdaptiveOverride(tracks, budget, "tracks")) {
                    markDroppedFrameAdaptationGrace()
                    return
                }

                val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (videoGroups.isEmpty() || videoGroups.any { it.isSelected }) return
                if (fpsFallbackRelaxedForItem) return

                // No track survived the projection/speed-aware source-fps ceiling. Keep the
                // picture/HW budget strict, but relax only source fps so video does not disappear.
                fpsFallbackRelaxedForItem = true
                val relaxed = currentVideoBudget(relaxFrameRate = true)
                trackSelector?.let { PlayerFactory.applyCarVideoConstraints(it, relaxed) }
                if (currentSourceIsManifest) applyManifestHardwareAdaptiveOverride(tracks, relaxed, "fps-relax")
                HeadUnitDiagnostics.warning(
                    "ExoPlayerVideoFragment",
                    "no video selected under sourceFps=${budget.maxSourceFps}; relaxing fps only " +
                        "projectionFps=${budget.projectionFps} speed=${budget.playbackSpeed} videoId=$currentVideoId"
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (relinquished) return
                HeadUnitDiagnostics.error(
                    "ExoPlayerVideoFragment",
                    "player error videoId=$currentVideoId code=${error.errorCodeName} " +
                        "rejectedItag=${PlaybackSources.rejectedItag(error)} message=${error.message}",
                    error
                )
                val ctx = context ?: return
                val current = queue.getOrNull(queueIndex) ?: return
                val videoId = current.mediaId ?: return
                val resumePosition = exo.currentPosition.coerceAtLeast(0L)
                val keepPlaying = if (awaitingReady) requestedPlayWhenReady else exo.playWhenReady || exo.isPlaying
                val settings = SettingsRepository(ctx)

                if (isDecoderError(error) && !settings.isAudioOnly()) {
                    if (currentSourceIsManifest) {
                        manifestHardwareOverrideKey?.let { manifestHardwareExcludedTrackKeys += it }
                        val budget = currentVideoBudget(relaxFrameRate = fpsFallbackRelaxedForItem)
                        val choice = Media3HardwareDecode.bestManifestHardwareTrackSet(
                            context = ctx.applicationContext,
                            tracks = exo.currentTracks,
                            budget = budget,
                            excludedKeys = manifestHardwareExcludedTrackKeys,
                            excludedFormatKeys = manifestHardwareFailedFormatKeys
                        )
                        if (choice != null) {
                            manifestHardwareOverrideKey = choice.key
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setOverrideForType(
                                    TrackSelectionOverride(choice.group.mediaTrackGroup, choice.trackIndices)
                                )
                                .build()
                            exo.playWhenReady = keepPlaying
                            exo.prepare()
                            HeadUnitDiagnostics.warning(
                                "HardwareDecode",
                                "decoder-error-manifest-next-set videoId=$videoId error=${error.errorCodeName} " +
                                    "tracks=${choice.trackIndices.joinToString()} excluded=${manifestHardwareExcludedTrackKeys.size}"
                            )
                            return
                        }
                    } else {
                        currentResolvedStreamInfo?.let { failed ->
                            if (hardwareRecoveryStreamInFlight != failed.id) {
                                hardwareRecoveryExcludedStreamIds += failed.id
                                hardwareRecoveryAttempts++
                                hardwareRecoveryStreamInFlight = failed.id
                                HeadUnitDiagnostics.warning(
                                    "HardwareDecode",
                                    "decoder-error-progressive-next-itag videoId=$videoId error=${error.errorCodeName} " +
                                        "rejectStream=${failed.id} itag=${failed.itag}"
                                )
                                loadVideo(
                                    videoId, current, resumePosition,
                                    playWhenReady = keepPlaying, hardwareRecovery = true
                                )
                                return
                            }
                        }
                    }
                }

                // Direct YouTube stream URLs can expire or break after a network handover.
                // Re-resolve once and restore the position before falling back to WebView/error.
                // Two attempts per window: a rejected URL now fails within ~1 s instead of ~17 s,
                // so a second fresh resolve is cheap and beats an error screen or WebView.
                val now = SystemClock.elapsedRealtime()
                if (recoveryAttemptedForVideoId != videoId || now - lastRecoveryAttemptElapsedMs >= RECOVERY_COOLDOWN_MS) {
                    recoveryAttemptsInWindow = 0
                }
                if (recoveryAttemptsInWindow < MAX_RECOVERY_ATTEMPTS) {
                    recoveryAttemptsInWindow++
                    recoveryAttemptedForVideoId = videoId
                    lastRecoveryAttemptElapsedMs = now
                    resetStallWatch()
                    // Cached NewPipe extractors contain signed CDN URLs. Reusing the extractor
                    // after a 403/expiry/network handover would simply retry the same dead URL.
                    streamResolver.invalidate(videoId)
                    showStatus(ctx.getString(R.string.video_player_loading))
                    if (settings.isAudioOnly()) {
                        loadAudio(videoId, resumePosition, playWhenReady = keepPlaying)
                    } else {
                        loadVideo(videoId, current, resumePosition, playWhenReady = keepPlaying)
                    }
                    return
                }

                if (!settings.isAudioOnly() && settings.getVideoBackend() != "exoplayer") {
                    videoHost()?.switchToWebViewFallback(current)
                } else {
                    playbackStatus?.error(ctx.getString(if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) R.string.playback_status_network else R.string.playback_status_error))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (relinquished) return
                if (playbackState == Player.STATE_READY && awaitingReady) {
                    awaitingReady = false
                    // The phone may have kept playing while extraction/buffering ran. Capture
                    // its latest VOD position at takeover instead of replaying that interval.
                    val previous = VideoSessionBridge.controller?.takeIf { it !== this@ExoPlayerVideoFragment }?.snapshot()
                    if (previous != null && !currentIsLive && previous.queue.getOrNull(previous.queueIndex)?.mediaId == currentVideoId) {
                        previous.positionMs.takeIf { it > 0L }?.let { target -> alignTakeoverPosition(exo, target) }
                    }
                    VideoSessionBridge.activate(this@ExoPlayerVideoFragment, VideoSessionBridge.Role.HEAD_UNIT)
                    CarPlaybackState.markActive(CarPlaybackState.Owner.PROJECTION, currentVideoId)
                    publishCurrent()
                    currentVideoId?.let { videoHost()?.onHeadUnitPlayerReady(it, castRequestId) }
                    exo.playWhenReady = requestedPlayWhenReady
                }
                if (playbackState == Player.STATE_BUFFERING) {
                    NowPlayingBus.updatePlayback(
                        isPlaying = false,
                        positionMs = exo.currentPosition,
                        durationMs = exo.duration,
                        isBuffering = true, owner = this@ExoPlayerVideoFragment)
                } else if (playbackState == Player.STATE_ENDED) {
                    saveHistoryProgress(completed = true)
                    val ctx = context
                    if (
                        ctx != null &&
                        SettingsRepository(ctx).isAutoplayEnabled() &&
                        queueIndex < queue.lastIndex
                    ) {
                        skipToNext()
                    } else {
                        HeadUnitDiagnostics.event(
                            "ExoPlayerVideoFragment",
                            "playback ended videoId=$currentVideoId queueIndex=$queueIndex queue=${queue.size}"
                        )
                        CarPlaybackState.clear(CarPlaybackState.Owner.PROJECTION)
                        NowPlayingBus.clear(owner = this@ExoPlayerVideoFragment)
                        videoHost()?.leavePlayback(stopSession = true)
                    }
                } else if (playbackState == Player.STATE_READY) {
                    stallRecoveriesWithoutReady = 0
                    // Autoplay of the next queue item then starts without the page fetch.
                    queue.getOrNull(queueIndex + 1)?.mediaId?.let { context?.let { ctx -> StreamResolver.prefetch(ctx, listOf(it)) } }
                    HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "player READY videoId=$currentVideoId pos=${exo.currentPosition} duration=${exo.duration}")
                    NowPlayingBus.updatePlayback(exo.isPlaying, exo.currentPosition, exo.duration, owner = this@ExoPlayerVideoFragment)
                    updateAudioProgressUi()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (relinquished) return
                if (!isPlaying) saveHistoryProgress()
                NowPlayingBus.updatePlayback(isPlaying, exo.currentPosition, exo.duration,
                    isBuffering = exo.playbackState == Player.STATE_BUFFERING, owner = this@ExoPlayerVideoFragment)
                audioPlayPause?.setImageResource(
                    if (exo.playWhenReady) R.drawable.ic_pause_on_primary else R.drawable.ic_play_on_primary
                )
            }
        })

        queue = arguments?.let { BundleCompat.getParcelableArrayList(it, ARG_QUEUE, MediaDescriptionCompat::class.java) }
            ?: emptyList()
        val argVideo = arguments?.let { BundleCompat.getParcelable(it, ARG_VIDEO, MediaDescriptionCompat::class.java) }
        val restoredVideoId = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString(KEY_LAST_VIDEO_ID, null)
        val video = argVideo ?: restoredVideoId?.let { id ->
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(id).build()
        }

        if (video == null) {
            showStatus(
                getString(R.string.video_placeholder) +
                    "\n\n" + getString(R.string.browse_empty)
            )
            return
        }

        queueIndex = queue.indexOfFirst { it.mediaId == video.mediaId }.takeIf { it >= 0 } ?: 0
        if (queue.isEmpty()) {
            queue = listOf(video)
            queueIndex = 0
        }
        currentVideoId = video.mediaId
        // Do not start YouTubeMediaBrowserService from the projection UI. On Android 12+
        // this may throw ForegroundServiceStartNotAllowedException when the car projection
        // is not considered an eligible foreground context. The HU player is self-contained
        // and can publish state through NowPlayingBus/VideoSessionBridge without starting it.
        applyModeUi()
        val initialPositionMs = arguments?.getLong(ARG_START_POSITION_MS, 0L) ?: 0L
        val startPaused = arguments?.getBoolean(ARG_START_PAUSED, false) ?: false
        playItem(video, resumePositionMs = initialPositionMs, forcePlay = !startPaused)
        startPositionTicker()
    }

    private fun togglePlaybackMode() {
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val keepPlaying = if (awaitingReady || loadJob?.isActive == true) requestedPlayWhenReady else exo.playWhenReady || exo.isPlaying
        val settings = SettingsRepository(requireContext())
        val wasAudioOnly = settings.isAudioOnly()

        settings.cyclePlaybackMode()
        val audioOnly = settings.isAudioOnly()
        NowPlayingBus.updatePresentation(
            if (audioOnly) PlaybackPresentation.AUDIO else PlaybackPresentation.VIDEO,
            queueIndex = queueIndex,
            queueSize = queue.size.coerceAtLeast(1), owner = this@ExoPlayerVideoFragment)
        applyModeUi()
        if (!audioOnly) {
            fpsFallbackRelaxedForItem = false
            manifestHardwareOverrideKey = null
            trackSelector?.let { PlayerFactory.applyCarVideoConstraints(it, currentVideoBudget()) }
        }

        // Keep the current MediaSource and audio buffer when possible. Re-resolving the same
        // YouTube item here used to cause the short pause during video -> audio switching.
        // Muxed sources keep downloading video with a disabled track: reload pure audio instead.
        if (!wasAudioOnly && audioOnly && currentSourceHasAudioForSeamlessSwitch &&
            trackSelector?.let { PlayerFactory.effectiveLayout(it, currentSourceLayout) }?.videoCanBeDroppedForFree == true
        ) {
            loadJob?.cancel()
            preserveSourcePosition = false
            trackSelector?.let { PlayerFactory.setAudioOnly(it, true, currentSourceLayout) }
            exo.playWhenReady = keepPlaying
            if (keepPlaying) exo.play()
            updateAudioProgressUi()
            return
        }

        if (wasAudioOnly && !audioOnly && currentSourceHasVideo) {
            loadJob?.cancel()
            preserveSourcePosition = false
            trackSelector?.let { PlayerFactory.setAudioOnly(it, false) }
            exo.playWhenReady = keepPlaying
            if (keepPlaying) exo.play()
            return
        }

        // Pure audio sources do not contain a video renderer; only those need a fresh video load.
        val video = queue.getOrNull(queueIndex) ?: return
        playItem(video, resumePositionMs = position, forcePlay = keepPlaying, preserveSource = true)
    }

    private fun applyModeUi() {
        val audioOnly = SettingsRepository(requireContext()).isAudioOnly()
        applyLipSync()
        if (audioOnly) {
            playerView?.player = null
            playerView?.visibility = View.GONE
        } else {
            playerView?.visibility = View.VISIBLE
            playerView?.player = player
        }
        audioPanel?.visibility = if (audioOnly) View.VISIBLE else View.GONE
        // The mode switch sits in PlayerView's transport row next to play, so it already shows
        // and hides together with the controller overlay.
        updateVideoModeButtonVisibility()
        updateProjectionQos()
    }

    private fun updateProjectionQos() {
        val videoVisible = projectionVisible && context?.let { !SettingsRepository(it).isAudioOnly() } == true
        CarPlaybackQos.setProjectedVideoVisible(videoVisible)
    }

    private fun updateVideoModeButtonVisibility() {
        val audioOnly = context?.let { SettingsRepository(it).isAudioOnly() } ?: true
        modeButton?.visibility = if (!audioOnly) View.VISIBLE else View.GONE
    }

    private fun currentVideoBudget(relaxFrameRate: Boolean = fpsFallbackRelaxedForItem): CarVideoPolicy.Budget {
        val settings = SettingsRepository(requireContext())
        return CarVideoPolicy.budget(
            quality = settings.getCarVideoQuality(),
            projectionFps = projectionSnapshot.effectiveFrameRate,
            playbackSpeed = settings.getPlaybackSpeed(),
            thermalStatus = maxOf(policyThermalStatus, droppedFramePressureStatus),
            relaxFrameRate = relaxFrameRate,
            metered = meteredWatcher?.isMetered ?: NetworkPolicy.isMetered(requireContext()),
            mobileDataQuality = settings.getMobileDataQuality()
        )
    }

    private fun schedulePerformanceAdaptation(reason: String) {
        if (!isAdded || relinquished) return
        performanceAdaptJob?.cancel()
        performanceAdaptJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(PERFORMANCE_ADAPT_DEBOUNCE_MS)
            if (!isAdded || relinquished) return@launch
            applyPerformanceBudget(reason)
        }
    }

    private fun applyPerformanceBudget(reason: String) {
        val ctx = context ?: return
        if (SettingsRepository(ctx).isAudioOnly()) return
        // Hidden muxed source pinned to its lowest rung; the resume path re-applies the budget.
        if (trackSelector?.parameters?.forceLowestBitrate == true) return
        val budget = currentVideoBudget()
        val previous = lastAppliedVideoBudget
        if (previous == budget && reason != "tracks") return
        val budgetChanged = previous != budget
        lastAppliedVideoBudget = budget
        trackSelector?.let { PlayerFactory.applyCarVideoConstraints(it, budget) }
        HeadUnitDiagnostics.event(
            "HardwareAuto",
            "budget-applied reason=$reason quality=${budget.quality} effective=${budget.effectiveQuality} " +
                "projectionFps=${budget.projectionFps} speed=${budget.playbackSpeed} " +
                "maxSourceFps=${budget.maxSourceFps} thermal=${budget.thermalStatus} " +
                "windowFocus=${projectionSnapshot.hasWindowFocus}"
        )

        val exo = player ?: return
        if (currentSourceIsManifest) {
            val changed = applyManifestHardwareAdaptiveOverride(exo.currentTracks, budget, reason)
            if (budgetChanged || changed) markDroppedFrameAdaptationGrace()
            return
        }
        val info = currentResolvedStreamInfo ?: return
        if (loadJob?.isActive == true) return
        val family = VideoStreamChoice.familyOf(info.codecHint)
        val effectiveDecodeFps = budget.effectiveDecodeFps(info.fps)
        val hwStillCapable = family != VideoStreamChoice.Codec.OTHER && HardwareVideoCodecs.decodes(
            family, info.width, info.height, effectiveDecodeFps
        )
        val fpsFits = budget.relaxFrameRate || info.fps <= 0 || info.fps <= budget.maxSourceFps
        val pictureFits = budget.fits(info.width, info.height)
        if (!pictureFits || !fpsFits || !hwStillCapable) {
            HeadUnitDiagnostics.warning(
                "HardwareAuto",
                "progressive-reselect reason=$reason videoId=$currentVideoId itag=${info.itag} " +
                    "pictureFits=$pictureFits fpsFits=$fpsFits hwStillCapable=$hwStillCapable " +
                    "source=${info.width}x${info.height}@${info.fps} effectiveDecodeFps=$effectiveDecodeFps"
            )
            markDroppedFrameAdaptationGrace()
            reloadCurrent(keepPosition = true)
        } else if (budgetChanged) {
            markDroppedFrameAdaptationGrace()
        }
    }

    private fun markDroppedFrameAdaptationGrace() {
        ignoreDroppedFramesUntilElapsedMs =
            SystemClock.elapsedRealtime() + DROP_PRESSURE_ADAPT_GRACE_MS
    }

    private fun applyManifestHardwareAdaptiveOverride(
        tracks: Tracks,
        budget: CarVideoPolicy.Budget,
        reason: String
    ): Boolean {
        if (!currentSourceIsManifest || tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) return false
        val ctx = context ?: return false
        val choice = Media3HardwareDecode.bestManifestHardwareTrackSet(
            context = ctx.applicationContext,
            tracks = tracks,
            budget = budget,
            excludedKeys = manifestHardwareExcludedTrackKeys,
            excludedFormatKeys = manifestHardwareFailedFormatKeys
        ) ?: return false
        if (choice.key == manifestHardwareOverrideKey) return true
        manifestHardwareOverrideKey = choice.key
        val exo = player ?: return false
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(choice.group.mediaTrackGroup, choice.trackIndices)
            )
            .build()
        HeadUnitDiagnostics.event(
            "HardwareAuto",
            "manifest-hw-adaptive reason=$reason videoId=$currentVideoId tracks=${choice.trackIndices.joinToString()} " +
                "rungs=${choice.formats.joinToString { f -> "${f.width}x${f.height}@${f.frameRate}:${f.bitrate}" }} " +
                "mime=${choice.highestFormat.sampleMimeType} decoders=${choice.decoderNames.joinToString()}"
        )
        return true
    }

    private fun currentThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return runCatching {
            requireContext().getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1
        }.getOrDefault(-1)
    }

    private fun pollThermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastThermalCheckElapsedMs < THERMAL_POLL_INTERVAL_MS) return
        lastThermalCheckElapsedMs = now
        val current = currentThermalStatus()
        if (current == observedThermalStatus) return
        val previousObserved = observedThermalStatus
        observedThermalStatus = current
        HeadUnitDiagnostics.event(
            "HardwareAuto",
            "thermal-change previous=$previousObserved current=$current policy=$policyThermalStatus"
        )
        if (current > policyThermalStatus) {
            thermalRecoveryJob?.cancel()
            policyThermalStatus = current
            schedulePerformanceAdaptation("thermal-up")
        } else if (current >= 0 && current < policyThermalStatus) {
            thermalRecoveryJob?.cancel()
            thermalRecoveryJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(THERMAL_RECOVERY_DELAY_MS)
                if (observedThermalStatus <= current) {
                    policyThermalStatus = observedThermalStatus
                    schedulePerformanceAdaptation("thermal-recover")
                }
            }
        }
    }

    private fun onDroppedVideoFramesForAuto(droppedFrames: Int, elapsedMs: Long) {
        if (elapsedMs <= 0L || droppedFrames <= 0) return
        view?.post {
            if (!isAdded || relinquished) return@post
            // Right after a budget/track change Media3 often reports a short drop burst while the
            // renderer rebuilds. Treating that as real pressure caused 720→480→360 thrash with a
            // full buffer in the 2026-10-08 HU logs.
            if (SystemClock.elapsedRealtime() < ignoreDroppedFramesUntilElapsedMs) return@post
            // Tiny windows are dominated by a few late frames after seeks/overrides.
            if (elapsedMs < DROP_PRESSURE_MIN_WINDOW_MS) return@post
            // Late frames while the media buffer is already healthy are a surface/clock stall,
            // not a picture that is too large. 144p with 40 s buffered still dropped ~50 frames
            // a second on 2026-10-09, and treating that as pressure pinned Auto there for good.
            val bufferedMs = player?.totalBufferedDuration ?: 0L
            if (bufferedMs >= DROP_PRESSURE_HEALTHY_BUFFER_MS) return@post
            val expected = (elapsedMs * projectionSnapshot.effectiveFrameRate / 1000L).coerceAtLeast(1L)
            val ratio = droppedFrames.toDouble() / expected.toDouble()
            val pressure = when {
                ratio >= 0.10 -> 4
                ratio >= 0.05 -> 3
                ratio >= 0.02 -> 2
                else -> 0
            }
            if (pressure <= 0) return@post
            if (pressure > droppedFramePressureStatus) {
                droppedFramePressureStatus = pressure
                HeadUnitDiagnostics.warning(
                    "HardwareAuto",
                    "drop-pressure dropped=$droppedFrames elapsedMs=$elapsedMs ratio=$ratio pressure=$pressure"
                )
                schedulePerformanceAdaptation("dropped-frames")
            }
            // Any continuing pressure extends the cool-down. Otherwise a steady drop rate would
            // reset back to full Auto quality while the renderer is still overloaded.
            dropPressureRecoveryJob?.cancel()
            dropPressureRecoveryJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(DROP_PRESSURE_RECOVERY_MS)
                droppedFramePressureStatus = 0
                schedulePerformanceAdaptation("drop-pressure-recover")
            }
        }
    }

    private fun scheduleFocusSuspend() {
        if (!projectionVisible || context?.let { SettingsRepository(it).isAudioOnly() } != false) return
        if (focusSuspendJob?.isActive == true) return
        focusSuspendJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(PROJECTION_FOCUS_SUSPEND_GRACE_MS)
            if (projectionSnapshot.hasWindowFocus || !projectionVisible) return@launch
            if (currentSourceHasAudioForSeamlessSwitch) {
                suspendHiddenVideo()
            } else if (currentSourceHasVideo && requestedPlayWhenReady) {
                player?.pause()
            }
            playerView?.player = null
            focusVideoSuspended = true
            CarPlaybackQos.setProjectedVideoVisible(false)
            HeadUnitDiagnostics.event(
                "HardwareAuto",
                "projection-focus-suspended afterMs=$PROJECTION_FOCUS_SUSPEND_GRACE_MS videoId=$currentVideoId"
            )
        }
    }

    /**
     * Projection hidden but audio continues. Separate tracks (incl. YouTube HLS with audio
     * renditions) just disable video; a truly muxed HLS source (or one whose manifest is not
     * loaded yet) is pinned to its lowest rung instead, which needs the HW manifest override out
     * of the way until resume.
     */
    private fun suspendHiddenVideo() {
        val selector = trackSelector ?: return
        if (PlayerFactory.effectiveLayout(selector, currentSourceLayout).needsLowestPin) {
            PlayerFactory.clearVideoTrackOverride(selector)
            manifestHardwareOverrideKey = null
        }
        PlayerFactory.setVideoSuspended(selector, true, currentSourceLayout)
        HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "hidden-video-suspended layout=$currentSourceLayout")
    }

    private fun cancelFocusSuspendAndResume() {
        focusSuspendJob?.cancel()
        focusSuspendJob = null
        // Projection frame-rate callbacks arrive every few seconds while the window stays
        // focused (host 60 ↔ 30). Rebinding PlayerView and pushing track parameters on each of
        // those ticks tore the surface down, flushed the audio sink and produced the
        // buffering blips with tens of seconds already buffered.
        if (!focusVideoSuspended) return
        focusVideoSuspended = false
        if (!projectionVisible || context?.let { SettingsRepository(it).isAudioOnly() } != false) return
        trackSelector?.let {
            // Unsuspend first: it drops a lowest-rung pin, then the budget is re-applied.
            PlayerFactory.setVideoSuspended(it, false)
            PlayerFactory.applyCarVideoConstraints(it, currentVideoBudget())
        }
        playerView?.player = player
        if (requestedPlayWhenReady && player?.playWhenReady != true) player?.play()
        updateProjectionQos()
    }

    private fun startPositionTicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(POSITION_TICK_INTERVAL_MS)
                if (relinquished) break
                pollThermalStatus()
                val exo = player ?: continue
                checkLoadingStall(exo)
                if (exo.isPlaying) {
                    NowPlayingBus.updatePlayback(true, exo.currentPosition, exo.duration, owner = this@ExoPlayerVideoFragment)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastHistorySaveElapsedMs >= HISTORY_SAVE_INTERVAL_MS) {
                        lastHistorySaveElapsedMs = now
                        saveHistoryProgress()
                    }
                }
                updateAudioProgressUi()
            }
        }
    }

    private fun saveHistoryProgress(completed: Boolean = false) {
        val ctx = context ?: return
        if (!VideoSessionBridge.isOwner(this) || loadJob?.isActive == true || !SettingsRepository(ctx).isHistoryEnabled()) return
        val video = queue.getOrNull(queueIndex) ?: return
        val exo = player ?: return
        if (currentIsLive) {
            WatchHistoryStore(ctx).updateProgress(video, 0L, 0L)
            return
        }
        val reported = exo.duration
        val reportedValid = reported > 0L && reported != androidx.media3.common.C.TIME_UNSET
        val duration = when {
            knownDurationMs > 0L -> knownDurationMs
            reportedValid -> reported
            else -> 0L
        }
        val position = if (completed && duration > 0L) duration else exo.currentPosition.coerceAtLeast(0L)
        WatchHistoryStore(ctx).updateProgress(video, position, duration)
    }

    private fun updateAudioProgressUi() {
        if (audioPanel?.visibility != View.VISIBLE) return
        if (!SettingsRepository(requireContext()).isAudioOnly()) return
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val reported = exo.duration
        val reportedValid = reported > 0L && reported != androidx.media3.common.C.TIME_UNSET
        val duration = when {
            currentIsLive -> if (reportedValid) reported else 0L
            knownDurationMs > 0L -> knownDurationMs
            reportedValid -> {
                knownDurationMs = reported
                reported
            }
            else -> 0L
        }
        audioDuration?.text = if (duration > 0) formatPlaybackTime(duration) else "0:00"
        audioSeek?.apply {
            setDuration(duration)
            setBufferedPosition(
                if (duration > 0) exo.bufferedPosition.coerceIn(0L, duration) else 0L
            )
            isEnabled = duration > 0L
        }
        // Während der Nutzer scrubbt, zeigt der TimeBar-Listener die Vorschauzeit an — nicht mit
        // der echten Wiedergabeposition überschreiben (verhindert eine flackernde Zeitanzeige).
        if (!isSeeking) {
            audioPosition?.text = formatPlaybackTime(position)
            audioSeek?.setPosition(
                if (duration > 0) position.coerceIn(0L, duration) else 0L
            )
        }
    }

    private fun audioPrefs(): StreamResolver.AudioPrefs {
        val settings = SettingsRepository(requireContext())
        return StreamResolver.AudioPrefs(
            language = settings.getAudioLanguage(),
            bitrate = settings.getAudioBitrate(),
            preferHardware = true
        )
    }

    private fun applyPlaybackSpeed() {
        player?.setPlaybackSpeed(SettingsRepository(requireContext()).getPlaybackSpeed())
    }

    private fun applyLipSync() {
        val settings = SettingsRepository(requireContext())
        val offset = settings.getCarLipSyncMs()
        val videoMode = !settings.isAudioOnly()
        lipSync.update(offset, videoMode = videoMode)
        HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "lipSyncMs=$offset videoMode=$videoMode")
    }

    /**
     * Android Auto projection is hosted in a CarActivity. PopupMenu/AlertDialog create separate
     * windows whose focus/touch routing is unreliable inside that host, so the player settings
     * are rendered as a real full-screen sibling view above PlayerView instead.
     */
    private fun showSettingsMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        playerView?.hideController()
        settingsOverlay?.visibility = View.VISIBLE
        showSettingsRoot()
    }

    private fun hideSettingsMenu() {
        settingsOverlay?.visibility = View.GONE
        if (SettingsRepository(requireContext()).isAudioOnly().not()) {
            playerView?.showController()
        }
    }

    private fun showSettingsRoot() {
        val settings = SettingsRepository(requireContext())
        settingsTitle?.setText(R.string.player_settings)
        settingsBack?.visibility = View.GONE
        settingsRows?.removeAllViews()
        addSettingsRow(getString(R.string.player_setting_speed, speedLabel(settings.getPlaybackSpeed()))) {
            showSpeedDialog()
        }
        addSettingsRow(getString(R.string.player_setting_quality, qualityLabel(settings.getCarVideoQuality()))) {
            showQualityDialog()
        }
        addSettingsRow(getString(R.string.player_setting_audio, audioLanguageLabel(settings.getAudioLanguage()))) {
            showAudioLanguageDialog()
        }
        addSettingsRow(getString(R.string.player_setting_audio_quality, audioBitrateLabel(settings.getAudioBitrate()))) {
            showAudioBitrateDialog()
        }
        addSettingsRow(getString(R.string.player_setting_cache, cacheLabel(settings.getCacheSizeMbValue()))) {
            showCacheDialog()
        }
        if (!settings.isAudioOnly()) {
            addSettingsRow(getString(R.string.player_setting_lip_sync, lipSyncLabel(settings.getCarLipSyncMs()))) {
                showLipSyncPanel()
            }
        }
    }

    private fun lipSyncLabel(value: Int): String = getString(
        R.string.player_lip_sync_value, if (value > 0) "+$value" else value.toString()
    )

    private fun showLipSyncPanel() {
        val settings = SettingsRepository(requireContext())
        if (settings.isAudioOnly()) return
        settingsTitle?.setText(R.string.player_lip_sync_title)
        settingsBack?.visibility = View.VISIBLE
        val rows = settingsRows ?: return
        rows.removeAllViews()
        val panel = layoutInflater.inflate(R.layout.view_car_lip_sync, rows, false)
        rows.addView(panel)
        val value = panel.findViewById<TextView>(R.id.lip_sync_value)
        val slider = panel.findViewById<SeekBar>(R.id.lip_sync_slider)
        val earlier = panel.findViewById<Button>(R.id.lip_sync_earlier)
        val later = panel.findViewById<Button>(R.id.lip_sync_later)
        val reset = panel.findViewById<Button>(R.id.lip_sync_reset)
        slider.max = (CarLipSync.MAX_MS - CarLipSync.MIN_MS) / CarLipSync.STEP_MS

        fun refresh() {
            val offset = settings.getCarLipSyncMs()
            value.text = lipSyncLabel(offset)
            slider.progress = (offset - CarLipSync.MIN_MS) / CarLipSync.STEP_MS
            slider.contentDescription = getString(R.string.player_setting_lip_sync, lipSyncLabel(offset))
            earlier.isEnabled = offset > CarLipSync.MIN_MS
            later.isEnabled = offset < CarLipSync.MAX_MS
            reset.isEnabled = offset != 0
        }

        fun change(offset: Int) {
            settings.setCarLipSyncMs(offset)
            applyLipSync()
            refresh()
        }

        refresh()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) change(CarLipSync.MIN_MS + progress * CarLipSync.STEP_MS)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        earlier.setOnClickListener { change(settings.getCarLipSyncMs() - CarLipSync.STEP_MS) }
        later.setOnClickListener { change(settings.getCarLipSyncMs() + CarLipSync.STEP_MS) }
        reset.setOnClickListener { change(0) }
    }

    private fun addSettingsRow(label: String, onClick: () -> Unit) {
        val context = requireContext()
        val button = Button(context, null, 0, R.style.Widget_YouTubeCar_TextButton).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            setTextColor(context.getColor(R.color.car_on_surface))
            setBackgroundResource(R.drawable.bg_car_button)
            minHeight = dp(60)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { onClick() }
        }
        settingsRows?.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    private fun showChoicePanel(
        titleRes: Int,
        labels: Array<String>,
        current: Int,
        onSelected: (Int) -> Unit
    ) {
        settingsTitle?.setText(titleRes)
        settingsBack?.visibility = View.VISIBLE
        settingsRows?.removeAllViews()
        labels.forEachIndexed { index, label ->
            addSettingsRow(if (index == current) "✓  $label" else label) {
                hideSettingsMenu()
                onSelected(index)
            }
        }
    }

    private fun showSpeedDialog() {
        val settings = SettingsRepository(requireContext())
        val values = SettingsRepository.PLAYBACK_SPEED_VALUES
        val labels = values.map { speedLabel(it.toFloatOrNull() ?: 1f) }.toTypedArray()
        val current = values.indexOf(speedValue(settings.getPlaybackSpeed())).coerceAtLeast(0)
        showChoicePanel(R.string.player_speed_title, labels, current) { which ->
            settings.setPlaybackSpeed(values[which])
            applyPlaybackSpeed()
            fpsFallbackRelaxedForItem = false
            manifestHardwareOverrideKey = null
            schedulePerformanceAdaptation("playback-speed")
        }
    }

    private fun showQualityDialog() {
        val settings = SettingsRepository(requireContext())
        val values = SettingsRepository.CAR_VIDEO_QUALITY_VALUES
        val labels = values.map(::qualityLabel).toTypedArray()
        val current = values.indexOf(settings.getCarVideoQuality()).coerceAtLeast(0)
        showChoicePanel(R.string.player_quality_title, labels, current) { which ->
            val selected = values[which]
            if (selected != settings.getCarVideoQuality()) {
                settings.setCarVideoQuality(selected)
                reloadCurrent(keepPosition = true)
            }
        }
    }

    private fun showAudioLanguageDialog() {
        val settings = SettingsRepository(requireContext())
        val values = SettingsRepository.AUDIO_LANGUAGE_VALUES
        val labels = values.map(::audioLanguageLabel).toTypedArray()
        val current = values.indexOf(settings.getAudioLanguage()).coerceAtLeast(0)
        showChoicePanel(R.string.player_audio_track_title, labels, current) { which ->
            val selected = values[which]
            if (selected != settings.getAudioLanguage()) {
                settings.setAudioLanguage(selected)
                reloadCurrent(keepPosition = true)
            }
        }
    }

    private fun showAudioBitrateDialog() {
        val settings = SettingsRepository(requireContext())
        val values = SettingsRepository.AUDIO_BITRATE_VALUES
        val labels = values.map(::audioBitrateLabel).toTypedArray()
        val current = values.indexOf(settings.getAudioBitrate()).coerceAtLeast(0)
        showChoicePanel(R.string.player_audio_quality_title, labels, current) { which ->
            val selected = values[which]
            if (selected != settings.getAudioBitrate()) {
                settings.setAudioBitrate(selected)
                reloadCurrent(keepPosition = true)
            }
        }
    }

    private fun showCacheDialog() {
        val settings = SettingsRepository(requireContext())
        val values = SettingsRepository.CACHE_SIZE_VALUES
        val labels = values.map(::cacheLabel).toTypedArray()
        val current = values.indexOf(settings.getCacheSizeMbValue()).coerceAtLeast(0)
        showChoicePanel(R.string.player_cache_title, labels, current) { which ->
            settings.setCacheSizeMb(values[which])
        }
    }

    private fun cacheLabel(value: String): String = "$value MB"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun speedValue(speed: Float): String =
        SettingsRepository.PLAYBACK_SPEED_VALUES.minByOrNull {
            kotlin.math.abs((it.toFloatOrNull() ?: 1f) - speed)
        } ?: "1"

    private fun speedLabel(speed: Float): String {
        val raw = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
        return "$raw×"
    }

    private fun qualityLabel(value: String): String = when (value) {
        "auto" -> getString(R.string.player_quality_auto)
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        "360" -> "360p"
        "240" -> "240p"
        "144" -> "144p"
        else -> value
    }

    private fun audioLanguageLabel(value: String): String = when (value) {
        "auto" -> getString(R.string.player_audio_auto)
        "device" -> getString(R.string.player_audio_device)
        "original" -> getString(R.string.player_audio_original)
        "de" -> getString(R.string.player_audio_german)
        "en" -> getString(R.string.player_audio_english)
        else -> value
    }

    private fun audioBitrateLabel(value: String): String = when (value) {
        "auto" -> getString(R.string.player_audio_quality_auto)
        "high" -> getString(R.string.player_audio_quality_high)
        "medium" -> getString(R.string.player_audio_quality_medium)
        "low" -> getString(R.string.player_audio_quality_low)
        else -> value
    }

    private fun reloadCurrent(keepPosition: Boolean) {
        val video = queue.getOrNull(queueIndex) ?: return
        val exo = player
        val position = if (keepPosition) exo?.currentPosition ?: 0L else 0L
        val keepPlaying = if (awaitingReady || loadJob?.isActive == true) requestedPlayWhenReady else exo?.let { it.playWhenReady || it.isPlaying } ?: false
        hardwareRecoveryExcludedStreamIds.clear()
        hardwareRecoveryAttempts = 0
        hardwareRecoveryStreamInFlight = null
        manifestHardwareExcludedTrackKeys.clear()
        manifestHardwareFailedFormatKeys.clear()
        manifestHardwareOverrideKey = null
        lastAppliedVideoBudget = null
        playItem(video, resumePositionMs = position, forcePlay = keepPlaying, preserveSource = true)
    }

    private fun bindAudioChrome(video: MediaDescriptionCompat) {
        titleText?.text = video.title
        view?.findViewById<TextView>(R.id.audio_title)?.text = video.title
        view?.findViewById<TextView>(R.id.audio_channel)?.apply {
            text = video.subtitle
            visibility = if (video.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        loadArtwork(video.iconUri?.toString())
    }

    private fun loadArtwork(url: String?) {
        thumbJob?.cancel()
        val bg = view?.findViewById<ImageView>(R.id.audio_art)
        val fg = view?.findViewById<ImageView>(R.id.audio_art_foreground)
        bg?.setImageDrawable(null)
        fg?.setImageDrawable(null)
        if (url.isNullOrBlank()) return
        thumbJob = viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = loadThumbnailBitmap(url, targetPx = 640)
            if (bitmap != null) {
                bg?.setImageBitmap(bitmap)
                fg?.setImageBitmap(bitmap)
            }
        }
    }

    private fun playItem(
        video: MediaDescriptionCompat,
        resumePositionMs: Long = 0L,
        forcePlay: Boolean = true,
        preserveSource: Boolean = false
    ) {
        val videoId = video.mediaId ?: return
        relinquished = false
        awaitingReady = false
        requestedPlayWhenReady = forcePlay
        loadJob?.cancel()
        preserveSourcePosition = preserveSource && (player?.mediaItemCount ?: 0) > 0
        if (!preserveSourcePosition) {
            currentSourceHasVideo = false
            currentSourceHasAudioForSeamlessSwitch = false
            currentSourceLayout = SourceLayout.AUDIO_ONLY
            player?.stop()
            player?.clearMediaItems()
        }
        HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "playItem videoId=$videoId resume=$resumePositionMs forcePlay=$forcePlay audioOnly=${SettingsRepository(requireContext()).isAudioOnly()}")
        resetStallWatch()
        stallRecoveriesWithoutReady = 0
        if (currentVideoId != videoId) {
            recoveryAttemptedForVideoId = null
            recoveryAttemptsInWindow = 0
            hardwareRecoveryExcludedStreamIds.clear()
            hardwareRecoveryAttempts = 0
            hardwareRecoveryStreamInFlight = null
            manifestHardwareExcludedTrackKeys.clear()
            manifestHardwareFailedFormatKeys.clear()
            currentResolvedStreamInfo = null
            currentSourceIsManifest = false
        }
        currentVideoId = videoId
        if (!preserveSourcePosition) {
            knownDurationMs = 0L
            currentIsLive = false
        }
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit()
            .putString(KEY_LAST_VIDEO_ID, videoId)
            .apply()
        NowPlayingBus.update(
            videoId = videoId,
            title = video.title?.toString() ?: videoId,
            channelTitle = video.subtitle?.toString(),
            thumbnailUrl = video.iconUri?.toString(),
            isPlaying = false,
            positionMs = resumePositionMs,
            durationMs = 0L,
            controllable = true,
            isBuffering = true,
            presentation = if (SettingsRepository(requireContext()).isAudioOnly()) PlaybackPresentation.AUDIO else PlaybackPresentation.VIDEO,
            queueIndex = queueIndex,
            queueSize = queue.size.coerceAtLeast(1), owner = this@ExoPlayerVideoFragment)
        if (SettingsRepository(requireContext()).isHistoryEnabled()) {
            WatchHistoryStore(requireContext()).recordFromDescription(video)
        }
        bindAudioChrome(video)
        viewLifecycleOwner.lifecycleScope.launch {
            val repaired = metadataRefetcher.repairIfNeeded(video)
            if (repaired.mediaId == currentVideoId && repaired !== video) {
                queue = queue.map { if (it.mediaId == repaired.mediaId) repaired else it }
                bindAudioChrome(repaired)
                val currentState = NowPlayingBus.state.value ?: return@launch
                val repairedId = repaired.mediaId ?: return@launch
                if (currentState.videoId == repairedId) {
                    NowPlayingBus.update(
                        videoId = repairedId,
                        title = repaired.title?.toString() ?: repairedId,
                        channelTitle = repaired.subtitle?.toString(),
                        thumbnailUrl = repaired.iconUri?.toString(),
                        isPlaying = currentState.isPlaying,
                        positionMs = currentState.positionMs,
                        durationMs = currentState.durationMs,
                        controllable = currentState.controllable,
                        isBuffering = currentState.isBuffering,
                        presentation = currentState.presentation,
                        queueIndex = queueIndex,
                        queueSize = queue.size.coerceAtLeast(1), owner = this@ExoPlayerVideoFragment)
                }
            }
        }

        val settings = SettingsRepository(requireContext())
        if (!settings.isAudioOnly() && settings.getVideoBackend() == "webview") {
            videoHost()?.switchToWebViewFallback(video)
            return
        }
        if (settings.isAudioOnly()) {
            loadAudio(videoId, resumePositionMs, playWhenReady = forcePlay)
        } else {
            loadVideo(videoId, video, resumePositionMs, playWhenReady = forcePlay)
        }
    }

    private fun loadAudio(
        videoId: String,
        resumePositionMs: Long,
        playWhenReady: Boolean = true,
        stallRecovery: Boolean = false
    ) {
        requestedPlayWhenReady = playWhenReady
        HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "loadAudio videoId=$videoId resume=$resumePositionMs play=$playWhenReady stallRecovery=$stallRecovery")
        if (!stallRecovery) showStatus(getString(R.string.video_player_loading_audio))
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            when (val result = streamResolver.resolveAudioStreamUrl(videoId, audioPrefs())) {
                is StreamResolver.Result.Success -> {
                    currentResolvedStreamInfo = null
                    currentSourceIsManifest = false
                    manifestHardwareExcludedTrackKeys.clear()
                    manifestHardwareFailedFormatKeys.clear()
                    currentIsLive = result.isLive
                    if (result.durationMs > 0L) knownDurationMs = result.durationMs
                    currentSourceHasVideo = false
                    currentSourceHasAudioForSeamlessSwitch = false
                    currentSourceLayout = result.layout
                    trackSelector?.let { PlayerFactory.setAudioOnly(it, true, result.layout) }
                    playResolvedSource(
                        result.streamUrl,
                        resumePositionMs,
                        isLive = result.isLive,
                        playWhenReady = playWhenReady
                    )
                }
                is StreamResolver.Result.Error -> {
                    // The stalled source is still loaded and may recover by itself; never trade
                    // it for an error screen just because the refetch failed too.
                    if (stallRecovery) {
                        HeadUnitDiagnostics.warning("ExoPlayerVideoFragment", "stall-recovery resolve failed videoId=$videoId message=${result.message}")
                    } else {
                        showPlaybackError(result.message)
                    }
                }
            }
        }
    }

    private fun sourcePosition(requested: Long): Long {
        val position = if (preserveSourcePosition) player?.currentPosition?.coerceAtLeast(0L) ?: requested else requested
        preserveSourcePosition = false
        return position
    }

    private fun playResolvedSource(
        url: String,
        resumePositionMs: Long,
        isLive: Boolean = false,
        playWhenReady: Boolean = true
    ) {
        statusText?.visibility = View.GONE
        playbackStatus?.clear()
        val exo = player ?: return
        awaitingReady = true
        PlayerFactory.replaceSource(
            player = exo,
            source = PlaybackSources.mediaSource(url),
            resumePositionMs = sourcePosition(resumePositionMs),
            isLive = isLive,
            playWhenReady = false
        )
        applyPlaybackSpeed()
        updateAudioProgressUi()
    }

    /**
     * Last-resort runtime guard. The custom renderer already removes software codecs whenever
     * the *actual Media3 format* has a capable hardware decoder. If software still starts on a
     * progressive YouTube itag, its extractor metadata was insufficient/mismatched or no HW
     * decoder supports that concrete format. Try other known-hardware itags before accepting the
     * software path.
     */
    private fun onVideoDecoderStarted(identity: HardwareVideoCodecs.DecoderIdentity, runtimeFormat: Format?) {
        if (identity.hardwareAccelerated) {
            hardwareRecoveryStreamInFlight = null
            return
        }
        val ctx = context ?: return
        if (relinquished || SettingsRepository(ctx).isAudioOnly()) return
        if (currentSourceIsManifest) {
            runtimeFormat?.let { format ->
                manifestHardwareFailedFormatKeys += Media3HardwareDecode.formatKey(format)
                HeadUnitDiagnostics.warning(
                    "HardwareDecode",
                    "manifest-runtime-format-blacklisted videoId=$currentVideoId " +
                        "format=${format.width}x${format.height}@${format.frameRate} codecs=${format.codecs}"
                )
            }
            manifestHardwareOverrideKey?.let { manifestHardwareExcludedTrackKeys += it }
            val exo = player ?: return
            var budget = currentVideoBudget()
            var choice = Media3HardwareDecode.bestManifestHardwareTrackSet(
                context = ctx.applicationContext,
                tracks = exo.currentTracks,
                budget = budget,
                excludedKeys = manifestHardwareExcludedTrackKeys,
                excludedFormatKeys = manifestHardwareFailedFormatKeys
            )
            if (choice == null && !budget.relaxFrameRate) {
                fpsFallbackRelaxedForItem = true
                budget = currentVideoBudget(relaxFrameRate = true)
                trackSelector?.let { PlayerFactory.applyCarVideoConstraints(it, budget) }
                choice = Media3HardwareDecode.bestManifestHardwareTrackSet(
                    context = ctx.applicationContext,
                    tracks = exo.currentTracks,
                    budget = budget,
                    excludedKeys = manifestHardwareExcludedTrackKeys,
                    excludedFormatKeys = manifestHardwareFailedFormatKeys
                )
            }
            if (choice == null) {
                HeadUnitDiagnostics.warning(
                    "HardwareDecode",
                    "software-fallback-accepted-manifest videoId=$currentVideoId decoder=${identity.name} " +
                        "reason=all-runtime-hardware-sets-exhausted tried=${manifestHardwareExcludedTrackKeys.size}"
                )
                return
            }
            manifestHardwareOverrideKey = choice.key
            val top = choice.highestFormat
            HeadUnitDiagnostics.warning(
                "HardwareDecode",
                "software-decoder-manifest-recovery videoId=$currentVideoId decoder=${identity.name} " +
                    "tracks=${choice.trackIndices.joinToString()} mime=${top.sampleMimeType} codecs=${top.codecs} " +
                    "top=${top.width}x${top.height}@${top.frameRate} " +
                    "hwDecoders=${choice.decoderNames.joinToString()} tried=${manifestHardwareExcludedTrackKeys.size}"
            )
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(choice.group.mediaTrackGroup, choice.trackIndices)
                )
                .build()
            return
        }
        val info = currentResolvedStreamInfo ?: return
        if (hardwareRecoveryStreamInFlight == info.id) return
        val videoId = currentVideoId ?: return
        val video = queue.getOrNull(queueIndex) ?: return
        val exo = player ?: return
        val resumePosition = exo.currentPosition.coerceAtLeast(0L)
        val keepPlaying = if (awaitingReady || loadJob?.isActive == true) {
            requestedPlayWhenReady
        } else {
            exo.playWhenReady || exo.isPlaying
        }

        hardwareRecoveryExcludedStreamIds += info.id
        hardwareRecoveryAttempts++
        hardwareRecoveryStreamInFlight = info.id
        HeadUnitDiagnostics.warning(
            "HardwareDecode",
            "software-decoder-recovery videoId=$videoId decoder=${identity.name} " +
                "rejectStream=${info.id} itag=${info.itag} codecHint=${info.codecHint} " +
                "tried=$hardwareRecoveryAttempts excluded=${hardwareRecoveryExcludedStreamIds.size}"
        )
        loadVideo(
            videoId,
            video,
            resumePosition,
            playWhenReady = keepPlaying,
            hardwareRecovery = true
        )
    }

    private fun applyResolvedProgressiveBudget(info: StreamResolver.VideoResult.StreamInfo?) {
        info ?: return
        val budget = currentVideoBudget(relaxFrameRate = false)
        if (budget.maxSourceFps != Int.MAX_VALUE && info.fps > 0 && info.fps > budget.maxSourceFps) {
            fpsFallbackRelaxedForItem = true
            val relaxed = currentVideoBudget(relaxFrameRate = true)
            trackSelector?.let { PlayerFactory.applyCarVideoConstraints(it, relaxed) }
            lastAppliedVideoBudget = relaxed
            HeadUnitDiagnostics.warning(
                "HardwareAuto",
                "progressive-fps-fallback itag=${info.itag} sourceFps=${info.fps} " +
                    "requestedMaxSourceFps=${budget.maxSourceFps} projectionFps=${budget.projectionFps} " +
                    "speed=${budget.playbackSpeed}"
            )
        }
    }

    private fun loadVideo(
        videoId: String,
        video: MediaDescriptionCompat,
        resumePositionMs: Long,
        playWhenReady: Boolean = true,
        hardwareRecovery: Boolean = false,
        stallRecovery: Boolean = false
    ) {
        requestedPlayWhenReady = playWhenReady
        HeadUnitDiagnostics.event(
            "ExoPlayerVideoFragment",
            "loadVideo videoId=$videoId resume=$resumePositionMs play=$playWhenReady " +
                "hardwareRecovery=$hardwareRecovery stallRecovery=$stallRecovery " +
                "excluded=${hardwareRecoveryExcludedStreamIds.joinToString()}"
        )
        val settings = SettingsRepository(requireContext())
        val quality = settings.getCarVideoQuality()
        fpsFallbackRelaxedForItem = false
        val budget = currentVideoBudget(relaxFrameRate = false)
        if (!hardwareRecovery && !stallRecovery) showStatus(getString(R.string.video_player_loading))
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            trackSelector?.let {
                if (!hardwareRecovery) {
                    PlayerFactory.clearVideoTrackOverride(it)
                    manifestHardwareOverrideKey = null
                }
                // Before the budget: leaving a lowest-rung pin resets the size ceiling.
                PlayerFactory.setAudioOnly(it, false)
                PlayerFactory.applyCarVideoConstraints(it, budget)
                lastAppliedVideoBudget = budget
            }
            // Keep VOD adaptive for projection reliability. The player-level bitrate, codec,
            // size and FPS constraints bound HLS/DASH traffic before Media3 selects a rung.
            val preferAdaptive = true
            when (val result = streamResolver.resolveVideoStream(
                videoId,
                quality,
                audioPrefs(),
                maxFps = budget.maxSourceFps,
                // A stall refetch must not fall back onto an itag the decoder already rejected.
                excludedStreamIds = if (hardwareRecovery || stallRecovery) hardwareRecoveryExcludedStreamIds else emptySet(),
                requireHardware = hardwareRecovery,
                videoBudget = budget,
                preferAdaptive = preferAdaptive
            )) {
                is StreamResolver.VideoResult.Muxed -> {
                    currentResolvedStreamInfo = result.info
                    applyResolvedProgressiveBudget(result.info)
                    currentSourceIsManifest = false
                    currentSourceLayout = SourceLayout.MUXED_PROGRESSIVE
                    hardwareRecoveryStreamInFlight = null
                    currentIsLive = false
                    currentSourceHasVideo = true
                    currentSourceHasAudioForSeamlessSwitch = true
                    playResolvedSource(result.url, resumePositionMs, playWhenReady = playWhenReady)
                }
                is StreamResolver.VideoResult.Manifest -> {
                    currentResolvedStreamInfo = null
                    currentSourceIsManifest = true
                    currentSourceLayout = result.layout
                    if (!hardwareRecovery) {
                        manifestHardwareExcludedTrackKeys.clear()
                        manifestHardwareFailedFormatKeys.clear()
                        manifestHardwareOverrideKey = null
                    }
                    hardwareRecoveryStreamInFlight = null
                    currentIsLive = result.isLive
                    currentSourceHasVideo = true
                    currentSourceHasAudioForSeamlessSwitch = true
                    playResolvedSource(
                        result.url,
                        resumePositionMs,
                        isLive = result.isLive,
                        playWhenReady = playWhenReady
                    )
                }
                is StreamResolver.VideoResult.Separate -> {
                    currentResolvedStreamInfo = result.info
                    applyResolvedProgressiveBudget(result.info)
                    currentSourceIsManifest = false
                    currentSourceLayout = SourceLayout.SEPARATE
                    hardwareRecoveryStreamInFlight = null
                    currentIsLive = false
                    statusText?.visibility = View.GONE
        playbackStatus?.clear()
                    val exo = player ?: return@launch
                    val audioUrl = result.audioUrl
                    currentSourceHasVideo = true
                    currentSourceHasAudioForSeamlessSwitch = audioUrl != null
                    val source = if (audioUrl != null) {
                        PlaybackSources.merged(result.videoUrl, audioUrl)
                    } else {
                        PlaybackSources.mediaSource(result.videoUrl)
                    }
                    awaitingReady = true
                    PlayerFactory.replaceSource(
                        exo,
                        source,
                        sourcePosition(resumePositionMs),
                        isLive = false,
                        playWhenReady = false
                    )
                    applyPlaybackSpeed()
                }
                is StreamResolver.VideoResult.Error -> {
                    if (hardwareRecovery) {
                        hardwareRecoveryStreamInFlight = null
                        HeadUnitDiagnostics.warning(
                            "HardwareDecode",
                            "hardware-recovery-exhausted videoId=$videoId attempts=$hardwareRecoveryAttempts " +
                                "keeping-current-software-decoder message=${result.message}"
                        )
                        return@launch
                    }
                    if (stallRecovery) {
                        HeadUnitDiagnostics.warning("ExoPlayerVideoFragment", "stall-recovery resolve failed videoId=$videoId message=${result.message}")
                        return@launch
                    }
                    if (settings.getVideoBackend() != "exoplayer") {
                        videoHost()?.switchToWebViewFallback(video)
                    } else {
                        showPlaybackError(result.message)
                    }
                }
            }
        }
    }

    private fun resetStallWatch() {
        stallBufferedPositionMs = C.TIME_UNSET
        stallSinceElapsedMs = 0L
        bufferingSinceElapsedMs = 0L
        stallNetworkWaitLogged = false
    }

    /**
     * Head-unit logs showed loads and rebuffers stuck for 8-39 s with the buffer standing still
     * (a dead socket or a throttled signed URL) until the user changed the quality by hand.
     * While the player waits for data — the first load or a rebuffer during play — a buffer that
     * has not grown for a few seconds, or a wait that runs far too long, gets a fresh resolve
     * at the current position. Guard rails: never without a validated network (a refetch cannot
     * help then and could only turn a wait into an error), a cooldown, and at most a few
     * attempts until the player reaches READY again. A failed refetch keeps the old source.
     */
    private fun checkLoadingStall(exo: ExoPlayer) {
        val waitingForData = !relinquished && loadJob?.isActive != true &&
            exo.playbackState == Player.STATE_BUFFERING && (awaitingReady || exo.playWhenReady)
        if (!waitingForData) {
            resetStallWatch()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (bufferingSinceElapsedMs == 0L) bufferingSinceElapsedMs = now
        val buffered = exo.bufferedPosition
        if (buffered != stallBufferedPositionMs) {
            stallBufferedPositionMs = buffered
            stallSinceElapsedMs = now
        }
        val noProgressMs = now - stallSinceElapsedMs
        val bufferingMs = now - bufferingSinceElapsedMs
        val noProgressLimit = if (awaitingReady) STALL_STARTUP_NO_PROGRESS_MS else STALL_NO_PROGRESS_MS
        if (noProgressMs < noProgressLimit && bufferingMs < STALL_MAX_BUFFERING_MS) return
        if (now - lastStallRecoveryElapsedMs < STALL_RECOVERY_COOLDOWN_MS) return
        if (stallRecoveriesWithoutReady >= MAX_STALL_RECOVERIES) return
        val ctx = context ?: return
        if (!hasValidatedNetwork(ctx)) {
            if (!stallNetworkWaitLogged) {
                stallNetworkWaitLogged = true
                HeadUnitDiagnostics.warning("ExoPlayerVideoFragment", "stall waiting-for-network videoId=$currentVideoId")
            }
            return
        }
        val current = queue.getOrNull(queueIndex) ?: return
        val videoId = current.mediaId ?: return
        if (videoId != currentVideoId) return
        stallRecoveriesWithoutReady++
        lastStallRecoveryElapsedMs = now
        val position = exo.currentPosition.coerceAtLeast(0L)
        val keepPlaying = if (awaitingReady) requestedPlayWhenReady else exo.playWhenReady
        HeadUnitDiagnostics.warning(
            "ExoPlayerVideoFragment",
            "stall-recovery videoId=$videoId pos=$position buffered=$buffered noProgressMs=$noProgressMs " +
                "bufferingMs=$bufferingMs startup=$awaitingReady attempt=$stallRecoveriesWithoutReady"
        )
        resetStallWatch()
        streamResolver.invalidate(videoId)
        if (SettingsRepository(ctx).isAudioOnly()) {
            loadAudio(videoId, position, playWhenReady = keepPlaying, stallRecovery = true)
        } else {
            loadVideo(videoId, current, position, playWhenReady = keepPlaying, stallRecovery = true)
        }
    }

    /**
     * Takeover from a still-playing phone/native-audio player. A seek outside the buffer threw
     * the fresh buffer away and rebuffered 1.5-2.6 s of frozen picture at every handoff in HU
     * logs. The handoff therefore loads slightly ahead ([HANDOFF_LEAD_MS]); a target inside the
     * buffer is a cheap seek, a target just behind the loaded start skips that short gap
     * instead of rebuffering. Only a far-off target still pays for a full seek.
     */
    private fun alignTakeoverPosition(exo: ExoPlayer, target: Long) {
        val current = exo.currentPosition
        val action = when {
            target in current..(exo.bufferedPosition - TAKEOVER_BUFFER_MARGIN_MS) -> "seek-in-buffer"
            target < current && current - target <= HANDOFF_LEAD_MS + TAKEOVER_BEHIND_TOLERANCE_MS -> "keep-ahead"
            else -> "seek-rebuffer"
        }
        HeadUnitDiagnostics.event(
            "ExoPlayerVideoFragment",
            "takeover-align action=$action target=$target current=$current buffered=${exo.bufferedPosition}"
        )
        if (action != "keep-ahead") exo.seekTo(target)
    }

    private fun hasValidatedNetwork(ctx: Context): Boolean {
        val connectivity = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isDecoderError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> true
        else -> false
    }

    private fun showPlaybackError(message: String) {
        awaitingReady = false
        if (castRequestId != 0L && !VideoSessionBridge.isOwner(this)) {
            RemoteCastBus.projectionFailed(castRequestId)
            return
        }
        statusText?.visibility = View.GONE
        playbackStatus?.error(message)
        CarPlaybackState.clear(CarPlaybackState.Owner.PROJECTION)
        NowPlayingBus.clear(owner = this@ExoPlayerVideoFragment)
    }

    override fun play() {
        requestedPlayWhenReady = true
        if (!awaitingReady) player?.play()
    }

    override fun pause() {
        requestedPlayWhenReady = false
        player?.pause()
    }

    override fun stopPlayback() {
        saveHistoryProgress()
        HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "stopPlayback videoId=$currentVideoId")
        player?.stop()
        CarPlaybackState.clear(CarPlaybackState.Owner.PROJECTION)
        NowPlayingBus.clear(owner = this@ExoPlayerVideoFragment)
        videoHost()?.leavePlayback(stopSession = true)
    }

    override fun skipToNext() {
        if (queueIndex < queue.lastIndex) {
            saveHistoryProgress()
            queueIndex++
            val next = queue[queueIndex]
            playItem(next, resumePositionMs = WatchHistoryStore.resumePositionFromDescription(next))
        }
    }

    override fun skipToPrevious() {
        if (queueIndex > 0) {
            saveHistoryProgress()
            queueIndex--
            val previous = queue[queueIndex]
            playItem(previous, resumePositionMs = WatchHistoryStore.resumePositionFromDescription(previous))
        } else {
            player?.seekTo(0)
        }
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun playMediaId(videoId: String) {
        val index = queue.indexOfFirst { it.mediaId == videoId }
        val video: MediaDescriptionCompat
        if (index >= 0) {
            queueIndex = index
            video = queue[index]
        } else {
            video = MediaDescriptionCompat.Builder().setMediaId(videoId).setTitle(videoId).build()
            queue = listOf(video)
            queueIndex = 0
        }
        playItem(video, resumePositionMs = WatchHistoryStore.resumePositionFromDescription(video))
    }

    override fun playQueue(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) {
        saveHistoryProgress()
        this.queue = queue.ifEmpty { listOf(video) }
        queueIndex = this.queue.indexOfFirst { it.mediaId == video.mediaId }.takeIf { it >= 0 } ?: 0
        val selected = this.queue[queueIndex]
        playItem(selected, resumePositionMs = WatchHistoryStore.resumePositionFromDescription(selected))
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun getPositionMs(): Long = player?.currentPosition ?: 0L

    override fun getDurationMs(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L

    /**
     * Called by the car host immediately before the projection activity is destroyed. Preserve
     * queue and position on the phone, but intentionally leave playback paused. This gives the
     * user an explicit Play action on the handset after an AA disconnect instead of surprising
     * them with speaker playback.
     */
    fun handoffToPhonePaused() {
        val current = queue.getOrNull(queueIndex) ?: return
        val position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        player?.pause()
        // Persist first: the process may be killed after AA is gone, and the native AA player
        // restores this item/position on the next connection.
        LastSessionStore(requireContext()).save(
            queue, queueIndex, position,
            knownDurationMs.takeIf { it > 0L } ?: player?.duration?.coerceAtLeast(0L) ?: 0L
        )
        PlaybackEngine.adoptProjectionSessionPaused(
            context = requireContext().applicationContext,
            video = current,
            projectionQueue = queue,
            projectionQueueIndex = queueIndex,
            positionMs = position,
            isLive = currentIsLive
        )
    }

    override fun snapshot() = VideoSessionBridge.Snapshot(
        queue, queueIndex, getPositionMs().coerceAtLeast(0L),
        if (awaitingReady || loadJob?.isActive == true) requestedPlayWhenReady else player?.playWhenReady == true
    )

    override fun onOwnershipLost() {
        relinquished = true
        awaitingReady = false
        projectionSuspendJob?.cancel()
        projectionSuspendJob = null
        loadJob?.cancel()
        thumbJob?.cancel()
        playerView?.player = null
        player?.pause()
        player?.stop()
        player?.clearMediaItems()
        CarPlaybackQos.setProjectedVideoVisible(false)
        CarPlaybackState.clear(CarPlaybackState.Owner.PROJECTION)
    }

    fun cancelPendingCast(requestId: Long) {
        if (requestId == castRequestId) onOwnershipLost()
    }

    private fun videoHost(): VideoHost? = context as? VideoHost

    private fun showStatus(message: String) {
        statusText?.visibility = View.GONE
        playbackStatus?.loading(message)
    }

    override fun onStart() {
        super.onStart()
        projectionVisible = true
        projectionSuspendJob?.cancel()
        projectionSuspendJob = null
        focusSuspendJob?.cancel()
        focusSuspendJob = null
        focusVideoSuspended = false
        projectionSnapshot = ProjectionPerformanceState.snapshot()
        val settings = SettingsRepository(requireContext())
        if (!settings.isAudioOnly()) {
            trackSelector?.let {
                // Unsuspend first: it drops a lowest-rung pin, then the budget is re-applied.
                PlayerFactory.setVideoSuspended(it, false)
                PlayerFactory.applyCarVideoConstraints(it, currentVideoBudget())
            }
            playerView?.player = player
            if (requestedPlayWhenReady && player?.playWhenReady != true) player?.play()
        }
        updateProjectionQos()
        schedulePerformanceAdaptation("onStart")
        if (!projectionSnapshot.hasWindowFocus) scheduleFocusSuspend()
        HeadUnitDiagnostics.event(
            "ExoPlayerVideoFragment",
            "projection-visible=true videoId=$currentVideoId projectionFps=${projectionSnapshot.effectiveFrameRate} " +
                "windowFocus=${projectionSnapshot.hasWindowFocus}"
        )
    }

    override fun onStop() {
        projectionVisible = false
        focusSuspendJob?.cancel()
        focusSuspendJob = null
        projectionSuspendJob?.cancel()
        val ctx = context
        val audioOnly = ctx?.let { SettingsRepository(it).isAudioOnly() } ?: true
        if (!audioOnly) {
            // Android Auto produces short onStop/onStart transitions while changing chrome/screens.
            // Suspending the video track immediately tears down MediaCodec and discards the useful
            // buffer, so the next onStart looks like a network rebuffer. Keep the decoder/surface
            // warm for a short grace period and only enter background-audio mode if the projection
            // really remains hidden. Playback QoS intentionally stays elevated during that grace.
            projectionSuspendJob = lifecycleScope.launch {
                delay(PROJECTION_SUSPEND_GRACE_MS)
                if (projectionVisible) return@launch
                if (currentSourceHasAudioForSeamlessSwitch) {
                    suspendHiddenVideo()
                } else if (currentSourceHasVideo && requestedPlayWhenReady) {
                    // A video-only source has no useful background payload. Pause it once the HU
                    // has genuinely been away long enough to justify releasing decode resources.
                    player?.pause()
                }
                playerView?.player = null
                CarPlaybackQos.setProjectedVideoVisible(false)
                HeadUnitDiagnostics.event(
                    "ExoPlayerVideoFragment",
                    "projection-suspended afterMs=$PROJECTION_SUSPEND_GRACE_MS " +
                        "videoTrack=${currentSourceHasAudioForSeamlessSwitch} videoId=$currentVideoId"
                )
            }
        } else {
            CarPlaybackQos.setProjectedVideoVisible(false)
        }
        HeadUnitDiagnostics.event(
            "ExoPlayerVideoFragment",
            "projection-visible=false suspendScheduled=${!audioOnly} graceMs=$PROJECTION_SUSPEND_GRACE_MS videoId=$currentVideoId"
        )
        super.onStop()
    }

    override fun onDestroyView() {
        ProjectionPerformanceState.unregister(projectionPerformanceListener)
        meteredWatcher?.stop()
        meteredWatcher = null
        projectionSuspendJob?.cancel()
        projectionSuspendJob = null
        focusSuspendJob?.cancel()
        focusSuspendJob = null
        performanceAdaptJob?.cancel()
        performanceAdaptJob = null
        thermalRecoveryJob?.cancel()
        thermalRecoveryJob = null
        dropPressureRecoveryJob?.cancel()
        dropPressureRecoveryJob = null
        CarPlaybackQos.setProjectedVideoVisible(false)
        saveHistoryProgress()
        HeadUnitDiagnostics.event("ExoPlayerVideoFragment", "onDestroyView videoId=$currentVideoId pos=${player?.currentPosition ?: -1L}")
        thumbJob?.cancel()
        loadJob?.cancel()
        val wasOwner = VideoSessionBridge.isOwner(this)
        if (wasOwner) {
            CarPlaybackState.clear(CarPlaybackState.Owner.PROJECTION)
            NowPlayingBus.clear(owner = this@ExoPlayerVideoFragment)
        }
        VideoSessionBridge.unregister(this)
        playerView?.player = null
        player?.release()
        player = null
        trackSelector = null
        playbackStatus = null
        statusText = null
        settingsOverlay = null
        settingsTitle = null
        settingsRows = null
        settingsBack = null
        super.onDestroyView()
    }

    companion object {
        private const val PROJECTION_SUSPEND_GRACE_MS = 10_000L
        private const val PROJECTION_FOCUS_SUSPEND_GRACE_MS = 10_000L
        private const val PERFORMANCE_ADAPT_DEBOUNCE_MS = 1_500L
        private const val THERMAL_POLL_INTERVAL_MS = 5_000L
        private const val THERMAL_RECOVERY_DELAY_MS = 60_000L
        private const val DROP_PRESSURE_RECOVERY_MS = 20_000L
        /** Drops above this buffered duration are not a reason to lower Auto. */
        private const val DROP_PRESSURE_HEALTHY_BUFFER_MS = 8_000L
        /** Suppress drop-pressure while Media3 rebuilds after a quality/track adaptation. */
        private const val DROP_PRESSURE_ADAPT_GRACE_MS = 5_000L
        /** Drop bursts shorter than this are treated as seek/override noise. */
        private const val DROP_PRESSURE_MIN_WINDOW_MS = 2_000L
        private const val MENU_SPEED = 1001
        private const val MENU_QUALITY = 1002
        private const val MENU_AUDIO_LANGUAGE = 1003
        private const val MENU_AUDIO_QUALITY = 1004
        private const val RECOVERY_COOLDOWN_MS = 30_000L
        private const val MAX_RECOVERY_ATTEMPTS = 2
        /** First load: connect + container index + 2.5 s of media normally take 2-4 s. */
        private const val STALL_STARTUP_NO_PROGRESS_MS = 8_000L
        private const val STALL_NO_PROGRESS_MS = 4_000L
        /** Typical handoff load time (HU logs: 2.1-3.7 s) the still-playing source advances by. */
        const val HANDOFF_LEAD_MS = 3_000L
        private const val TAKEOVER_BEHIND_TOLERANCE_MS = 1_500L
        private const val TAKEOVER_BUFFER_MARGIN_MS = 250L
        /** Trickling (throttled) data never counts as "no progress"; cap the total wait too. */
        private const val STALL_MAX_BUFFERING_MS = 20_000L
        private const val STALL_RECOVERY_COOLDOWN_MS = 12_000L
        private const val MAX_STALL_RECOVERIES = 3
        private const val POSITION_TICK_INTERVAL_MS = 1_000L
        private const val HISTORY_SAVE_INTERVAL_MS = 30_000L
        const val ARG_VIDEO = "video"
        const val ARG_QUEUE = "queue"
        const val ARG_START_POSITION_MS = "start_position_ms"
        const val ARG_START_PAUSED = "start_paused"
        const val ARG_CAST_REQUEST_ID = "cast_request_id"
        const val KEY_LAST_VIDEO_ID = "last_video_id"

        fun newInstance(
            video: MediaDescriptionCompat,
            queue: List<MediaDescriptionCompat> = listOf(video),
            startPositionMs: Long = 0L,
            startPaused: Boolean = false,
            requestId: Long = 0L
        ) = ExoPlayerVideoFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_VIDEO, video)
                putParcelableArrayList(ARG_QUEUE, ArrayList(queue))
                putLong(ARG_START_POSITION_MS, startPositionMs.coerceAtLeast(0L))
                putBoolean(ARG_START_PAUSED, startPaused)
                putLong(ARG_CAST_REQUEST_ID, requestId)
            }
        }
    }
}
