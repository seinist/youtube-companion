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

    /**
     * Video thumbnails whose size can be chosen by name. `hq720` (720x404, ~90 KB) is what
     * search, trending and most feeds list as the largest image; the default family has the same
     * picture at 320x180 (~10 KB) to 640x480. `_live` variants keep their suffix (current frame).
     * Shorts (`oardefault`, `frame0`) and per-frame images (`hq1` …) have no smaller sibling of
     * the same picture and stay as they are.
     */
    private val knownVideoThumb = Regex(
        "/vi(?:_webp)?/([^/]+)/(maxresdefault|sddefault|hqdefault|mqdefault|default|hq720|0|1|2|3)(_live)?\\.(?:jpg|webp)(?:\\?.*)?$"
    )

    /** Channel avatar size parameter on yt3.ggpht.com / yt3.googleusercontent.com (`=s900-c-k…`). */
    private val avatarSize = Regex("=s(\\d+)(?=-|$)")

    fun candidates(url: String, quality: String, targetPx: Int): List<String> {
        if (isAvatarHost(url)) return avatarCandidates(url, targetPx)
        if (!url.contains("i.ytimg.com/vi") && !url.contains("img.youtube.com/vi")) return listOf(url)
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
        val match = knownVideoThumb.find(url) ?: return listOf(url)
        val videoId = match.groupValues[1]
        val live = match.groupValues[3]
        val prefix = url.substring(0, match.range.first)
        return (names.map { name ->
            val folder = if (name.endsWith(".webp")) "vi_webp" else "vi"
            val sized = if (live.isEmpty()) name else name.replace(".", "$live.")
            "$prefix/$folder/$videoId/$sized"
        } + url).distinct()
    }

    /**
     * The URL handed to a consumer that downloads by itself (the Android Auto host): the first,
     * smallest sufficient candidate instead of the 720p original.
     */
    fun sizedUrl(url: String, targetPx: Int): String = candidates(url, "auto", targetPx).first()

    private fun isAvatarHost(url: String): Boolean =
        url.contains("yt3.ggpht.com/") || url.contains("yt3.googleusercontent.com/")

    /** Avatars come as `=s900` (~100 KB) although rows show 48-96 dp; ask for the size class. */
    private fun avatarCandidates(url: String, targetPx: Int): List<String> {
        val match = avatarSize.find(url) ?: return listOf(url)
        val requested = match.groupValues[1].toIntOrNull() ?: return listOf(url)
        val wanted = bucket(targetPx)
        if (requested <= wanted) return listOf(url)
        val sized = url.replaceRange(match.range, "=s$wanted")
        return listOf(sized, url)
    }

    /**
     * Picks the image to keep from a list of (url, width, height): the smallest one whose long
     * side still reaches [minLongSide], else the largest. Used where the source lists several
     * sizes (Shorts, avatars) that [candidates] cannot rewrite by name.
     */
    fun pickSource(options: List<Triple<String, Int, Int>>, minLongSide: Int = SOURCE_MIN_LONG_SIDE): String? {
        if (options.isEmpty()) return null
        val sufficient = options.filter { maxOf(it.second, it.third) >= minLongSide }
        val chosen = sufficient.minByOrNull { it.second.toLong() * it.third }
            ?: options.maxByOrNull { it.second.toLong() * it.third }
        return chosen?.first
    }

    /** Large enough for the biggest view (phone card / AA art, 640-720 px). */
    const val SOURCE_MIN_LONG_SIDE = 720
}
