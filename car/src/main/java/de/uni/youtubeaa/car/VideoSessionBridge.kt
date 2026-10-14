@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.support.v4.media.MediaDescriptionCompat

/**
 * Bridges MediaSession/phone controls to the currently authoritative video player.
 *
 * More than one player can exist briefly during a phone -> head-unit handoff. The head-unit
 * player must win while it is alive; when it goes away, control falls back to the phone player
 * instead of becoming null.
 */
object VideoSessionBridge {

    enum class Role(val priority: Int) {
        PHONE(1),
        NATIVE_AUDIO(2),
        HEAD_UNIT(3)
    }

    data class Snapshot(
        val queue: List<MediaDescriptionCompat>,
        val queueIndex: Int,
        val positionMs: Long,
        val playWhenReady: Boolean
    )

    interface Controller {
        fun play()
        fun pause()
        fun stopPlayback()
        fun skipToNext()
        fun skipToPrevious()
        fun seekTo(positionMs: Long)
        fun playMediaId(videoId: String)
        fun playQueue(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>)
        fun isPlaying(): Boolean
        fun getPositionMs(): Long
        fun getDurationMs(): Long
        fun snapshot(): Snapshot? = null
        /** Cancel pending loads as well as pausing, so an old resolve cannot restart output. */
        fun onOwnershipLost() { pause() }
    }

    private data class Entry(
        val controller: Controller,
        val role: Role,
        val order: Long
    )

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var nextOrder = 0L

    @Volatile
    private var activeController: Controller? = null

    @Volatile
    private var activeControllerRole: Role? = null

    val controller: Controller?
        get() = activeController

    val activeRole: Role?
        get() = activeControllerRole

    fun register(controller: Controller, role: Role = Role.PHONE) {
        val previous = activeController
        val previousRole = activeControllerRole
        synchronized(lock) {
            entries.removeAll { it.controller === controller }
            entries += Entry(controller, role, ++nextOrder)
            recomputeActiveLocked()
        }
        if (previous !== activeController && previousRole != activeControllerRole) {
            previous?.onOwnershipLost()
        }
    }

    /** Explicitly choosing an output replaces the previous output's controllers. */
    fun activate(controller: Controller, role: Role) {
        val previous = activeController
        val previousRole = activeControllerRole
        synchronized(lock) {
            entries.clear()
            entries += Entry(controller, role, ++nextOrder)
            recomputeActiveLocked()
        }
        if (previous !== controller && (previousRole != role || role != Role.PHONE)) previous?.onOwnershipLost()
    }

    fun isOwner(controller: Controller): Boolean = activeController === controller

    fun unregister(controller: Controller) {
        synchronized(lock) {
            entries.removeAll { it.controller === controller }
            recomputeActiveLocked()
        }
    }

    fun isVideoActive(): Boolean = activeController != null

    private fun recomputeActiveLocked() {
        val active = entries.maxWithOrNull(
            compareBy<Entry> { it.role.priority }.thenBy { it.order }
        )
        activeController = active?.controller
        activeControllerRole = active?.role
    }
}
