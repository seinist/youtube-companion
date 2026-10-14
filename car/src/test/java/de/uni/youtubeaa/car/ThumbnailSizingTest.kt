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
}
