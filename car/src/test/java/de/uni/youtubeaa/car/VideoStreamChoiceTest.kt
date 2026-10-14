package de.uni.youtubeaa.car

import de.uni.youtubeaa.car.VideoStreamChoice.Codec
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStreamChoiceTest {

    private data class S(
        val height: Int,
        val codec: String?,
        val fps: Int = 30,
        val width: Int = height * 16 / 9,
        val sourceCost: Int = 0,
        val bitrate: Int = 1_000_000
    )

    // Snapdragon 8+ Gen 1 (the reporting device): H.264 and VP9 in hardware, AV1 not.
    private val noAv1 = { c: Codec, _: String?, _: Int, _: Int, _: Int -> c == Codec.AVC || c == Codec.VP9 }

    private fun pick(
        streams: List<S>,
        quality: String = "auto",
        hw: (Codec, String?, Int, Int, Int) -> Boolean = noAv1,
        maxFps: Int? = null
    ) = VideoStreamChoice.pick(
        streams, quality,
        { it.width }, { it.height }, { it.codec }, { it.fps }, hw,
        hardwareLikelyDecodes = { family, width, height, fps -> hw(family, null, width, height, fps) },
        maxFps = maxFps,
        sourceCostOf = { it.sourceCost },
        bitrateOf = { it.bitrate }
    )

    private val youtube1080 = listOf(
        S(1080, "av01.0.08M.08"), S(1080, "vp9"), S(1080, "avc1.640028"),
        S(720, "av01.0.05M.08"), S(720, "vp9"), S(720, "avc1.4d401f"),
        S(1440, "vp9"), S(2160, "av01.0.12M.08")
    )

    @Test
    fun neverPicksSoftwareAv1WhenAHardwareCodecExists() {
        val chosen = pick(youtube1080)!!
        assertEquals(1080, chosen.height)
        assertEquals(true, VideoStreamChoice.familyOf(chosen.codec) != Codec.AV1)
    }

    @Test
    fun hardwareBeatsResolution() {
        val streams = listOf(S(1080, "av01.0.08M.08"), S(720, "avc1.4d401f"))
        assertEquals(S(720, "avc1.4d401f"), pick(streams))
    }

    @Test
    fun exactHardwareCapabilityParticipatesInRanking() {
        val streams = listOf(S(1080, "vp9"), S(720, "avc1.4d401f"))
        val only720 = { _: Codec, _: String?, _: Int, height: Int, fps: Int -> height <= 720 && fps <= 30 }
        assertEquals(S(720, "avc1.4d401f"), pick(streams, hw = only720))
    }

    @Test
    fun av1IsFineWhereTheDeviceDecodesIt() {
        val streams = listOf(S(1080, "av01.0.08M.08"), S(720, "avc1.4d401f"))
        assertEquals(S(1080, "av01.0.08M.08"), pick(streams, hw = { _, _, _, _, _ -> true }))
    }

    @Test
    fun phoneAutoStaysAtOrBelow1080AndExplicitHeightsAreCeilings() {
        assertEquals(1080, pick(youtube1080)!!.height)
        assertEquals(720, pick(youtube1080, quality = "720")!!.height)
        assertEquals(1440, pick(youtube1080, quality = "high")!!.height)
    }

    @Test
    fun projectedAutoCapsAt1080() {
        assertEquals(1080, pick(youtube1080, maxFps = 30)!!.height)
        assertEquals(1080, pick(youtube1080, quality = "1080", maxFps = 30)!!.height)
    }

    @Test
    fun lowPicksTheSmallestPicture() {
        val streams = listOf(S(144, "avc1"), S(360, "avc1"), S(720, "avc1"))
        assertEquals(144, pick(streams, quality = "low")!!.height)
    }

    @Test
    fun frameRateCeilingPrefersSafeStreamAcrossResolutions() {
        val streams = listOf(S(720, "avc1", 60), S(480, "vp9", 30))
        assertEquals(S(480, "vp9", 30), pick(streams, maxFps = 30))
    }

    @Test
    fun hardwareFamilyBeatsSoftwareCodecEvenWhenHardwareNeeds60Fps() {
        val streams = listOf(S(720, "avc1.4d401f", 60), S(720, "av01.0.05M.08", 30))
        assertEquals(S(720, "avc1.4d401f", 60), pick(streams, maxFps = 30))
    }

    @Test
    fun onlyHighFrameRateAvailableUsesSmallestPicture() {
        val streams = listOf(S(720, "avc1", 60), S(480, "avc1", 60))
        assertEquals(S(480, "avc1", 60), pick(streams, maxFps = 30))
    }

    @Test
    fun moreEfficientHardwareCodecWinsTieToSaveData() {
        val streams = listOf(S(720, "avc1.4d401f", 30), S(720, "vp9", 30))
        assertEquals(S(720, "vp9", 30), pick(streams, maxFps = 30))
        val withHwAv1 = { _: Codec, _: String?, _: Int, _: Int, _: Int -> true }
        val all = streams + S(720, "av01.0.05M.08", 30)
        assertEquals(S(720, "av01.0.05M.08", 30), pick(all, hw = withHwAv1, maxFps = 30))
    }

    @Test
    fun hardwareAndQualityBeatMuxedConvenience() {
        val muxedSoftware = S(720, "av01.0.05M.08", sourceCost = 0)
        val separateHardware = S(720, "avc1.4d401f", sourceCost = 1)
        assertEquals(separateHardware, pick(listOf(muxedSoftware, separateHardware), maxFps = 30))
    }

    @Test
    fun muxedWinsOnlyAfterHardwareQualityAndCodecTie() {
        val muxed = S(720, "avc1.4d401f", sourceCost = 0)
        val separate = S(720, "avc1.4d401f", sourceCost = 1)
        assertEquals(muxed, pick(listOf(separate, muxed), maxFps = 30))
    }

    @Test
    fun lowerBitrateBreaksAnOtherwiseEqualTie() {
        val high = S(720, "avc1.4d401f", bitrate = 2_000_000)
        val low = S(720, "avc1.4d401f", bitrate = 900_000)
        assertEquals(low, pick(listOf(high, low), maxFps = 30))
    }

    @Test
    fun nothingWithinRangeFallsBackToTheSmallestStream() {
        val streams = listOf(S(1440, "vp9"), S(2160, "vp9"))
        assertEquals(1440, pick(streams)!!.height)
    }

    @Test
    fun codecFamiliesAreRecognised() {
        assertEquals(Codec.AVC, VideoStreamChoice.familyOf("avc1.640028"))
        assertEquals(Codec.VP9, VideoStreamChoice.familyOf("vp09.00.40.08"))
        assertEquals(Codec.VP9, VideoStreamChoice.familyOf("vp9"))
        assertEquals(Codec.AV1, VideoStreamChoice.familyOf("av01.0.08M.08"))
        assertEquals(Codec.OTHER, VideoStreamChoice.familyOf(null))
    }
}
