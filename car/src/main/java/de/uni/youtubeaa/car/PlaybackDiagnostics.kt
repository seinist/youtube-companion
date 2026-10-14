package de.uni.youtubeaa.car

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlin.math.abs

/**
 * Event-driven diagnostics plus a coarse clock sampler: no per-frame logging, and the sampler
 * only writes a line when something is actually off.
 *
 * The audit left the reported head-unit stalls and the "audio runs fast, then slow" reports
 * without a proven cause because no runtime log ever recorded them. These are the signals that
 * distinguish the candidates: a speed change (live speed control), a starving audio sink
 * (underrun), a sink error, or the player clock drifting away from the wall clock.
 */
@OptIn(markerClass = [UnstableApi::class])
internal object PlaybackDiagnostics {

    private const val SAMPLE_INTERVAL_MS = 5_000L
    /** Below this the sample is noise: position granularity alone is tens of milliseconds. */
    private const val DRIFT_REPORT_RATIO = 0.05

    data class VideoExpectation(
        val streamId: String,
        val itag: Int,
        val source: String,
        val codecHint: String?,
        val widthHint: Int,
        val heightHint: Int,
        val fpsHint: Int
    )

    fun attach(
        player: ExoPlayer,
        tag: String,
        context: Context? = null,
        onVideoDecoderStarted: ((HardwareVideoCodecs.DecoderIdentity, Format?) -> Unit)? = null,
        videoExpectationProvider: (() -> VideoExpectation?)? = null,
        onDroppedVideoFrames: ((Int, Long) -> Unit)? = null
    ) {
        fun state() = "state=${player.playbackState} wantsPlay=${player.playWhenReady} " +
            "playing=${player.isPlaying} suppression=${player.playbackSuppressionReason} " +
            "positionMs=${player.currentPosition} bufferedMs=${player.totalBufferedDuration} " +
            "owner=${VideoSessionBridge.activeRole}"

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val cause = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user-or-app-command"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio-focus-loss"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio-route-disconnected"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote-command"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end-of-item"
                    else -> "reason-$reason"
                }
                HeadUnitDiagnostics.event(tag, "playWhenReady=$playWhenReady cause=$cause ${state()}")
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                HeadUnitDiagnostics.event(tag, "suppression-changed ${state()}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                HeadUnitDiagnostics.event(tag, "state-changed ${state()}")
            }
        })
        player.addAnalyticsListener(
            ClockWatchdog(
                player,
                tag,
                context?.applicationContext,
                onVideoDecoderStarted,
                videoExpectationProvider,
                onDroppedVideoFrames
            )
        )
    }

    /**
     * Samples the player clock against the wall clock while playing. A healthy player advances
     * its position by the elapsed real time multiplied by the playback speed; a persistent
     * deviation is exactly what resampling, a stalling sink or an unexpected speed change look
     * like from the outside.
     */
    private class ClockWatchdog(
        private val player: ExoPlayer,
        private val tag: String,
        private val context: Context?,
        private val onVideoDecoderStarted: ((HardwareVideoCodecs.DecoderIdentity, Format?) -> Unit)?,
        private val videoExpectationProvider: (() -> VideoExpectation?)?,
        private val onDroppedVideoFrames: ((Int, Long) -> Unit)?
    ) : AnalyticsListener {

        private val handler = Handler(Looper.getMainLooper())
        private var lastPositionMs = 0L
        private var lastElapsedMs = 0L
        private var sampling = false
        private var lastVideoFormat: Format? = null

        private val sample = object : Runnable {
            override fun run() {
                if (!sampling) return
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val nowPositionMs = player.currentPosition
                val speed = player.playbackParameters.speed
                if (player.isPlaying && lastElapsedMs != 0L) {
                    val wallMs = nowElapsedMs - lastElapsedMs
                    val expectedMs = wallMs * speed
                    val actualMs = nowPositionMs - lastPositionMs
                    // Seeks and item changes move the position by an unrelated amount; only a
                    // plausible step says anything about the clock. Zero is plausible: a clock
                    // that stands still while the player claims to play is the stall this
                    // watchdog exists to catch.
                    if (expectedMs > 0 && actualMs >= 0 && actualMs < wallMs * 4) {
                        val ratio = abs(actualMs - expectedMs) / expectedMs
                        if (ratio > DRIFT_REPORT_RATIO) {
                            val kind = if (actualMs == 0L) "clock-stalled" else "clock-drift"
                            HeadUnitDiagnostics.warning(tag,
                                "$kind expectedMs=${expectedMs.toLong()} actualMs=$actualMs " +
                                    "deviation=${(ratio * 100).toInt()}% speed=$speed " +
                                    "live=${player.isCurrentMediaItemLive} " +
                                    "bufferedMs=${player.totalBufferedDuration}")
                        }
                    }
                }
                lastElapsedMs = nowElapsedMs
                lastPositionMs = nowPositionMs
                handler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }

        private fun startSampling() {
            if (sampling) return
            sampling = true
            lastElapsedMs = 0L
            handler.postDelayed(sample, SAMPLE_INTERVAL_MS)
        }

        private fun stopSampling() {
            sampling = false
            handler.removeCallbacks(sample)
        }

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            if (isPlaying) startSampling() else stopSampling()
        }

        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // A seek or item change invalidates the running comparison.
            lastElapsedMs = 0L
        }

        override fun onPlaybackParametersChanged(
            eventTime: AnalyticsListener.EventTime,
            playbackParameters: PlaybackParameters
        ) {
            HeadUnitDiagnostics.event(tag,
                "speed-changed speed=${playbackParameters.speed} pitch=${playbackParameters.pitch} " +
                    "live=${player.isCurrentMediaItemLive}")
            lastElapsedMs = 0L
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long
        ) {
            HeadUnitDiagnostics.warning(tag,
                "audio-underrun bufferMs=$bufferSizeMs lastFeedMs=$elapsedSinceLastFeedMs")
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            HeadUnitDiagnostics.error(tag, "audio-sink-error", audioSinkError)
        }

        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long
        ) {
            HeadUnitDiagnostics.event(tag, "audio-output-started")
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            HeadUnitDiagnostics.warning(
                tag,
                "dropped-video-frames count=$droppedFrames elapsedMs=$elapsedMs thermal=${thermalStatus()} " +
                    "bufferedMs=${player.totalBufferedDuration}"
            )
            onDroppedVideoFrames?.invoke(droppedFrames, elapsedMs)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            val identity = HardwareVideoCodecs.classifyDecoder(decoderName)
            HeadUnitDiagnostics.event(
                tag,
                "audio-decoder name=${identity.name} hardware=${identity.hardwareAccelerated} " +
                    "software=${identity.softwareOnly} vendor=${identity.vendor} alias=${identity.alias} " +
                    "thermal=${thermalStatus()}"
            )
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            HeadUnitDiagnostics.event(
                tag,
                "audio-format mime=${format.sampleMimeType} codecs=${format.codecs} " +
                    "bitrate=${format.bitrate} sampleRate=${format.sampleRate} channels=${format.channelCount}"
            )
        }

        // Which decoder actually runs is the first question for stutter and CPU load. Resolve
        // the platform MediaCodecInfo instead of relying on codec-name prefixes on Android 10+.
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            val identity = HardwareVideoCodecs.classifyDecoder(decoderName)
            val format = lastVideoFormat
            val family = format?.let { videoCodecFamily(it) } ?: VideoStreamChoice.Codec.OTHER
            val maxInstances = if (family != VideoStreamChoice.Codec.OTHER) {
                HardwareVideoCodecs.maxSupportedInstances(family)
            } else {
                -1
            }
            HeadUnitDiagnostics.event(
                tag,
                "video-decoder name=${identity.name} hardware=${identity.hardwareAccelerated} " +
                    "software=${identity.softwareOnly} vendor=${identity.vendor} alias=${identity.alias} " +
                    "HW_CONTRACT=${if (identity.hardwareAccelerated) "PASS" else "SOFTWARE_FALLBACK"} " +
                    "codecFamily=$family maxSupportedInstances=$maxInstances thermal=${thermalStatus()}"
            )
            onVideoDecoderStarted?.invoke(identity, lastVideoFormat)
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            lastVideoFormat = format
            val expected = videoExpectationProvider?.invoke()
            val codecMismatch = expected?.codecHint?.takeIf { it.isNotBlank() }?.let { hint ->
                val runtime = format.codecs.orEmpty()
                runtime.isNotBlank() && !hint.equals(runtime, ignoreCase = true)
            } ?: false
            HeadUnitDiagnostics.event(
                tag,
                "video-format ${format.width}x${format.height} fps=${format.frameRate} " +
                    "codecs=${format.codecs} mime=${format.sampleMimeType}" +
                    (expected?.let {
                        " resolverStream=${it.streamId} itag=${it.itag} source=${it.source} " +
                            "resolverCodec=${it.codecHint} resolverSize=${it.widthHint}x${it.heightHint} " +
                            "resolverFps=${it.fpsHint} codecMismatch=$codecMismatch"
                    } ?: "")
            )
            if (codecMismatch) {
                HeadUnitDiagnostics.warning(
                    "HardwareDecode",
                    "resolver-runtime-codec-mismatch stream=${expected?.streamId} itag=${expected?.itag} " +
                        "resolver=${expected?.codecHint} runtime=${format.codecs} " +
                        "runtimeSize=${format.width}x${format.height} runtimeFps=${format.frameRate}"
                )
            }
        }

        private fun videoCodecFamily(format: Format): VideoStreamChoice.Codec {
            val fromCodec = VideoStreamChoice.familyOf(format.codecs)
            if (fromCodec != VideoStreamChoice.Codec.OTHER) return fromCodec
            return when (format.sampleMimeType?.lowercase()) {
                "video/avc" -> VideoStreamChoice.Codec.AVC
                "video/x-vnd.on2.vp9" -> VideoStreamChoice.Codec.VP9
                "video/av01" -> VideoStreamChoice.Codec.AV1
                else -> VideoStreamChoice.Codec.OTHER
            }
        }


        private fun thermalStatus(): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
            return runCatching {
                context?.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1
            }.getOrDefault(-1)
        }

        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
            stopSampling()
        }
    }
}
