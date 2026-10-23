package de.uni.youtubeaa.car

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeteredLoadControlTest {

    @Test fun `forwards mandatory player-specific load control methods`() {
        val delegate = DefaultLoadControl.Builder()
            .setBackBuffer(2_000, true)
            .build()
        val loadControl = PlayerFactory.MeteredLoadControl(delegate, isMetered = { false })
        val playerId = PlayerId.UNSET

        // Media3 calls these while constructing ExoPlayer. LoadControl's compatibility defaults
        // throw IllegalStateException, which is the regression this adapter must never expose.
        assertEquals(delegate.getBackBufferDurationUs(playerId), loadControl.getBackBufferDurationUs(playerId))
        assertEquals(delegate.retainBackBufferFromKeyframe(playerId), loadControl.retainBackBufferFromKeyframe(playerId))
        assertNotNull(loadControl.getAllocator(playerId))

        // The remaining player lifecycle defaults throw for the same reason if they are not
        // forwarded explicitly. A complete start/stop/release cycle must remain safe.
        loadControl.onPrepared(playerId)
        loadControl.onStopped(playerId)
        loadControl.onReleased(playerId)
    }
}
