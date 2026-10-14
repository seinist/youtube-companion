package de.uni.youtubeaa.car

/** Bounded decode sizes keep a small avatar from occupying a full cover's bitmap memory. */
internal object ThumbnailSizing {
    fun bucket(targetPx: Int): Int = when {
        targetPx <= 96 -> 96
        targetPx <= 192 -> 192
        targetPx <= 384 -> 384
        targetPx <= 640 -> 640
        else -> 1280
    }

    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        val limit = bucket(targetPx)
        var sample = 1
        // Keep enough pixels for the view; decoding stays below twice the size class.
        while (maxOf(width, height) / (sample * 2) >= limit) sample *= 2
        return sample
    }

    fun candidates(url: String, quality: String, targetPx: Int): List<String> {
        if (!url.contains("i.ytimg.com/vi/") && !url.contains("img.youtube.com/vi/")) return listOf(url)
        // Only mqdefault (320x180) and hqdefault (480x360) exist for every video; maxresdefault
        // and sddefault can be missing, and each miss costs a request before the first picture.
        // So the large rungs are only tried for large targets, always with hq as fallback.
        // Large images come as WebP from /vi_webp/: 40-60 % smaller than the JPEG for sd/maxres.
        // For mq/hq WebP saves nothing (sometimes it is larger), so those stay JPEG.
        val names = when (quality) {
            "max" -> listOf("maxresdefault.webp", "hqdefault.jpg", "mqdefault.jpg")
            // Without sddefault, "high" must still differ from "medium": it asks for the large
            // image whenever the target is big enough to show it, with hq as reliable fallback.
            "high" -> if (targetPx > 480) listOf("maxresdefault.webp", "hqdefault.jpg", "mqdefault.jpg")
                else listOf("hqdefault.jpg", "mqdefault.jpg")
            "medium" -> listOf("hqdefault.jpg", "mqdefault.jpg")
            "low" -> listOf("mqdefault.jpg", "default.jpg")
            else -> when {
                targetPx <= 320 -> listOf("mqdefault.jpg", "hqdefault.jpg")
                targetPx <= 640 -> listOf("hqdefault.jpg", "mqdefault.jpg")
                // Large phone cards: 640 px is plenty on a phone screen and costs about a third
                // of maxres (60-100 KB JPEG vs 140-220 KB), which is also missing on many uploads.
                else -> listOf("sddefault.webp", "hqdefault.jpg", "mqdefault.jpg")
            }
        }
        val known = Regex("/vi(?:_webp)?/([^/]+)/(?:maxresdefault|sddefault|hqdefault|mqdefault|default|0|1|2|3)\\.(?:jpg|webp)(?:\\?.*)?$")
        val match = known.find(url) ?: return listOf(url)
        val videoId = match.groupValues[1]
        val prefix = url.substring(0, match.range.first)
        return (names.map { name ->
            val folder = if (name.endsWith(".webp")) "vi_webp" else "vi"
            "$prefix/$folder/$videoId/$name"
        } + url).distinct()
    }
}
