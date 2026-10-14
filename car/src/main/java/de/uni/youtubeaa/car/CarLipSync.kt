package de.uni.youtubeaa.car

/** Per-head-unit-player timing. Positive values make audio later relative to the picture. */
class CarLipSync {
    // The UI writes, the playback thread reads. Never access preferences on the rendering path.
    @Volatile private var effectiveOffsetMs = 0

    fun update(audioOffsetMs: Int, videoMode: Boolean) {
        effectiveOffsetMs = if (videoMode) clamp(audioOffsetMs) else 0
    }

    /**
     * Advance/delay the picture relative to the unchanged audio clock. Do not alter media
     * timestamps or ExoPlayer's public position: seeks, history and handoffs keep their timeline.
     * Scale by speed so the chosen offset remains wall-clock milliseconds at 0.25x through 2x.
     */
    fun videoPositionUs(positionUs: Long, playbackSpeed: Float): Long {
        val offsetMs = effectiveOffsetMs
        if (offsetMs == 0) return positionUs
        return positionUs + (offsetMs.toDouble() * 1_000.0 * playbackSpeed).toLong()
    }

    companion object {
        const val MIN_MS = -1_000
        const val MAX_MS = 1_000
        const val STEP_MS = 25

        fun clamp(value: Int): Int = value.coerceIn(MIN_MS, MAX_MS)
    }
}
