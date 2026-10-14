package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUpdatesTest {
    private fun playing() = NowPlaying(
        "video", "Title", "Channel", "https://example.invalid/art.jpg", true,
        positionMs = 0L, durationMs = 60_000L, controllable = true
    )

    @Test fun tenMinutesOfPositionTicksDoNotRepublishArtwork() {
        val initial = playing()
        val key = SessionMetadataKey.from(initial)
        val notifications = NotificationUpdateGate()
        assertTrue(notifications.accept(3, 1, true))
        repeat(1_200) { tick ->
            val state = initial.copy(positionMs = tick * 500L)
            assertEquals(key, SessionMetadataKey.from(state))
            assertFalse(notifications.accept(3, 1, true))
        }
    }

    @Test fun realMetadataAndArtworkUpdatesStillReachTheNotification() {
        val state = playing()
        val key = SessionMetadataKey.from(state)
        for (changed in listOf(state.copy(videoId = "next"), state.copy(title = "Repaired"),
            state.copy(channelTitle = "Other"), state.copy(thumbnailUrl = "new.jpg"),
            state.copy(durationMs = 70_000L))) {
            assertFalse(key == SessionMetadataKey.from(changed))
        }
        val gate = NotificationUpdateGate()
        assertTrue(gate.accept(3, 1, true))
        // The same title first has no bitmap, then receives artwork asynchronously.
        assertTrue(gate.accept(3, 2, true))
        assertFalse(gate.accept(3, 2, true))
    }

    @Test fun transientPauseUserPauseResumeAndServiceRestartAreDistinct() {
        val gate = NotificationUpdateGate()
        assertTrue(gate.accept(3, 1, true)) // playing
        assertTrue(gate.accept(2, 1, true)) // temporarily suppressed, still wants playback
        assertFalse(gate.accept(2, 1, true))
        assertTrue(gate.accept(2, 1, false)) // user explicitly pauses during interruption
        assertTrue(gate.accept(3, 1, true)) // explicit resume
        assertTrue(gate.accept(0, 2, false)) // stopped, metadata cleared
        gate.reset()
        assertTrue(gate.accept(0, 2, false))
    }
}
