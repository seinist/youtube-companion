package de.uni.youtubeaa.car

/**
 * Process-wide marker for playback currently owned by Android Auto.
 *
 * RemoteCastBus.isAvailable() only tells us whether the custom projection activity is currently
 * alive. Native AA audio playback can be active without a projection receiver, so phone-side
 * routing must not use RemoteCastBus availability as the sole signal.
 */
object CarPlaybackState {

    enum class Owner {
        NATIVE_AUDIO,
        PROJECTION
    }

    @Volatile
    private var owner: Owner? = null

    @Volatile
    private var mediaId: String? = null

    fun markActive(owner: Owner, mediaId: String? = null) {
        this.owner = owner
        this.mediaId = mediaId
        HeadUnitDiagnostics.event(
            "CarPlaybackState",
            "active owner=$owner mediaId=${mediaId.orEmpty()}"
        )
    }

    fun clear(owner: Owner) {
        if (this.owner != owner) return
        HeadUnitDiagnostics.event(
            "CarPlaybackState",
            "clear owner=$owner mediaId=${mediaId.orEmpty()}"
        )
        this.owner = null
        this.mediaId = null
    }

    fun clearAll() {
        val previousOwner = owner
        if (previousOwner != null) {
            HeadUnitDiagnostics.event(
                "CarPlaybackState",
                "clearAll owner=$previousOwner mediaId=${mediaId.orEmpty()}"
            )
        }
        owner = null
        mediaId = null
    }

    fun isActive(): Boolean = owner != null

    fun activeOwner(): Owner? = owner
}
