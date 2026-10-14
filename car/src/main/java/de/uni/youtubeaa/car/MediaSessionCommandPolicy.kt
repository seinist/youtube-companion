package de.uni.youtubeaa.car

/**
 * Ownership-aware filtering for commands emitted by the stock Android Auto media controller.
 * A STOP that arrives after playback has moved to the projected head-unit player is stale: the
 * projected UI owns its own stop/back controls and must not be torn down by the retired audio UI.
 */
internal object MediaSessionCommandPolicy {
    fun allowStop(role: VideoSessionBridge.Role?): Boolean =
        role != VideoSessionBridge.Role.HEAD_UNIT
}
