package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchHistoryStoreTest {

    @Test
    fun resumePosition_ignoresTinyProgress() {
        assertEquals(0L, WatchHistoryStore.normalizeResumePosition(4_999L, 60_000L))
    }

    @Test
    fun resumePosition_keepsMeaningfulProgress() {
        assertEquals(30_000L, WatchHistoryStore.normalizeResumePosition(30_000L, 120_000L))
    }

    @Test
    fun resumePosition_restartsCompletedVideo() {
        assertEquals(0L, WatchHistoryStore.normalizeResumePosition(118_000L, 120_000L))
    }

    @Test
    fun resumePosition_keepsNinetyFivePercentWhenMoreThanTenSecondsRemain() {
        assertEquals(3_420_000L, WatchHistoryStore.normalizeResumePosition(3_420_000L, 3_600_000L))
    }
}
