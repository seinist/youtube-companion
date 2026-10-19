package de.uni.youtubeaa.car

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** Shared ExoPlayer setup: audio-only mode disables video tracks to save bandwidth. */
@OptIn(markerClass = [UnstableApi::class])
object PlayerFactory {

    data class Handle(
        val player: ExoPlayer,
        val trackSelector: DefaultTrackSelector
    )

    internal fun create(
        context: Context,
        carLipSync: CarLipSync? = null,
        diagnosticTag: String = "PhonePlayer",
        onVideoDecoderStarted: ((HardwareVideoCodecs.DecoderIdentity, Format?) -> Unit)? = null,
        videoExpectationProvider: (() -> PlaybackDiagnostics.VideoExpectation?)? = null,
        onDroppedVideoFrames: ((Int, Long) -> Unit)? = null
    ): Handle {
        val appContext = context.applicationContext
        val trackSelector = DefaultTrackSelector(appContext)
        // Thin startup cushion on phone and HU: ABR/progressive only need a short first rung
        // before STATE_READY. min/max still refill afterward to limit mid-play underruns.
        val loadControl = MeteredLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs */ MIN_BUFFER_MS,
                    /* maxBufferMs */ MAX_BUFFER_MS, // read-ahead is lost on skip; 30 s rides out dead spots
                    /* bufferForPlaybackMs */ 750,
                    /* bufferForPlaybackAfterRebufferMs */ 2_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
            appContext
        )
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Use the same hardware-first MediaCodec policy on the phone and on the projection.
        // Lip-sync timing is only active when [carLipSync] is supplied; audio renderers remain
        // Media3 defaults in both cases.
        val builder = ExoPlayer.Builder(appContext, CarRenderersFactory(appContext, carLipSync))
        // Media3 keeps a live stream near its edge by playing at 0.97x to 1.03x. Speeding up is
        // the audible "too fast" part and is disabled. Slowing down stays allowed: it is how a
        // live stream with a thin buffer avoids stalling, and pinning it to exactly 1.0x turned
        // those moments into stutter on a jittery mobile connection.
        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(LIVE_MIN_SPEED)
            .setFallbackMaxPlaybackSpeed(1f)
            .build()
        val player = builder
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            // Requires WAKE_LOCK in the merging app manifest.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // Matches YouTube's 10s skip; used by Player.seekBack()/seekForward() (rew/ffwd
            // buttons in the custom PlayerControlView layouts).
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        PlaybackDiagnostics.attach(
            player,
            if (carLipSync != null) "HeadUnitPlayer" else diagnosticTag,
            appContext,
            onVideoDecoderStarted,
            videoExpectationProvider,
            onDroppedVideoFrames
        )
        return Handle(player, trackSelector)
    }

    private const val MIN_BUFFER_MS = 8_000
    private const val MAX_BUFFER_MS = 30_000

    /**
     * Shortens the read-ahead on metered networks ([DataSaverPolicy.METERED_MAX_BUFFER_MS]).
     * Players are long-lived (the engine survives screens), so the limit is evaluated per call
     * instead of being baked into the DefaultLoadControl. Start/rebuffer thresholds stay as is.
     */
    private class MeteredLoadControl(
        private val delegate: DefaultLoadControl,
        private val appContext: Context
    ) : LoadControl by delegate {
        private val meteredMaxBufferUs =
            DataSaverPolicy.maxBufferMs(metered = true, defaultMaxBufferMs = MAX_BUFFER_MS) * 1_000L

        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
            val wanted = delegate.shouldContinueLoading(parameters)
            if (!wanted) return false
            // Never starve playback below the regular minimum buffer.
            if (parameters.bufferedDurationUs < MIN_BUFFER_MS * 1_000L) return true
            return !(parameters.bufferedDurationUs >= meteredMaxBufferUs && NetworkPolicy.isMeteredCached(appContext))
        }
    }

    /** Media3's default lower bound; only catch-up (speeding up) is disabled. */
    const val LIVE_MIN_SPEED = 0.97f
    const val CAR_MAX_VIDEO_FPS = 30

    /**
     * Enforce the head-unit decode budget at the TrackSelector as well as in direct-itag choice.
     * This is critical for HLS/DASH manifests where ExoPlayer, not StreamResolver, picks the track.
     *
     * The max dimension is square on purpose: it admits portrait 720x1280/1080x1920 while still
     * rejecting a larger long side. The 30 fps ceiling is strict (`exceed... = false`).
     */
    fun applyCarVideoConstraints(
        trackSelector: DefaultTrackSelector,
        quality: String,
        maxFps: Int = CAR_MAX_VIDEO_FPS,
        relaxFrameRate: Boolean = false
    ) {
        applyCarVideoConstraints(
            trackSelector,
            CarVideoPolicy.budget(
                quality = quality,
                projectionFps = maxFps,
                playbackSpeed = 1f,
                thermalStatus = -1,
                relaxFrameRate = relaxFrameRate
            )
        )
    }

    fun applyCarVideoConstraints(
        trackSelector: DefaultTrackSelector,
        budget: CarVideoPolicy.Budget
    ) {
        val preferredMimes = HardwareVideoCodecs.preferredMimeTypes()
        val builder = trackSelector.buildUponParameters()
            // Media3 exposes width/height limits, but not an orientation-independent pixel budget.
            // Use the long-side square here; the manifest HW override applies the exact
            // long/short/pixel budget once tracks are known.
            .setMaxVideoSize(budget.maxLongSide, budget.maxLongSide)
            .setMaxVideoFrameRate(budget.maxSourceFps)
            .setExceedVideoConstraintsIfNecessary(false)
            .setExceedRendererCapabilitiesIfNecessary(false)
            .setAllowVideoMixedDecoderSupportAdaptiveness(false)
            .setAllowVideoMixedMimeTypeAdaptiveness(false)
            .setPreferredVideoMimeTypes(*preferredMimes)
        trackSelector.setParameters(builder)
        HeadUnitDiagnostics.event(
            "PlayerFactory",
            "car-video-budget quality=${budget.quality} effectiveQuality=${budget.effectiveQuality} " +
                "maxLong=${budget.maxLongSide} maxShort=${budget.maxShortSide} maxPixels=${budget.maxPixels} " +
                "projectionFps=${budget.projectionFps} speed=${budget.playbackSpeed} " +
                "maxSourceFps=${budget.maxSourceFps} thermal=${budget.thermalStatus} " +
                "relaxFps=${budget.relaxFrameRate} mimeOrder=${preferredMimes.joinToString()} " +
                "exceedRenderer=false mixedDecoder=false"
        )
    }

    /** Drop an explicit manifest video-track override before loading a new source/item. */
    fun clearVideoTrackOverride(trackSelector: DefaultTrackSelector) {
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        )
    }

    /**
     * Phone ceiling from [DataSaverPolicy.phoneCeiling]: Auto stops at 720p30 (a phone screen
     * gains little above it, 1080p60 costs ~2.5x the data), and on metered networks the
     * mobile-data cap also limits explicit qualities. Applied to adaptive manifests; a fixed
     * progressive itag is chosen by the resolver with the same limits.
     */
    fun applyPhoneVideoCeiling(trackSelector: DefaultTrackSelector, ceiling: DataSaverPolicy.Ceiling) {
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setMaxVideoSize(ceiling.maxLongSide, ceiling.maxLongSide)
                .setMaxVideoFrameRate(ceiling.maxFps)
                .setExceedVideoConstraintsIfNecessary(true)
        )
        HeadUnitDiagnostics.event(
            "PlayerFactory",
            "phone-video-ceiling maxLong=${ceiling.maxLongSide} maxFps=${ceiling.maxFps}"
        )
    }

    internal fun maxCarVideoLongSide(quality: String): Int = CarVideoPolicy.qualityLongSide(quality)

    /**
     * Audio-only track selection. With a [SourceLayout.MUXED_MANIFEST] (HLS) source a disabled
     * video track does not stop the variant download: the chunk source keeps fetching muxed
     * segments at whatever rung it last used. There the video track stays enabled but pinned to
     * the lowest rung (force-lowest + 256x144); the picture is never shown.
     */
    fun setAudioOnly(
        trackSelector: DefaultTrackSelector,
        audioOnly: Boolean,
        layout: SourceLayout = SourceLayout.SEPARATE
    ) {
        val params = trackSelector.parameters
        val pinLowest = audioOnly && layout == SourceLayout.MUXED_MANIFEST
        val disableVideo = audioOnly && !pinLowest
        // Skip no-op updates — mid-playback parameter changes can cause audible glitches.
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == disableVideo &&
            params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) == audioOnly &&
            params.forceLowestBitrate == pinLowest
        ) return
        val builder = trackSelector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, audioOnly)
            .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, audioOnly)
        applyLowestPin(builder, pin = pinLowest, wasPinned = params.forceLowestBitrate)
        trackSelector.setParameters(builder)
    }

    /**
     * Temporarily stop video decode/render while keeping audio playback alive in the background.
     * Muxed HLS sources are pinned to the lowest rung instead (see [setAudioOnly]). Callers
     * re-apply their video constraints before resuming.
     */
    fun setVideoSuspended(
        trackSelector: DefaultTrackSelector,
        suspended: Boolean,
        layout: SourceLayout = SourceLayout.SEPARATE
    ) {
        val params = trackSelector.parameters
        val pinLowest = suspended && layout == SourceLayout.MUXED_MANIFEST
        val disableVideo = suspended && !pinLowest
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == disableVideo &&
            params.forceLowestBitrate == pinLowest
        ) return
        val builder = trackSelector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
        applyLowestPin(builder, pin = pinLowest, wasPinned = params.forceLowestBitrate)
        trackSelector.setParameters(builder)
    }

    private fun applyLowestPin(
        builder: DefaultTrackSelector.Parameters.Builder,
        pin: Boolean,
        wasPinned: Boolean
    ) {
        if (pin) {
            builder.setForceLowestBitrate(true)
                .setMaxVideoSize(AUDIO_ONLY_PIN_WIDTH, AUDIO_ONLY_PIN_HEIGHT)
                .setExceedVideoConstraintsIfNecessary(true)
        } else if (wasPinned) {
            // Leave no 144p ceiling behind; the video path re-applies its own budget.
            builder.setForceLowestBitrate(false)
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    private const val AUDIO_ONLY_PIN_WIDTH = 256
    private const val AUDIO_ONLY_PIN_HEIGHT = 144

    /**
     * Swap media and keep playback going when [playWhenReady] is true.
     * Call [setAudioOnly] before this when entering audio mode so tracks are correct on prepare.
     */
    fun replaceSource(
        player: ExoPlayer,
        source: MediaSource,
        resumePositionMs: Long,
        isLive: Boolean,
        playWhenReady: Boolean = true
    ) {
        val start = when {
            isLive -> C.TIME_UNSET
            resumePositionMs > 0L -> resumePositionMs
            else -> C.TIME_UNSET
        }
        // Keep playWhenReady through the switch so audio continues without an extra Play tap.
        player.playWhenReady = playWhenReady
        player.setMediaSource(source, start)
        player.prepare()
        if (playWhenReady) {
            player.play()
        }
    }
}
