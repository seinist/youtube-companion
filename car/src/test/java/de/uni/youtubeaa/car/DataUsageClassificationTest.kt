package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Test

class DataUsageClassificationTest {
    private fun sub(host: String, path: String, default: DataUsageStats.Subsystem? = null) =
        DataUsageStats.subsystemOf(host, path, default)

    @Test fun `requests are bucketed by purpose`() {
        assertEquals(DataUsageStats.Subsystem.SEARCH, sub("www.youtube.com", "/youtubei/v1/search"))
        assertEquals(DataUsageStats.Subsystem.FEED, sub("www.youtube.com", "/youtubei/v1/browse"))
        assertEquals(DataUsageStats.Subsystem.FEED, sub("www.youtube.com", "/channel/UC123"))
        assertEquals(DataUsageStats.Subsystem.EXTRACTOR, sub("youtubei.googleapis.com", "/youtubei/v1/player"))
        assertEquals(DataUsageStats.Subsystem.EXTRACTOR, sub("www.youtube.com", "/youtubei/v1/next"))
        assertEquals(DataUsageStats.Subsystem.EXTRACTOR, sub("youtubei.googleapis.com", "/youtubei/v1/reel/reel_item_watch"))
        assertEquals(DataUsageStats.Subsystem.EXTRACTOR, sub("manifest.googlevideo.com", "/api/manifest/hls_variant/x"))
        assertEquals(DataUsageStats.Subsystem.IMAGES, sub("i.ytimg.com", "/vi/abc/hq720.jpg"))
        assertEquals(DataUsageStats.Subsystem.IMAGES, sub("yt3.ggpht.com", "/abc=s88"))
        assertEquals(DataUsageStats.Subsystem.OTHER, sub("oauth2.googleapis.com", "/token"))
        assertEquals(DataUsageStats.Subsystem.OTHER, sub("i.ytimg.com", "/vi/abc/hq720.jpg", DataUsageStats.Subsystem.OTHER))
    }

    @Test fun `uid deltas survive reboots and unsupported counters`() {
        assertEquals(0L, DataUsageStats.uidDelta(previous = -1L, current = 5_000L, sameBoot = true))
        assertEquals(1_000L, DataUsageStats.uidDelta(previous = 4_000L, current = 5_000L, sameBoot = true))
        assertEquals(5_000L, DataUsageStats.uidDelta(previous = 9_000L, current = 5_000L, sameBoot = true))
        assertEquals(5_000L, DataUsageStats.uidDelta(previous = 4_000L, current = 5_000L, sameBoot = false))
        assertEquals(0L, DataUsageStats.uidDelta(previous = 4_000L, current = -1L, sameBoot = true))
    }
}
