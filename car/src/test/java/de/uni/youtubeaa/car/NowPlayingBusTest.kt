package de.uni.youtubeaa.car

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingBusTest {

    @After
    fun tearDown() {
        NowPlayingBus.clear()
    }

    @Test
    fun playbackUpdatesPreserveMetadataAndExposeBuffering() {
        NowPlayingBus.update(
            videoId = "video-id",
            title = "Title",
            channelTitle = "Channel",
            thumbnailUrl = "https://example.invalid/image.jpg",
            isPlaying = false,
            positionMs = 10L,
            durationMs = 100L,
            controllable = true
        )

        NowPlayingBus.updatePlayback(
            isPlaying = false,
            positionMs = 25L,
            durationMs = 120L,
            isBuffering = true
        )

        val state = requireNotNull(NowPlayingBus.state.value)
        assertEquals("video-id", state.videoId)
        assertEquals("Title", state.title)
        assertEquals(25L, state.positionMs)
        assertEquals(120L, state.durationMs)
        assertFalse(state.isPlaying)
        assertTrue(state.isBuffering)
    }

    @Test
    fun clearRemovesPublishedState() {
        NowPlayingBus.update(
            videoId = "video-id",
            title = "Title",
            channelTitle = null,
            thumbnailUrl = null,
            isPlaying = true,
            positionMs = 0L,
            durationMs = 0L,
            controllable = true
        )

        NowPlayingBus.clear()

        assertNull(NowPlayingBus.state.value)
    }
}
