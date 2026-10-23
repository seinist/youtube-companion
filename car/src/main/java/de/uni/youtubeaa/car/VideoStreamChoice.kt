package de.uni.youtubeaa.car

/**
 * Picks the video stream to play. Pure (no Android) so the policy is unit-tested.
 *
 * Height alone used to decide, and YouTube offers each resolution as H.264, VP9 and AV1. When
 * the first match was a codec the phone cannot decode in hardware, ExoPlayer fell back to a
 * software decoder: head-unit logs then showed sustained dropped frames and audio underruns with
 * a full media buffer — CPU/render starvation rather than a network shortage. Hardware decode,
 * frame rate and projected-display cost therefore participate in the choice.
 */
internal object VideoStreamChoice {

    enum class Codec { AVC, VP9, AV1, OTHER }

    /** Codec family of an itag codec string such as "avc1.640028", "vp9", "vp09.00.40.08", "av01.0.08M.08". */
    fun familyOf(codec: String?): Codec {
        val c = codec?.trim()?.lowercase().orEmpty()
        return when {
            c.startsWith("avc") -> Codec.AVC
            c.startsWith("vp9") || c.startsWith("vp09") -> Codec.VP9
            c.startsWith("av01") || c.startsWith("av1") -> Codec.AV1
            else -> Codec.OTHER
        }
    }

    /**
     * @param quality "auto", "low"/"360", "high", or an explicit maximum height.
     * @param hardwareDecodes whether a hardware decoder supports this concrete codec/size/rate.
     * @param maxFps projection frame-rate ceiling. When supplied, streams at or below it are a
     *   hard preference: a >maxFps stream is considered only if no safe stream exists. In that
     *   fallback case the smallest picture is used to minimise decode work.
     */
    fun <T> pick(
        streams: List<T>,
        quality: String,
        widthOf: (T) -> Int,
        heightOf: (T) -> Int,
        codecOf: (T) -> String?,
        fpsOf: (T) -> Int,
        hardwareDecodes: (Codec, String?, Int, Int, Int) -> Boolean,
        hardwareLikelyDecodes: (Codec, Int, Int, Int) -> Boolean = { _, _, _, _ -> false },
        maxFps: Int? = null,
        /** Lower is preferred, but only after HW/FPS/quality/codec have been decided. */
        sourceCostOf: (T) -> Int = { 0 },
        /** Estimated total wire bitrate; lower wins once picture/HW/FPS safety is equal. */
        bitrateOf: (T) -> Int = { 0 },
        /** Exact orientation-independent picture budget (long side, short side and pixel count). */
        withinBudgetOf: ((T) -> Boolean)? = null
    ): T? {
        if (streams.isEmpty()) return null
        val target = quality.toIntOrNull()?.takeIf { it > 0 }
        val ceiling = when {
            target != null -> target
            quality == "high" -> Int.MAX_VALUE
            quality == "low" || quality == "360" -> 360
            // Auto and unset labels may pick up to 1080p (same as a manual 1080 setting).
            // CarVideoPolicy still lowers the HU ceiling under thermal / drop pressure.
            else -> 1080
        }
        val inRange = if (withinBudgetOf != null) {
            streams.filter(withinBudgetOf)
                .ifEmpty {
                    listOfNotNull(streams.filter { widthOf(it) > 0 && heightOf(it) > 0 }
                        .minByOrNull { widthOf(it).toLong() * heightOf(it).toLong() })
                }
                .ifEmpty { streams }
        } else {
            streams.filter { heightOf(it) in 1..ceiling }
                .ifEmpty { listOfNotNull(streams.filter { heightOf(it) > 0 }.minByOrNull(heightOf)) }
                .ifEmpty { streams }
        }

        // Hardware comes before the projection FPS preference. Otherwise a 30 fps software-only
        // AV1 track could beat a 60 fps AVC/VP9 track that the phone can decode in hardware.
        // Exact extractor profile metadata is only a hint (the parsed container may differ), so
        // hardware family + size/rate capability is the primary gate. The extractor's exact
        // profile string is only a later tie-breaker.
        fun hardwareClass(stream: T): Int {
            val family = familyOf(codecOf(stream))
            val width = widthOf(stream)
            val height = heightOf(stream)
            val fps = fpsOf(stream)
            return when {
                family != Codec.OTHER && hardwareLikelyDecodes(family, width, height, fps) -> 0
                family != Codec.OTHER -> 2
                else -> 3
            }
        }
        val bestHardwareClass = inRange.minOf { hardwareClass(it) }
        val hardwarePreferred = inRange.filter { hardwareClass(it) == bestHardwareClass }

        val fpsSafe = if (maxFps == null) hardwarePreferred else hardwarePreferred.filter {
            val fps = fpsOf(it)
            fps <= 0 || fps <= maxFps
        }
        val forcedHighFpsFallback = maxFps != null && fpsSafe.isEmpty()
        val candidates = fpsSafe.ifEmpty { hardwarePreferred }

        fun exactCapabilityCost(stream: T): Int {
            val family = familyOf(codecOf(stream))
            val width = widthOf(stream)
            val height = heightOf(stream)
            val fps = fpsOf(stream)
            return when {
                family != Codec.OTHER && hardwareDecodes(family, codecOf(stream), width, height, fps) -> 0
                else -> 1
            }
        }
        fun codecPreference(stream: T): Int = when (familyOf(codecOf(stream))) {
            // At equal resolution and hardware decode, the newer codec needs far fewer bytes for
            // the same picture (AV1 < VP9 < AVC), which is what the official app prefers too.
            // Hardware support is already decided above, so this never picks software AV1.
            Codec.AV1 -> 0
            Codec.VP9 -> 1
            Codec.AVC -> 2
            Codec.OTHER -> 3
        }

        val lowest = quality == "low" || quality == "360" || forcedHighFpsFallback
        return candidates.minWithOrNull(
            compareBy<T> { if (lowest) heightOf(it) else -heightOf(it) }
                .thenBy { exactCapabilityCost(it) }
                .thenBy { fpsOf(it).takeIf { fps -> fps > 0 } ?: Int.MAX_VALUE }
                // Advertised bitrate is the direct data-cost signal. Prefer it before codec and
                // muxing convenience: a nominally efficient codec can still be encoded at a
                // higher rate, and a muxed stream can cost more than separate video + audio.
                .thenBy { bitrateOf(it).takeIf { bitrate -> bitrate > 0 } ?: Int.MAX_VALUE }
                .thenBy { codecPreference(it) }
                .thenBy { sourceCostOf(it) }
        )
    }
}
