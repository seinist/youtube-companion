package de.uni.youtubeaa.car

import java.util.Locale

/**
 * How audio and video travel inside a prepared source. Only [AUDIO_ONLY] and [SEPARATE] stop
 * downloading video bytes when the video track is disabled; a muxed source keeps paying for the
 * picture even when only the sound is used.
 */
enum class SourceLayout {
    /** Pure audio URL (itag 140/251 …). */
    AUDIO_ONLY,
    /** Separate progressive video + audio (MergingMediaSource) or a DASH manifest. */
    SEPARATE,
    /** Progressive itag carrying audio and video in one file (e.g. itag 18). */
    MUXED_PROGRESSIVE,
    /** HLS manifest: variants may carry muxed audio+video (always for YouTube live). */
    MUXED_MANIFEST;

    /** True when disabling the video track really stops video downloads. */
    val videoCanBeDroppedForFree: Boolean
        get() = this == AUDIO_ONLY || this == SEPARATE
}

/**
 * Pure data-saver policy (no Android dependencies, unit-tested).
 *
 * Rough YouTube data rates: audio 128 kbit/s ≈ 1 MB/min (~60 MB/h), 480p ≈ 0.5 GB/h,
 * 720p30 ≈ 0.7–1.1 GB/h, 1080p30/60 ≈ 1.1–2.7 GB/h. A phone screen gains little above 720p30,
 * so phone "Auto" stops there; on metered networks the user's mobile-data cap applies to every
 * quality setting, including explicit ones.
 */
object DataSaverPolicy {
    const val UNLIMITED = "unlimited"
    const val DEFAULT_MOBILE_QUALITY = "480"
    val MOBILE_QUALITY_VALUES = listOf("360", "480", "720", UNLIMITED)

    const val PHONE_AUTO_MAX_HEIGHT = 720
    const val PHONE_AUTO_MAX_FPS = 30

    /** Read-ahead limit on metered networks (skips and seeks throw the read-ahead away). */
    const val METERED_MAX_BUFFER_MS = 15_000

    data class Ceiling(val maxLongSide: Int, val maxFps: Int) {
        val limited: Boolean get() = maxLongSide != Int.MAX_VALUE || maxFps != Int.MAX_VALUE

        companion object {
            val NONE = Ceiling(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    fun normalizeMobileQuality(value: String?): String =
        value?.takeIf { it in MOBILE_QUALITY_VALUES } ?: DEFAULT_MOBILE_QUALITY

    /** Height ceiling on metered networks; null = no limit. */
    fun meteredCapHeight(mobileQuality: String): Int? {
        val normalized = normalizeMobileQuality(mobileQuality)
        if (normalized == UNLIMITED) return null
        return normalized.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun isAuto(quality: String) = quality.lowercase(Locale.ROOT) == "auto"

    /**
     * Applies the mobile-data cap to any quality label (explicit or the result of an Auto
     * policy). Unmetered or "no limit" returns [quality] unchanged; "auto" becomes the cap.
     */
    fun capQuality(quality: String, metered: Boolean, mobileQuality: String): String {
        if (!metered) return quality
        val cap = meteredCapHeight(mobileQuality) ?: return quality
        if (isAuto(quality)) return cap.toString()
        return if (CarVideoPolicy.qualityShortSide(quality) > cap) cap.toString() else quality
    }

    /** Quality label handed to the stream resolver for the phone player. */
    fun phoneResolveQuality(requested: String, metered: Boolean, mobileQuality: String): String {
        val base = if (isAuto(requested)) PHONE_AUTO_MAX_HEIGHT.toString() else requested
        return capQuality(base, metered, mobileQuality)
    }

    /** Source frame-rate ceiling for the phone resolver; null = no limit (explicit qualities). */
    fun phoneResolveMaxFps(requested: String): Int? =
        if (isAuto(requested)) PHONE_AUTO_MAX_FPS else null

    /** TrackSelector ceiling for the phone player (applies to adaptive manifests). */
    fun phoneCeiling(requested: String, metered: Boolean, mobileQuality: String): Ceiling {
        val auto = isAuto(requested)
        val cap = if (metered) meteredCapHeight(mobileQuality) else null
        val height = when {
            auto -> minOf(PHONE_AUTO_MAX_HEIGHT, cap ?: Int.MAX_VALUE)
            cap != null -> minOf(CarVideoPolicy.qualityShortSide(requested), cap)
            else -> null
        }
        return Ceiling(
            maxLongSide = height?.let { CarVideoPolicy.qualityLongSide(it.toString()) } ?: Int.MAX_VALUE,
            maxFps = if (auto) PHONE_AUTO_MAX_FPS else Int.MAX_VALUE
        )
    }

    fun maxBufferMs(metered: Boolean, defaultMaxBufferMs: Int): Int =
        if (metered) minOf(defaultMaxBufferMs, METERED_MAX_BUFFER_MS) else defaultMaxBufferMs
}
