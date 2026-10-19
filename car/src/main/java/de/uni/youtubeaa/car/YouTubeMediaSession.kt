@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import kotlin.math.abs
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import de.uni.youtubeaa.car.hybrid.BrowseFacade
import de.uni.youtubeaa.car.hybrid.BrowseOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Playback-Steuerung (Play/Pause/Seek/Next/Previous) über MediaSessionCompat (Tech-Stack),
// All playback paths use Media3 audio-focus management, including transient suppression/resume.
@OptIn(markerClass = [UnstableApi::class])
class YouTubeMediaSession(private val context: Context) {

    val mediaSession: MediaSessionCompat = MediaSessionCompat(context, "YouTubeMediaSession")

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val streamResolver = StreamResolver()
    private val metadataRefetcher = VideoMetadataRefetcher()
    private val browseFacade = BrowseFacade(context.applicationContext)
    private val nativeHandle = PlayerFactory.create(context, diagnosticTag = "NativeAudioPlayer").also {
        PlayerFactory.setAudioOnly(it.trackSelector, true)
    }
    private val exoPlayer: ExoPlayer = nativeHandle.player

    private var browseQueue: List<MediaDescriptionCompat> = emptyList()
    private var activeQueue: List<MediaDescriptionCompat> = emptyList()
    private var currentIndex: Int = -1
    private var lastArtUrl: String? = null
    private var lastArtBitmap: Bitmap? = null
    private var lastMetadataKey: SessionMetadataKey? = null
    private var lastPublishedArt: Bitmap? = null
    private var audioLoadJob: Job? = null
    private var artLoadJob: Job? = null
    private var voiceSearchJob: Job? = null
    private var audioRequestId = 0L
    private var commandGeneration = 0L
    private var nativeCurrentIsLive = false
    private var nativeWantsPlay = true
    private val lastSessionStore = LastSessionStore(context)

    /** True while the session only shows the persisted last item (nothing loaded yet). */
    var isRestoredPreview = false
        private set

    private val nativeController = object : VideoSessionBridge.Controller {
        override fun play() {
            nativeWantsPlay = true
            if (audioLoadJob?.isActive == true) return
            exoPlayer.play()
        }
        override fun pause() { nativeWantsPlay = false; exoPlayer.pause(); saveNativeHistoryProgress() }
        override fun stopPlayback() {
            saveNativeHistoryProgress()
            audioLoadJob?.cancel()
            audioRequestId++
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            NowPlayingBus.clear(owner = this)
            CarPlaybackState.clear(CarPlaybackState.Owner.NATIVE_AUDIO)
            VideoSessionBridge.unregister(this)
        }
        override fun skipToNext() = this@YouTubeMediaSession.skipToNext()
        override fun skipToPrevious() = this@YouTubeMediaSession.skipToPrevious()
        override fun seekTo(positionMs: Long) { exoPlayer.seekTo(positionMs.coerceAtLeast(0L)) }
        override fun playMediaId(videoId: String) {
            val index = activeQueue.indexOfFirst { it.mediaId == videoId }
            if (index < 0) return
            saveNativeHistoryProgress()
            currentIndex = index
            playAudio(activeQueue[index])
        }
        override fun playQueue(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) {
            saveNativeHistoryProgress()
            activeQueue = queue.ifEmpty { listOf(video) }
            currentIndex = activeQueue.indexOfFirst { it.mediaId == video.mediaId }.coerceAtLeast(0)
            playAudio(activeQueue[currentIndex])
        }
        override fun isPlaying() = exoPlayer.isPlaying
        override fun getPositionMs() = exoPlayer.currentPosition
        override fun getDurationMs() = exoPlayer.duration.coerceAtLeast(0L)
        override fun snapshot() = VideoSessionBridge.Snapshot(
            activeQueue, currentIndex,
            if (audioLoadJob?.isActive == true) NowPlayingBus.state.value?.positionMs ?: 0L else exoPlayer.currentPosition.coerceAtLeast(0L),
            if (audioLoadJob?.isActive == true) nativeWantsPlay else exoPlayer.playWhenReady
        )
        override fun onOwnershipLost() {
            saveNativeHistoryProgress()
            audioLoadJob?.cancel()
            audioRequestId++
            exoPlayer.pause()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            CarPlaybackState.clear(CarPlaybackState.Owner.NATIVE_AUDIO)
        }
    }

