package de.uni.youtubeaa.car

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlin.math.ceil

/** Runtime Media3-format hardware checks for adaptive HLS/DASH tracks. */
@OptIn(markerClass = [UnstableApi::class])
internal object Media3HardwareDecode {

    data class TrackSetChoice(
        val key: String,
        val group: Tracks.Group,
        val trackIndices: List<Int>,
        val formats: List<Format>,
        val decoderNames: List<String>
    ) {
        val highestFormat: Format
            get() = formats.maxByOrNull { format ->
                format.width.coerceAtLeast(0).toLong() * format.height.coerceAtLeast(0).toLong()
            } ?: formats.first()
    }

    /**
     * Uses Media3's own format support implementation, including profile/level, size/rate,
     * performance points and device workarounds. This is authoritative compared with extractor
     * metadata, which is only a hint before the container is parsed.
     */
    fun capableHardwareDecoders(context: Context, format: Format): List<MediaCodecInfo> {
        val mime = format.sampleMimeType ?: return emptyList()
        return runCatching {
            MediaCodecSelector.DEFAULT
                .getDecoderInfos(mime, /* requiresSecureDecoder= */ false, /* requiresTunnelingDecoder= */ false)
                .filter { info ->
                    info.hardwareAccelerated && info.isFormatSupported(context, format)
                }
        }.getOrDefault(emptyList())
    }

    /**
     * Finds a complete adaptive set of tracks that are all HW-decodable under the same AA budget.
     * Keeping multiple indices in one [androidx.media3.common.TrackSelectionOverride] preserves
     * HLS/DASH ABR instead of pinning one fixed quality after hardware recovery.
     */
    fun bestManifestHardwareTrackSet(
        context: Context,
        tracks: Tracks,
        budget: CarVideoPolicy.Budget,
        excludedKeys: Set<String> = emptySet(),
        excludedFormatKeys: Set<String> = emptySet()
    ): TrackSetChoice? {
        val mimeOrder = HardwareVideoCodecs.preferredMimeTypes().withIndex()
            .associate { it.value to it.index }
        val sets = mutableListOf<TrackSetChoice>()

        tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
            val byMime = linkedMapOf<String, MutableList<Pair<Int, Format>>>()
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                if (formatKey(format) in excludedFormatKeys) continue
                val mime = format.sampleMimeType ?: continue
                if (!budget.fits(format.width, format.height)) continue
                if (format.bitrate > 0 && format.bitrate > budget.maxVideoBitrate) continue
                val sourceFps = format.frameRate.takeIf { it > 0f }?.let { ceil(it.toDouble()).toInt() } ?: 0
                if (!budget.relaxFrameRate && sourceFps > 0 && sourceFps > budget.maxSourceFps) continue
                val family = familyForMime(mime)
                val effectiveDecodeFps = budget.effectiveDecodeFps(sourceFps)
                if (family != VideoStreamChoice.Codec.OTHER &&
                    !HardwareVideoCodecs.decodes(
                        family,
                        format.width,
                        format.height,
                        effectiveDecodeFps
                    )
                ) continue
                if (capableHardwareDecoders(context, format).isEmpty()) continue
                byMime.getOrPut(mime) { mutableListOf() } += index to format
            }

            byMime.forEach { (mime, entries) ->
                if (entries.isEmpty()) return@forEach
                val indices = entries.map { it.first }
                val formats = entries.map { it.second }
                val key = trackSetKey(group, mime, indices, formats)
                if (key in excludedKeys) return@forEach
                val decoderNames = entries.flatMap { (_, format) ->
                    capableHardwareDecoders(context, format).map { it.name }
                }.distinct()
                sets += TrackSetChoice(key, group, indices, formats, decoderNames)
            }
        }
        if (sets.isEmpty()) return null

        fun bestPixels(choice: TrackSetChoice): Long = choice.formats.maxOf { format ->
            format.width.coerceAtLeast(0).toLong() * format.height.coerceAtLeast(0).toLong()
        }
        fun mimeCost(choice: TrackSetChoice): Int =
            mimeOrder[choice.highestFormat.sampleMimeType] ?: Int.MAX_VALUE
        fun maxBitrate(choice: TrackSetChoice): Int = choice.formats.maxOf { it.bitrate.coerceAtLeast(0) }

        return sets.minWithOrNull(
            compareBy<TrackSetChoice> { -bestPixels(it) }
                .thenBy { mimeCost(it) }
                // More adaptive rungs are preferred at the same picture/codec quality.
                .thenBy { -it.trackIndices.size }
                .thenBy { maxBitrate(it) }
        )
    }

    private fun familyForMime(mime: String): VideoStreamChoice.Codec = when (mime.lowercase()) {
        "video/avc" -> VideoStreamChoice.Codec.AVC
        "video/x-vnd.on2.vp9" -> VideoStreamChoice.Codec.VP9
        "video/av01" -> VideoStreamChoice.Codec.AV1
        else -> VideoStreamChoice.Codec.OTHER
    }

    private fun trackSetKey(
        group: Tracks.Group,
        mime: String,
        indices: List<Int>,
        formats: List<Format>
    ): String = buildString {
        append(group.mediaTrackGroup.id)
        append('|').append(mime)
        append('|').append(indices.joinToString(","))
        append('|').append(formats.joinToString(";") { format -> formatKey(format) })
    }

    fun formatKey(format: Format): String = buildString {
        append(format.id ?: "-")
        append('|').append(format.sampleMimeType ?: "-")
        append('|').append(format.codecs ?: "-")
        append('|').append(format.width).append('x').append(format.height)
        append('@').append(format.frameRate)
        append('|').append(format.bitrate)
    }
}
