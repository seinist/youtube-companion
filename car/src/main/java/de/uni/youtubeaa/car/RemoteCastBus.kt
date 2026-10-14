@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaDescriptionCompat

/** Main-thread command channel. Acceptance is distinct from a stream reaching STATE_READY. */
object RemoteCastBus {
    private const val PROJECTION_READY_TIMEOUT_MS = 20_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextRequestId = 0L

    /** Why a receiver did or did not take a cast. Only PARK_BLOCKED is the parked-video gate. */
    enum class Acceptance { ACCEPTED, PARK_BLOCKED, UNAVAILABLE }

    interface Receiver {
        fun castVideo(
            video: MediaDescriptionCompat,
            queue: List<MediaDescriptionCompat>,
            startPositionMs: Long,
            requestId: Long,
            onStarted: () -> Unit
        ): Acceptance
        fun cancelCast(requestId: Long)
        /** True while the projection is the visible car screen, not just alive behind another. */
        fun isInForeground(): Boolean
        /** Ask Android Auto, through the running projection itself, to show it. */
        fun bringToFront(): Boolean
    }

    sealed class CastResult {
        data object Queued : CastResult()
        /** Refused by the parked-video gate. */
        data object ParkBlocked : CastResult()
        /** The receiver exists but cannot take the item right now (e.g. being destroyed). */
        data object Rejected : CastResult()
    }

    private class Pending(
        val id: Long,
        val video: MediaDescriptionCompat,
        val queue: List<MediaDescriptionCompat>,
        val positionMs: Long,
        val onStarted: (() -> Unit)?,
        val onFailed: (() -> Unit)?
    ) {
        var deliveredTo: Receiver? = null
        var timeout: Runnable? = null
    }

    @Volatile var receiver: Receiver? = null
        private set
    @Volatile private var pending: Pending? = null

    fun register(receiver: Receiver) {
        this.receiver = receiver
        retryPendingIfAny()
    }

    fun unregister(receiver: Receiver) {
        if (this.receiver !== receiver) return
        this.receiver = null
        pending?.takeIf { it.deliveredTo === receiver }?.let(::fail)
    }

    fun isAvailable() = receiver != null
    fun hasPendingCast() = pending != null

    fun cancelPending() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelPending() }
            return
        }
        val old = pending ?: return
        pending = null
        old.timeout?.let(mainHandler::removeCallbacks)
        old.deliveredTo?.cancelCast(old.id)
    }

    fun cast(
        video: MediaDescriptionCompat,
        queue: List<MediaDescriptionCompat> = listOf(video),
        context: Context? = null,
        startPositionMs: Long = 0L,
        onProjectionFailed: (() -> Unit)? = null,
        onStarted: (() -> Unit)? = null
    ): CastResult {
        // Not a parked-video question: an item without an id simply cannot be played.
        if (video.mediaId.isNullOrBlank()) return CastResult.Rejected
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cast(video, queue, context, startPositionMs, onProjectionFailed, onStarted) }
            return CastResult.Queued
        }
        val existing = pending
        if (existing != null && existing.video.mediaId == video.mediaId) {
            // Repeated AA custom-action taps must not replace the pending request. Replacing it
            // used to cancel the receiver/ProjectionLauncher handshake that was about to succeed.
            HeadUnitDiagnostics.event(
                "RemoteCastBus",
                "duplicate cast ignored requestId=${existing.id} videoId=${video.mediaId}"
            )
            return CastResult.Queued
        }
        cancelPending()
        val request = Pending(++nextRequestId, video, queue.ifEmpty { listOf(video) },
            startPositionMs.coerceAtLeast(0L), onStarted, onProjectionFailed)
        pending = request
        request.timeout = Runnable { fail(request) }.also {
            mainHandler.postDelayed(it, PROJECTION_READY_TIMEOUT_MS)
        }
        val target = receiver
        if (target != null) {
            when (deliver(target, request)) {
                Acceptance.ACCEPTED ->
                    // The projection can be alive but sitting behind the native AA media screen.
                    // Swapping its fragment alone stays invisible there, so ask Android Auto to
                    // bring it forward — only then: restarting an already visible projection opens
                    // a needless Car connection and, if AA recreates the activity, its teardown
                    // hands the session it just accepted back to the phone.
                    // The running projection asks through its own CarActivityHost first: a
                    // separate Car connection from the media service is refused at once by
                    // current Android Auto ("car disconnected before projection start").
                    if (!target.isInForeground() && !target.bringToFront() && context != null) {
                        ProjectionLauncher.requestOpen(context, bringToFront = true)
                    }
                Acceptance.PARK_BLOCKED -> {
                    pending = null
                    request.timeout?.let(mainHandler::removeCallbacks)
                    return CastResult.ParkBlocked
                }
                Acceptance.UNAVAILABLE -> {
                    pending = null
                    request.timeout?.let(mainHandler::removeCallbacks)
                    return CastResult.Rejected
                }
            }
        } else if (context != null) {
            ProjectionLauncher.requestOpen(context) { success ->
                if (!success) fail(request) else retryPendingIfAny()
            }
        } else {
            fail(request)
        }
        return CastResult.Queued
    }

    fun retryPendingIfAny() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { retryPendingIfAny() }
            return
        }
        val target = receiver ?: return
        val request = pending ?: return
        // onCreate/onResume/park updates can all retry before a transaction has run.
        if (request.deliveredTo === target) return
        deliver(target, request)
    }

    fun projectionFailed(requestId: Long) {
        pending?.takeIf { it.id == requestId }?.let(::fail)
    }

    private fun deliver(target: Receiver, request: Pending): Acceptance {
        request.deliveredTo = target
        val acceptance = target.castVideo(request.video, request.queue, request.positionMs, request.id) {
            if (pending !== request) return@castVideo
            pending = null
            request.timeout?.let(mainHandler::removeCallbacks)
            request.onStarted?.invoke()
        }
        if (acceptance != Acceptance.ACCEPTED) request.deliveredTo = null
        return acceptance
    }

    private fun fail(request: Pending) {
        if (pending !== request) return
        pending = null
        request.timeout?.let(mainHandler::removeCallbacks)
        request.deliveredTo?.cancelCast(request.id)
        request.onFailed?.invoke()
    }
}
