package de.uni.youtubeaa.car.hybrid

import de.uni.youtubeaa.car.ThumbnailSizing
import org.json.JSONArray
import org.json.JSONObject

/** Feed cards only. No network, Android views, endpoint scraping or unrelated menu videos. */
internal object InnerTubeFeedParser {
    data class VideoCard(val id: String, val title: String, val channel: String?,
        val thumbnailUrl: String?, val channelId: String?,
        /** Relative upload label ("vor 3 Tagen"); feed cards carry no absolute date. */
        val publishedText: String? = null,
        /** Shorts tile/reel/lockup; YouTube ships no upload date for these. */
        val isShort: Boolean = false)
    private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * The feed's filter chips. The selected chip usually carries no continuation token — it is
     * the unfiltered feed, so a null token means "load FEsubscriptions again".
     */
    fun chips(root: JSONObject): List<FeedChip> {
        val out = LinkedHashMap<String, FeedChip>()
        fun scan(node: Any?, depth: Int) {
            if (depth > 12) return
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("chipCloudChipRenderer")?.let { chip ->
                        val title = extractText(chip.opt("text"))
                            ?: chip.optString("accessibilityLabel").takeIf { it.isNotBlank() }
                        if (title != null) {
                            out.putIfAbsent(title, FeedChip(
                                title = title,
                                token = continuationToken(chip.opt("navigationEndpoint")),
                                selected = chip.optBoolean("isSelected", false)
                            ))
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) scan(node.opt(keys.next()), depth + 1)
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i), depth + 1)
            }
        }
        scan(root, 0)
        return out.values.toList()
    }

    private fun continuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationCommand")?.optString("token")
                    ?.takeIf { it.isNotBlank() }?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) continuationToken(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) continuationToken(node.opt(i))?.let { return it }
        }
        return null
    }

    fun videos(root: JSONObject): List<VideoCard> {
        val out = LinkedHashMap<String, VideoCard>()
        fun addVideo(videoId: String, title: String?, channel: String?, thumbnailUrl: String?,
                     channelId: String? = null, publishedText: String? = null, isShort: Boolean = false) {
            // TVHTML5's tileRenderer also wraps ad/promoted cards; their contentId is an ad
            // tracking id, not a real video id, and feeding it to the stream extractor fails
            // with "JSON response is too short". Real YouTube video ids are always 11 chars.
            if (!YOUTUBE_VIDEO_ID.matches(videoId)) return
            val item = VideoCard(videoId, title?.takeIf { it.isNotBlank() } ?: videoId,
                channel?.takeIf { it.isNotBlank() }, thumbnailUrl, channelId,
                publishedText?.takeIf { RelativeTimeText.looksLikeAge(it) }, isShort)
            val existing = out[videoId]
            if (existing == null) out[videoId] = item else out[videoId] = existing.copy(
                title = if (existing.title == videoId) item.title else existing.title,
                channel = existing.channel ?: item.channel,
                thumbnailUrl = existing.thumbnailUrl ?: item.thumbnailUrl,
                channelId = existing.channelId ?: item.channelId,
                publishedText = existing.publishedText ?: item.publishedText,
                isShort = existing.isShort || item.isShort
            )
        }

        walkContent(root) { obj ->
            // Current TVHTML5 browse responses commonly use tileRenderer.
            obj.optJSONObject("tileRenderer")?.let { tile ->
                val videoId = tile.optString("contentId").ifBlank {
                    tile.optJSONObject("onSelectCommand")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId")
                        .orEmpty()
                }
                val header = tile.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
                val metadata = tile.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
                val overlayMetadata = header?.optJSONArray("thumbnailOverlays")?.let { overlays ->
                    (0 until overlays.length()).firstNotNullOfOrNull {
                        overlays.optJSONObject(it)?.optJSONObject("tileMetadataRenderer")
                    }
                }
                val showMenu = tile.optJSONObject("onLongPressCommand")?.optJSONObject("showMenuCommand")
                val title = extractText(metadata?.opt("title"))
                    ?: extractText(overlayMetadata?.opt("title"))
                    ?: extractText(showMenu?.opt("title"))
                val channel = extractTileLine(metadata, 0)
                    ?: extractText(showMenu?.opt("subtitle"))
                val channelId = findChannelBrowseId(metadata)
                    ?: findChannelBrowseId(showMenu)
                    ?: findChannelBrowseId(header)
                val thumb = firstThumbnail(header?.optJSONObject("thumbnail"))
                val isShort = tile.optString("contentType") == "TILE_CONTENT_TYPE_SHORTS" ||
                    tile.optString("style") == "TILE_STYLE_YTLR_SHORTS" ||
                    tile.optJSONObject("onSelectCommand")?.has("reelWatchEndpoint") == true
                addVideo(videoId, title, channel, thumb, channelId, isShort = isShort,
                    // Only the metadata lines below the title: a title such as "24 Stunden im
                    // Supermarkt" would otherwise read as the upload age.
                    publishedText = findAgeText(metadata?.opt("lines")) ?: findAgeText(overlayMetadata?.opt("lines")))
            }

            obj.optJSONObject("reelItemRenderer")?.let { reel ->
                val videoId = reel.optString("videoId").ifBlank {
                    reel.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("reelWatchEndpoint")
                        ?.optString("videoId")
                        .orEmpty()
                }
                val title = extractText(reel.opt("headline"))
                    ?: reel.optString("accessibilityText").substringBefore(", ").takeIf { it.isNotBlank() }
                val thumb = firstThumbnail(reel.optJSONObject("thumbnail")) ?: findImageUrl(reel.opt("thumbnail"))
                addVideo(videoId, title, null, thumb, isShort = true)
            }

            obj.optJSONObject("shortsLockupViewModel")?.let { shorts ->
                val videoId = watchVideoId(shorts.opt("onTap"))
                    ?: watchVideoId(shorts.opt("rendererContext")) ?: ""
                val title = shorts.optJSONObject("overlayMetadata")
                    ?.optJSONObject("primaryText")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: shorts.optString("accessibilityText").substringBefore(", ").takeIf { it.isNotBlank() }
                val thumb = findImageUrl(shorts.opt("thumbnail"))
                    ?: findImageUrl(shorts.opt("thumbnailViewModel"))
                addVideo(videoId, title, null, thumb, isShort = true)
            }

            obj.optJSONObject("lockupViewModel")?.let { model ->
                val type = model.optString("contentType")
                if (type == "LOCKUP_CONTENT_TYPE_VIDEO" || type == "LOCKUP_CONTENT_TYPE_SHORT") {
                    val metadata = model.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                    val linkedChannel = findChannelText(metadata)
                    addVideo(model.optString("contentId"), extractText(metadata?.opt("title")),
                        linkedChannel?.second, findImageUrl(model.opt("contentImage")),
                        linkedChannel?.first ?: findChannelBrowseId(metadata),
                        publishedText = findAgeText(metadata?.opt("metadata")),
                        isShort = type == "LOCKUP_CONTENT_TYPE_SHORT")
                }
            }

            // YouTube changes renderer families regularly. Supporting the classic renderers keeps
            // Home/Subscriptions usable when TVHTML5 switches away from tileRenderer.
            val classicKeys = arrayOf(
                "videoRenderer",
                "gridVideoRenderer",
                "compactVideoRenderer",
                "videoWithContextRenderer",
                "playlistVideoRenderer"
            )
            for (key in classicKeys) {
                val renderer = obj.optJSONObject(key) ?: continue
                val videoId = renderer.optString("videoId")
                val title = extractText(renderer.opt("title"))
                val channel = extractText(renderer.opt("ownerText"))
                    ?: extractText(renderer.opt("longBylineText"))
                    ?: extractText(renderer.opt("shortBylineText"))
                val thumb = firstThumbnail(renderer.optJSONObject("thumbnail"))
                val channelId = findChannelBrowseId(renderer.opt("ownerText"))
                    ?: findChannelBrowseId(renderer.opt("longBylineText"))
                    ?: findChannelBrowseId(renderer.opt("shortBylineText"))
                addVideo(videoId, title, channel, thumb, channelId,
                    publishedText = extractText(renderer.opt("publishedTimeText"))
                        ?: findAgeText(renderer.opt("videoInfo")))
            }
        }
        return out.values.toList()
    }

    private val cardKeys = setOf("tileRenderer", "videoRenderer", "gridVideoRenderer",
        "compactVideoRenderer", "videoWithContextRenderer", "playlistVideoRenderer",
        "lockupViewModel", "shortsLockupViewModel", "reelItemRenderer")
    private val ignoredKeys = setOf("menu", "topbar", "header", "engagementPanels", "frameworkUpdates",
        "trackingParams", "navigationEndpoint", "onTap", "onLongPressCommand", "onSelectCommand",
        "guideRenderer", "chipCloudRenderer", "sortFilterSubMenuRenderer")
    private val shelfKeys = setOf("shelfRenderer", "richShelfRenderer", "reelShelfRenderer", "horizontalListRenderer")

    /** Prefer the selected tab and returned feed contents, never an unselected tab's cards. */
    private fun contentRoots(root: JSONObject): List<Any> {
        val names = listOf("contents", "continuationContents", "onResponseReceivedActions",
            "onResponseReceivedEndpoints", "onResponseReceivedCommands")
        return names.mapNotNull { root.opt(it) }.ifEmpty { listOf(root) }
    }

    private fun walkContent(root: JSONObject, visitor: (JSONObject) -> Unit) {
        fun scan(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("tabRenderer")?.let { tab ->
                        if (!tab.has("selected") || tab.optBoolean("selected")) scan(tab.opt("content"))
                        return
                    }
                    visitor(node)
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key !in ignoredKeys && key !in cardKeys && !key.contains("adSlot", true) &&
                            key != "promotedSparklesWebRenderer" && key != "promotedVideoRenderer") scan(node.opt(key))
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i))
            }
        }
        contentRoots(root).forEach(::scan)
    }

    /** Main-feed continuations outrank nested Shorts/channel shelf continuations. */
    fun continuations(root: JSONObject): List<String> {
        data class Candidate(val token: String, val shelves: Int, val depth: Int)
        val candidates = mutableListOf<Candidate>()
        fun token(node: Any?): String? {
            if (node !is JSONObject) return null
            node.optString("token").takeIf { it.isNotBlank() }?.let { return it }
            node.optString("continuation").takeIf { it.isNotBlank() }?.let { return it }
            for (key in listOf("continuationEndpoint", "continuationCommand", "innertubeCommand",
                "command", "rendererContext", "commandContext", "onTap")) {
                token(node.opt(key))?.let { return it }
            }
            return null
        }
        fun scan(node: Any?, shelves: Int, depth: Int) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("tabRenderer")?.let { tab ->
                        if (!tab.has("selected") || tab.optBoolean("selected")) scan(tab.opt("content"), shelves, depth + 1)
                        return
                    }
                    for (key in listOf("continuationItemRenderer", "continuationItemViewModel",
                        "nextContinuationData", "reloadContinuationData")) {
                        token(node.opt(key))?.let { candidates += Candidate(it, shelves, depth) }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key !in ignoredKeys && key !in cardKeys && !key.contains("adSlot", true)) {
                            scan(node.opt(key), shelves + if (key in shelfKeys) 1 else 0, depth + 1)
                        }
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i), shelves, depth + 1)
            }
        }
        contentRoots(root).forEach { scan(it, 0, 0) }
        val shelfLevel = candidates.minOfOrNull { it.shelves } ?: return emptyList()
        val main = candidates.filter { it.shelves == shelfLevel }
        val depth = main.minOf { it.depth }
        return main.filter { it.depth == depth }.map { it.token }.distinct()
    }

    private fun watchVideoId(node: Any?): String? {
        if (node !is JSONObject) return null
        for (key in listOf("watchEndpoint", "reelWatchEndpoint")) {
            node.optJSONObject(key)?.optString("videoId")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val keys = node.keys()
        while (keys.hasNext()) watchVideoId(node.opt(keys.next()))?.let { return it }
        return null
    }

    /** Attributed text runs can carry the channel endpoint alongside their character range. */
    private fun findChannelText(node: Any?): Pair<String, String>? {
        when (node) {
            is JSONObject -> {
                val text = extractText(node)
                val runs = node.optJSONArray("commandRuns")
                if (text != null && runs != null) {
                    for (i in 0 until runs.length()) {
                        val run = runs.optJSONObject(i) ?: continue
                        val id = findChannelBrowseId(run) ?: continue
                        val start = run.optInt("startIndex", 0).coerceIn(0, text.length)
                        val end = (start.toLong() + run.optInt("length", text.length).coerceAtLeast(0))
                            .coerceAtMost(text.length.toLong()).toInt()
                        text.substring(start, end).takeIf { it.isNotBlank() }?.let { return id to it }
                    }
                }
                val id = findChannelBrowseId(node.opt("navigationEndpoint"))
                if (id != null && text != null) return id to text
                val keys = node.keys()
                while (keys.hasNext()) findChannelText(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findChannelText(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun findChannelBrowseId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("browseEndpoint")?.optString("browseId")
                    ?.takeIf { it.startsWith("UC") }
                    ?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findChannelBrowseId(node.opt(keys.next()))
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findChannelBrowseId(node.opt(i))
                    if (found != null) return found
                }
            }
        }
        return null
    }

    /**
     * Renderer families put the upload age in different places (publishedTimeText, a tile's
     * second metadata line, a lockup's metadata rows), so the card's own subtree is searched for
     * the first text that reads as an age. Scoped to one card, never the whole response.
     */
    private fun findAgeText(node: Any?, depth: Int = 0): String? {
        if (depth > 8) return null
        when (node) {
            is JSONObject -> {
                extractText(node)?.takeIf { RelativeTimeText.looksLikeAge(it) }?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in ignoredKeys || key in cardKeys || key in ageSkippedKeys) continue
                    findAgeText(node.opt(key), depth + 1)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findAgeText(node.opt(i), depth + 1)?.let { return it }
            }
            is String -> if (RelativeTimeText.looksLikeAge(node)) return node
        }
        return null
    }

    /** Free text that can contain "100 Tage" without being an upload age. */
    private val ageSkippedKeys = setOf("title", "headline", "accessibility", "accessibilityText",
        "accessibilityData", "descriptionSnippet", "detailedMetadataSnippets")

    private fun extractTileLine(metadata: JSONObject?, index: Int): String? {
        val item = metadata?.optJSONArray("lines")
            ?.optJSONObject(index)
            ?.optJSONObject("lineRenderer")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
            ?.optJSONObject("lineItemRenderer")
            ?: return null
        return extractText(item.opt("text"))
    }

    private fun extractText(node: Any?): String? {
        when (node) {
            is String -> return node.takeIf { it.isNotBlank() }
            is JSONObject -> {
                node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
                node.optString("content").takeIf { it.isNotBlank() }?.let { return it }
                val runs = node.optJSONArray("runs") ?: return null
                val sb = StringBuilder()
                for (i in 0 until runs.length()) {
                    sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
                }
                return sb.toString().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun findImageUrl(node: Any?): String? {
        var bestUrl: String? = null
        // Every listed size; the smallest one that is still large enough wins (see pickSource).
        val options = mutableListOf<Triple<String, Int, Int>>()

        fun considerArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val rawUrl = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                options += Triple(normalizeImageUrl(rawUrl), item.optInt("width", 0), item.optInt("height", 0))
            }
        }

        fun scan(value: Any?) {
            when (value) {
                is JSONObject -> {
                    considerArray(value.optJSONArray("thumbnails"))
                    considerArray(value.optJSONArray("sources"))
                    // Some image models expose a direct URL instead of an array.
                    value.optString("url").takeIf { it.isNotBlank() }?.let { raw ->
                        if (bestUrl == null) bestUrl = normalizeImageUrl(raw)
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) scan(value.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until value.length()) scan(value.opt(i))
            }
        }
        scan(node)
        return ThumbnailSizing.pickSource(options) ?: bestUrl
    }

    private fun normalizeImageUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    private fun firstThumbnail(thumbObj: JSONObject?): String? {
        val thumbs = thumbObj?.optJSONArray("thumbnails") ?: return null
        val options = mutableListOf<Triple<String, Int, Int>>()
        for (i in 0 until thumbs.length()) {
            val thumb = thumbs.optJSONObject(i) ?: continue
            val url = thumb.optString("url").takeIf { it.isNotBlank() }?.let(::normalizeImageUrl) ?: continue
            options += Triple(url, thumb.optInt("width", 0), thumb.optInt("height", 0))
        }
        return ThumbnailSizing.pickSource(options)
    }

}
