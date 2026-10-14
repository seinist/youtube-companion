package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Test

class CarLipSyncTest {
    @Test fun disabledAndDefaultDoNotShiftVideo() {
        val sync = CarLipSync()
        assertEquals(10_000_000L, sync.videoPositionUs(10_000_000L, 1f))
        sync.update(250, videoMode = false)
        assertEquals(10_000_000L, sync.videoPositionUs(10_000_000L, 1f))
    }

    @Test fun positiveMakesAudioLaterByAdvancingPicture() {
        val sync = CarLipSync()
        sync.update(200, videoMode = true)
        // At audio position 10s, show picture 10.2s: the matching 10.2s audio arrives 200ms later.
        assertEquals(10_200_000L, sync.videoPositionUs(10_000_000L, 1f))
        sync.update(-200, videoMode = true)
        assertEquals(9_800_000L, sync.videoPositionUs(10_000_000L, 1f))
    }

    @Test fun wallClockDelayIsConstantAcrossPlaybackSpeeds() {
        val sync = CarLipSync()
        for (offset in listOf(-1_000, -25, 0, 25, 1_000)) {
            sync.update(offset, videoMode = true)
            for (speed in listOf(0.25f, 0.5f, 1f, 1.25f, 2f)) {
                val mediaDelta = sync.videoPositionUs(50_000_000L, speed) - 50_000_000L
                assertEquals(offset * 1_000L, (mediaDelta / speed).toLong())
            }
        }
    }

    @Test fun switchingModesRestoresSavedValueWithoutCumulativeDrift() {
        val sync = CarLipSync()
        repeat(10) {
            sync.update(325, videoMode = true)
            assertEquals(20_325_000L, sync.videoPositionUs(20_000_000L, 1f))
            sync.update(325, videoMode = false)
            assertEquals(20_000_000L, sync.videoPositionUs(20_000_000L, 1f))
        }
        sync.update(0, videoMode = true)
        assertEquals(20_000_000L, sync.videoPositionUs(20_000_000L, 1f))
    }

    @Test fun outOfRangeValuesAreClampedWithoutIntegerOverflow() {
        val sync = CarLipSync()
        sync.update(Int.MAX_VALUE, videoMode = true)
        assertEquals(11_000_000L, sync.videoPositionUs(10_000_000L, 1f))
        sync.update(Int.MIN_VALUE, videoMode = true)
        assertEquals(9_000_000L, sync.videoPositionUs(10_000_000L, 1f))
    }

    @Test fun seekUsesNewPositionAndPlayerInstancesStayIndependent() {
        val sync = CarLipSync()
        sync.update(-500, videoMode = true)
        // A negative virtual position is valid near the start; clipping it would lose the delay.
        assertEquals(-500_000L, sync.videoPositionUs(0L, 1f))
        assertEquals(119_500_000L, sync.videoPositionUs(120_000_000L, 1f))
        assertEquals(0L, CarLipSync().videoPositionUs(0L, 1f))
    }
}
