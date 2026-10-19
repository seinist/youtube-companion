package de.uni.youtubeaa.car

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSaverPolicyTest {

    @Test fun `unknown or missing mobile setting falls back to 480`() {
        assertEquals("480", DataSaverPolicy.normalizeMobileQuality(null))
        assertEquals("480", DataSaverPolicy.normalizeMobileQuality("1080"))
        assertEquals("720", DataSaverPolicy.normalizeMobileQuality("720"))
        assertNull(DataSaverPolicy.meteredCapHeight("unlimited"))
        assertEquals(360, DataSaverPolicy.meteredCapHeight("360"))
    }

    @Test fun `cap only applies on metered networks`() {
        assertEquals("1080", DataSaverPolicy.capQuality("1080", metered = false, mobileQuality = "360"))
        assertEquals("360", DataSaverPolicy.capQuality("1080", metered = true, mobileQuality = "360"))
        assertEquals("high", DataSaverPolicy.capQuality("high", metered = true, mobileQuality = "unlimited"))
        assertEquals("480", DataSaverPolicy.capQuality("high", metered = true, mobileQuality = "480"))
        assertEquals("480", DataSaverPolicy.capQuality("auto", metered = true, mobileQuality = "480"))
        assertEquals("144", DataSaverPolicy.capQuality("144", metered = true, mobileQuality = "480"))
    }

    @Test fun `phone auto stops at 720p30 on wi-fi`() {
        assertEquals("720", DataSaverPolicy.phoneResolveQuality("auto", metered = false, mobileQuality = "480"))
        assertEquals(30, DataSaverPolicy.phoneResolveMaxFps("auto"))
        val ceiling = DataSaverPolicy.phoneCeiling("auto", metered = false, mobileQuality = "480")
        assertEquals(1280, ceiling.maxLongSide)
        assertEquals(30, ceiling.maxFps)
    }

    @Test fun `phone auto on mobile data uses the mobile cap but never exceeds 720`() {
        assertEquals("480", DataSaverPolicy.phoneResolveQuality("auto", metered = true, mobileQuality = "480"))
        assertEquals(854, DataSaverPolicy.phoneCeiling("auto", metered = true, mobileQuality = "480").maxLongSide)
        assertEquals(1280, DataSaverPolicy.phoneCeiling("auto", metered = true, mobileQuality = "unlimited").maxLongSide)
        assertEquals("720", DataSaverPolicy.phoneResolveQuality("auto", metered = true, mobileQuality = "unlimited"))
    }

    @Test fun `explicit phone quality is unlimited on wi-fi and capped on mobile data`() {
        assertEquals("1080", DataSaverPolicy.phoneResolveQuality("1080", metered = false, mobileQuality = "480"))
        assertNull(DataSaverPolicy.phoneResolveMaxFps("1080"))
        val wifi = DataSaverPolicy.phoneCeiling("1080", metered = false, mobileQuality = "480")
        assertEquals(DataSaverPolicy.Ceiling.NONE, wifi)
        assertFalse(wifi.limited)

        assertEquals("480", DataSaverPolicy.phoneResolveQuality("2160", metered = true, mobileQuality = "480"))
        val mobile = DataSaverPolicy.phoneCeiling("2160", metered = true, mobileQuality = "480")
        assertEquals(854, mobile.maxLongSide)
        assertEquals(Int.MAX_VALUE, mobile.maxFps)
        assertTrue(mobile.limited)
        // An explicit choice below the cap stays as is.
        assertEquals(640, DataSaverPolicy.phoneCeiling("360", metered = true, mobileQuality = "720").maxLongSide)
    }

    @Test fun `metered read-ahead is shortened`() {
        assertEquals(15_000, DataSaverPolicy.maxBufferMs(metered = true, defaultMaxBufferMs = 30_000))
        assertEquals(30_000, DataSaverPolicy.maxBufferMs(metered = false, defaultMaxBufferMs = 30_000))
        assertEquals(10_000, DataSaverPolicy.maxBufferMs(metered = true, defaultMaxBufferMs = 10_000))
    }

    @Test fun `only separate tracks drop video for free`() {
        assertTrue(SourceLayout.AUDIO_ONLY.videoCanBeDroppedForFree)
        assertTrue(SourceLayout.SEPARATE.videoCanBeDroppedForFree)
        assertFalse(SourceLayout.MUXED_PROGRESSIVE.videoCanBeDroppedForFree)
        assertFalse(SourceLayout.MUXED_MANIFEST.videoCanBeDroppedForFree)
    }

    @Test fun `byte counter buckets by track type and googlevideo mime`() {
        assertEquals(DataUsageStats.Kind.VIDEO, DataUsageStats.kindOf(C.TRACK_TYPE_VIDEO, C.DATA_TYPE_MEDIA, null))
        assertEquals(DataUsageStats.Kind.AUDIO, DataUsageStats.kindOf(C.TRACK_TYPE_AUDIO, C.DATA_TYPE_MEDIA, null))
        assertEquals(DataUsageStats.Kind.MUXED, DataUsageStats.kindOf(C.TRACK_TYPE_DEFAULT, C.DATA_TYPE_MEDIA, null))
        assertEquals(DataUsageStats.Kind.AUDIO, DataUsageStats.kindOf(C.TRACK_TYPE_UNKNOWN, C.DATA_TYPE_MEDIA, "audio/webm"))
        assertEquals(DataUsageStats.Kind.VIDEO, DataUsageStats.kindOf(C.TRACK_TYPE_UNKNOWN, C.DATA_TYPE_MEDIA, "video/mp4"))
        assertEquals(DataUsageStats.Kind.OTHER, DataUsageStats.kindOf(C.TRACK_TYPE_UNKNOWN, C.DATA_TYPE_MANIFEST, "video/mp4"))
        assertEquals(DataUsageStats.Kind.OTHER, DataUsageStats.kindOf(C.TRACK_TYPE_UNKNOWN, C.DATA_TYPE_MEDIA, null))
    }
}
