package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSizingTest {
    private val url = "https://i.ytimg.com/vi/abc123/hqdefault.jpg?sqp=xyz"

    @Test
    fun largeAutoTargetUsesSdWebpWithJpegFallbacks() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi_webp/abc123/sddefault.webp",
                "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
                "https://i.ytimg.com/vi/abc123/mqdefault.jpg",
                url
            ),
            ThumbnailSizing.candidates(url, "auto", 1280)
        )
    }

    @Test
    fun listTargetKeepsHqJpeg() {
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", ThumbnailSizing.candidates(url, "auto", 448).first())
    }

    @Test
    fun maxQualityUsesMaxresWebp() {
        assertEquals("https://i.ytimg.com/vi_webp/abc123/maxresdefault.webp", ThumbnailSizing.candidates(url, "max", 1280).first())
    }

    @Test
    fun foreignUrlIsUntouched() {
        assertEquals(listOf("https://yt3.ggpht.com/a.jpg"), ThumbnailSizing.candidates("https://yt3.ggpht.com/a.jpg", "auto", 1280))
    }

    @Test
    fun hq720FromSearchIsResizedByName() {
        val hq720 = "https://i.ytimg.com/vi/rFZHOHl-L8A/hq720.jpg?sqp=-oaymwEXCNAFEJQDSFryq4qpAwkIARUAAIhCGAE=&rs=AOn4CLB"
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/rFZHOHl-L8A/mqdefault.jpg",
                "https://i.ytimg.com/vi/rFZHOHl-L8A/hqdefault.jpg",
                hq720
            ),
            ThumbnailSizing.candidates(hq720, "auto", 320)
        )
        assertEquals("https://i.ytimg.com/vi/rFZHOHl-L8A/hqdefault.jpg", ThumbnailSizing.candidates(hq720, "auto", 480).first())
    }

    @Test
    fun liveThumbnailKeepsLiveSuffix() {
        val live = "https://i.ytimg.com/vi/live1/hqdefault_live.jpg"
        assertEquals("https://i.ytimg.com/vi/live1/mqdefault_live.jpg", ThumbnailSizing.candidates(live, "auto", 320).first())
    }

    @Test
    fun shortsAndFrameImagesStayUntouched() {
        val shorts = "https://i.ytimg.com/vi/s1/oardefault.jpg?sqp=abc"
        assertEquals(listOf(shorts), ThumbnailSizing.candidates(shorts, "auto", 320))
        val frame = "https://i.ytimg.com/vi/s1/hq2.jpg"
        assertEquals(listOf(frame), ThumbnailSizing.candidates(frame, "auto", 320))
    }

    @Test
    fun avatarSizeIsRewrittenToTheSizeClass() {
        val avatar = "https://yt3.ggpht.com/abcDEF=s900-c-k-c0x00ffffff-no-rj"
        assertEquals(
            listOf("https://yt3.ggpht.com/abcDEF=s96-c-k-c0x00ffffff-no-rj", avatar),
            ThumbnailSizing.candidates(avatar, "auto", 72)
        )
        assertEquals("https://yt3.googleusercontent.com/x=s192", ThumbnailSizing.candidates("https://yt3.googleusercontent.com/x=s800", "auto", 150).first())
        // Already small enough: unchanged.
        assertEquals(listOf("https://yt3.ggpht.com/a=s88-c"), ThumbnailSizing.candidates("https://yt3.ggpht.com/a=s88-c", "auto", 96))
    }

    @Test
    fun sizedUrlForAndroidAutoPicksTheFirstCandidate() {
        assertEquals("https://i.ytimg.com/vi/abc123/mqdefault.jpg", ThumbnailSizing.sizedUrl(url, 320))
    }

    @Test
    fun pickSourcePrefersSmallestSufficientImage() {
        val options = listOf(
            Triple("small", 168, 94),
            Triple("hq720", 720, 404),
            Triple("max", 1280, 720)
        )
        assertEquals("hq720", ThumbnailSizing.pickSource(options))
        assertEquals("max", ThumbnailSizing.pickSource(options, minLongSide = 1000))
        // Nothing large enough (or sizes unknown): the largest wins.
        assertEquals("max", ThumbnailSizing.pickSource(options, minLongSide = 4000))
        assertEquals("only", ThumbnailSizing.pickSource(listOf(Triple("only", 0, 0))))
        assertEquals(null, ThumbnailSizing.pickSource(emptyList()))
    }
}
