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

    @Test fun `metered connection caps auto at 480 but leaves explicit quality alone`() {
        assertEquals("480", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 0, metered = true))
        assertEquals("360", CarVideoPolicy.effectiveQuality("auto", thermalStatus = 4, metered = true))
        assertEquals("1080", CarVideoPolicy.effectiveQuality("1080", thermalStatus = 0, metered = true))
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
