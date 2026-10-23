package de.uni.youtubeaa.car

import java.util.Locale

/**
 * How audio and video travel inside a prepared source. Only [AUDIO_ONLY] and [SEPARATE] stop
 * downloading video bytes when the video track is disabled; a muxed source keeps paying for the
 * picture even when only the sound is used.
 */
enum class SourceLayout {
    /** Pure audio URL (itag 140/251 …) or an HLS audio-rendition playlist. */
    AUDIO_ONLY,
    /** Separate progressive video + audio (MergingMediaSource), DASH, or HLS with audio renditions. */
    SEPARATE,
    /** Progressive itag carrying audio and video in one file (e.g. itag 18). */
    MUXED_PROGRESSIVE,
    /** HLS whose variants carry muxed audio+video (no separate audio rendition). */
    MUXED_MANIFEST,
    /**
     * HLS whose multivariant playlist has not been inspected yet. YouTube's HLS (VOD and live)
     * has separate audio renditions, but that is only known once Media3 has loaded the manifest
     * ([HlsLayout.classify]); until then it is handled as conservatively as [MUXED_MANIFEST].
     */
    HLS_PENDING;

    /** True when disabling the video track really stops video downloads. */
    val videoCanBeDroppedForFree: Boolean
        get() = this == AUDIO_ONLY || this == SEPARATE

    /** Audio-only needs the lowest video rung instead of a disabled video track. */
    val needsLowestPin: Boolean
        get() = this == MUXED_MANIFEST || this == HLS_PENDING
}

/** Pure HLS layout detection (unit-tested); fed from Media3's loaded multivariant playlist. */
object HlsLayout {
    /**
     * [audioRenditionUris] are the URIs of the playlist's `EXT-X-MEDIA TYPE=AUDIO` entries (null
     * for a rendition without URI, whose audio is muxed into the variants). Any rendition with its
     * own URI means the variants are video-only: disabling video stops the video download.
     */
    fun classify(audioRenditionUris: List<String?>): SourceLayout =
        if (audioRenditionUris.any { !it.isNullOrBlank() }) SourceLayout.SEPARATE else SourceLayout.MUXED_MANIFEST

    /** The layout to act on: a pending HLS source takes the detected value once known. */
    fun refine(declared: SourceLayout, detected: SourceLayout?): SourceLayout =
        if (declared == SourceLayout.HLS_PENDING && detected != null) detected else declared

    data class AudioRendition(val groupId: String, val uri: String)

    /** `EXT-X-MEDIA TYPE=AUDIO` entries with a URI, relative URIs resolved against [baseUrl]. */
    fun audioRenditions(masterPlaylist: String, baseUrl: String? = null): List<AudioRendition> =
        masterPlaylist.lineSequence()
            .filter { it.startsWith("#EXT-X-MEDIA:") && attribute(it, "TYPE") == "AUDIO" }
            .mapNotNull { line ->
                val uri = attribute(line, "URI")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AudioRendition(attribute(line, "GROUP-ID").orEmpty(), resolve(baseUrl, uri))
            }
            .toList()

    /**
     * Picks the audio rendition for audio-only live playback: the group used by the highest
     * bandwidth variant (YouTube: "234" = AAC-LC ~128 kbit/s); [preferLow] picks the group of
     * the lowest variant instead (YouTube: "233" = HE-AAC ~48 kbit/s).
     */
    fun pickAudioRendition(masterPlaylist: String, baseUrl: String? = null, preferLow: Boolean = false): AudioRendition? {
        val renditions = audioRenditions(masterPlaylist, baseUrl)
        if (renditions.isEmpty()) return null
        val groupsByBandwidth = masterPlaylist.lineSequence()
            .filter { it.startsWith("#EXT-X-STREAM-INF:") }
            .mapNotNull { line ->
                val bandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull() ?: return@mapNotNull null
                val group = attribute(line, "AUDIO") ?: return@mapNotNull null
                bandwidth to group
            }
            .sortedBy { it.first }
            .map { it.second }
            .toList()
        val wanted = if (preferLow) groupsByBandwidth.firstOrNull() else groupsByBandwidth.lastOrNull()
        return renditions.firstOrNull { it.groupId == wanted } ?: renditions.last()
    }

