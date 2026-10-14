package de.uni.youtubeaa.car

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Lightweight AA audio-decoder capability map used only as a stream-selection preference. */
internal object HardwareAudioCodecs {
    private val hardwareDecoderMimes: Set<String> by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrDefault(emptyArray())
            .asSequence()
            .filter { !it.isEncoder && isHardware(it) }
            .flatMap { it.supportedTypes.asSequence() }
            .map { it.lowercase() }
            .toSet()
            .also { mimes ->
                HeadUnitDiagnostics.event(
                    "HardwareAudioCodecs",
                    "hardware decoder mimes=${mimes.filter { it.startsWith("audio/") }.sorted().joinToString()}"
                )
            }
    }

    fun decodes(mime: String?): Boolean =
        !mime.isNullOrBlank() && mime.lowercase() in hardwareDecoderMimes

    /** Decoder MIME inferred from YouTube/NewPipe codec/container metadata. */
    fun decoderMime(codec: String?, containerMime: String?): String? {
        val c = codec?.trim()?.lowercase().orEmpty()
        return when {
            c.startsWith("mp4a") -> "audio/mp4a-latm"
            c.startsWith("opus") -> "audio/opus"
            c.startsWith("vorbis") -> "audio/vorbis"
            containerMime.equals("audio/mp4", ignoreCase = true) -> "audio/mp4a-latm"
            containerMime.equals("audio/webm", ignoreCase = true) && c.isNotBlank() -> when {
                "opus" in c -> "audio/opus"
                "vorbis" in c -> "audio/vorbis"
                else -> null
            }
            containerMime?.startsWith("audio/", ignoreCase = true) == true -> containerMime
            else -> null
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isAlias
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }
}
