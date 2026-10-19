package de.uni.youtubeaa.car

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure AA video-budget policy shared by progressive YouTube itag selection and Media3 manifests.
 *
 * "Auto" is intentionally thermal/projection aware. Explicit user qualities keep their requested
 * picture budget, but still respect the real projection frame rate and playback-speed decode load.
 */
object CarVideoPolicy {

    data class Budget(
        val quality: String,
        val effectiveQuality: String,
        val maxLongSide: Int,
        val maxShortSide: Int,
        val maxPixels: Long,
        val projectionFps: Int,
        val playbackSpeed: Float,
        val maxSourceFps: Int,
        val thermalStatus: Int,
        val relaxFrameRate: Boolean
    ) {
        fun fits(width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return true
            val longSide = max(width, height)
            val shortSide = min(width, height)
            val pixels = width.toLong() * height.toLong()
            return longSide <= maxLongSide && shortSide <= maxShortSide && pixels <= maxPixels
        }

        /** Real decoder throughput required when playback runs faster than real time. */
        fun effectiveDecodeFps(sourceFps: Int): Int {
            if (sourceFps <= 0) return 0
            return ceil(sourceFps * playbackSpeed.coerceAtLeast(1f)).toInt().coerceAtLeast(sourceFps)
        }
    }

    fun budget(
        quality: String,
        projectionFps: Int,
        playbackSpeed: Float,
        thermalStatus: Int,
        relaxFrameRate: Boolean = false,
        metered: Boolean = false,
        mobileDataQuality: String = DataSaverPolicy.DEFAULT_MOBILE_QUALITY
    ): Budget {
        val effectiveQuality = effectiveQuality(quality, thermalStatus, metered, mobileDataQuality)
        val shortSide = qualityShortSide(effectiveQuality)
        val longSide = qualityLongSide(effectiveQuality)
        val cappedProjectionFps = projectionFps.takeIf { it in 1..240 }
            ?.coerceAtMost(PlayerFactory.CAR_MAX_VIDEO_FPS)
            ?: PlayerFactory.CAR_MAX_VIDEO_FPS
        val speed = playbackSpeed.coerceIn(0.25f, 4f)
        val sourceFps = if (relaxFrameRate) {
            Int.MAX_VALUE
        } else {
            val divisor = if (speed > 1f) speed else 1f
            floor(cappedProjectionFps / divisor).toInt().coerceAtLeast(1)
        }
        return Budget(
            quality = quality,
            effectiveQuality = effectiveQuality,
            maxLongSide = longSide,
            maxShortSide = shortSide,
            maxPixels = longSide.toLong() * shortSide.toLong(),
            projectionFps = cappedProjectionFps,
            playbackSpeed = speed,
            maxSourceFps = sourceFps,
            thermalStatus = thermalStatus,
            relaxFrameRate = relaxFrameRate
        )
    }

    /**
     * Thermal / drop-pressure adaptation is limited to Auto; explicit qualities stay user-owned.
     * Healthy Auto stops at 720p: the projection re-encodes the picture at the head unit's
     * resolution (typically 800x480 or 1280x720), so more pixels only cost data and heat. Under
     * load it steps down, but not below 360 so adaptive manifests keep several ABR rungs.
     *
     * On a metered connection the user's mobile-data cap ([DataSaverPolicy], default 480p)
     * applies to every setting, explicit qualities included.
     */
    fun effectiveQuality(
        quality: String,
        thermalStatus: Int,
        metered: Boolean = false,
        mobileDataQuality: String = DataSaverPolicy.DEFAULT_MOBILE_QUALITY
    ): String {
        val base = if (!quality.equals("auto", ignoreCase = true)) quality else when {
            thermalStatus >= 4 -> "360" // CRITICAL / EMERGENCY / extreme drop pressure
            thermalStatus >= 3 -> "480" // SEVERE
            else -> "720"
        }
        return DataSaverPolicy.capQuality(base, metered, mobileDataQuality)
    }

    fun qualityShortSide(quality: String): Int = when (quality.lowercase()) {
        "2160" -> 2160
        "1440" -> 1440
        "1080", "high", "auto" -> 1080
        "720" -> 720
        "480" -> 480
        "360", "low" -> 360
        "240" -> 240
        "144" -> 144
        else -> quality.toIntOrNull()?.takeIf { it > 0 } ?: 1080
    }

    fun qualityLongSide(quality: String): Int {
        val short = qualityShortSide(quality)
        return when (short) {
            2160 -> 3840
            1440 -> 2560
            1080 -> 1920
            720 -> 1280
            480 -> 854
            360 -> 640
            240 -> 426
            144 -> 256
            else -> (short * 16 / 9).coerceAtLeast(short)
        }
    }
}