    init {
        mediaSession.setCallback(SessionCallback())
        mediaSession.isActive = true
        exoPlayer.addListener(PlayerEventListener())
        updatePlaybackState(PlaybackStateCompat.STATE_NONE)
        // Mirrors whichever screen currently owns playback (phone VideoPlayerActivity or the
        // car projection's ExoPlayerVideoFragment, via VideoSessionBridge) into this session's
        // metadata/state, so the phone notification and the head unit's media widget stay in sync.
        sessionScope.launch {
            NowPlayingBus.state.collect { applyNowPlaying(it) }
        }
        sessionScope.launch {
            while (true) {
                delay(HISTORY_SAVE_INTERVAL_MS)
                saveNativeHistoryProgress()
                saveLastSession()
            }
        }
    }

    private fun applyNowPlaying(nowPlaying: NowPlaying?) {
        if (nowPlaying == null) {
            artLoadJob?.cancel()
            lastArtUrl = null
            lastArtBitmap = null
            lastMetadataKey = null
            lastPublishedArt = null
            val restored = lastSessionStore.load()
            isRestoredPreview = restored != null
            if (restored != null) {
                publishRestoredSession(restored)
            } else {
                mediaSession.setMetadata(null)
                updatePlaybackState(PlaybackStateCompat.STATE_NONE)
            }
            return
        }
        isRestoredPreview = false
        if (!nowPlaying.controllable) return
        updatePlaybackState(
            when {
                nowPlaying.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
                nowPlaying.isPlaying -> PlaybackStateCompat.STATE_PLAYING
                else -> PlaybackStateCompat.STATE_PAUSED
            },
            positionMs = nowPlaying.positionMs
        )
        if (nowPlaying.thumbnailUrl != lastArtUrl) {
            lastArtUrl = nowPlaying.thumbnailUrl
            lastArtBitmap = null
            artLoadJob?.cancel()
            setNowPlayingMetadata(nowPlaying, null)
            artLoadJob = sessionScope.launch {
                val bitmap = nowPlaying.thumbnailUrl?.let { loadThumbnailBitmap(it, targetPx = MEDIA_SESSION_ART_PX) }
                if (lastArtUrl == nowPlaying.thumbnailUrl) {
                    lastArtBitmap = bitmap
                    NowPlayingBus.state.value?.takeIf { it.videoId == nowPlaying.videoId }
                        ?.let { setNowPlayingMetadata(it, bitmap) }
                }
            }
        } else {
            setNowPlayingMetadata(nowPlaying, lastArtBitmap)
        }
    }

