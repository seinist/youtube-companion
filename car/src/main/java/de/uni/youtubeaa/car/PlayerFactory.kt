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
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 8_000,
                /* maxBufferMs */ 30_000, // read-ahead is lost on skip; 30 s rides out dead spots
                /* bufferForPlaybackMs */ 750,
                /* bufferForPlaybackAfterRebufferMs */ 2_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
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
     * Phone Auto + adaptive: keep the previous progressive Auto ceiling (≤1080) so ABR does not
     * jump straight to 1440/2160 on a generous bandwidth estimate.
     */
    fun applyPhoneAdaptiveAutoCeiling(trackSelector: DefaultTrackSelector, metered: Boolean = false) {
        val longSide = if (metered) PHONE_METERED_MAX_LONG_SIDE else PHONE_AUTO_MAX_LONG_SIDE
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setMaxVideoSize(longSide, longSide)
                .setExceedVideoConstraintsIfNecessary(true)
        )
    }

    /** Restore unconstrained sizes before binding a fixed progressive itag. */
    fun clearPhoneAdaptiveCeiling(trackSelector: DefaultTrackSelector) {
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        )
    }

    private const val PHONE_AUTO_MAX_LONG_SIDE = 1920
    /** Data saver on mobile data: 480p, the official app's default there. */
    private const val PHONE_METERED_MAX_LONG_SIDE = 854

    internal fun maxCarVideoLongSide(quality: String): Int = CarVideoPolicy.qualityLongSide(quality)

    fun setAudioOnly(trackSelector: DefaultTrackSelector, audioOnly: Boolean) {
        val videoDisabled = trackSelector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
        // Skip no-op updates — mid-playback parameter changes can cause audible glitches.
        if (videoDisabled == audioOnly) return
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, audioOnly)
                .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, audioOnly)
        )
    }

    /** Temporarily stop video decode/render while keeping audio playback alive in the background. */
    fun setVideoSuspended(trackSelector: DefaultTrackSelector, suspended: Boolean) {
        val videoDisabled = trackSelector.parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
        if (videoDisabled == suspended) return
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, suspended)
        )
    }

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
