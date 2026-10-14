package de.uni.youtubeaa.car

/**
 * Lightweight process-wide signal for expensive projected-video playback.
 *
 * Phone UI, AA projection, thumbnail decoding and metadata repair share one process. When a
 * visible head-unit video is decoding, background image/metadata work is deliberately throttled
 * so it cannot starve MediaCodec / rendering threads on thermally constrained phones.
 */
object CarPlaybackQos {
    @Volatile
    private var projectedVideoVisible = false

    fun setProjectedVideoVisible(visible: Boolean) {
        if (projectedVideoVisible == visible) return
        projectedVideoVisible = visible
        HeadUnitDiagnostics.event("CarPlaybackQos", "projectedVideoVisible=$visible")
    }

    fun isProjectedVideoVisible(): Boolean = projectedVideoVisible
}
