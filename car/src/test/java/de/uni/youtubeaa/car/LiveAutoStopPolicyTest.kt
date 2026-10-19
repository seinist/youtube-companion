package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAutoStopPolicyTest {
    private val minute = 60_000L

    @Test fun `unknown values fall back to 60 minutes`() {
        assertEquals("60", LiveAutoStopPolicy.normalize(null))
        assertEquals("60", LiveAutoStopPolicy.normalize("45"))
        assertEquals("120", LiveAutoStopPolicy.normalize("120"))
        assertEquals(60 * minute, LiveAutoStopPolicy.timeoutMs(null))
        assertNull(LiveAutoStopPolicy.timeoutMs("off"))
    }

    @Test fun `only a playing background live stream is stopped`() {
        assertTrue(LiveAutoStopPolicy.shouldStop(isLive = true, inBackground = true, playing = true, 60 * minute, "60"))
        assertFalse(LiveAutoStopPolicy.shouldStop(isLive = true, inBackground = true, playing = true, 59 * minute, "60"))
        assertFalse(LiveAutoStopPolicy.shouldStop(isLive = false, inBackground = true, playing = true, 600 * minute, "60"))
        assertFalse(LiveAutoStopPolicy.shouldStop(isLive = true, inBackground = false, playing = true, 600 * minute, "60"))
        assertFalse(LiveAutoStopPolicy.shouldStop(isLive = true, inBackground = true, playing = false, 600 * minute, "60"))
    }

    @Test fun `off never stops`() {
        assertFalse(LiveAutoStopPolicy.applies(isLive = true, inBackground = true, playing = true, value = "off"))
        assertFalse(LiveAutoStopPolicy.shouldStop(true, true, true, 10_000 * minute, "off"))
    }

    @Test fun `30 and 120 minute limits`() {
        assertTrue(LiveAutoStopPolicy.shouldStop(true, true, true, 30 * minute, "30"))
        assertFalse(LiveAutoStopPolicy.shouldStop(true, true, true, 119 * minute, "120"))
        assertTrue(LiveAutoStopPolicy.shouldStop(true, true, true, 120 * minute, "120"))
    }
}
