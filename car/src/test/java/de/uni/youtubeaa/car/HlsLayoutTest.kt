package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsLayoutTest {
    // Shape of a real YouTube HLS master (VOD and live): audio renditions with URIs, video-only variants.
    private val youtubeMaster = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x/itag/233/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x/itag/234/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_timedtext_playlist/id/x/lang/en",TYPE=SUBTITLES,GROUP-ID="vtt",NAME="English"
        #EXT-X-STREAM-INF:BANDWIDTH=290288,CODECS="avc1.42C00B,mp4a.40.5",RESOLUTION=256x144,FRAME-RATE=15,AUDIO="233"
        https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x/itag/269/playlist/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2969452,CODECS="avc1.4D401F,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=30,AUDIO="234"
        https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x/itag/232/playlist/index.m3u8
    """.trimIndent()

    @Test fun `audio renditions with uri mean separate audio`() {
        assertEquals(SourceLayout.SEPARATE, HlsLayout.classify(listOf("https://a/233.m3u8", null)))
    }

    @Test fun `no audio rendition or only uri-less ones mean muxed variants`() {
        assertEquals(SourceLayout.MUXED_MANIFEST, HlsLayout.classify(emptyList()))
        assertEquals(SourceLayout.MUXED_MANIFEST, HlsLayout.classify(listOf(null, "")))
    }

    @Test fun `pending hls takes the detected layout, others stay`() {
        assertEquals(SourceLayout.HLS_PENDING, HlsLayout.refine(SourceLayout.HLS_PENDING, null))
        assertEquals(SourceLayout.SEPARATE, HlsLayout.refine(SourceLayout.HLS_PENDING, SourceLayout.SEPARATE))
        assertEquals(SourceLayout.MUXED_MANIFEST, HlsLayout.refine(SourceLayout.HLS_PENDING, SourceLayout.MUXED_MANIFEST))
        assertEquals(SourceLayout.MUXED_PROGRESSIVE, HlsLayout.refine(SourceLayout.MUXED_PROGRESSIVE, SourceLayout.SEPARATE))
        assertEquals(SourceLayout.AUDIO_ONLY, HlsLayout.refine(SourceLayout.AUDIO_ONLY, SourceLayout.MUXED_MANIFEST))
    }

    @Test fun `parses only audio renditions`() {
        val renditions = HlsLayout.audioRenditions(youtubeMaster)
        assertEquals(listOf("233", "234"), renditions.map { it.groupId })
        assertEquals(SourceLayout.SEPARATE, HlsLayout.classify(renditions.map { it.uri }))
    }

    @Test fun `picks the audio group of the best variant, or the lowest on request`() {
        assertEquals("234", HlsLayout.pickAudioRendition(youtubeMaster)?.groupId)
        assertEquals("233", HlsLayout.pickAudioRendition(youtubeMaster, preferLow = true)?.groupId)
    }

    @Test fun `relative rendition uri is resolved against the master`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="en",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO="aac"
            video/500.m3u8
        """.trimIndent()
        assertEquals("https://cdn.example/live/audio/en.m3u8",
            HlsLayout.pickAudioRendition(master, "https://cdn.example/live/master.m3u8")?.uri)
    }

    @Test fun `muxed master has no audio rendition to play`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=500000,CODECS="avc1.4D401E,mp4a.40.2"
            500.m3u8
        """.trimIndent()
        assertNull(HlsLayout.pickAudioRendition(master))
        assertEquals(SourceLayout.MUXED_MANIFEST, HlsLayout.classify(HlsLayout.audioRenditions(master).map { it.uri }))
    }
}
