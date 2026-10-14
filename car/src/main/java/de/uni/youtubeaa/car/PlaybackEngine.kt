@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.content.Context
import android.os.SystemClock
import android.support.v4.media.MediaDescriptionCompat
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-wide playback engine: owns the single ExoPlayer instance so audio-only playback keeps
 * running after VideoPlayerActivity is destroyed (back navigation, task cleanup, ...). The
 * Activity always plays through this engine's [player]/[trackSelector] instead of creating its
 * own, so the object identity never changes across an Activity's create/destroy cycles - only
 * the UI (PlayerView surface, VideoSessionBridge registration) attaches and detaches.
 *
 * On hand-off (audio-only mode, [activityDetached]) this registers itself as a
 * [VideoSessionBridge.Controller] so the media notification and any mini-player UI keep working
 * with no Activity alive; VideoPlayerActivity re-registering itself on top simply wins via
 * VideoSessionBridge's existing priority/order tie-break, and control falls back here the moment
 * it unregisters again.
 */
object PlaybackEngine : VideoSessionBridge.Controller {

    private const val HISTORY_SAVE_INTERVAL_MS = 30_000L

    private lateinit var appContext: Context
    private var initialized = false

    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val streamResolver = StreamResolver()
    private val metadataRefetcher = VideoMetadataRefetcher()
    private var loadJob: Job? = null
    private var metadataJob: Job? = null
    private var tickerJob: Job? = null
    var currentIsLive: Boolean = false
    var sourceHasVideo = false
    var sourceHasAudio = false
    var knownDurationMs = 0L
    private var pendingPositionMs = 0L
    fun isResolving(): Boolean = loadJob?.isActive == true
    /** False when play() alone cannot start output (no source after a failed resolve/error). */
    fun canResumeInPlace(): Boolean = isResolving() || player.playbackState != Player.STATE_IDLE
    private var lastHistorySaveElapsedMs: Long = 0L

