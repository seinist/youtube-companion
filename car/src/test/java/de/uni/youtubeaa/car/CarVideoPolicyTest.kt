package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Test

class CarVideoPolicyTest {

    @Test fun `explicit quality ignores thermal pressure`() {
        assertEquals("1080", CarVideoPolicy.effectiveQuality("1080", thermalStatus = 4))
        assertEquals("144", CarVideoPolicy.effectiveQuality("144", thermalStatus = 0))
    }

    @Test fun `auto stops at 720 because the projection re-encodes at head-unit size`() {
        assertEquals("720", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 0))
        assertEquals("720", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 2))
        assertEquals("480", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 3))
    }

    @Test fun `metered connection caps auto and explicit qualities at the mobile-data limit`() {
        assertEquals("480", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 0, metered = true))
        assertEquals("360", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 4, metered = true))
        assertEquals("480", CarVideoPolicy.effectiveQuality("1080", thermalStatus = 0, metered = true))
        assertEquals("360", CarVideoPolicy.effectiveQuality("1080", thermalStatus = 0, metered = true, mobileDataQuality = "360"))
        assertEquals("240", CarVideoPolicy.effectiveQuality("240", thermalStatus = 0, metered = true))
    }

    @Test fun `no-limit mobile setting keeps the previous unmetered behaviour`() {
        assertEquals("1080", CarVideoPolicy.effectiveQuality("1080", thermalStatus = 0, metered = true, mobileDataQuality = "unlimited"))
        assertEquals("720", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 0, metered = true, mobileDataQuality = "unlimited"))
        assertEquals("1080", CarVideoPolicy.effectiveQuality("1080", thermalStatus = 0, metered = false, mobileDataQuality = "360"))
    }

    @Test fun `budget follows the mobile-data cap`() {
        val budget = CarVideoPolicy.budget(
            "1080", projectionFps = 30, playbackSpeed = 1f, thermalStatus = 0,
            metered = true, mobileDataQuality = "720"
        )
        assertEquals("720", budget.effectiveQuality)
        assertEquals(1280, budget.maxLongSide)
    }

    @Test fun `auto floors at 360 instead of pinning a single 144p rung`() {
        assertEquals("360", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 4))
        assertEquals("360", CarVideoPolicy.effectiveQuality("AUTO", thermalStatus = 6))
        val budget = CarVideoPolicy.budget("auto", projectionFps = 30, playbackSpeed = 1f, thermalStatus = 4)
        assertEquals("360", budget.effectiveQuality)
        assertEquals(640, budget.maxLongSide)
        assertEquals(360, budget.maxShortSide)
    }
}
