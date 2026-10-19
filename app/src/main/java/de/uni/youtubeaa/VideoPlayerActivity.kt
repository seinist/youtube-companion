@file:Suppress("DEPRECATION")
package de.uni.youtubeaa

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import de.uni.youtubeaa.car.CarVideoPolicy
import de.uni.youtubeaa.car.DataSaverPolicy
import de.uni.youtubeaa.car.MeteredNetworkWatcher
import de.uni.youtubeaa.car.NetworkPolicy
import de.uni.youtubeaa.car.SourceLayout
import de.uni.youtubeaa.car.NowPlayingBus
import de.uni.youtubeaa.car.PlaybackEngine
import de.uni.youtubeaa.car.PlaybackPresentation
import de.uni.youtubeaa.car.PlaybackSources
import de.uni.youtubeaa.car.PlayerFactory
import de.uni.youtubeaa.car.CarPlaybackState
import de.uni.youtubeaa.car.RemoteCastBus
import de.uni.youtubeaa.car.SettingsRepository
import de.uni.youtubeaa.car.StreamResolver
import de.uni.youtubeaa.car.UserPlaylistStore
import de.uni.youtubeaa.car.VideoSessionBridge
import de.uni.youtubeaa.car.WatchHistoryStore
import de.uni.youtubeaa.car.YouTubeMediaBrowserService
import de.uni.youtubeaa.car.hybrid.BrowseFacade
import de.uni.youtubeaa.car.hybrid.BrowseOutcome
import de.uni.youtubeaa.car.formatPlaybackTime
import de.uni.youtubeaa.car.loadThumbnailBitmap
import de.uni.youtubeaa.car.publishedAtMs
import de.uni.youtubeaa.car.relativePublishedLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handy-Player mit Video- und Audio-only-Modus (umschaltbar, Position bleibt erhalten).
 *
 * Registriert sich als [VideoSessionBridge.Controller] und meldet seinen Zustand an
 * [NowPlayingBus], damit dieselbe Wiedergabe über die Medien-Benachrichtigung des Handys
 * (und, sofern im Auto sichtbar, dessen kleines Media-Widget) gesteuert werden kann.
 */
@OptIn(markerClass = [UnstableApi::class])
class VideoPlayerActivity : AppCompatActivity(), VideoSessionBridge.Controller {