    private fun attribute(line: String, name: String): String? {
        val match = Regex("""(?:^|[:,])$name=("([^"]*)"|[^,]*)""").find(line) ?: return null
        return match.groupValues[2].ifEmpty { match.groupValues[1] }.trim('"')
    }

    private fun resolve(baseUrl: String?, uri: String): String {
        if (baseUrl == null || uri.startsWith("http://") || uri.startsWith("https://")) return uri
        return runCatching { java.net.URI(baseUrl).resolve(uri).toString() }.getOrDefault(uri)
    }
}

/**
 * Background live-stream auto-stop (pure, unit-tested). A live stream never ends on its own, so
 * one left playing in the background (player closed via Home / screen-off) is stopped after the
 * configured time without user interaction.
 */
object LiveAutoStopPolicy {
    const val OFF = "off"
    const val DEFAULT_MINUTES = "60"
    val VALUES = listOf("30", "60", "120", OFF)

    fun normalize(value: String?): String = value?.takeIf { it in VALUES } ?: DEFAULT_MINUTES

    /** Timeout in ms, null = never stop. */
    fun timeoutMs(value: String?): Long? =
        normalize(value).toLongOrNull()?.takeIf { it > 0L }?.let { it * 60_000L }

    /** Whether a running timer should exist at all for the given playback situation. */
    fun applies(isLive: Boolean, inBackground: Boolean, playing: Boolean, value: String?): Boolean =
        isLive && inBackground && playing && timeoutMs(value) != null

    /** True when the live stream has run [elapsedSinceInteractionMs] past the limit. */
    fun shouldStop(isLive: Boolean, inBackground: Boolean, playing: Boolean, elapsedSinceInteractionMs: Long, value: String?): Boolean {
        if (!applies(isLive, inBackground, playing, value)) return false
        return elapsedSinceInteractionMs >= (timeoutMs(value) ?: return false)
    }
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

    data class Ceiling(
        val maxLongSide: Int,
        val maxFps: Int,
        val maxVideoBitrate: Int
    ) {
        val limited: Boolean
            get() = maxLongSide != Int.MAX_VALUE || maxFps != Int.MAX_VALUE || maxVideoBitrate != Int.MAX_VALUE

        companion object {
            val NONE = Ceiling(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    /**
     * Conservative upper bounds for adaptive tracks. Resolution alone is not a data budget:
     * YouTube can expose AVC/HLS variants at several times the bitrate of AV1/VP9 at the same
     * height. These remain soft on the phone (Media3 may exceed them if no playable track exists)
     * and are paired with progressive selection by actual advertised bitrate.
     */
    fun maxVideoBitrateForHeight(height: Int): Int = when {
        height <= 144 -> 180_000
        height <= 240 -> 300_000
        height <= 360 -> 500_000
        height <= 480 -> 800_000
        height <= 720 -> 1_500_000
        height <= 1080 -> 2_500_000
        height <= 1440 -> 5_000_000
        height <= 2160 -> 10_000_000
        else -> Int.MAX_VALUE
    }

    fun maxVideoBitrateForQuality(quality: String): Int =
        maxVideoBitrateForHeight(CarVideoPolicy.qualityShortSide(quality))

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
        val requestedHeight = if (auto) PHONE_AUTO_MAX_HEIGHT else CarVideoPolicy.qualityShortSide(requested)
        val height = minOf(requestedHeight, cap ?: Int.MAX_VALUE)
        return Ceiling(
            maxLongSide = CarVideoPolicy.qualityLongSide(height.toString()),
            maxFps = if (auto) PHONE_AUTO_MAX_FPS else Int.MAX_VALUE,
            maxVideoBitrate = maxVideoBitrateForHeight(height)
        )
    }

    fun maxBufferMs(metered: Boolean, defaultMaxBufferMs: Int): Int =
        if (metered) minOf(defaultMaxBufferMs, METERED_MAX_BUFFER_MS) else defaultMaxBufferMs
}
