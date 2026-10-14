package de.uni.youtubeaa.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackPresentation { VIDEO, AUDIO }

data class NowPlaying(
    val videoId: String,
    val title: String,
    val channelTitle: String?,
    val thumbnailUrl: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val isBuffering: Boolean = false,
    // true, wenn VideoSessionBridge.controller aktiv ist (Play/Pause/Skip wirken sich aus).
    val controllable: Boolean,
    /** Shared UI/session presentation. Phone, native AA and projection render this same state. */
    val presentation: PlaybackPresentation = PlaybackPresentation.VIDEO,
    val queueIndex: Int = 0,
    val queueSize: Int = 1,
    /** Remains true during temporary focus suppression; false after an explicit Pause. */
    val playWhenReady: Boolean = isPlaying
)

/**
 * Prozessweiter Zustand "was läuft gerade auf dem Head-Unit-Display" – von der projizierten
 * AA-Video-UI (ExoPlayerVideoFragment / WebViewFallbackFragment) geschrieben, von der
 * Smartphone-App (MainActivity) beobachtet. Läuft im selben Android-Prozess wie die
 * AA-Projektion (Standardverhalten bei Android-Auto-Apps), daher genügt ein In-Memory-StateFlow
 * anstelle von IPC.
 */
object NowPlayingBus {

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    fun update(
        videoId: String,
        title: String,
        channelTitle: String?,
        thumbnailUrl: String?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        controllable: Boolean,
        isBuffering: Boolean = false,
        presentation: PlaybackPresentation = _state.value?.presentation ?: PlaybackPresentation.VIDEO,
        queueIndex: Int = _state.value?.queueIndex ?: 0,
        queueSize: Int = _state.value?.queueSize ?: 1,
        owner: VideoSessionBridge.Controller? = null
    ) {
        if (owner != null && !VideoSessionBridge.isOwner(owner)) return
        _state.value = NowPlaying(
            videoId = videoId,
            title = title,
            channelTitle = channelTitle,
            thumbnailUrl = thumbnailUrl,
            isPlaying = isPlaying,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            isBuffering = isBuffering,
            controllable = controllable,
            presentation = presentation,
            queueIndex = queueIndex.coerceAtLeast(0),
            queueSize = queueSize.coerceAtLeast(1),
            playWhenReady = owner?.snapshot()?.playWhenReady ?: (isPlaying || isBuffering)
        )
    }

    fun updatePresentation(presentation: PlaybackPresentation, queueIndex: Int? = null, queueSize: Int? = null, owner: VideoSessionBridge.Controller? = null) {
        if (owner != null && !VideoSessionBridge.isOwner(owner)) return
        val current = _state.value ?: return
        _state.value = current.copy(
            presentation = presentation,
            queueIndex = queueIndex ?: current.queueIndex,
            queueSize = queueSize?.coerceAtLeast(1) ?: current.queueSize
        )
    }

    /** Aktualisiert nur Wiedergabestatus/Position (und optional Dauer) eines laufenden Videos. */
    fun updatePlayback(
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long? = null,
        isBuffering: Boolean = false,
        owner: VideoSessionBridge.Controller? = null
    ) {
        if (owner != null && !VideoSessionBridge.isOwner(owner)) return
        val current = _state.value ?: return
        _state.value = current.copy(
            isPlaying = isPlaying,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs?.takeIf { it > 0 } ?: current.durationMs,
            isBuffering = isBuffering,
            playWhenReady = owner?.snapshot()?.playWhenReady ?: (isPlaying || isBuffering)
        )
    }

    fun clear(owner: VideoSessionBridge.Controller? = null) {
        if (owner != null && !VideoSessionBridge.isOwner(owner)) return
        _state.value = null
    }
}
