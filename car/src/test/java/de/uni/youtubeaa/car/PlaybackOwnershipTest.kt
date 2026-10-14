package de.uni.youtubeaa.car

import android.support.v4.media.MediaDescriptionCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlaybackOwnershipTest {
    private val controllers = mutableListOf<FakeController>()
    private fun controller() = FakeController().also { controllers += it }

    @After fun cleanup() {
        controllers.forEach { VideoSessionBridge.unregister(it) }
        NowPlayingBus.clear()
    }

    @Test fun projectedHeadUnitRejectsStaleMediaSessionStop() {
        assertFalse(MediaSessionCommandPolicy.allowStop(VideoSessionBridge.Role.HEAD_UNIT))
        assertTrue(MediaSessionCommandPolicy.allowStop(VideoSessionBridge.Role.NATIVE_AUDIO))
        assertTrue(MediaSessionCommandPolicy.allowStop(VideoSessionBridge.Role.PHONE))
        assertTrue(MediaSessionCommandPolicy.allowStop(null))
    }

    @Test fun aRetiredPhoneCannotOverwriteOrClearCarPlayback() {
        val phone = controller()
        val car = controller()
        VideoSessionBridge.register(phone)
        publish(phone, "phone")
        VideoSessionBridge.activate(car, VideoSessionBridge.Role.HEAD_UNIT)
        publish(car, "car")
        publish(phone, "stale")
        NowPlayingBus.updatePlayback(false, 999L, owner = phone)
        NowPlayingBus.updatePresentation(PlaybackPresentation.AUDIO, owner = phone)
        NowPlayingBus.clear(owner = phone)
        assertEquals("car", NowPlayingBus.state.value?.videoId)
        assertEquals(40L, NowPlayingBus.state.value?.positionMs)
        assertEquals(PlaybackPresentation.VIDEO, NowPlayingBus.state.value?.presentation)
        assertEquals(1, phone.lost)
    }

    @Test fun explicitPhoneSelectionReplacesHeadUnit() {
        val car = controller()
        val phone = controller()
        VideoSessionBridge.activate(car, VideoSessionBridge.Role.HEAD_UNIT)
        VideoSessionBridge.activate(phone, VideoSessionBridge.Role.PHONE)
        assertSame(phone, VideoSessionBridge.controller)
        assertEquals(1, car.lost)
        VideoSessionBridge.unregister(phone)
        assertNull(VideoSessionBridge.controller)
    }

    @Test fun nativeAudioCanOwnControlsWithoutAProjection() {
        val phone = controller()
        val native = controller()
        VideoSessionBridge.activate(phone, VideoSessionBridge.Role.PHONE)
        VideoSessionBridge.activate(native, VideoSessionBridge.Role.NATIVE_AUDIO)
        assertSame(native, VideoSessionBridge.controller)
        assertEquals(1, phone.lost)
        publish(native, "audio")
        NowPlayingBus.updatePlayback(false, 75L, isBuffering = true, owner = native)
        assertTrue(NowPlayingBus.state.value!!.isBuffering)
        assertEquals(75L, NowPlayingBus.state.value!!.positionMs)
    }

    @Test fun attachingThePhoneUiDoesNotPauseItsSharedEngine() {
        val engine = controller()
        val screen = controller()
        VideoSessionBridge.register(engine)
        VideoSessionBridge.register(screen)
        assertEquals(0, engine.lost)
        VideoSessionBridge.unregister(screen)
        assertSame(engine, VideoSessionBridge.controller)
    }

    @Test fun aNewPhoneActivityDoesNotRestoreAnObsoleteController() {
        val old = controller()
        val fresh = controller()
        VideoSessionBridge.activate(old, VideoSessionBridge.Role.PHONE)
        VideoSessionBridge.activate(fresh, VideoSessionBridge.Role.PHONE)
        VideoSessionBridge.unregister(fresh)
        assertNull(VideoSessionBridge.controller)
        assertEquals(0, old.lost) // Both Activities use the same ExoPlayer.
    }

    @Test fun explicitPauseDuringFocusSuppressionPublishesChangedIntent() {
        val car = controller()
        car.wantsPlay = true
        VideoSessionBridge.activate(car, VideoSessionBridge.Role.HEAD_UNIT)
        publish(car, "car")
        NowPlayingBus.updatePlayback(false, 40L, owner = car)
        val suppressed = NowPlayingBus.state.value!!
        assertFalse(suppressed.isPlaying)
        assertTrue(suppressed.playWhenReady)
        car.wantsPlay = false
        NowPlayingBus.updatePlayback(false, 40L, owner = car)
        val paused = NowPlayingBus.state.value!!
        assertFalse(paused.isPlaying)
        assertFalse(paused.playWhenReady)
        assertFalse(suppressed == paused) // StateFlow must deliver the explicit Pause as well.
    }

    private fun publish(owner: VideoSessionBridge.Controller, id: String) {
        NowPlayingBus.update(id, id, null, null, true, 40L, 100L, true,
            presentation = PlaybackPresentation.VIDEO, owner = owner)
    }

    private class FakeController : VideoSessionBridge.Controller {
        var lost = 0
        var wantsPlay: Boolean? = null
        override fun snapshot() = wantsPlay?.let { VideoSessionBridge.Snapshot(emptyList(), 0, 0L, it) }
        override fun onOwnershipLost() { lost++ }
        override fun play() = Unit
        override fun pause() = Unit
        override fun stopPlayback() = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun playMediaId(videoId: String) = Unit
        override fun playQueue(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) = Unit
        override fun isPlaying() = false
        override fun getPositionMs() = 0L
        override fun getDurationMs() = 0L
    }
}
