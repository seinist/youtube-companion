package de.uni.youtubeaa.car

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/** Hardware video decoder capabilities, including concrete size/frame-rate support. */
internal object HardwareVideoCodecs {

    data class Capability(val supported: Boolean, val decoderName: String? = null)

    data class DecoderIdentity(
        val name: String,
        val hardwareAccelerated: Boolean,
        val softwareOnly: Boolean,
        val vendor: Boolean,
        val alias: Boolean
    )

    private val mimes = mapOf(
        VideoStreamChoice.Codec.AVC to "video/avc",
        VideoStreamChoice.Codec.VP9 to "video/x-vnd.on2.vp9",
        VideoStreamChoice.Codec.AV1 to "video/av01"
    )

    private val hardwareDecoders: Map<VideoStreamChoice.Codec, List<MediaCodecInfo>> by lazy {
        val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrDefault(emptyArray())
            .filter { !it.isEncoder && isHardware(it) }
        mimes.mapValues { (_, mime) ->
            decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        }.also { found ->
            HeadUnitDiagnostics.event(
                "HardwareVideoCodecs",
                "hardware decoders=" + found.entries.joinToString { (codec, infos) -> "$codec:${infos.joinToString { it.name }}" }
            )
        }
    }

    private val platformDecoderByName: Map<String, MediaCodecInfo> by lazy {
        runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos }
            .getOrDefault(emptyArray())
            .filter { !it.isEncoder }
            .associateBy { it.name.lowercase() }
    }

    private data class Query(
        val family: VideoStreamChoice.Codec,
        val codecString: String,
        val width: Int,
        val height: Int,
        val fps: Int
    )
    private val capabilityCache = ConcurrentHashMap<Query, Capability>()

    fun decodes(family: VideoStreamChoice.Codec): Boolean =
        family != VideoStreamChoice.Codec.OTHER && hardwareDecoders[family].orEmpty().isNotEmpty()

    /** Hardware-backed MIME preference used by Media3 manifests. Unsupported families are omitted. */
    fun preferredMimeTypes(): Array<String> {
        // Most efficient first, as in VideoStreamChoice: same picture for fewer bytes.
        val preferred = listOf(
            VideoStreamChoice.Codec.AV1,
            VideoStreamChoice.Codec.VP9,
            VideoStreamChoice.Codec.AVC
        ).mapNotNull { family -> mimes[family]?.takeIf { decodes(family) } }
        // Keep playback possible on unusual/emulated devices; the renderer still enforces
        // hardware whenever a concrete format has a matching HW decoder.
        return (preferred.ifEmpty { mimes.values.toList() }).toTypedArray()
    }

    fun mimeFor(family: VideoStreamChoice.Codec): String? = mimes[family]


    fun maxSupportedInstances(family: VideoStreamChoice.Codec): Int =
        hardwareDecoders[family].orEmpty().mapNotNull { info ->
            val mime = mimes[family] ?: return@mapNotNull null
            runCatching { info.getCapabilitiesForType(mime).maxSupportedInstances }.getOrNull()
        }.maxOrNull() ?: -1

    /** Exact runtime decoder classification. Name heuristics are only a pre-Android-10 fallback. */
    fun classifyDecoder(name: String): DecoderIdentity {
        val info = platformDecoderByName[name.lowercase()]
        if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return DecoderIdentity(
                name = info.name,
                hardwareAccelerated = info.isHardwareAccelerated,
                softwareOnly = info.isSoftwareOnly,
                vendor = info.isVendor,
                alias = info.isAlias
            )
        }
        val lower = name.lowercase()
        val software = lower.startsWith("c2.android.") || lower.startsWith("omx.google.")
        return DecoderIdentity(
            name = name,
            hardwareAccelerated = !software,
            softwareOnly = software,
            vendor = !software,
            alias = false
        )
    }

    /** True only when a hardware codec reports support for this exact picture size/rate. */
    fun decodes(family: VideoStreamChoice.Codec, width: Int, height: Int, fps: Int): Boolean =
        capability(family, null, width, height, fps).supported

    fun decodes(
        family: VideoStreamChoice.Codec,
        codecString: String?,
        width: Int,
        height: Int,
        fps: Int
    ): Boolean = capability(family, codecString, width, height, fps).supported

    fun capability(
        family: VideoStreamChoice.Codec,
        codecString: String?,
        width: Int,
        height: Int,
        fps: Int
    ): Capability {
        if (family == VideoStreamChoice.Codec.OTHER) return Capability(false)
        val query = Query(
            family,
            codecString?.trim()?.lowercase().orEmpty(),
            width.coerceAtLeast(0),
            height.coerceAtLeast(0),
            fps.coerceAtLeast(0)
        )
        return capabilityCache.getOrPut(query) {
            val mime = mimes[family] ?: return@getOrPut Capability(false)
            val candidates = hardwareDecoders[family].orEmpty()
            if (width <= 0 || height <= 0) {
                return@getOrPut candidates.firstOrNull()?.let { Capability(true, it.name) } ?: Capability(false)
            }
            for (info in candidates) {
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val video = caps.videoCapabilities ?: continue
                if (!supportsProfile(caps, family, codecString)) continue
                val supported = supports(video, width, height, fps)
                if (supported) return@getOrPut Capability(true, info.name)
            }
            Capability(false)
        }
    }


    /**
     * RFC 6381 codec strings carry a profile. Reject a hardware codec that explicitly advertises
     * a different profile instead of treating all AVC/VP9/AV1 variants as equivalent. Unknown or
     * abbreviated strings remain permissive and are still validated by size/rate.
     */
    private fun supportsProfile(
        caps: MediaCodecInfo.CodecCapabilities,
        family: VideoStreamChoice.Codec,
        codecString: String?
    ): Boolean {
        val required = requiredProfile(family, codecString) ?: return true
        val advertised = caps.profileLevels
        if (advertised.isEmpty()) return true // Some vendor codecs omit profile metadata.
        return advertised.any { it.profile == required }
    }

    private fun requiredProfile(family: VideoStreamChoice.Codec, codecString: String?): Int? {
        val codec = codecString?.trim()?.lowercase().orEmpty()
        if (codec.isBlank()) return null
        return when (family) {
            VideoStreamChoice.Codec.AVC -> {
                val hex = codec.substringAfter('.', "").take(2)
                val profileIdc = hex.toIntOrNull(16) ?: return null
                when (profileIdc) {
                    66 -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    77 -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    88 -> MediaCodecInfo.CodecProfileLevel.AVCProfileExtended
                    100 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    110 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
                    122 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422
                    244 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444
                    else -> null
                }
            }
            VideoStreamChoice.Codec.VP9 -> {
                val profile = codec.split('.').getOrNull(1)?.toIntOrNull() ?: return null
                when (profile) {
                    0 -> MediaCodecInfo.CodecProfileLevel.VP9Profile0
                    1 -> MediaCodecInfo.CodecProfileLevel.VP9Profile1
                    2 -> MediaCodecInfo.CodecProfileLevel.VP9Profile2
                    3 -> MediaCodecInfo.CodecProfileLevel.VP9Profile3
                    else -> null
                }
            }
            VideoStreamChoice.Codec.AV1 -> {
                // Android's AV1 profile constants describe Main 4:2:0 by bit depth. YouTube's
                // ordinary av01.0.* streams use Main; leave unusual profiles permissive rather
                // than falsely rejecting a vendor decoder whose profile table is incomplete.
                val parts = codec.split('.')
                val profile = parts.getOrNull(1)?.toIntOrNull() ?: return null
                if (profile != 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
                val bitDepth = parts.getOrNull(3)?.toIntOrNull() ?: return null
                if (bitDepth <= 8) MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8
                else MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
            }
            VideoStreamChoice.Codec.OTHER -> null
        }
    }

    private fun supports(video: MediaCodecInfo.VideoCapabilities, width: Int, height: Int, fps: Int): Boolean =
        runCatching {
            if (!video.isSizeSupported(width, height)) return@runCatching false
            fps <= 0 || video.areSizeAndRateSupported(width, height, fps.toDouble())
        }.getOrDefault(false)

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isAlias
        } else {
            // API 28: the platform's software decoders are the OMX.google / c2.android ones.
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }
}
