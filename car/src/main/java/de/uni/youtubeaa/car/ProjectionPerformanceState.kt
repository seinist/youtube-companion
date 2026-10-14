package de.uni.youtubeaa.car

import java.util.concurrent.CopyOnWriteArraySet

/** Process-wide snapshot of the real Android Auto projection transport. */
internal object ProjectionPerformanceState {

    data class Snapshot(
        val hostMaxFrameRate: Int = 0,
        val currentFrameRate: Int = 0,
        val powerState: Int = Int.MIN_VALUE,
        val hasWindowFocus: Boolean = true,
        val secondaryWindowFlag: Boolean = true
    ) {
        val effectiveFrameRate: Int
            get() = listOf(hostMaxFrameRate, currentFrameRate)
                .filter { it in 1..240 }
                .minOrNull()
                ?.coerceAtMost(PlayerFactory.CAR_MAX_VIDEO_FPS)
                ?: PlayerFactory.CAR_MAX_VIDEO_FPS
    }

    fun interface Listener {
        fun onProjectionPerformanceChanged(snapshot: Snapshot)
    }

    @Volatile
    private var snapshot = Snapshot()
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun snapshot(): Snapshot = snapshot

    fun register(listener: Listener) {
        listeners += listener
        listener.onProjectionPerformanceChanged(snapshot)
    }

    fun unregister(listener: Listener) {
        listeners -= listener
    }

    fun updateHost(maxFrameRate: Int, powerState: Int, hasWindowFocus: Boolean) {
        publish(
            snapshot.copy(
                hostMaxFrameRate = maxFrameRate.saneFps(),
                currentFrameRate = snapshot.currentFrameRate.takeIf { it > 0 } ?: maxFrameRate.saneFps(),
                powerState = powerState,
                hasWindowFocus = hasWindowFocus
            )
        )
    }

    fun updateFrameRate(frameRate: Int) = publish(snapshot.copy(currentFrameRate = frameRate.saneFps()))

    fun updatePowerState(powerState: Int) = publish(snapshot.copy(powerState = powerState))

    fun updateWindowFocus(hasFocus: Boolean, secondaryFlag: Boolean) = publish(
        snapshot.copy(hasWindowFocus = hasFocus, secondaryWindowFlag = secondaryFlag)
    )

    fun clear() = publish(Snapshot())

    private fun publish(next: Snapshot) {
        if (next == snapshot) return
        snapshot = next
        HeadUnitDiagnostics.event(
            "ProjectionPerformance",
            "hostMaxFps=${next.hostMaxFrameRate} currentFps=${next.currentFrameRate} " +
                "effectiveFps=${next.effectiveFrameRate} powerState=${next.powerState} " +
                "windowFocus=${next.hasWindowFocus} secondaryWindowFlag=${next.secondaryWindowFlag}"
        )
        listeners.forEach { listener -> runCatching { listener.onProjectionPerformanceChanged(next) } }
    }

    private fun Int.saneFps(): Int = takeIf { it in 1..240 } ?: 0
}