    private fun setNowPlayingMetadata(nowPlaying: NowPlaying, art: Bitmap?) {
        val key = SessionMetadataKey.from(nowPlaying)
        if (key == lastMetadataKey && art === lastPublishedArt) return
        lastMetadataKey = key
        lastPublishedArt = art
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, nowPlaying.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nowPlaying.channelTitle ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, nowPlaying.durationMs)
        if (art != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        }
        mediaSession.setMetadata(builder.build())
    }

    /**
     * Nothing is loaded (fresh process after an AA disconnect, or the session was cleared): show
     * the last persisted item as paused so the AA player offers it again instead of an empty,
     * unplayable screen. Play / "continue as video" then resume it at the saved position.
     */
    private fun publishRestoredSession(session: LastSessionStore.Session) {
        val item = session.current ?: return
        val position = session.resumePositionMs()
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title?.toString() ?: item.mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.subtitle?.toString() ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, session.durationMs)
                .build()
        )
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, positionMs = position)
        val artUrl = item.iconUri?.toString() ?: return
        artLoadJob = sessionScope.launch {
            val art = loadThumbnailBitmap(artUrl, targetPx = MEDIA_SESSION_ART_PX) ?: return@launch
            if (NowPlayingBus.state.value != null) return@launch
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title?.toString() ?: item.mediaId)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.subtitle?.toString() ?: "")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, session.durationMs)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                    .build()
            )
        }
    }

    /** The session to continue: the live controller's queue/position, else the persisted one. */
    private fun currentSession(): LastSessionStore.Session? {
        val controller = VideoSessionBridge.controller
        val snapshot = controller?.snapshot()
        if (controller != null && snapshot != null && snapshot.queue.getOrNull(snapshot.queueIndex)?.mediaId != null) {
            return LastSessionStore.Session(
                snapshot.queue, snapshot.queueIndex, snapshot.positionMs, controller.getDurationMs().coerceAtLeast(0L)
            )
        }
        return lastSessionStore.load()
    }

    private fun saveLastSession() {
        val controller = VideoSessionBridge.controller ?: return
        val snapshot = controller.snapshot() ?: return
        lastSessionStore.save(snapshot.queue, snapshot.queueIndex, snapshot.positionMs, controller.getDurationMs().coerceAtLeast(0L))
    }

    /** True when the active controller can start output by a plain play(). */
    private fun canResumeInPlace(controller: VideoSessionBridge.Controller): Boolean = when {
        controller === PlaybackEngine -> PlaybackEngine.canResumeInPlace()
        controller === nativeController -> audioLoadJob?.isActive == true || exoPlayer.playbackState != Player.STATE_IDLE
        else -> true
    }

    /** Re-start the current/persisted item as native audio at its last position. */
    private fun resumeAsAudio(): Boolean {
        val session = currentSession() ?: return false
        val item = session.current ?: return false
        val videoId = item.mediaId ?: return false
        HeadUnitDiagnostics.event("MediaSession", "resumeAsAudio videoId=$videoId position=${session.resumePositionMs()}")
        RemoteCastBus.cancelPending()
        commandGeneration++
        activeQueue = session.queue
        currentIndex = session.queueIndex
        playVideoId(videoId, item, session.resumePositionMs())
        return true
    }

    /** AA shows an error message on a non-error state as a transient toast. */
    private fun showNonFatalError(message: String) {
        val current = mediaSession.controller.playbackState
        updatePlaybackState(
            current?.state ?: PlaybackStateCompat.STATE_PAUSED,
            errorMessage = message,
            positionMs = current?.position ?: exoPlayer.currentPosition
        )
    }

    /** Latest list exposed by MediaBrowser. Browsing must not mutate the active playback queue. */
    fun setBrowseQueue(items: List<MediaDescriptionCompat>) {
        browseQueue = items.map(::normalizeDescription)
    }

    fun release() {
        saveNativeHistoryProgress()
        audioLoadJob?.cancel()
        artLoadJob?.cancel()
        voiceSearchJob?.cancel()
        CarPlaybackState.clear(CarPlaybackState.Owner.NATIVE_AUDIO)
        exoPlayer.release()
        VideoSessionBridge.unregister(nativeController)
        sessionScope.cancel()
        mediaSession.release()
    }

    private fun saveNativeHistoryProgress(completed: Boolean = false) {
        if (CarPlaybackState.activeOwner() != CarPlaybackState.Owner.NATIVE_AUDIO) return
        if (!SettingsRepository(context).isHistoryEnabled()) return
        val video = activeQueue.getOrNull(currentIndex) ?: return
        if (nativeCurrentIsLive) {
            WatchHistoryStore(context).updateProgress(video, 0L, 0L)
            return
        }
        val reported = exoPlayer.duration
        val duration = if (reported > 0L && reported != androidx.media3.common.C.TIME_UNSET) reported else 0L
        val position = if (completed && duration > 0L) duration else exoPlayer.currentPosition.coerceAtLeast(0L)
        WatchHistoryStore(context).updateProgress(video, position, duration)
    }

    /**
     * Prefer projection video: keep the full active queue and ask AA to open „YouTube Video“.
     * Audio fallback is used only when projection opening definitively fails.
     */
    private fun playViaProjectionOrAudio(video: MediaDescriptionCompat, livePositionMs: Long? = null) {
        val queueForPlayback = activeQueue.ifEmpty { listOf(video) }
        val requestedVideoId = video.mediaId
        val generation = commandGeneration
        val resumePositionMs = livePositionMs ?: WatchHistoryStore.resumePositionFromDescription(video)
        when (
            RemoteCastBus.cast(
                video = video,
                queue = queueForPlayback,
                context = context,
                startPositionMs = resumePositionMs,
                onProjectionFailed = {
                    // Projection startup can complete after a newer MediaSession selection.
                    // Never resurrect the stale item as an audio fallback.
                    if (generation == commandGeneration && activeQueue.getOrNull(currentIndex)?.mediaId == requestedVideoId) playAudio(video, resumePositionMs)
                },
                onStarted = {
                    // Likewise, only relinquish audio focus for the item actually handed off.
                    if (activeQueue.getOrNull(currentIndex)?.mediaId == requestedVideoId) {
                        exoPlayer.pause()
                    }
                }
            )
        ) {
            RemoteCastBus.CastResult.Queued -> {
                // "Started" means the request reached the projection host. Actual ownership is
                // confirmed asynchronously by onStarted after the HU fragment registered itself.
                // Until then keep the MediaSession buffering and do not stop phone/audio playback.
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            }
            RemoteCastBus.CastResult.ParkBlocked,
            RemoteCastBus.CastResult.Rejected -> playAudio(video, resumePositionMs)
        }
    }

    private fun normalizeDescription(description: MediaDescriptionCompat): MediaDescriptionCompat {
        val rawId = description.mediaId?.removePrefix(YouTubeMediaBrowserService.VIDEO_PREFIX)
            ?.takeIf { it.isNotBlank() } ?: return description
        return MediaDescriptionCompat.Builder()
            .setMediaId(rawId)
            .setTitle(description.title ?: rawId)
            .setSubtitle(description.subtitle)
            .setDescription(description.description)
            .setIconBitmap(description.iconBitmap)
            .setIconUri(description.iconUri)
            .setMediaUri(description.mediaUri)
            .setExtras(description.extras)
            .build()
    }

    private fun playAudio(
        video: MediaDescriptionCompat,
        resumePositionMs: Long = WatchHistoryStore.resumePositionFromDescription(video)
    ) {
        val videoId = video.mediaId ?: return
        playVideoId(videoId, video, resumePositionMs)
    }

    private fun repairMetadataAsync(description: MediaDescriptionCompat) {
        val videoId = description.mediaId ?: return
        if (!metadataRefetcher.needsRepair(description, videoId)) return
        sessionScope.launch {
            val repaired = metadataRefetcher.repairIfNeeded(description)
            if (repaired.mediaId != videoId) return@launch
            activeQueue = activeQueue.map { if (it.mediaId == videoId) repaired else it }
            val current = activeQueue.getOrNull(currentIndex)
            if (current?.mediaId == videoId) setNowPlayingFromDescription(repaired)
        }
    }

    private fun playVideoId(
        videoId: String,
        description: MediaDescriptionCompat? = null,
        resumePositionMs: Long = 0L
    ) {
        VideoSessionBridge.activate(nativeController, VideoSessionBridge.Role.NATIVE_AUDIO)
        isRestoredPreview = false
        val requestId = ++audioRequestId
        nativeWantsPlay = true
        nativeCurrentIsLive = false
        audioLoadJob?.cancel()
        exoPlayer.pause()
        CarPlaybackState.markActive(CarPlaybackState.Owner.NATIVE_AUDIO, videoId)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(ExoPlayerVideoFragment.KEY_LAST_VIDEO_ID, videoId)
            .apply()
        updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, positionMs = resumePositionMs.coerceAtLeast(0L))
        description?.let {
            if (SettingsRepository(context).isHistoryEnabled()) {
                WatchHistoryStore(context).recordFromDescription(it)
            }
            NowPlayingBus.update(
                videoId = videoId,
                title = it.title?.toString() ?: videoId,
                channelTitle = it.subtitle?.toString(),
                thumbnailUrl = it.iconUri?.toString(),
                isPlaying = false,
                positionMs = resumePositionMs.coerceAtLeast(0L),
                durationMs = 0L,
                controllable = true,
                isBuffering = true,
                presentation = PlaybackPresentation.AUDIO,
                queueIndex = currentIndex.coerceAtLeast(0),
                queueSize = activeQueue.size.coerceAtLeast(1), owner = nativeController)
        }
        audioLoadJob = sessionScope.launch {
            val settings = SettingsRepository(context)
            val audioPrefs = StreamResolver.AudioPrefs(
                language = settings.getAudioLanguage(),
                bitrate = settings.getAudioBitrate()
            )
            when (val result = streamResolver.resolveAudioStreamUrl(videoId, audioPrefs)) {
                is StreamResolver.Result.Success -> {
                    if (requestId != audioRequestId) return@launch
                    nativeCurrentIsLive = result.isLive
                    // A muxed HLS fallback (live) is pinned to its lowest rung; a pure audio URL
                    // or DASH just keeps the video track disabled.
                    PlayerFactory.setAudioOnly(nativeHandle.trackSelector, true, result.layout)
                    exoPlayer.setMediaSource(PlaybackSources.mediaSource(result.streamUrl))
                    exoPlayer.prepare()
                    if (resumePositionMs > 0L && !result.isLive) {
                        exoPlayer.seekTo(resumePositionMs)
                    }
                    exoPlayer.setPlaybackSpeed(SettingsRepository(context).getPlaybackSpeed())
                    exoPlayer.playWhenReady = nativeWantsPlay
                    description?.let {
                        setNowPlayingFromDescription(it)
                        repairMetadataAsync(it)
                    }
                }
                is StreamResolver.Result.Error -> {
                    if (requestId != audioRequestId) return@launch
                    CarPlaybackState.clear(CarPlaybackState.Owner.NATIVE_AUDIO)
                    NowPlayingBus.clear(owner = nativeController)
                    updatePlaybackState(PlaybackStateCompat.STATE_ERROR, result.message)
                }
            }
        }
    }

    private fun skipToNext() {
        if (currentIndex in 0 until activeQueue.size - 1) {
            saveNativeHistoryProgress()
            currentIndex++
            val next = activeQueue[currentIndex]
            playVideoId(next.mediaId ?: return, next, WatchHistoryStore.resumePositionFromDescription(next))
        } else {
            saveNativeHistoryProgress()
            audioLoadJob?.cancel()
            audioRequestId++
            CarPlaybackState.clear(CarPlaybackState.Owner.NATIVE_AUDIO)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            NowPlayingBus.clear(owner = nativeController)
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        }
    }

    private fun skipToPrevious() {
        if (currentIndex > 0) {
            saveNativeHistoryProgress()
            currentIndex--
            val previous = activeQueue[currentIndex]
            playVideoId(previous.mediaId ?: return, previous, WatchHistoryStore.resumePositionFromDescription(previous))
        } else if (currentIndex == 0) {
            exoPlayer.seekTo(0)
        }
    }

    private fun setNowPlayingFromDescription(description: MediaDescriptionCompat) {
        if (!VideoSessionBridge.isOwner(nativeController)) return
        val id = description.mediaId ?: return
        NowPlayingBus.update(
            videoId = id,
            title = description.title?.toString() ?: id,
            channelTitle = description.subtitle?.toString(),
            thumbnailUrl = description.iconUri?.toString(),
            isPlaying = exoPlayer.isPlaying,
            isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
            positionMs = exoPlayer.currentPosition,
            durationMs = exoPlayer.duration.coerceAtLeast(0L),
            controllable = true,
            presentation = PlaybackPresentation.AUDIO,
            queueIndex = currentIndex.coerceAtLeast(0),
            queueSize = activeQueue.size.coerceAtLeast(1), owner = nativeController)
    }

    private fun updatePlaybackState(
        state: Int,
        errorMessage: String? = null,
        positionMs: Long = exoPlayer.currentPosition,
        speed: Float = exoPlayer.playbackParameters.speed
    ) {
        val headUnitOwnsPlayback = !MediaSessionCommandPolicy.allowStop(VideoSessionBridge.activeRole)
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            (if (headUnitOwnsPlayback) 0L else PlaybackStateCompat.ACTION_STOP)

        val effectiveSpeed = if (state == PlaybackStateCompat.STATE_PLAYING) speed else 0f
        val offersStop = !headUnitOwnsPlayback

        // Every PlaybackState goes over IPC to Android Auto, the system media controls and
        // Bluetooth, and each of them redraws. A state carries speed and an update time, so they
        // extrapolate the position themselves: re-sending it on every 0.5-1 s position tick only
        // burned CPU in all of them. Publish when something a controller cannot infer changed.
        val nowElapsed = SystemClock.elapsedRealtime()
        val published = PublishedState(state, effectiveSpeed, offersStop, errorMessage)
        val extrapolated = lastPublishedPositionMs +
            ((nowElapsed - lastPublishedAtElapsedMs) * (lastPublished?.speed ?: 0f)).toLong()
        if (published == lastPublished && errorMessage == null &&
            abs(positionMs - extrapolated) < POSITION_RESYNC_TOLERANCE_MS) return
        lastPublished = published
        lastPublishedPositionMs = positionMs
        lastPublishedAtElapsedMs = nowElapsed

        val builder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, positionMs, effectiveSpeed, nowElapsed)
        if (errorMessage != null) {
            builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, errorMessage)
        }
        mediaSession.setPlaybackState(builder.build())
    }

    /** What a controller sees of the last published state, besides the extrapolated position. */
    private data class PublishedState(
        val state: Int,
        val speed: Float,
        val offersStop: Boolean,
        val error: String?
    )
    private var lastPublished: PublishedState? = null
    private var lastPublishedPositionMs = 0L
    private var lastPublishedAtElapsedMs = 0L

    private inner class PlayerEventListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!VideoSessionBridge.isOwner(nativeController) || audioLoadJob?.isActive == true) return
            NowPlayingBus.updatePlayback(
                exoPlayer.isPlaying, exoPlayer.currentPosition, exoPlayer.duration,
                isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
                owner = nativeController
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!VideoSessionBridge.isOwner(nativeController) || audioLoadJob?.isActive == true) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    NowPlayingBus.updatePlayback(
                        isPlaying = false,
                        positionMs = exoPlayer.currentPosition,
                        durationMs = exoPlayer.duration,
                        isBuffering = true, owner = nativeController)
                    updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                }
                Player.STATE_READY -> updatePlaybackState(
                    if (exoPlayer.isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
                )
                Player.STATE_ENDED -> {
                    saveNativeHistoryProgress(completed = true)
                    if (SettingsRepository(context).isAutoplayEnabled()) {
                        skipToNext()
                    } else {
                        CarPlaybackState.clear(CarPlaybackState.Owner.NATIVE_AUDIO)
                        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    }
                }
                Player.STATE_IDLE -> updatePlaybackState(PlaybackStateCompat.STATE_NONE)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!VideoSessionBridge.isOwner(nativeController)) return
            audioLoadJob?.cancel()
            audioRequestId++
            CarPlaybackState.clear(CarPlaybackState.Owner.NATIVE_AUDIO)
            NowPlayingBus.clear(owner = nativeController)
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR, AppText.get(R.string.playback_error_detail, error.message.orEmpty()))
        }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            RemoteCastBus.cancelPending()
            commandGeneration++
            saveNativeHistoryProgress()
            val videoId = mediaId?.removePrefix(YouTubeMediaBrowserService.VIDEO_PREFIX) ?: return
            // Re-selecting the item native audio is already playing must continue from the live
            // position. Browse rows carry no (or a stale) history position, so the fallback
            // restarted the video at 0 (head-unit log: 24008 ms -> 0 after a failed projection).
            val livePositionMs = exoPlayer.currentPosition.takeIf {
                VideoSessionBridge.isOwner(nativeController) &&
                    activeQueue.getOrNull(currentIndex)?.mediaId == videoId &&
                    exoPlayer.playbackState != Player.STATE_IDLE
            }
            HeadUnitDiagnostics.event(
                "MediaSession",
                "command=playFromMediaId videoId=$videoId owner=${VideoSessionBridge.activeRole} livePosition=$livePositionMs"
            )
            val normalizedBrowseQueue = browseQueue.map(::normalizeDescription)
            val index = normalizedBrowseQueue.indexOfFirst { it.mediaId == videoId }
            if (index >= 0) {
                val selectedBrowseItem = normalizedBrowseQueue[index]
                if (WatchHistoryStore.isHistoryDescription(selectedBrowseItem)) {
                    val freshHistory = WatchHistoryStore(context).toMediaDescriptions()
                    activeQueue = freshHistory.ifEmpty { normalizedBrowseQueue }
                    currentIndex = activeQueue.indexOfFirst { it.mediaId == videoId }
                        .takeIf { it >= 0 } ?: index.coerceIn(0, activeQueue.lastIndex)
                } else {
                    activeQueue = normalizedBrowseQueue
                    currentIndex = index
                }
            } else {
                val single = MediaDescriptionCompat.Builder()
                    .setMediaId(videoId)
                    // Keep the ID internally, but immediately repair metadata before it becomes
                    // a long-lived UI title.
                    .setTitle(videoId)
                    .build()
                activeQueue = listOf(single)
                currentIndex = 0
            }
            val selected = activeQueue[currentIndex]

            val video = VideoSessionBridge.controller
            if (video != null && VideoSessionBridge.activeRole == VideoSessionBridge.Role.HEAD_UNIT) {
                exoPlayer.pause()
                video.playQueue(selected, activeQueue)
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, positionMs = video.getPositionMs())
                return
            }
            // A phone player is not a projection target. Starting an item from Android Auto must
            // still open/hand off to the head unit; RemoteCastBus will pause the phone on success.
            playViaProjectionOrAudio(selected, livePositionMs)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val normalizedQuery = query?.trim().orEmpty()
            if (normalizedQuery.isBlank()) return
            val generation = ++commandGeneration
            voiceSearchJob?.cancel()
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            voiceSearchJob = sessionScope.launch {
                val outcome = browseFacade.search(normalizedQuery)
                if (generation != commandGeneration) return@launch
                val results = (outcome as? BrowseOutcome.Ok)?.data.orEmpty()
                val first = results.firstOrNull { !it.mediaId.isNullOrBlank() }
                if (first == null) {
                    updatePlaybackState(
                        PlaybackStateCompat.STATE_ERROR,
                        AppText.get(R.string.search_no_results_for, normalizedQuery)
                    )
                    return@launch
                }
                browseQueue = results.map(::normalizeDescription)
                onPlayFromMediaId(first.mediaId, extras)
            }
        }

        override fun onPlay() {
            HeadUnitDiagnostics.event("MediaSession", "command=play owner=${VideoSessionBridge.activeRole}")
            val video = VideoSessionBridge.controller
            if (video != null && canResumeInPlace(video)) {
                video.play()
                return
            }
            if (video == null && exoPlayer.playbackState != Player.STATE_IDLE) {
                exoPlayer.play()
                return
            }
            // Nothing loaded that play() could start (session adopted after an AA disconnect
            // whose audio source failed, or a fresh process): resume the current/persisted item
            // as native audio at its last position instead of silently ignoring Play.
            resumeAsAudio()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            HeadUnitDiagnostics.event("MediaSession", "command=custom action=$action owner=${VideoSessionBridge.activeRole}")
        }

        override fun onPause() {
            HeadUnitDiagnostics.event("MediaSession", "command=pause owner=${VideoSessionBridge.activeRole}")
            val video = VideoSessionBridge.controller
            if (video != null) {
                video.pause()
                saveLastSession()
                return
            }
            exoPlayer.pause()
            saveNativeHistoryProgress()
        }

        override fun onStop() {
            val owner = VideoSessionBridge.activeRole
            HeadUnitDiagnostics.event("MediaSession", "command=stop owner=$owner")
            // The stock Android Auto media controller may emit a stale STOP immediately after
            // handing playback to the projected video activity. That command belongs to the old
            // native-audio controller and must never tear down the newly established HU session.
            if (!MediaSessionCommandPolicy.allowStop(owner)) {
                HeadUnitDiagnostics.event("MediaSession", "command=stop ignored owner=HEAD_UNIT")
                return
            }
            // An explicit stop ends a native/phone-backed session; do not offer it again on the
            // next connection.
            lastSessionStore.clear()
            RemoteCastBus.cancelPending()
            commandGeneration++
            voiceSearchJob?.cancel()
            audioLoadJob?.cancel()
            audioRequestId++
            val video = VideoSessionBridge.controller
            if (video != null) {
                video.stopPlayback()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, positionMs = video.getPositionMs())
                return
            }
            saveNativeHistoryProgress()
            CarPlaybackState.clear(CarPlaybackState.Owner.NATIVE_AUDIO)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            activeQueue = emptyList()
            currentIndex = -1
            NowPlayingBus.clear(owner = nativeController)
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        }

        override fun onSkipToNext() {
            val video = VideoSessionBridge.controller
            if (video != null) {
                video.skipToNext()
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, positionMs = video.getPositionMs())
                return
            }
            skipToNext()
        }

        override fun onSkipToPrevious() {
            val video = VideoSessionBridge.controller
            if (video != null) {
                video.skipToPrevious()
                updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, positionMs = video.getPositionMs())
                return
            }
            skipToPrevious()
        }

        override fun onSeekTo(pos: Long) {
            val video = VideoSessionBridge.controller
            if (video != null) {
                video.seekTo(pos)
                return
            }
            exoPlayer.seekTo(pos)
        }
    }

    companion object {
        private const val HISTORY_SAVE_INTERVAL_MS = 30_000L
        private const val MEDIA_SESSION_ART_PX = 384
        /** A seek or stall moves the real position away from the extrapolated one by more. */
        private const val POSITION_RESYNC_TOLERANCE_MS = 1_500L
    }
}
