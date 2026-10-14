package de.uni.youtubeaa.car

/** Stable metadata only: position ticks must not repeatedly parcel the artwork to Android. */
internal data class SessionMetadataKey(
    val videoId: String,
    val title: String,
    val channelTitle: String?,
    val thumbnailUrl: String?,
    val durationMs: Long
) {
    companion object {
        fun from(state: NowPlaying) = SessionMetadataKey(
            state.videoId, state.title, state.channelTitle, state.thumbnailUrl, state.durationMs
        )
    }
}

/** Notification content has no position counter; only content/state/foreground changes matter. */
internal class NotificationUpdateGate {
    private data class Key(val state: Int, val metadataRevision: Long, val keepForeground: Boolean)
    private var previous: Key? = null

    fun accept(state: Int, metadataRevision: Long, keepForeground: Boolean): Boolean {
        val next = Key(state, metadataRevision, keepForeground)
        if (next == previous) return false
        previous = next
        return true
    }

    fun reset() { previous = null }
}
