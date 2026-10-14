package de.uni.youtubeaa.car

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer

/**
 * Hardware-first video renderer for both phone and projection playback.
 * The head-unit path (a [CarLipSync] is supplied) also keeps the audio sink alive when the
 * virtual-display surface stalls the video renderer.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class CarRenderersFactory(context: Context, private val lipSync: CarLipSync? = null) :
    DefaultRenderersFactory(context) {

    private val headUnit = lipSync != null

    init {
        // The renderer returns suitable hardware codecs first and only then a format-supported
        // software codec as an emergency init fallback. Runtime recovery will leave software
        // again if another hardware-capable YouTube stream exists.
        setEnableDecoderFallback(true)
        if (headUnit) {
            // Codec buffer queues then run off the playback thread. A virtual-display surface
            // that is slow to accept frames must not block the thread that feeds AudioTrack —
            // that was the 500 ms underrun with 20 s of media already buffered.
            forceEnableMediaCodecAsynchronousQueueing()
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // The HU path uses the normal MediaCodec renderer without effects, tunneling or prewarming.
        // Keep Media3's codec adapter/selector and normal decoder fallback behavior.
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(getCodecAdapterFactory())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        if (headUnit) {
            // Drop decoder input that is already this late instead of decoding a catch-up
            // backlog. The 2026-10-09 logs dropped 50 frames in about a second while the
            // media buffer was full; that backlog stalled the playback clock.
            builder.experimentalSetLateThresholdToDropDecoderInputUs(HEAD_UNIT_LATE_INPUT_US)
        }
        out.add(LipSyncVideoRenderer(builder, lipSync, context.applicationContext))
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean
    ): AudioSink? {
        // Buffer sizes: phone gets modest increase (1.5s min) to fix Opus software underruns;
        // head unit gets larger (3s target) for virtual-display stall absorption.
        // ExoPlayer still starts from load-control threshold (750ms); extra capacity only
        // absorbs feed gaps from slow software decoders or surface stalls.
        val minUs: Int
        val targetUs: Int
        val maxUs: Int
        val factor: Int
        if (headUnit) {
            minUs = HEAD_UNIT_MIN_PCM_BUFFER_US
            targetUs = HEAD_UNIT_TARGET_PCM_BUFFER_US
            maxUs = HEAD_UNIT_MAX_PCM_BUFFER_US
            factor = HEAD_UNIT_PCM_BUFFER_FACTOR
        } else {
            minUs = PHONE_MIN_PCM_BUFFER_US
            targetUs = PHONE_TARGET_PCM_BUFFER_US
            maxUs = PHONE_MAX_PCM_BUFFER_US
            factor = PHONE_PCM_BUFFER_FACTOR
        }
        val buffer = DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(minUs)
            .setTargetPcmBufferDurationUs(targetUs)
            .setMaxPcmBufferDurationUs(maxUs)
            .setPcmBufferMultiplicationFactor(factor)
            .build()
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            .setAudioTrackBufferSizeProvider(buffer)
            .build()
    }

    private companion object {
        const val HEAD_UNIT_LATE_INPUT_US = 100_000L
        // Head unit: 2–4s (virtual-display can stall video thread)
        const val HEAD_UNIT_MIN_PCM_BUFFER_US = 2_000_000
        const val HEAD_UNIT_TARGET_PCM_BUFFER_US = 3_000_000
        const val HEAD_UNIT_MAX_PCM_BUFFER_US = 4_000_000
        const val HEAD_UNIT_PCM_BUFFER_FACTOR = 4
        // Phone: 1.5–3.5s (fixes Opus software decode underruns, low latency for AAC)
        const val PHONE_MIN_PCM_BUFFER_US = 1_500_000
        const val PHONE_TARGET_PCM_BUFFER_US = 2_500_000
        const val PHONE_MAX_PCM_BUFFER_US = 3_500_000
        const val PHONE_PCM_BUFFER_FACTOR = 3
    }
}

@OptIn(markerClass = [UnstableApi::class])
private class LipSyncVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val lipSync: CarLipSync?,
    private val context: Context
) :
    MediaCodecVideoRenderer(builder) {

    private val diagnosedFormats = HashSet<String>()

    /**
     * MediaCodecSelector only knows the MIME type. This override runs later, when Media3 has the
     * real container Format (profile/level/size/fps), so this is the authoritative HW contract:
     * every suitable hardware decoder is tried before a software fallback can initialize.
     */
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        val all = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        val support = all.associateWith { info ->
            runCatching { info.isFormatSupported(context, format) }.getOrDefault(false)
        }
        val hardware = all.filter { info -> info.hardwareAccelerated && support[info] == true }
        val selected = if (hardware.isNotEmpty()) {
            // A vendor codec can advertise support yet still fail at MediaCodec.configure(). Keep
            // software only *behind* every suitable hardware decoder so decoderFallback can keep
            // playback alive. If that emergency fallback starts, the fragment immediately tries
            // another hardware-capable YouTube itag/manifest track.
            hardware + all.filter { info ->
                !info.hardwareAccelerated && support[info] == true
            }
        } else {
            all
        }

        val key = "${format.sampleMimeType}|${format.codecs}|${format.width}x${format.height}|${format.frameRate}"
        if (diagnosedFormats.add(key)) {
            val candidates = all.joinToString(separator = ",") { info ->
                val supported = support[info] == true
                "${info.name}{hw=${info.hardwareAccelerated},supported=$supported}"
            }
            HeadUnitDiagnostics.event(
                "HardwareDecode",
                "runtime-format mime=${format.sampleMimeType} codecs=${format.codecs} " +
                    "size=${format.width}x${format.height} fps=${format.frameRate} candidates=[$candidates] " +
                    "selectedOrder=${selected.joinToString { it.name }} " +
                    "contract=${if (hardware.isNotEmpty()) "HARDWARE_FIRST" else "SOFTWARE_ALLOWED_NO_HW"}"
            )
        }
        return selected
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean = super.processOutputBuffer(
        lipSync?.videoPositionUs(positionUs, playbackSpeed) ?: positionUs,
        elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags, sampleCount,
        bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format
    )

    // Match the scheduler to the same offset; otherwise it could sleep past an early frame.
    override fun getDurationToProgressUs(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        isOnBufferAvailableListenerRegistered: Boolean
    ): Long = super.getDurationToProgressUs(
        lipSync?.videoPositionUs(positionUs, playbackSpeed) ?: positionUs,
        elapsedRealtimeUs, isOnBufferAvailableListenerRegistered
    )
}