    private val streamResolver = StreamResolver()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Playback remains available when notifications are declined. */ }
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var queue: List<MediaDescriptionCompat> = emptyList()
    private var queueIndex: Int = 0
    private var playbackEndHandled: Boolean = false
    private lateinit var settings: SettingsRepository
    private var thumbJob: Job? = null
    private var loadJob: Job? = null
    private var preserveSourcePosition = false
    private var recoveryAttempts = 0
    private var bufferingSinceMs = 0L
    private var bufferingPositionMs = 0L
    private var lastRecoveryAtMs = 0L
    private var isSeeking = false
    private var isFullscreen = false
    private var controllerOverlayVisible = true
    /** Authoritative duration from NewPipe metadata; avoids progressive estimate "growth". */
    private var knownDurationMs: Long
        get() = PlaybackEngine.knownDurationMs
        set(value) { PlaybackEngine.knownDurationMs = value }
    /** Live streams have no fixed duration; ExoPlayer's duration grows as the DVR window does,
     *  so it must never be locked into [knownDurationMs] like a VOD estimate would be. */
    private var currentIsLive: Boolean
        get() = PlaybackEngine.currentIsLive
        set(value) { PlaybackEngine.currentIsLive = value }
    /** True when the currently prepared source still contains a video track. */
    private var currentSourceHasVideo: Boolean
        get() = PlaybackEngine.sourceHasVideo
        set(value) { PlaybackEngine.sourceHasVideo = value }
    /** True when video -> audio can be done by track selection without replacing the source. */
    private var currentSourceHasAudioForSeamlessSwitch: Boolean
        get() = PlaybackEngine.sourceHasAudio
        set(value) { PlaybackEngine.sourceHasAudio = value }

    /** Packaging of the prepared source (shared with the engine for background handoff). */
    private var currentSourceLayout: SourceLayout
        get() = PlaybackEngine.sourceLayout
        set(value) { PlaybackEngine.sourceLayout = value }
    /** Height of a fixed progressive itag (0 for adaptive manifests / audio). */
    private var currentProgressiveHeight = 0
    private var meteredWatcher: MeteredNetworkWatcher? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            updateAudioProgressUi()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val historyTicker = object : Runnable {
        override fun run() {
            saveHistoryProgress()
            mainHandler.postDelayed(this, HISTORY_SAVE_INTERVAL_MS)
        }
    }

    // Registered on the shared PlaybackEngine player, not just held locally — it must be removed
    // explicitly in onDestroy() since that player now outlives this Activity (audio-only mode).
    private var playerListener: Player.Listener? = null
    private var backgroundAudioHandoffDone = false
    private var screenOffReceiverRegistered = false
    private var relatedJob: Job? = null
    private var channelAvatarJob: Job? = null
    private val browseFacade by lazy { BrowseFacade(this) }
    private var minimizeDownX = 0f
    private var minimizeDownY = 0f
    private var minimizeTracking = false
    private var minimizeDragging = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || isAudioOnlyMode()) return
            // ACTION_SCREEN_OFF arrives before/around onStop. Convert the shared source to
            // audio-only while the Activity still owns all queue/source state so playback can
            // continue with the exact position and play/pause state after the display sleeps.
            handoffVideoToBackgroundAudio()
        }
    }

    private var playbackStatus: de.uni.youtubeaa.car.PlaybackStatusView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContentView(R.layout.activity_video_player)
        applySystemBarInsets()
        playbackStatus = de.uni.youtubeaa.car.PlaybackStatusView(findViewById(R.id.player_stage)) {
            val exo = player ?: return@PlaybackStatusView
            if (!VideoSessionBridge.isOwner(this)) return@PlaybackStatusView
            val position = exo.currentPosition.coerceAtLeast(0L)
            val keepPlaying = exo.playWhenReady
            streamResolver.invalidate(queue.getOrNull(queueIndex)?.mediaId ?: return@PlaybackStatusView)
            playIndex(queueIndex, resumePositionMs = position, forcePlay = keepPlaying)
        }
        settings = SettingsRepository(this)
        meteredWatcher = MeteredNetworkWatcher(this) { metered -> onMeteredChanged(metered) }.also { it.start() }
        PlaybackEngine.ensureInit(this)

        val wantsResume = intent.getBooleanExtra(EXTRA_RESUME_SESSION, false)
        val remoteSnapshot = if (wantsResume && VideoSessionBridge.activeRole != VideoSessionBridge.Role.PHONE)
            VideoSessionBridge.controller?.snapshot() else null
        val resuming = wantsResume && remoteSnapshot == null && PlaybackEngine.hasSession()
        RemoteCastBus.cancelPending()
        val start: MediaDescriptionCompat
        if (remoteSnapshot != null) {
            queue = remoteSnapshot.queue
            queueIndex = remoteSnapshot.queueIndex
            start = queue.getOrNull(queueIndex) ?: run { finish(); return }
            PlaybackEngine.queue = queue
            PlaybackEngine.queueIndex = queueIndex
            phoneModeOverride = PlaybackEngine.phonePresentation == PlaybackEngine.PhonePresentation.AUDIO
        } else if (resuming) {
            // Re-opened from the mini player / notification: PlaybackEngine already has a live,
            // playing source on the shared player — attach to it in place instead of restarting
            // playback from scratch.
            queue = PlaybackEngine.queue
            queueIndex = PlaybackEngine.queueIndex
            start = queue.getOrNull(queueIndex) ?: run { finish(); return }
            // Headless playback may currently be backed by an audio-only source, but the mini
            // player must restore the foreground presentation the user last selected.
            phoneModeOverride = PlaybackEngine.phonePresentation == PlaybackEngine.PhonePresentation.AUDIO
        } else {
            @Suppress("DEPRECATION")
            val video = intent.getParcelableExtra<MediaDescriptionCompat>(EXTRA_VIDEO)
            @Suppress("DEPRECATION")
            val queueExtra = intent.getParcelableArrayListExtra<MediaDescriptionCompat>(EXTRA_QUEUE)
            queue = queueExtra?.takeIf { it.isNotEmpty() } ?: listOfNotNull(video)
            val first = video ?: queue.firstOrNull()
            val videoId = first?.mediaId
            if (first == null || videoId.isNullOrBlank() || queue.isEmpty()) {
                finish()
                return
            }
            start = first
            queueIndex = queue.indexOfFirst { it.mediaId == start.mediaId }.takeIf { it >= 0 } ?: 0
            PlaybackEngine.queue = queue
            PlaybackEngine.queueIndex = queueIndex
        }

        val initialHistoryResumeMs = if (resuming) 0L else WatchHistoryStore.resumePositionFromDescription(start)

        bindChrome(start)
        lifecycleScope.launch {
            val repaired = browseFacade.repairMetadataIfNeeded(start)
            if (repaired !== start && repaired.mediaId == start.mediaId) {
                queue = queue.map { if (it.mediaId == repaired.mediaId) repaired else it }
                PlaybackEngine.queue = queue
                if (queue.getOrNull(queueIndex)?.mediaId == repaired.mediaId) bindChrome(repaired)
                val currentState = NowPlayingBus.state.value ?: return@launch
                if (currentState.videoId == repaired.mediaId) {
                    NowPlayingBus.update(
                        videoId = repaired.mediaId ?: return@launch,
                        title = repaired.title?.toString() ?: repaired.mediaId.orEmpty(),
                        channelTitle = repaired.subtitle?.toString(),
                        thumbnailUrl = repaired.iconUri?.toString(),
                        isPlaying = currentState.isPlaying,
                        positionMs = currentState.positionMs,
                        durationMs = currentState.durationMs,
                        controllable = currentState.controllable,
                        isBuffering = currentState.isBuffering,
                        presentation = currentState.presentation,
                        queueIndex = queueIndex,
                        queueSize = queue.size.coerceAtLeast(1), owner = this@VideoPlayerActivity)
                }
            }
        }
        setupControls()
        // The predictive-back gesture (edge swipe) is delivered through this dispatcher, not
        // through the deprecated Activity.onBackPressed() — a plain onBackPressed() override is
        // silently skipped for gesture navigation and finishes the Activity anyway, killing
        // playback even in audio-only mode.
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOffReceiverRegistered = true

        // Always the same shared player instance so its identity never changes across this
        // Activity's create/destroy cycles — only the UI (surface, VideoSessionBridge
        // registration) attaches and detaches. This is what lets audio-only playback survive
        // this Activity being destroyed by back navigation.
        val exo = PlaybackEngine.player
        player = exo
        trackSelector = PlaybackEngine.trackSelector
        findViewById<PlayerView>(R.id.player_view).apply {
            player = exo
            setControllerShowTimeoutMs(3000)
            setControllerHideOnTouch(true)
            setControllerAutoShow(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                controllerOverlayVisible = visibility == View.VISIBLE
                updateFullscreenAudioButton(controllerOverlayVisible)
            })
        }
        applySpeed()
        refreshQuickLabels()
        applyModeUi()
        val listener = createPlayerListener(exo)
        playerListener = listener
        exo.addListener(listener)

        PlaybackEngine.activityAttached()
        VideoSessionBridge.unregister(PlaybackEngine)
        VideoSessionBridge.activate(this, VideoSessionBridge.Role.PHONE)
        CarPlaybackState.clearAll()
        ContextCompat.startForegroundService(this, Intent(this, YouTubeMediaBrowserService::class.java))
        if (remoteSnapshot != null) {
            PlaybackEngine.cancelPendingLoad()
            playIndex(queueIndex, remoteSnapshot.positionMs, remoteSnapshot.playWhenReady)
        } else if (resuming) {
            val resumePosition = PlaybackEngine.getPositionMs().coerceAtLeast(0L)
            val keepPlaying = exo.playWhenReady || exo.isPlaying
            if ((PlaybackEngine.phonePresentation == PlaybackEngine.PhonePresentation.VIDEO && !currentSourceHasVideo) ||
                (exo.mediaItemCount == 0 && !PlaybackEngine.isResolving())) {
                // Background playback is intentionally audio-only. Re-resolve a video-capable
                // source when the foreground player is restored instead of attaching a surface
                // to the audio source (which produces the black-player bug).
                playIndex(queueIndex, resumePositionMs = resumePosition, forcePlay = keepPlaying, preserveSource = true)
            } else {
                trackSelector?.let { PlayerFactory.setAudioOnly(it, isAudioOnlyMode(), currentSourceLayout) }
                updateAudioProgressUi()
                NowPlayingBus.updatePlayback(exo.isPlaying, exo.currentPosition, exo.duration, owner = this@VideoPlayerActivity)
            }
        } else {
            PlaybackEngine.phonePresentation =
                if (isAudioOnlyMode()) PlaybackEngine.PhonePresentation.AUDIO
                else PlaybackEngine.PhonePresentation.VIDEO
            playIndex(queueIndex, resumePositionMs = initialHistoryResumeMs)
        }
        mainHandler.removeCallbacks(historyTicker)
        mainHandler.postDelayed(historyTicker, HISTORY_SAVE_INTERVAL_MS)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun createPlayerListener(exo: ExoPlayer) = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (VideoSessionBridge.isOwner(this@VideoPlayerActivity)) {
                playbackStatus?.update(player)
                findViewById<ImageButton>(R.id.audio_play_pause)?.apply {
                    setImageResource(if (player.playWhenReady) R.drawable.ic_pause_on_primary else R.drawable.ic_play_on_primary)
                    contentDescription = getString(if (player.playWhenReady) R.string.now_playing_pause else R.string.now_playing_play)
                }
            }
            if (!VideoSessionBridge.isOwner(this@VideoPlayerActivity) || loadJob?.isActive == true) return
            NowPlayingBus.updatePlayback(
                player.isPlaying, player.currentPosition, player.duration,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                owner = this@VideoPlayerActivity
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!VideoSessionBridge.isOwner(this@VideoPlayerActivity)) return
            Log.e(TAG, "Player error code=${error.errorCode} msg=${error.message}", error)
            schedulePlaybackRecovery("player-error-${error.errorCode}")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!VideoSessionBridge.isOwner(this@VideoPlayerActivity)) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    beginBufferWatch(exo)
                    NowPlayingBus.updatePlayback(
                        isPlaying = false,
                        positionMs = exo.currentPosition,
                        durationMs = exo.duration,
                        isBuffering = true, owner = this@VideoPlayerActivity)
                }
                Player.STATE_READY -> {
                    clearBufferWatch()
                    recoveryAttempts = 0
                    hideStatus()
                }
                Player.STATE_IDLE, Player.STATE_ENDED -> clearBufferWatch()
            }
            if (playbackState == Player.STATE_ENDED && !playbackEndHandled) {
                saveHistoryProgress(completed = true)
                playbackEndHandled = true
                if (settings.isAutoplayEnabled() && queueIndex < queue.lastIndex) {
                    queueIndex++
                    playIndex(queueIndex)
                } else {
                    // A completed single item (or the last item in a queue) should behave like
                    // YouTube's back navigation: close the player and reveal the browse list
                    // that launched it. If autoplay is disabled, finishing the current item also
                    // returns immediately instead of leaving a dead player at 100%.
                    finish()
                }
                return
            }
            if (playbackState == Player.STATE_READY) {
                updateAudioProgressUi()
                NowPlayingBus.updatePlayback(exo.isPlaying, exo.currentPosition, exo.duration, owner = this@VideoPlayerActivity)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!VideoSessionBridge.isOwner(this@VideoPlayerActivity)) return
            if (!isPlaying) saveHistoryProgress()
            if (isFinishing || isDestroyed) return
            findViewById<ImageButton>(R.id.audio_play_pause)?.setImageResource(
                if (exo.playWhenReady) R.drawable.ic_pause_on_primary else R.drawable.ic_play_on_primary
            )
            NowPlayingBus.updatePlayback(isPlaying, exo.currentPosition, exo.duration,
                isBuffering = exo.playbackState == Player.STATE_BUFFERING, owner = this@VideoPlayerActivity)
        }
    }

    private fun saveHistoryProgress(completed: Boolean = false) {
        if (!::settings.isInitialized || !VideoSessionBridge.isOwner(this) || loadJob?.isActive == true || !settings.isHistoryEnabled()) return
        val video = queue.getOrNull(queueIndex) ?: return
        val exo = player ?: return
        if (currentIsLive) {
            WatchHistoryStore(this).updateProgress(video, 0L, 0L)
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
        WatchHistoryStore(this).updateProgress(video, position, duration)
    }

    private fun stopAndReturnToSource() {
        saveHistoryProgress()
        playbackEndHandled = true
        loadJob?.cancel()
        PlaybackEngine.stopAndClear()
        finish()
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.player_overlay_back).setOnClickListener {
            handoffVideoToBackgroundAudio()
            finish()
        }
        findViewById<ImageButton>(R.id.player_overlay_stop).setOnClickListener { stopAndReturnToSource() }
        findViewById<ImageButton>(R.id.player_overlay_cast).setOnClickListener { castCurrent() }
        findViewById<ImageButton>(R.id.audio_cast).setOnClickListener { castCurrent() }
        findViewById<ImageButton>(R.id.audio_stop).setOnClickListener { stopAndReturnToSource() }
        findViewById<ImageButton>(R.id.player_overlay_settings).setOnClickListener {
            showSettingsMenu(it)
        }
        findViewById<ImageButton>(R.id.audio_settings).setOnClickListener {
            showSettingsMenu(it)
        }
        findViewById<PlayerView>(R.id.player_view).setFullscreenButtonClickListener { fullscreen ->
            setFullscreen(fullscreen)
        }
        findViewById<View>(R.id.player_browser_button).setOnClickListener {
            val id = queue.getOrNull(queueIndex)?.mediaId ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
        }
        findViewById<View>(R.id.player_playlist_button).setOnClickListener {
            val current = queue.getOrNull(queueIndex) ?: return@setOnClickListener
            val added = UserPlaylistStore(this).addFromDescription(current)
            Toast.makeText(
                this,
                getString(if (added) R.string.browse_playlist_added else R.string.browse_playlist_already),
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<View>(R.id.player_share_button).setOnClickListener {
            val id = queue.getOrNull(queueIndex)?.mediaId ?: return@setOnClickListener
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$id")
            }
            startActivity(Intent.createChooser(share, getString(R.string.player_share)))
        }
        findViewById<Button>(R.id.player_mode).setOnClickListener { togglePlaybackMode() }
        findViewById<MaterialButton>(R.id.fullscreen_audio_button).setOnClickListener { togglePlaybackMode() }
        findViewById<Button>(R.id.audio_switch_video).setOnClickListener {
            if (isAudioOnlyMode()) togglePlaybackMode()
        }
        findViewById<ImageButton>(R.id.audio_play_pause).setOnClickListener {
            val exo = player ?: return@setOnClickListener
            if (exo.isPlaying) exo.pause() else exo.play()
        }
        findViewById<ImageButton>(R.id.audio_prev).setOnClickListener {
            if (queueIndex > 0) {
                queueIndex--
                playIndex(queueIndex)
            } else {
                player?.seekTo(0)
            }
        }
        findViewById<ImageButton>(R.id.audio_next).setOnClickListener {
            if (queueIndex < queue.lastIndex) {
                queueIndex++
                playIndex(queueIndex)
            }
        }
        findViewById<SeekBar>(R.id.audio_seek).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = knownDurationMs.takeIf { it > 0 }
                            ?: player?.duration?.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET }
                            ?: 0L
                        findViewById<TextView>(R.id.audio_position).text =
                            formatPlaybackTime(duration * progress / seekBar.max)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    isSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    isSeeking = false
                    val duration = knownDurationMs.takeIf { it > 0 }
                        ?: player?.duration?.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET }
                        ?: return
                    player?.seekTo(duration * seekBar.progress / seekBar.max)
                }
            }
        )
    }

    private fun castCurrent() {
        val current = queue.getOrNull(queueIndex) ?: return
        val castVideoId = current.mediaId ?: return
        val decoderLease = PlaybackEngine.suspendPhoneVideoDecoderForProjection()
        val result = RemoteCastBus.cast(
            video = current,
            queue = queue,
            context = this,
            startPositionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            onProjectionFailed = {
                PlaybackEngine.restorePhoneVideoDecoderAfterProjectionFailure(decoderLease)
                showCastFailedToast()
            },
            onStarted = {
                // A queued projection can finish after the user already selected another
                // phone item. Never pause that newer playback because an older cast became ready.
                if (queue.getOrNull(queueIndex)?.mediaId == castVideoId) player?.pause()
            }
        )
        val message = when (result) {
            RemoteCastBus.CastResult.Queued -> R.string.browse_cast_queued
            RemoteCastBus.CastResult.ParkBlocked -> {
                PlaybackEngine.restorePhoneVideoDecoderAfterProjectionFailure(decoderLease)
                R.string.browse_cast_park_blocked
            }
            RemoteCastBus.CastResult.Rejected -> {
                PlaybackEngine.restorePhoneVideoDecoderAfterProjectionFailure(decoderLease)
                R.string.browse_cast_unavailable
            }
        }
        Toast.makeText(this, getString(message), Toast.LENGTH_LONG).show()
    }

    /** Optional phone-only default: start in audio mode, but allow a temporary switch to video.
     * The override is activity-local so the head-unit playback preference remains untouched. */
    private var phoneModeOverride: Boolean? = null

    private fun isPhoneAudioPreferred(): Boolean = settings.isPhoneAudioOnlyEnabled()

    private fun isAudioOnlyMode(): Boolean = phoneModeOverride ?:
        if (isPhoneAudioPreferred()) true else settings.isAudioOnly()

    private fun togglePlaybackMode() {
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val keepPlaying = exo.playWhenReady || exo.isPlaying
        val wasAudioOnly = isAudioOnlyMode()

        // A resumed Activity already has an override. Update it on every tap; otherwise
        // cycling the preference leaves the effective mode stuck at that old override.
        phoneModeOverride = !wasAudioOnly
        if (!isPhoneAudioPreferred()) settings.setPlaybackMode(if (wasAudioOnly) "video" else "audio")
        val audioOnly = isAudioOnlyMode()
        PlaybackEngine.phonePresentation =
            if (audioOnly) PlaybackEngine.PhonePresentation.AUDIO
            else PlaybackEngine.PhonePresentation.VIDEO
        NowPlayingBus.updatePresentation(
            if (audioOnly) PlaybackPresentation.AUDIO else PlaybackPresentation.VIDEO,
            queueIndex = queueIndex,
            queueSize = queue.size.coerceAtLeast(1), owner = this@VideoPlayerActivity)
        if (!audioOnly) backgroundAudioHandoffDone = false
        applyModeUi()
        refreshQuickLabels()

        // Video -> audio does not need a new URL or MediaSource when the currently prepared
        // source already contains audio. Disabling the video renderer keeps the audio clock and
        // buffer alive, avoiding the audible pause caused by setMediaSource()+prepare().
        // Only separate audio/video tracks stop downloading video when it is disabled; a muxed
        // source falls through to a pure audio reload below (data saver).
        if (!wasAudioOnly && audioOnly && currentSourceHasAudioForSeamlessSwitch &&
            currentSourceLayout.videoCanBeDroppedForFree
        ) {
            loadJob?.cancel()
            preserveSourcePosition = false
            trackSelector?.let { PlayerFactory.setAudioOnly(it, true, currentSourceLayout) }
            exo.playWhenReady = keepPlaying
            if (keepPlaying) exo.play()
            updateAudioProgressUi()
            return
        }

        // If audio mode was entered from the current video source, the video track is still
        // present. Re-enable it in-place instead of resolving the video again.
        if (wasAudioOnly && !audioOnly && currentSourceHasVideo) {
            loadJob?.cancel()
            preserveSourcePosition = false
            trackSelector?.let {
                PlayerFactory.setAudioOnly(it, false, currentSourceLayout)
                applyPhoneVideoCeiling(it)
            }
            exo.playWhenReady = keepPlaying
            if (keepPlaying) exo.play()
            return
        }

        // A pure audio source (for example after starting/advancing while in audio mode) has no
        // video track to re-enable, so only that case requires a source reload.
        playIndex(queueIndex, resumePositionMs = position, forcePlay = keepPlaying, preserveSource = true)
    }

    private fun applyModeUi() {
        val audioOnly = isAudioOnlyMode()
        val playerView = findViewById<PlayerView>(R.id.player_view)
        // Detach surface in audio mode so PlayerView visibility changes don't interrupt audio.
        if (audioOnly) {
            playerView.player = null
            playerView.visibility = View.GONE
        } else {
            playerView.visibility = View.VISIBLE
            playerView.player = player
        }
        findViewById<View>(R.id.audio_player_panel).visibility =
            if (audioOnly) View.VISIBLE else View.GONE
        findViewById<View>(R.id.player_meta_panel).visibility =
            if (audioOnly || isFullscreen) View.GONE else View.VISIBLE
        updatePlayerStageLayout()
        updatePlayerModeButton()
        updateFullscreenAudioButton()
        if (audioOnly) {
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.post(progressTicker)
        } else {
            mainHandler.removeCallbacks(progressTicker)
        }
    }

    private fun bindChrome(video: MediaDescriptionCompat) {
        findViewById<TextView>(R.id.player_title).text = video.title
        findViewById<TextView>(R.id.player_overlay_title).text = video.title

        val channel = video.subtitle?.toString()?.trim().orEmpty()
        findViewById<TextView>(R.id.player_channel_name).apply {
            text = channel
            visibility = if (channel.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.player_channel).apply {
            val published = relativePublishedLabel(video.publishedAtMs()).orEmpty()
            text = published
            visibility = if (published.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.audio_title).text = video.title
        findViewById<TextView>(R.id.audio_channel).apply {
            text = video.subtitle
            visibility = if (video.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        loadArtwork(video.iconUri?.toString())
        loadChannelAvatar(channel)
        loadRelatedVideos(video)
    }

    private fun loadChannelAvatar(channelTitle: String) {
        channelAvatarJob?.cancel()
        val avatar = findViewById<ImageView>(R.id.player_channel_avatar)
        avatar.setPadding(9.dp(), 9.dp(), 9.dp(), 9.dp())
        avatar.setImageResource(R.drawable.ic_person)
        if (channelTitle.isBlank()) return

        channelAvatarJob = lifecycleScope.launch {
            val channels = (browseFacade.subscriptionChannels(forceRefresh = false) as? BrowseOutcome.Ok)?.data.orEmpty()
            val wanted = normalizeChannelTitle(channelTitle)
            val match = channels.firstOrNull { normalizeChannelTitle(it.title) == wanted } ?: return@launch
            val url = match.thumbnailUrl ?: browseFacade.channelAvatar(match.channelId) ?: return@launch
            val bitmap = loadThumbnailBitmap(url, targetPx = 96) ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            avatar.setPadding(0, 0, 0, 0)
            avatar.setImageBitmap(bitmap)
        }
    }

    private fun normalizeChannelTitle(value: String): String =
        value.trim().removePrefix("@").lowercase()

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun loadRelatedVideos(video: MediaDescriptionCompat) {
        relatedJob?.cancel()
        val container = findViewById<LinearLayout>(R.id.player_related_container)
        val titleView = findViewById<TextView>(R.id.player_related_title)
        container.removeAllViews()
        titleView.visibility = View.GONE
        val query = video.title?.toString()?.trim().orEmpty()
        if (query.isBlank()) return

        relatedJob = lifecycleScope.launch {
            val outcome = browseFacade.search(query)
            val currentId = video.mediaId
            val related = (outcome as? BrowseOutcome.Ok)?.data.orEmpty()
                .filter { !it.mediaId.isNullOrBlank() && it.mediaId != currentId }
                .distinctBy { it.mediaId }
                .take(8)
            if (related.isEmpty() || isFinishing || isDestroyed) return@launch

            titleView.visibility = View.VISIBLE
            val inflater = LayoutInflater.from(this@VideoPlayerActivity)
            val rowLayout = if (settings.isPhoneBrowseCardsEnabled()) R.layout.item_youtube_video_card else R.layout.item_youtube_video
            val thumbPx = if (settings.isPhoneBrowseCardsEnabled()) 640 else 480
            related.forEach { candidate ->
                val row = inflater.inflate(rowLayout, container, false)
                row.findViewById<TextView>(R.id.video_title).text = candidate.title
                row.findViewById<TextView>(R.id.video_channel).apply {
                    text = candidate.subtitle
                    visibility = if (candidate.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
                }
                row.findViewById<View>(R.id.video_more_button).visibility = View.GONE
                val thumbnail = row.findViewById<ImageView>(R.id.video_thumbnail)
                val url = candidate.iconUri?.toString()
                thumbnail.tag = url
                thumbnail.setImageDrawable(null)
                if (!url.isNullOrBlank()) {
                    lifecycleScope.launch {
                        val bitmap = loadThumbnailBitmap(url, settings.getThumbnailQuality(), targetPx = thumbPx)
                        if (thumbnail.tag == url && bitmap != null) thumbnail.setImageBitmap(bitmap)
                    }
                }
                row.setOnClickListener {
                    val merged = (queue.take(queueIndex + 1) + related).distinctBy { it.mediaId }
                    val nextIndex = merged.indexOfFirst { it.mediaId == candidate.mediaId }
                    if (nextIndex < 0) return@setOnClickListener

                    if (RemoteCastBus.isAvailable() || CarPlaybackState.isActive()) {
                        val playHere = {
                            queue = merged
                            PlaybackEngine.queue = merged
                            playIndex(nextIndex)
                        }
                        val result = RemoteCastBus.cast(
                            video = candidate,
                            queue = merged,
                            context = this@VideoPlayerActivity,
                            onProjectionFailed = {
                                CarPlaybackState.clearAll()
                                if (!isFinishing && !isDestroyed) runOnUiThread { playHere() }
                            }
                        )
                        // An immediate refusal never reaches onProjectionFailed, so without this
                        // the tap did nothing at all. Play on the phone and say why — after
                        // releasing the car state exactly as the failure fallback does, or the
                        // car keeps playing and later taps keep routing there.
                        when (result) {
                            RemoteCastBus.CastResult.Queued -> Unit
                            RemoteCastBus.CastResult.ParkBlocked -> {
                                Toast.makeText(this@VideoPlayerActivity, getString(R.string.browse_cast_park_blocked), Toast.LENGTH_LONG).show()
                                CarPlaybackState.clearAll()
                                playHere()
                            }
                            RemoteCastBus.CastResult.Rejected -> {
                                Toast.makeText(this@VideoPlayerActivity, getString(R.string.browse_cast_unavailable), Toast.LENGTH_LONG).show()
                                CarPlaybackState.clearAll()
                                playHere()
                            }
                        }
                    } else {
                        queue = merged
                        PlaybackEngine.queue = merged
                        playIndex(nextIndex)
                    }
                }
                container.addView(row)
            }
        }
    }

    private fun loadArtwork(url: String?) {
        thumbJob?.cancel()
        val bg = findViewById<ImageView>(R.id.audio_art)
        val fg = findViewById<ImageView>(R.id.audio_art_foreground)
        bg.setImageDrawable(null)
        fg.setImageDrawable(null)
        if (url.isNullOrBlank()) return
        thumbJob = lifecycleScope.launch {
            val bitmap = loadThumbnailBitmap(url, targetPx = 640)
            if (bitmap != null) {
                bg.setImageBitmap(bitmap)
                fg.setImageBitmap(bitmap)
            }
        }
    }

    private fun refreshQuickLabels() {
        updatePlayerModeButton()
    }

    private fun updatePlayerModeButton() {
        val audioOnly = isAudioOnlyMode()
        findViewById<MaterialButton>(R.id.player_mode).apply {
            visibility = View.VISIBLE
            setIconResource(if (audioOnly) R.drawable.ic_video_cam else R.drawable.ic_headphones)
            text = getString(if (audioOnly) R.string.player_switch_to_video else R.string.player_switch_to_audio)
        }
        findViewById<TextView>(R.id.player_mode_label).text =
            getString(if (audioOnly) R.string.player_switch_to_video else R.string.player_switch_to_audio)
        findViewById<Button>(R.id.audio_switch_video).visibility = View.VISIBLE
    }

    /**
     * YouTube-style settings entry point. The first level shows the current value; tapping an
     * item opens a checked single-choice sheet instead of cycling blindly through presets.
     */
    private fun showSettingsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_SPEED, 0, getString(R.string.player_setting_speed, speedLabel(settings.getPlaybackSpeed())))
        if (!isAudioOnlyMode()) {
            popup.menu.add(0, MENU_QUALITY, 1, getString(R.string.player_setting_quality, qualityLabel(settings.getVideoQuality())))
        }
        popup.menu.add(0, MENU_AUDIO_LANGUAGE, 2, getString(R.string.player_setting_audio, audioLanguageLabel(settings.getAudioLanguage())))
        popup.menu.add(0, MENU_AUDIO_QUALITY, 3, getString(R.string.player_setting_audio_quality, audioBitrateLabel(settings.getAudioBitrate())))
        popup.menu.add(0, MENU_CACHE, 4, getString(R.string.player_setting_cache, cacheLabel(settings.getCacheSizeMbValue())))
        popup.menu.add(0, MENU_STOP, 5, getString(R.string.player_stop_and_return))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SPEED -> showSpeedDialog()
                MENU_QUALITY -> showQualityDialog()
                MENU_AUDIO_LANGUAGE -> showAudioLanguageDialog()
                MENU_AUDIO_QUALITY -> showAudioBitrateDialog()
                MENU_CACHE -> showCacheDialog()
                MENU_STOP -> stopAndReturnToSource()
            }
            true
        }
        popup.show()
    }

    private fun showSpeedDialog() {
        val values = SettingsRepository.PLAYBACK_SPEED_VALUES
        val labels = values.map { speedLabel(it.toFloatOrNull() ?: 1f) }.toTypedArray()
        val current = values.indexOf(speedValue(settings.getPlaybackSpeed())).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.player_speed_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                settings.setPlaybackSpeed(values[which])
                applySpeed()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showQualityDialog() {
        val values = SettingsRepository.VIDEO_QUALITY_VALUES
        val labels = values.map(::qualityLabel).toTypedArray()
        val current = values.indexOf(settings.getVideoQuality()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.player_quality_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val selected = values[which]
                if (selected != settings.getVideoQuality()) {
                    val keepPlaying = player?.playWhenReady ?: true
                    settings.setVideoQuality(selected)
                    reloadCurrent(keepPosition = true, keepPlaying = keepPlaying)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAudioLanguageDialog() {
        val values = SettingsRepository.AUDIO_LANGUAGE_VALUES
        val labels = values.map(::audioLanguageLabel).toTypedArray()
        val current = values.indexOf(settings.getAudioLanguage()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.player_audio_track_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val selected = values[which]
                if (selected != settings.getAudioLanguage()) {
                    val keepPlaying = player?.playWhenReady ?: true
                    settings.setAudioLanguage(selected)
                    reloadCurrent(keepPosition = true, keepPlaying = keepPlaying)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAudioBitrateDialog() {
        val values = SettingsRepository.AUDIO_BITRATE_VALUES
        val labels = values.map(::audioBitrateLabel).toTypedArray()
        val current = values.indexOf(settings.getAudioBitrate()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.player_audio_quality_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val selected = values[which]
                if (selected != settings.getAudioBitrate()) {
                    val keepPlaying = player?.playWhenReady ?: true
                    settings.setAudioBitrate(selected)
                    reloadCurrent(keepPosition = true, keepPlaying = keepPlaying)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCacheDialog() {
        val values = SettingsRepository.CACHE_SIZE_VALUES
        val labels = values.map(::cacheLabel).toTypedArray()
        val current = values.indexOf(settings.getCacheSizeMbValue()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.player_cache_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                settings.setCacheSizeMb(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cacheLabel(value: String): String = "$value MB"

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
        "2160" -> "2160p (4K)"
        "1440" -> "1440p"
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        "360" -> "360p"
        "240" -> "240p"
        "144" -> "144p"
        "low" -> getString(R.string.player_quality_low)
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

    private fun updatePlayerStageLayout() {
        val fillScreen = isFullscreen || isAudioOnlyMode()
        findViewById<View>(R.id.player_stage).layoutParams =
            (findViewById<View>(R.id.player_stage).layoutParams as ConstraintLayout.LayoutParams).apply {
                height = 0
                if (fillScreen) {
                    dimensionRatio = null
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                } else {
                    bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                    dimensionRatio = "16:9"
                }
            }
    }

    private fun updateFullscreenAudioButton(controllerVisible: Boolean? = null) {
        val button = findViewById<MaterialButton>(R.id.fullscreen_audio_button)
        val overlayVisible = controllerVisible ?: controllerOverlayVisible
        button.visibility = if (isFullscreen && !isAudioOnlyMode() && overlayVisible) View.VISIBLE else View.GONE
    }

    private fun setFullscreen(fullscreen: Boolean) {
        isFullscreen = fullscreen
        requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        findViewById<View>(R.id.player_meta_panel).visibility =
            if (fullscreen || isAudioOnlyMode()) View.GONE else View.VISIBLE
        // Portrait uses a YouTube-like 16:9 stage. Fullscreen must fill the landscape window;
        // keeping the ratio constraint there can make the stage taller than the display.
        updatePlayerStageLayout()
        // Media3 1.11+ exposes this directly, so the controller icon remains correct when
        // fullscreen is left through Back as well as through the fullscreen button itself.
        findViewById<PlayerView>(R.id.player_view).setFullscreenButtonState(fullscreen)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        updateFullscreenAudioButton()
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isFullscreen) {
                setFullscreen(false)
                return
            }
            handoffVideoToBackgroundAudio()
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }

    private fun handoffVideoToBackgroundAudio() {
        if (backgroundAudioHandoffDone || isAudioOnlyMode() || !VideoSessionBridge.isOwner(this)) return
        val exo = player ?: return
        val keepPlaying = exo.playWhenReady || exo.isPlaying
        val position = exo.currentPosition.coerceAtLeast(0L)
        // Stop Activity-owned resolving before the headless engine possibly starts its own audio resolve.
        loadJob?.cancel()
        PlaybackEngine.queue = queue
        PlaybackEngine.queueIndex = queueIndex
        PlaybackEngine.handoffToBackgroundAudio(
            positionMs = position,
            keepPlaying = keepPlaying,
            currentSourceHasAudio = currentSourceHasAudioForSeamlessSwitch,
            isLive = currentIsLive
        )
        // Do not overwrite PlaybackEngine.phonePresentation here: background playback is an
        // implementation detail. The mini player should return to the mode the user had open.
        phoneModeOverride = true
        backgroundAudioHandoffDone = true
        applyModeUi()
    }

    private fun applySpeed() {
        player?.setPlaybackSpeed(settings.getPlaybackSpeed())
    }

    private fun audioPrefs() = StreamResolver.AudioPrefs(
        language = settings.getAudioLanguage(),
        bitrate = settings.getAudioBitrate()
    )

    private fun playIndex(index: Int, resumePositionMs: Long = 0L, forcePlay: Boolean = true, resetRecovery: Boolean = true, preserveSource: Boolean = false) {
        val video = queue.getOrNull(index) ?: return
        RemoteCastBus.cancelPending()
        PlaybackEngine.cancelPendingLoad()
        loadJob?.cancel()
        preserveSourcePosition = preserveSource && (player?.mediaItemCount ?: 0) > 0
        if (!preserveSourcePosition) {
            player?.stop()
            player?.clearMediaItems()
            currentSourceHasVideo = false
            currentSourceHasAudioForSeamlessSwitch = false
            currentSourceLayout = SourceLayout.AUDIO_ONLY
            currentProgressiveHeight = 0
        }
        player?.playWhenReady = forcePlay
        playbackEndHandled = false
        if (resetRecovery) recoveryAttempts = 0
        clearBufferWatch()
        queueIndex = index
        PlaybackEngine.queueIndex = index
        if (!preserveSourcePosition) {
            knownDurationMs = 0L
            currentIsLive = false
        }
        bindChrome(video)
        NowPlayingBus.update(
            videoId = video.mediaId ?: return,
            title = video.title?.toString() ?: video.mediaId.toString(),
            channelTitle = video.subtitle?.toString(),
            thumbnailUrl = video.iconUri?.toString(),
            isPlaying = false,
            positionMs = resumePositionMs,
            durationMs = 0L,
            controllable = true,
            isBuffering = true,
            presentation = if (isAudioOnlyMode()) PlaybackPresentation.AUDIO else PlaybackPresentation.VIDEO,
            queueIndex = queueIndex,
            queueSize = queue.size.coerceAtLeast(1), owner = this@VideoPlayerActivity)
        if (settings.isHistoryEnabled()) {
            WatchHistoryStore(this).recordFromDescription(video)
        }
        if (isAudioOnlyMode()) {
            loadAudio(video.mediaId ?: return, resumePositionMs, playWhenReady = forcePlay)
        } else {
            loadVideo(video.mediaId ?: return, resumePositionMs, playWhenReady = forcePlay)
        }
    }

    private fun reloadCurrent(keepPosition: Boolean, keepPlaying: Boolean = player?.playWhenReady ?: true) {
        val exo = player
        val position = if (keepPosition) exo?.currentPosition ?: 0L else 0L
        playIndex(queueIndex, resumePositionMs = position, forcePlay = keepPlaying, preserveSource = true)
    }

    private fun loadAudio(
        videoId: String,
        resumePositionMs: Long = 0L,
        playWhenReady: Boolean = true
    ) {
        showStatus(getString(de.uni.youtubeaa.car.R.string.video_player_loading))
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            when (val result = streamResolver.resolveAudioStreamUrl(videoId, audioPrefs())) {
                is StreamResolver.Result.Success -> {
                    currentIsLive = result.isLive
                    if (result.durationMs > 0L) knownDurationMs = result.durationMs
                    currentSourceHasVideo = false
                    currentSourceHasAudioForSeamlessSwitch = true
                    currentSourceLayout = result.layout
                    currentProgressiveHeight = 0
                    // Set audio-only tracks before binding the new source (not on the old video).
                    trackSelector?.let { PlayerFactory.setAudioOnly(it, true, result.layout) }
                    Log.d(
                        TAG,
                        "Audio source pure=${result.pureAudio} live=${result.isLive} dur=${result.durationMs}"
                    )
                    playResolvedSource(
                        result.streamUrl,
                        resumePositionMs,
                        isLive = result.isLive,
                        playWhenReady = player?.playWhenReady ?: playWhenReady
                    )
                }
                is StreamResolver.Result.Error -> showPlaybackError(result.message)
            }
        }
    }

    private fun loadVideo(
        videoId: String,
        resumePositionMs: Long = 0L,
        playWhenReady: Boolean = true
    ) {
        showStatus(getString(de.uni.youtubeaa.car.R.string.video_player_loading))
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val requested = settings.getVideoQuality()
            // Auto on the phone uses adaptive manifests so ABR can start low; explicit heights
            // stay on progressive itags for a predictable picture size.
            val preferAdaptive = requested.equals("auto", ignoreCase = true)
            // Data saver: Auto stops at 720p30; on mobile data the user's cap (default 480p)
            // limits every setting, explicit qualities and a progressive fallback included.
            val metered = meteredWatcher?.isMetered ?: NetworkPolicy.isMetered(this@VideoPlayerActivity)
            val mobileQuality = settings.getMobileDataQuality()
            val quality = DataSaverPolicy.phoneResolveQuality(requested, metered, mobileQuality)
            trackSelector?.let {
                PlayerFactory.setAudioOnly(it, false)
                PlayerFactory.applyPhoneVideoCeiling(it, DataSaverPolicy.phoneCeiling(requested, metered, mobileQuality))
            }
            when (
                val result = streamResolver.resolveVideoStream(
                    videoId,
                    quality,
                    audioPrefs(),
                    maxFps = DataSaverPolicy.phoneResolveMaxFps(requested),
                    preferAdaptive = preferAdaptive
                )
            ) {
                is StreamResolver.VideoResult.Muxed -> {
                    currentSourceHasVideo = true
                    currentSourceHasAudioForSeamlessSwitch = true
                    currentSourceLayout = SourceLayout.MUXED_PROGRESSIVE
                    currentProgressiveHeight = result.height
                    playResolvedSource(result.url, resumePositionMs, playWhenReady = player?.playWhenReady ?: playWhenReady)
                }
                is StreamResolver.VideoResult.Manifest -> {
                    currentIsLive = result.isLive
                    currentSourceHasVideo = true
                    currentSourceHasAudioForSeamlessSwitch = true
                    currentSourceLayout = result.layout
                    currentProgressiveHeight = 0
                    playResolvedSource(
                        result.url,
                        resumePositionMs,
                        isLive = result.isLive,
                        playWhenReady = player?.playWhenReady ?: playWhenReady
                    )
                }
                is StreamResolver.VideoResult.Separate -> {
                    hideStatus()
                    val exo = player ?: return@launch
                    val audioUrl = result.audioUrl
                    currentSourceHasVideo = true
                    currentSourceHasAudioForSeamlessSwitch = audioUrl != null
                    currentSourceLayout = SourceLayout.SEPARATE
                    currentProgressiveHeight = result.height
                    val source = if (audioUrl != null) {
                        PlaybackSources.merged(result.videoUrl, audioUrl)
                    } else {
                        PlaybackSources.mediaSource(result.videoUrl)
                    }
                    PlayerFactory.replaceSource(
                        exo,
                        source,
                        sourcePosition(resumePositionMs),
                        isLive = false,
                        playWhenReady = player?.playWhenReady ?: playWhenReady
                    )
                    applySpeed()
                }
                is StreamResolver.VideoResult.Error -> showPlaybackError(result.message)
            }
        }
    }

    private fun applyPhoneVideoCeiling(selector: DefaultTrackSelector, metered: Boolean = meteredWatcher?.isMetered ?: NetworkPolicy.isMetered(this)) {
        PlayerFactory.applyPhoneVideoCeiling(
            selector,
            DataSaverPolicy.phoneCeiling(settings.getVideoQuality(), metered, settings.getMobileDataQuality())
        )
    }

    /**
     * Wi-Fi <-> mobile data during playback. Adaptive manifests follow the new ceiling through
     * the TrackSelector without a reload; a fixed progressive itag above the new cap is reloaded
     * at the current position (once: the reload picks an itag within the cap).
     */
    private fun onMeteredChanged(metered: Boolean) {
        if (isFinishing || isDestroyed || !VideoSessionBridge.isOwner(this)) return
        if (isAudioOnlyMode() || !currentSourceHasVideo) return
        trackSelector?.let { applyPhoneVideoCeiling(it, metered) }
        if (!metered || currentProgressiveHeight <= 0) return
        val capped = DataSaverPolicy.phoneResolveQuality(settings.getVideoQuality(), true, settings.getMobileDataQuality())
        if (currentProgressiveHeight > CarVideoPolicy.qualityShortSide(capped)) {
            Log.i(TAG, "metered network: reloading progressive ${currentProgressiveHeight}p within the mobile-data cap")
            reloadCurrent(keepPosition = true)
        }
    }

    private fun showPlaybackError(message: String) {
        playbackStatus?.error(message)
        NowPlayingBus.clear(owner = this@VideoPlayerActivity)
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
        hideStatus()
        val exo = player ?: return
        PlayerFactory.replaceSource(
            player = exo,
            source = PlaybackSources.mediaSource(url),
            resumePositionMs = sourcePosition(resumePositionMs),
            isLive = isLive,
            playWhenReady = playWhenReady
        )
        applySpeed()
        updateAudioProgressUi()
    }

    private fun updateAudioProgressUi() {
        if (isFinishing || isDestroyed) return
        if (!isAudioOnlyMode()) return
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val reported = exo.duration
        val reportedValid = reported > 0L && reported != androidx.media3.common.C.TIME_UNSET
        // Live streams have no fixed length — their DVR window keeps growing, so the reported
        // duration must be read fresh every tick instead of being locked in like a VOD estimate.
        val duration = when {
            currentIsLive -> if (reportedValid) reported else 0L
            knownDurationMs > 0L -> knownDurationMs
            reportedValid -> {
                knownDurationMs = reported
                reported
            }
            else -> 0L
        }
        findViewById<TextView>(R.id.audio_duration)?.text =
            if (duration > 0) formatPlaybackTime(duration) else "0:00"
        // Während der Nutzer zieht, zeigt der SeekBar-Listener die Vorschauzeit an — nicht mit
        // der echten Wiedergabeposition überschreiben (führte zu "flackernder" Zeitanzeige).
        if (!isSeeking) {
            findViewById<TextView>(R.id.audio_position)?.text = formatPlaybackTime(position)
            if (duration > 0) {
                findViewById<SeekBar>(R.id.audio_seek)?.progress =
                    ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
            }
        }
    }


    private fun beginBufferWatch(exo: ExoPlayer) {
        if (bufferingSinceMs != 0L) return
        bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
        bufferingPositionMs = exo.currentPosition.coerceAtLeast(0L)
        mainHandler.postDelayed(bufferingWatchdog, BUFFERING_RECOVERY_DELAY_MS)
    }

    private fun clearBufferWatch() {
        bufferingSinceMs = 0L
        bufferingPositionMs = 0L
        mainHandler.removeCallbacks(bufferingWatchdog)
    }

    private val bufferingWatchdog = Runnable {
        val exo = player ?: return@Runnable
        if (exo.playbackState != Player.STATE_BUFFERING || bufferingSinceMs == 0L) return@Runnable
        val stalledFor = android.os.SystemClock.elapsedRealtime() - bufferingSinceMs
        val moved = kotlin.math.abs(exo.currentPosition - bufferingPositionMs) > 1_500L
        if (stalledFor >= BUFFERING_RECOVERY_DELAY_MS && !moved) {
            schedulePlaybackRecovery("buffer-stall")
        }
    }

    private fun schedulePlaybackRecovery(reason: String) {
        if (isFinishing || isDestroyed || !VideoSessionBridge.isOwner(this)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS || now - lastRecoveryAtMs < RECOVERY_COOLDOWN_MS) {
            playbackStatus?.error(getString(R.string.video_player_error))
            return
        }
        recoveryAttempts++
        lastRecoveryAtMs = now
        clearBufferWatch()
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val keepPlaying = exo.playWhenReady || exo.isPlaying
        Log.w(TAG, "Recover playback attempt=$recoveryAttempts reason=$reason pos=$position")
        showStatus(getString(R.string.player_retrying))
        loadJob?.cancel()
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed && VideoSessionBridge.isOwner(this)) {
                streamResolver.invalidate(queue.getOrNull(queueIndex)?.mediaId ?: return@postDelayed)
                playIndex(queueIndex, resumePositionMs = position, forcePlay = keepPlaying, resetRecovery = false)
            }
        }, RECOVERY_RETRY_DELAY_MS)
    }

    private fun showCastFailedToast() {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, getString(R.string.browse_cast_unavailable), Toast.LENGTH_LONG).show()
    }

    private fun showStatus(message: String) {
        if (isFinishing || isDestroyed) return
        playbackStatus?.loading(message)
    }

    private fun hideStatus() {
        if (isFinishing || isDestroyed) return
        playbackStatus?.clear()
        findViewById<TextView>(R.id.player_status_text)?.visibility = View.GONE
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val stage = findViewById<View?>(R.id.player_stage)
        val root = findViewById<View?>(R.id.player_root)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                minimizeDownX = ev.rawX
                minimizeDownY = ev.rawY
                minimizeDragging = false
                minimizeTracking = stage?.let {
                    val location = IntArray(2)
                    it.getLocationOnScreen(location)
                    ev.rawY >= location[1] && ev.rawY <= location[1] + it.height
                } == true
            }
            MotionEvent.ACTION_MOVE -> {
                if (minimizeTracking && root != null) {
                    val dx = ev.rawX - minimizeDownX
                    val dy = ev.rawY - minimizeDownY
                    val slop = 14f * resources.displayMetrics.density
                    if (!minimizeDragging && dy > slop && dy > kotlin.math.abs(dx) * 1.2f) {
                        minimizeDragging = true
                    }
                    if (minimizeDragging) {
                        root.translationY = dy.coerceAtLeast(0f) * 0.88f
                        root.alpha = (1f - root.translationY / root.height.coerceAtLeast(1) * 0.30f)
                            .coerceIn(0.70f, 1f)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (minimizeTracking && minimizeDragging && root != null) {
                    val dy = ev.rawY - minimizeDownY
                    minimizeTracking = false
                    minimizeDragging = false
                    val threshold = maxOf(96f * resources.displayMetrics.density, root.height * 0.14f)
                    if (dy >= threshold) {
                        handoffVideoToBackgroundAudio()
                        root.animate()
                            .translationY(root.height.toFloat())
                            .alpha(0.45f)
                            .setDuration(170L)
                            .withEndAction { finish() }
                            .start()
                    } else {
                        root.animate().translationY(0f).alpha(1f).setDuration(140L).start()
                    }
                    return true
                }
                minimizeTracking = false
                minimizeDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                minimizeTracking = false
                minimizeDragging = false
                root?.animate()?.translationY(0f)?.alpha(1f)?.setDuration(120L)?.start()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        if (player == null || !PlaybackEngine.hasSession()) return
        if (VideoSessionBridge.activeRole != VideoSessionBridge.Role.PHONE) return
        // The existing Activity can survive screen-off/app switching while the engine skips
        // tracks. Reattach to its current queue and restore the chosen foreground mode.
        if (VideoSessionBridge.controller === PlaybackEngine || backgroundAudioHandoffDone) {
            queue = PlaybackEngine.queue
            queueIndex = PlaybackEngine.queueIndex
            PlaybackEngine.activityAttached()
            VideoSessionBridge.unregister(PlaybackEngine)
            VideoSessionBridge.register(this, VideoSessionBridge.Role.PHONE)
            phoneModeOverride = PlaybackEngine.phonePresentation == PlaybackEngine.PhonePresentation.AUDIO
            backgroundAudioHandoffDone = false
            queue.getOrNull(queueIndex)?.let(::bindChrome)
            val exo = player ?: return
            if (!isAudioOnlyMode() && !currentSourceHasVideo) {
                playIndex(queueIndex, PlaybackEngine.getPositionMs().coerceAtLeast(0L), exo.playWhenReady, preserveSource = true)
            } else {
                trackSelector?.let { PlayerFactory.setAudioOnly(it, isAudioOnlyMode(), currentSourceLayout) }
            }
            applyModeUi()
        }
    }

    override fun snapshot() = VideoSessionBridge.Snapshot(
        queue, queueIndex, getPositionMs().coerceAtLeast(0L), player?.playWhenReady == true
    )

    override fun onOwnershipLost() {
        loadJob?.cancel()
        clearBufferWatch()
        findViewById<PlayerView>(R.id.player_view).player = null
        PlaybackEngine.onOwnershipLost()
        if (!isFinishing) finish()
    }

    override fun onStop() {
        saveHistoryProgress()
        if (!playbackEndHandled && !isAudioOnlyMode()) {
            // Leaving the visible player (Home, app switch, Back, screen off) must never pause the
            // media. A video surface cannot survive headlessly, so continue the exact session as
            // audio-only in the process-wide engine.
            handoffVideoToBackgroundAudio()
        }
        super.onStop()
    }

    override fun onDestroy() {
        saveHistoryProgress()
        meteredWatcher?.stop()
        meteredWatcher = null
        if (screenOffReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenOffReceiverRegistered = false
        }
        val wasOwner = VideoSessionBridge.isOwner(this)
        VideoSessionBridge.unregister(this)
        val wasAudioOnly = isAudioOnlyMode()
        // Video mode has no persistent surface to render to, so PlaybackEngine stops it here;
        // audio-only mode keeps running untouched — the shared player was never owned by this
        // Activity in the first place, which is what lets it survive back navigation.
        if (player != null && (VideoSessionBridge.controller == null || VideoSessionBridge.controller === PlaybackEngine)) {
            PlaybackEngine.activityDetached(wasAudioOnly)
        }
        if (wasOwner && !VideoSessionBridge.isVideoActive()) NowPlayingBus.clear()
        mainHandler.removeCallbacksAndMessages(null)
        thumbJob?.cancel()
        relatedJob?.cancel()
        channelAvatarJob?.cancel()
        loadJob?.cancel()
        playerListener?.let { player?.removeListener(it) }
        findViewById<PlayerView>(R.id.player_view).player = null
        player = null
        trackSelector = null
        super.onDestroy()
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stopPlayback() {
        PlaybackEngine.stopAndClear()
        runOnUiThread { finish() }
    }

    override fun skipToNext() {
        if (queueIndex < queue.lastIndex) {
            saveHistoryProgress()
            queueIndex++
            val next = queue[queueIndex]
            playIndex(queueIndex, resumePositionMs = WatchHistoryStore.resumePositionFromDescription(next))
        }
    }

    override fun skipToPrevious() {
        if (queueIndex > 0) {
            saveHistoryProgress()
            queueIndex--
            val previous = queue[queueIndex]
            playIndex(queueIndex, resumePositionMs = WatchHistoryStore.resumePositionFromDescription(previous))
        } else {
            player?.seekTo(0)
        }
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun playMediaId(videoId: String) {
        val index = queue.indexOfFirst { it.mediaId == videoId }
        if (index >= 0) {
            playIndex(index)
        }
    }

    override fun playQueue(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) {
        val normalized = queue.ifEmpty { listOf(video) }
        this.queue = normalized
        queueIndex = normalized.indexOfFirst { it.mediaId == video.mediaId }.takeIf { it >= 0 } ?: 0
        PlaybackEngine.queue = normalized
        PlaybackEngine.queueIndex = queueIndex
        playIndex(queueIndex, resumePositionMs = WatchHistoryStore.resumePositionFromDescription(normalized[queueIndex]))
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun getPositionMs(): Long = if (loadJob?.isActive == true)
        NowPlayingBus.state.value?.takeIf { it.videoId == queue.getOrNull(queueIndex)?.mediaId }?.positionMs ?: 0L
        else player?.currentPosition ?: 0L

    override fun getDurationMs(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L

    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val MENU_SPEED = 1
        private const val MENU_QUALITY = 2
        private const val MENU_AUDIO_LANGUAGE = 3
        private const val MENU_AUDIO_QUALITY = 4
        private const val MENU_CACHE = 5
        private const val MENU_STOP = 6
        private const val MAX_RECOVERY_ATTEMPTS = 2
        private const val BUFFERING_RECOVERY_DELAY_MS = 12_000L
        private const val RECOVERY_RETRY_DELAY_MS = 700L
        private const val RECOVERY_COOLDOWN_MS = 4_000L
        private const val HISTORY_SAVE_INTERVAL_MS = 30_000L
        const val EXTRA_VIDEO = "video"
        const val EXTRA_QUEUE = "queue"
        /** Set by the mini-player bar: reattach to PlaybackEngine's already-playing session
         *  instead of starting a fresh one from EXTRA_VIDEO/EXTRA_QUEUE. */
        const val EXTRA_RESUME_SESSION = "resume_session"
    }
}