    private val handle: PlayerFactory.Handle by lazy {
        PlayerFactory.create(appContext).also { handle ->
            handle.player.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (!VideoSessionBridge.isOwner(this@PlaybackEngine) || isResolving()) return
                    NowPlayingBus.updatePlayback(
                        player.isPlaying, player.currentPosition, player.duration,
                        isBuffering = loadJob?.isActive == true || player.playbackState == Player.STATE_BUFFERING,
                        owner = this@PlaybackEngine
                    )
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (!VideoSessionBridge.isOwner(this@PlaybackEngine) || state != Player.STATE_ENDED) return
                    saveHistoryProgress()
                    if (SettingsRepository(appContext).isAutoplayEnabled() && queueIndex < queue.lastIndex) {
                        playIndexHeadless(queueIndex + 1)
                    } else stopAndClear()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (!VideoSessionBridge.isOwner(this@PlaybackEngine)) return
                    handle.player.pause()
                    NowPlayingBus.updatePlayback(false, handle.player.currentPosition,
                        isBuffering = false, owner = this@PlaybackEngine)
                }
            })
        }
    }
    val player: ExoPlayer get() = handle.player
    val trackSelector: DefaultTrackSelector get() = handle.trackSelector

    var queue: List<MediaDescriptionCompat> = emptyList()
    var queueIndex: Int = 0

    /** Foreground presentation the user last selected on the phone. Background playback may
     * temporarily use an audio-only source, but reopening the mini player restores this mode. */
    enum class PhonePresentation { VIDEO, AUDIO }
    var phonePresentation: PhonePresentation = PhonePresentation.VIDEO

    fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
    }

    fun hasSession(): Boolean = queue.getOrNull(queueIndex) != null

    data class ProjectionHandoff(
        val video: MediaDescriptionCompat,
        val queue: List<MediaDescriptionCompat>,
        val queueIndex: Int,
        val positionMs: Long,
        internal val decoderLease: ProjectionDecoderLease
    )

    data class ProjectionDecoderLease internal constructor(
        val videoWasDisabled: Boolean,
        val wasPlaying: Boolean,
        val pausedForVideoOnlySource: Boolean
    )

    /**
     * Release the phone video renderer before the HU starts its own MediaCodec. If the phone
     * source also carries audio, audio keeps playing during the projection warm-up; otherwise the
     * phone pauses. This prevents a short but thermally expensive two-video-decoder overlap.
     */
    fun suspendPhoneVideoDecoderForProjection(): ProjectionDecoderLease {
        val wasVideoDisabled = trackSelector.parameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)
        val wasPlaying = player.playWhenReady || player.isPlaying
        val pauseForVideoOnly = sourceHasVideo && !sourceHasAudio
        if (sourceHasVideo && !wasVideoDisabled) {
            PlayerFactory.setVideoSuspended(trackSelector, true)
            HeadUnitDiagnostics.event(
                "PlaybackEngine",
                "phone-video-decoder-suspended-for-projection sourceHasAudio=$sourceHasAudio wasPlaying=$wasPlaying"
            )
        }
        if (pauseForVideoOnly && wasPlaying) player.pause()
        return ProjectionDecoderLease(wasVideoDisabled, wasPlaying, pauseForVideoOnly)
    }

    fun restorePhoneVideoDecoderAfterProjectionFailure(lease: ProjectionDecoderLease) {
        if (!lease.videoWasDisabled && sourceHasVideo) {
            PlayerFactory.setVideoSuspended(trackSelector, false)
        }
        if (lease.wasPlaying && (lease.pausedForVideoOnlySource || !player.playWhenReady)) player.play()
        HeadUnitDiagnostics.event(
            "PlaybackEngine",
            "phone-video-decoder-restored-after-projection-failure wasPlaying=${lease.wasPlaying}"
        )
    }

    /**
     * Snapshot the active phone session for a phone -> Android Auto handoff and pause the phone
     * player before the projected player takes over at the same position. The head unit is then
     * allowed to continue immediately, avoiding overlapping playback on both outputs.
     */
    fun prepareProjectionHandoff(): ProjectionHandoff? {
        val active = VideoSessionBridge.controller
        val snapshot = active?.snapshot()
        val current = snapshot?.let { it.queue.getOrNull(it.queueIndex) } ?: queue.getOrNull(queueIndex) ?: return null
        val position = snapshot?.positionMs ?: player.currentPosition.coerceAtLeast(0L)
        val decoderLease = suspendPhoneVideoDecoderForProjection()
        // Native AA audio keeps playing while the projected player resolves and buffers: the HU
        // player takes over at READY from the audio's then-current position. Pausing it here left
        // the car silent for the whole load (27 s in one head-unit log).
        if (VideoSessionBridge.activeRole != VideoSessionBridge.Role.NATIVE_AUDIO) {
            active?.pause() ?: player.pause()
            NowPlayingBus.updatePlayback(false, position, player.duration, owner = this@PlaybackEngine)
        }
        return ProjectionHandoff(
            video = current,
            queue = snapshot?.queue ?: queue.ifEmpty { listOf(current) },
            queueIndex = snapshot?.queueIndex ?: queueIndex,
            positionMs = position,
            decoderLease = decoderLease
        )
    }


    fun restoreProjectionHandoffAfterFailure(handoff: ProjectionHandoff) {
        restorePhoneVideoDecoderAfterProjectionFailure(handoff.decoderLease)
    }

    /**
     * Called from VideoPlayerActivity.onDestroy(). Audio-only playback simply keeps running
     * (this is the whole point - the shared player was never owned by the Activity in the first
     * place); video mode has no persistent surface to render to, so closing the screen stops it,
     * matching the already-established "video pauses on background/close" behavior.
     */
    fun activityAttached() {
        tickerJob?.cancel()
        tickerJob = null
    }

    fun cancelPendingLoad() { loadJob?.cancel() }

    fun activityDetached(wasAudioOnly: Boolean) {
        if (VideoSessionBridge.activeRole != null && VideoSessionBridge.activeRole != VideoSessionBridge.Role.PHONE) return
        if (wasAudioOnly) {
            // Guarded by hasSession() so a just-cleared session (explicit stop button, which
            // calls stopAndClear() right before the Activity finishes) doesn't leave this
            // registered as a controller for nothing to control.
            if (hasSession()) {
                VideoSessionBridge.register(this, VideoSessionBridge.Role.PHONE)
                startTicker()
            }
            return
        }
        player.pause()
        player.clearMediaItems()
        queue = emptyList()
        queueIndex = 0
    }

    /**
     * Hands an active phone-video session to the process-wide engine as audio-only playback.
     * If the current source already carries audio (muxed/manifest/merged), disabling video tracks
     * is instant and preserves the exact buffered position. If it does not, resolve a pure audio
     * stream headlessly and resume from the same position.
     */
    fun handoffToBackgroundAudio(
        positionMs: Long,
        keepPlaying: Boolean,
        currentSourceHasAudio: Boolean,
        isLive: Boolean
    ) {
        val video = queue.getOrNull(queueIndex) ?: return
        val videoId = video.mediaId ?: return
        currentIsLive = isLive
        pendingPositionMs = positionMs.coerceAtLeast(0L)
        sourceHasAudio = currentSourceHasAudio
        VideoSessionBridge.register(this, VideoSessionBridge.Role.PHONE)
        startTicker()

        if (currentSourceHasAudio && player.mediaItemCount > 0) {
            loadJob?.cancel()
            PlayerFactory.setAudioOnly(trackSelector, true)
            player.playWhenReady = keepPlaying
            if (keepPlaying) player.play()
            NowPlayingBus.updatePlayback(player.isPlaying || keepPlaying, player.currentPosition, player.duration, owner = this@PlaybackEngine)
            return
        }

        val settings = SettingsRepository(appContext)
        loadJob?.cancel()
        player.playWhenReady = keepPlaying
        loadJob = engineScope.launch {
            val prefs = StreamResolver.AudioPrefs(
                language = settings.getAudioLanguage(),
                bitrate = settings.getAudioBitrate()
            )
            when (val result = streamResolver.resolveAudioStreamUrl(videoId, prefs)) {
                is StreamResolver.Result.Success -> {
                    currentIsLive = result.isLive
                    sourceHasVideo = false
                    sourceHasAudio = true
                    knownDurationMs = result.durationMs
                    PlayerFactory.setAudioOnly(trackSelector, true)
                    PlayerFactory.replaceSource(
                        player = player,
                        source = PlaybackSources.mediaSource(result.streamUrl),
                        resumePositionMs = positionMs.coerceAtLeast(0L),
                        isLive = result.isLive,
                        playWhenReady = player.playWhenReady
                    )
                    NowPlayingBus.updatePlayback(player.isPlaying, positionMs.coerceAtLeast(0L), result.durationMs, owner = this@PlaybackEngine)
                }
                is StreamResolver.Result.Error -> {
                    // Keep the previous item paused rather than clearing the session entirely.
                    player.pause()
                    NowPlayingBus.updatePlayback(false, positionMs.coerceAtLeast(0L), player.duration, owner = this@PlaybackEngine)
                }
            }
        }
    }

    /**
     * Takes over a projection session after Android Auto disappears. The phone session is kept
     * paused on purpose so reconnecting/disconnecting the car never starts audio unexpectedly
     * through the handset speaker. Pressing Play from the notification/mini-player resumes the
     * same queue at the same position using an audio-only stream.
     */
    fun adoptProjectionSessionPaused(
        context: Context,
        video: MediaDescriptionCompat,
        projectionQueue: List<MediaDescriptionCompat>,
        projectionQueueIndex: Int,
        positionMs: Long,
        isLive: Boolean
    ) {
        ensureInit(context)
        val videoId = video.mediaId ?: return
        loadJob?.cancel()
        metadataJob?.cancel()
        pendingPositionMs = positionMs.coerceAtLeast(0L)
        currentIsLive = isLive
        sourceHasVideo = false
        sourceHasAudio = false
        knownDurationMs = 0L
        queue = projectionQueue.ifEmpty { listOf(video) }
        queueIndex = projectionQueueIndex.coerceIn(0, queue.lastIndex.coerceAtLeast(0))
        VideoSessionBridge.activate(this, VideoSessionBridge.Role.PHONE)
        startTicker()

        player.pause()
        player.clearMediaItems()
        NowPlayingBus.update(
            videoId = videoId,
            title = video.title?.toString() ?: videoId,
            channelTitle = video.subtitle?.toString(),
            thumbnailUrl = video.iconUri?.toString(),
            isPlaying = false,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            controllable = true,
            presentation = PlaybackPresentation.AUDIO,
            queueIndex = queueIndex,
            queueSize = queue.size.coerceAtLeast(1), owner = this@PlaybackEngine)

        val settings = SettingsRepository(appContext)
        loadJob?.cancel()
        loadJob = engineScope.launch {
            val prefs = StreamResolver.AudioPrefs(
                language = settings.getAudioLanguage(),
                bitrate = settings.getAudioBitrate()
            )
            when (val result = streamResolver.resolveAudioStreamUrl(videoId, prefs)) {
                is StreamResolver.Result.Success -> {
                    currentIsLive = result.isLive
                    sourceHasVideo = false
                    sourceHasAudio = true
                    knownDurationMs = result.durationMs
                    PlayerFactory.setAudioOnly(trackSelector, true)
                    PlayerFactory.replaceSource(
                        player = player,
                        source = PlaybackSources.mediaSource(result.streamUrl),
                        resumePositionMs = positionMs.coerceAtLeast(0L),
                        isLive = result.isLive,
                        playWhenReady = player.playWhenReady
                    )
                    NowPlayingBus.updatePlayback(player.isPlaying, positionMs.coerceAtLeast(0L), result.durationMs, owner = this@PlaybackEngine)
                }
                is StreamResolver.Result.Error -> {
                    player.pause()
                    NowPlayingBus.updatePlayback(false, positionMs.coerceAtLeast(0L), player.duration, owner = this@PlaybackEngine)
                }
            }
        }
    }

    private fun refreshMetadataAsync(video: MediaDescriptionCompat, videoId: String) {
        if (!metadataRefetcher.needsRepair(video, videoId)) return
        metadataJob?.cancel()
        metadataJob = engineScope.launch {
            val repaired = metadataRefetcher.repairIfNeeded(video)
            if (repaired === video || repaired.mediaId != videoId) return@launch
            queue = queue.map { if (it.mediaId == videoId) repaired else it }
            val state = NowPlayingBus.state.value ?: return@launch
            if (state.videoId != videoId) return@launch
            NowPlayingBus.update(
                videoId = videoId,
                title = repaired.title?.toString() ?: videoId,
                channelTitle = repaired.subtitle?.toString(),
                thumbnailUrl = repaired.iconUri?.toString(),
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                controllable = state.controllable,
                isBuffering = state.isBuffering,
                presentation = state.presentation,
                queueIndex = queueIndex,
                queueSize = queue.size.coerceAtLeast(1), owner = this@PlaybackEngine)
        }
    }

    fun stopAndClear() {
        RemoteCastBus.cancelPending()
        saveHistoryProgress()
        loadJob?.cancel()
        metadataJob?.cancel()
        tickerJob?.cancel()
        tickerJob = null
        player.stop()
        player.clearMediaItems()
        queue = emptyList()
        queueIndex = 0
        phonePresentation = PhonePresentation.VIDEO
        currentIsLive = false
        sourceHasVideo = false
        sourceHasAudio = false
        if (VideoSessionBridge.activeRole == VideoSessionBridge.Role.PHONE) {
            NowPlayingBus.clear(owner = VideoSessionBridge.controller)
        }
        VideoSessionBridge.unregister(this)
    }

    private fun saveHistoryProgress() {
        if (!initialized || !VideoSessionBridge.isOwner(this@PlaybackEngine) || isResolving() || player.mediaItemCount == 0 || !SettingsRepository(appContext).isHistoryEnabled()) return
        val video = queue.getOrNull(queueIndex) ?: return
        if (currentIsLive) {
            WatchHistoryStore(appContext).updateProgress(video, 0L, 0L)
            return
        }
        val duration = player.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
        WatchHistoryStore(appContext).updateProgress(
            video,
            player.currentPosition.coerceAtLeast(0L),
            duration
        )
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = engineScope.launch {
            while (true) {
                delay(1000)
                if (VideoSessionBridge.isOwner(this@PlaybackEngine) && player.mediaItemCount > 0 && NowPlayingBus.state.value != null && !isResolving()) {
                    NowPlayingBus.updatePlayback(player.isPlaying, player.currentPosition, player.duration,
                        isBuffering = player.playbackState == Player.STATE_BUFFERING, owner = this@PlaybackEngine)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastHistorySaveElapsedMs >= HISTORY_SAVE_INTERVAL_MS) {
                        lastHistorySaveElapsedMs = now
                        saveHistoryProgress()
                    }
                }
            }
        }
    }

    override fun snapshot() = VideoSessionBridge.Snapshot(
        queue, queueIndex, getPositionMs().coerceAtLeast(0L), player.playWhenReady
    )

    override fun onOwnershipLost() {
        loadJob?.cancel()
        metadataJob?.cancel()
        tickerJob?.cancel()
        tickerJob = null
        if (player.mediaItemCount > 0) pendingPositionMs = player.currentPosition.coerceAtLeast(0L)
        player.pause()
        // Handoff is confirmed before this callback. A merely paused decoder still retains
        // buffers and can keep downloading; stop releases media resources but keeps the engine.
        player.stop()
        player.clearMediaItems()
        sourceHasVideo = false
        sourceHasAudio = false
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
        saveHistoryProgress()
    }

    override fun stopPlayback() {
        stopAndClear()
    }

    override fun isPlaying(): Boolean = player.isPlaying

    override fun getPositionMs(): Long = if (isResolving() || player.mediaItemCount == 0) pendingPositionMs else player.currentPosition

    override fun getDurationMs(): Long = player.duration.coerceAtLeast(0L)

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun skipToNext() {
        if (queueIndex < queue.lastIndex) {
            saveHistoryProgress()
            playIndexHeadless(queueIndex + 1)
        }
    }

    override fun skipToPrevious() {
        if (queueIndex > 0) {
            saveHistoryProgress()
            playIndexHeadless(queueIndex - 1)
        } else player.seekTo(0)
    }

    override fun playMediaId(videoId: String) {
        val index = queue.indexOfFirst { it.mediaId == videoId }
        if (index >= 0) playIndexHeadless(index)
    }

    override fun playQueue(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) {
        val normalized = queue.ifEmpty { listOf(video) }
        this.queue = normalized
        queueIndex = normalized.indexOfFirst { it.mediaId == video.mediaId }.takeIf { it >= 0 } ?: 0
        VideoSessionBridge.register(this, VideoSessionBridge.Role.PHONE)
        startTicker()
        playIndexHeadless(queueIndex)
    }

    /** No Activity is alive to show a video surface, so a headless load/skip is always audio-only. */
    private fun playIndexHeadless(index: Int) {
        val video = queue.getOrNull(index) ?: return
        val videoId = video.mediaId ?: return
        currentIsLive = false
        RemoteCastBus.cancelPending()
        player.pause()
        player.clearMediaItems()
        player.playWhenReady = true
        sourceHasVideo = false
        sourceHasAudio = false
        queueIndex = index
        val resumePositionMs = WatchHistoryStore.resumePositionFromDescription(video)
        pendingPositionMs = resumePositionMs
        NowPlayingBus.update(
            videoId = videoId,
            title = video.title?.toString() ?: videoId,
            channelTitle = video.subtitle?.toString(),
            thumbnailUrl = video.iconUri?.toString(),
            isPlaying = false,
            isBuffering = true,
            positionMs = resumePositionMs,
            durationMs = 0L,
            controllable = true,
            presentation = PlaybackPresentation.AUDIO,
            queueIndex = queueIndex,
            queueSize = queue.size.coerceAtLeast(1), owner = this@PlaybackEngine)
        refreshMetadataAsync(video, videoId)
        val settings = SettingsRepository(appContext)
        if (settings.isHistoryEnabled()) {
            WatchHistoryStore(appContext).recordFromDescription(video)
        }
        loadJob?.cancel()
        loadJob = engineScope.launch {
            val prefs = StreamResolver.AudioPrefs(
                language = settings.getAudioLanguage(),
                bitrate = settings.getAudioBitrate()
            )
            when (val result = streamResolver.resolveAudioStreamUrl(videoId, prefs)) {
                is StreamResolver.Result.Success -> {
                    currentIsLive = result.isLive
                    sourceHasVideo = false
                    sourceHasAudio = true
                    knownDurationMs = result.durationMs
                    PlayerFactory.setAudioOnly(trackSelector, true)
                    PlayerFactory.replaceSource(
                        player = player,
                        source = PlaybackSources.mediaSource(result.streamUrl),
                        resumePositionMs = resumePositionMs,
                        isLive = result.isLive,
                        playWhenReady = player.playWhenReady
                    )
                }
                is StreamResolver.Result.Error -> {
                    player.pause()
                    NowPlayingBus.updatePlayback(false, resumePositionMs, isBuffering = false, owner = this@PlaybackEngine)
                }
                // No UI to show a status message to here; if VideoPlayerActivity is open it
                // shows its own error via the player's error listener instead.
            }
        }
    }
}
