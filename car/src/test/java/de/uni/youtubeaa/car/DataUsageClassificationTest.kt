package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `load byte tracker counts cumulative retry callbacks only once`() {
        val tracker = DataUsageStats.LoadByteDeltaTracker()
        assertEquals(1_000L, tracker.delta(loadTaskId = 7L, bytesLoaded = 1_000L, terminal = false))
        assertEquals(500L, tracker.delta(loadTaskId = 7L, bytesLoaded = 1_500L, terminal = false))
        assertEquals(500L, tracker.delta(loadTaskId = 7L, bytesLoaded = 2_000L, terminal = true))
        // A new load may use the same numeric id after the terminal event.
        assertEquals(300L, tracker.delta(loadTaskId = 7L, bytesLoaded = 300L, terminal = true))
    }

    @Test fun `playback rate uses downloaded bytes per active playback minute`() {
        val meter = DataUsageStats.PlaybackRateMeter()
        meter.add(DataUsageStats.Kind.VIDEO, 9L * 1024L * 1024L)
        meter.add(DataUsageStats.Kind.AUDIO, 1L * 1024L * 1024L)
        meter.onPlayingChanged(true, nowMs = 1_000L)
        val running = meter.snapshot(nowMs = 31_000L)
        assertEquals(30_000L, running.playedMs)
        assertTrue(kotlin.math.abs(running.bytesPerMinute - 20.0 * 1024.0 * 1024.0) < 1.0)
        meter.onPlayingChanged(false, nowMs = 61_000L)
        val stopped = meter.snapshot(nowMs = 120_000L)
        assertEquals(60_000L, stopped.playedMs)
        assertEquals(10L * 1024L * 1024L, stopped.bytesPerMinute.toLong())
    }
}
