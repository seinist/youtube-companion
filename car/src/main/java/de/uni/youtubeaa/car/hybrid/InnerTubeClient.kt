@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car.hybrid

import de.uni.youtubeaa.car.HeadUnitDiagnostics
import de.uni.youtubeaa.car.SharedHttp

import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import de.uni.youtubeaa.car.setBrowseExtras
import de.uni.youtubeaa.car.awaitBytes
import de.uni.youtubeaa.car.publishedAtMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HybridChannel(
    val channelId: String,
    val title: String,
    val thumbnailUrl: String? = null
)

/**
 * Minimal InnerTube client for personal subscription data using a TV OAuth access token.
 */
class InnerTubeClient(
    private val sessionStore: YtSessionStore,
    private val authClient: YtTvAuthClient = YtTvAuthClient(),
    private val http: OkHttpClient = defaultClient()
) {

    /**
     * The full subscription list. FEchannels pages like every other browse response, so a single
     * request returned only its first page and silently cut long subscription lists short.
     * Followed to the end rather than exposed as "load more": the strip wants to be complete.
     */
    suspend fun getSubscriptionChannels(): List<HybridChannel> = withContext(Dispatchers.IO) {
        // Pages are fetched one after another by necessity: each continuation token only arrives
        // with the previous page. FeedPager already de-duplicates by channel id.
        val pager = FeedPager<HybridChannel>(key = { it.channelId }) { token ->
            val json = if (token == null) browse("FEchannels") else browseContinuation(token)
            FeedPager.Page(parseChannels(json), InnerTubeFeedParser.continuations(json))
        }
        val snapshot = pager.load(MAX_CHANNEL_PAGES)
        // FeedPager reports a failed fetch instead of throwing. A failed first page must still
        // surface as an error, or the facade caches an empty list as success and never falls
        // back to the legacy login (stale TV session, network loss).
        if (snapshot.items.isEmpty() && snapshot.partial) error("Abo-Kanäle konnten nicht geladen werden")
        snapshot.items
    }

    data class HomePage(val items: List<MediaDescriptionCompat>, val hasMore: Boolean, val partial: Boolean)

    /** The pager already accumulates and de-duplicates every page it loaded. */
    private data class HomeSession(
        val account: String,
        val pager: FeedPager<MediaDescriptionCompat>
    )
    /** Separate from subscriptionMutex: a slow two-client Abos load must not hold up Home. */
    private val homeMutex = Mutex()
    private var homeSession: HomeSession? = null

    /** Home pages like the subscriptions feed; [loadMore] continues the session already open. */
    suspend fun getHomeFeed(loadMore: Boolean = false): HomePage = withContext(Dispatchers.IO) {
        homeMutex.withLock {
            val account = sessionStore.getTokens()?.refreshToken ?: error("Nicht angemeldet")
            // Never continue another account's recommendations after a switch.
            val session = homeSession?.takeIf { loadMore && it.account == account } ?: HomeSession(
                account,
                FeedPager(
                    key = { it.mediaId.orEmpty() },
                    onFailure = { continuation, e ->
                        HeadUnitDiagnostics.warning(
                            "InnerTubeClient",
                            "home-page-failed continuation=$continuation error=${e.javaClass.simpleName}: ${e.message}",
                            e
                        )
                    }
                ) { token ->
                    check(sessionStore.getTokens()?.refreshToken == account) { "Konto wurde gewechselt" }
                    val json = if (token == null) browse("FEwhat_to_watch") else browseContinuation(token)
                    FeedPager.Page(parseVideos(json), InnerTubeFeedParser.continuations(json))
                }
            ).also { homeSession = it }
            val snapshot = session.pager.load(1)
            check(sessionStore.getTokens()?.refreshToken == account) { "Konto wurde gewechselt" }
            HomePage(snapshot.items, snapshot.hasMore, snapshot.partial)
        }
    }

    data class SubscriptionPage(
        val items: List<MediaDescriptionCompat>, val hasMore: Boolean,
        val partial: Boolean, val fetchedAtMs: Long,
        val chips: List<FeedChip> = emptyList()
    )

    private data class SubscriptionSession(
        val account: String, val chipToken: String?, val fetchedAtMs: Long,
        val pagers: List<FeedPager<MediaDescriptionCompat>>,
        val items: LinkedHashMap<String, MediaDescriptionCompat> = linkedMapOf()
    )
    private val subscriptionMutex = Mutex()
    /**
     * One paging session per filter (null = unfiltered feed). A single shared session meant that
     * picking a chip and then "Alle" again restarted the unfiltered feed at page 1 on the next
     * "load more" — on every surface, since this client is process-wide.
     */
    private val subscriptionSessions = HashMap<String?, SubscriptionSession>()

    private data class ChipSet(val account: String, val chips: List<FeedChip>)
    /**
     * Chips only ship with the unfiltered feed; a chip's own continuation response does not
     * repeat them. Remembering the last set keeps the filter row on screen while a chip is active.
     * Bound to the account: another account's chip tokens must never be offered or sent.
     */
    @Volatile
    private var lastChips: ChipSet? = null

    fun subscriptionChips(): List<FeedChip> {
        val current = sessionStore.getTokens()?.refreshToken ?: return emptyList()
        return lastChips?.takeIf { it.account == current }?.chips.orEmpty()
    }

    /** Re-seeds the chip row from a cached feed so it survives a process restart. */
    fun restoreChips(account: String, chips: List<FeedChip>) {
        if (chips.isNotEmpty()) lastChips = ChipSet(account, chips)
    }

    /**
     * @param chipToken continuation of a selected filter chip, or null for the unfiltered feed.
     * The feed comes from the ANDROID client only, as in the official app; its chip tokens
     * belong to that client too.
     */
    suspend fun getSubscriptionFeed(
        loadMore: Boolean = false,
        chipToken: String? = null
    ): SubscriptionPage = withContext(Dispatchers.IO) {
        subscriptionMutex.withLock {
            val account = sessionStore.getTokens()?.refreshToken ?: error("Nicht angemeldet")
            // Only the ANDROID feed: merging the TV client's feed added cards at guessed positions.
            val clients = listOf(CHIP_CLIENT)
            // Sessions of another account are useless and must not be continued.
            subscriptionSessions.values.removeAll { it.account != account }
            val session = subscriptionSessions[chipToken]
                ?.takeIf { loadMore }
                ?: SubscriptionSession(account, chipToken, System.currentTimeMillis(), clients.map { client ->
                    var visitorData: String? = null
                    // Continuations must go to the client that issued them.
                    var pageClient = client
                    FeedPager<MediaDescriptionCompat>(
                        key = { it.mediaId.orEmpty() },
                        onFailure = { continuation, e ->
                            HeadUnitDiagnostics.warning(
                                "InnerTubeClient",
                                "subscription-page-failed continuation=$continuation chip=${chipToken != null} " +
                                    "error=${e.javaClass.simpleName}: ${e.message}",
                                e
                            )
                        }
                    ) { token ->
                        check(sessionStore.getTokens()?.refreshToken == account) { "Konto wurde gewechselt" }
                        val continuation = token ?: chipToken
                        val json = if (continuation == null) {
                            try {
                                browse("FEsubscriptions", client).also { pageClient = client }
                            } catch (e: IllegalStateException) {
                                if (!e.isHttp400()) throw e
                                // The TV device-code token is not always accepted by the ANDROID
                                // client (HTTP 400 "invalid argument" on a OnePlus, 2026-10-13):
                                // the whole Abo tab failed. TV's feed has no chips, but videos.
                                HeadUnitDiagnostics.warning("InnerTubeClient", "subscription-feed android-rejected fallback=TVHTML5")
                                browse("FEsubscriptions", TV_CLIENT).also { pageClient = TV_CLIENT }
                            }
                        } else {
                            browseContinuation(continuation, pageClient, visitorData)
                        }
                        check(sessionStore.getTokens()?.refreshToken == account) { "Konto wurde gewechselt" }
                        visitorData = json.optJSONObject("responseContext")?.optString("visitorData")
                            ?.takeIf { it.isNotBlank() } ?: visitorData
                        if (pageClient == CHIP_CLIENT) {
                            InnerTubeFeedParser.chips(json).takeIf { it.isNotEmpty() }
                                ?.let { lastChips = ChipSet(account, it) }
                        }
                        FeedPager.Page(parseVideos(json), InnerTubeFeedParser.continuations(json))
                    }
                }).also { subscriptionSessions[chipToken] = it }
            // One page per client: a second page waits for the first one's continuation token, so
            // it doubled the time before anything appeared. Remaining tokens serve Load more.
            // The clients are independent requests and run in parallel.
            val pages = coroutineScope { session.pagers.map { async { it.load(1) } }.awaitAll() }
            check(sessionStore.getTokens()?.refreshToken == account) { "Konto wurde gewechselt" }
            // YouTube's own order stays; cards only the other client knows are fitted in by age.
            // New cards are appended behind those already shown, so neither a refresh nor
            // "load more" reshuffles rows (the car restores its scroll position by index).
            FeedMerge.merge(
                feeds = pages.map { it.items },
                alreadyShown = session.items.keys,
                key = { it.mediaId.orEmpty() },
                dateOf = { it.publishedAtMs() }
            ).forEach { item -> session.items[item.mediaId.orEmpty()] = item }
            val items = session.items.values.toList()
            if (items.isEmpty() && pages.all { it.partial }) error("Abo-Feed konnte nicht geladen werden")
            SubscriptionPage(items, pages.any { it.hasMore }, pages.any { it.partial },
                session.fetchedAtMs, lastChips?.takeIf { it.account == account }?.chips.orEmpty())
        }
    }

    /**
     * Resolves the channel avatar from the channel page itself. FEchannels does not always
     * include avatar artwork for every subscription in the TV client response.
     */
    suspend fun getChannelAvatar(channelId: String): String? = withContext(Dispatchers.IO) {
        if (!channelId.startsWith("UC")) return@withContext null
        channelAvatarCache[channelId]?.let { return@withContext it }
        val avatar = findChannelAvatarUrl(browse(channelId))
        if (!avatar.isNullOrBlank()) channelAvatarCache[channelId] = avatar
        avatar
    }

    private suspend fun browseContinuation(token: String, client: ClientProfile = TV_CLIENT, visitorData: String? = null): JSONObject {
        val access = validAccessToken()
        val payload = JSONObject()
            .put("context", JSONObject().put("client", clientContext(client).also { if (visitorData != null) it.put("visitorData", visitorData) }))
            .put("continuation", token)
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer $access")
            .header("X-YouTube-Client-Name", client.numericName)
            .header("X-YouTube-Client-Version", client.version)
            .header("Content-Type", "application/json")
            .header("User-Agent", client.userAgent)
            .also { if (visitorData != null) it.header("X-Goog-Visitor-Id", visitorData) }
            .build()
        http.newCall(request).awaitBytes().let { response ->
            val text = response.bytes.toString(Charsets.UTF_8)
            if (!response.isSuccessful) {
                throw IllegalStateException("InnerTube continuation failed: HTTP ${response.code} ${text.take(200)}")
            }
            return JSONObject(text).also { check(!it.has("error")) { "YouTube hat den Feed abgelehnt" } }
        }
    }

    private suspend fun browse(browseId: String, client: ClientProfile = TV_CLIENT): JSONObject {
        val access = validAccessToken()
        val payload = JSONObject()
            .put("context", JSONObject().put("client", clientContext(client)))
            .put("browseId", browseId)
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .post(body)
            .header("Authorization", "Bearer $access")
            .header("X-YouTube-Client-Name", client.numericName)
            .header("X-YouTube-Client-Version", client.version)
            .header("Content-Type", "application/json")
            .header("User-Agent", client.userAgent)
            .build()
        http.newCall(request).awaitBytes().let { response ->
            val text = response.bytes.toString(Charsets.UTF_8)
            if (!response.isSuccessful) {
                throw IllegalStateException("InnerTube browse failed: HTTP ${response.code} ${text.take(200)}")
            }
            return JSONObject(text).also { check(!it.has("error")) { "YouTube hat den Feed abgelehnt" } }
        }
    }

    private fun IllegalStateException.isHttp400(): Boolean =
        message?.startsWith("InnerTube browse failed: HTTP 400") == true

    private fun clientContext(client: ClientProfile): JSONObject = JSONObject()
        .put("clientName", client.name)
        .put("clientVersion", client.version)
        .put("hl", "de")
        .put("gl", "DE")
        .put("userAgent", client.userAgent)
        .also { obj ->
            if (client.name == "ANDROID") {
                obj.put("androidSdkVersion", 30)
                    .put("osName", "Android")
                    .put("osVersion", "11")
            }
        }

    internal suspend fun validAccessToken(): String = tokenMutex.withLock {
        val tokens = sessionStore.getTokens() ?: throw IllegalStateException("Nicht angemeldet")
        if (!tokens.isAccessExpired()) return@withLock tokens.accessToken
        val refreshed = authClient.refresh(tokens)
        if (!sessionStore.saveIfCurrent(tokens.refreshToken, refreshed)) {
            throw IllegalStateException("Konto wurde gewechselt")
        }
        refreshed.accessToken
    }

    /** TVHTML5 exposes the signed-in account via `accounts_list`; `get_account_menu` is the WEB-style fallback. */
    suspend fun getAccountProfile(): YtTvAuthClient.ChannelProfile? =
        fetchAccountProfile("accounts_list") ?: fetchAccountProfile("get_account_menu")

    private suspend fun fetchAccountProfile(endpoint: String): YtTvAuthClient.ChannelProfile? = withContext(Dispatchers.IO) {
        val access = validAccessToken()
        val payload = JSONObject().put("context", JSONObject().put("client", clientContext(TV_CLIENT)))
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/account/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer $access")
            .header("X-YouTube-Client-Name", TV_CLIENT.numericName)
            .header("X-YouTube-Client-Version", TV_CLIENT.version)
            .header("User-Agent", TV_CLIENT.userAgent)
            .build()
        http.newCall(request).awaitBytes().let { response ->
            if (!response.isSuccessful) return@withContext null
            AccountProfileParser.parse(JSONObject(response.bytes.toString(Charsets.UTF_8)))
        }
    }

    /**
     * The TVHTML5 client wraps every home/subscriptions item in a `tileRenderer` whose
     * id/title/thumbnail/byline live in three separate sub-objects (onSelectCommand,
     * metadata.tileMetadataRenderer, header.tileHeaderRenderer) rather than as siblings on
     * one object, so a flat field-name walk (as WEB-client renderers would allow) always
     * misses title+thumbnail and silently falls back to the raw id.
     */
    private fun parseVideos(root: JSONObject): List<MediaDescriptionCompat> =
        InnerTubeFeedParser.videos(root).map { card ->
            MediaDescriptionCompat.Builder().setMediaId(card.id).setTitle(card.title)
                .setSubtitle(card.channel).setIconUri(card.thumbnailUrl?.let(Uri::parse))
                .setBrowseExtras(
                    publishedAtMs = RelativeTimeText.parse(card.publishedText),
                    channelId = card.channelId,
                    isShort = card.isShort
                ).build()
        }

    private fun parseChannels(root: JSONObject): List<HybridChannel> {
        val out = ArrayList<HybridChannel>()
        val seenChannelIds = HashSet<String>()
        fun addChannel(channelId: String, title: String?, thumbnailUrl: String?) {
            if (!channelId.startsWith("UC") || !seenChannelIds.add(channelId)) return
            out += HybridChannel(
                channelId = channelId,
                title = title?.takeIf { it.isNotBlank() } ?: channelId,
                thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() }
            )
        }

        walk(root) { obj ->
            obj.optJSONObject("tileRenderer")?.let { tile ->
                val channelId = tile.optString("contentId").ifBlank {
                    tile.optJSONObject("onSelectCommand")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId")
                        .orEmpty()
                }
                val metadata = tile.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
                val title = extractText(metadata?.opt("title"))
                val header = tile.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
                val thumb = firstThumbnail(header?.optJSONObject("thumbnail"))
                    ?: findAvatarLikeUrl(header)
                    ?: findAvatarLikeUrl(tile)
                    ?: findThumbnailUrl(header)
                addChannel(channelId, title, thumb)
            }

            val classicKeys = arrayOf("channelRenderer", "gridChannelRenderer")
            for (key in classicKeys) {
                val renderer = obj.optJSONObject(key) ?: continue
                val channelId = renderer.optString("channelId").ifBlank {
                    renderer.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId")
                        .orEmpty()
                }
                val title = extractText(renderer.opt("title"))
                val thumb = firstThumbnail(renderer.optJSONObject("thumbnail"))
                    ?: findAvatarLikeUrl(renderer)
                addChannel(channelId, title, thumb)
            }
        }
        return out
    }


    private fun walk(node: Any?, visitor: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visitor(node)
                val keys = node.keys()
                while (keys.hasNext()) {
                    walk(node.opt(keys.next()), visitor)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) walk(node.opt(i), visitor)
            }
        }
    }

    private fun extractText(node: Any?): String? {
        when (node) {
            is String -> return node.takeIf { it.isNotBlank() }
            is JSONObject -> {
                node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
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


    private fun findChannelAvatarUrl(root: JSONObject): String? {
        var found: String? = null
        walk(root) { obj ->
            if (found != null) return@walk

            // Classic channel headers.
            obj.optJSONObject("channelMetadataRenderer")
                ?.opt("avatar")
                ?.let(::findImageUrl)
                ?.let { found = it; return@walk }

            obj.optJSONObject("c4TabbedHeaderRenderer")
                ?.opt("avatar")
                ?.let(::findImageUrl)
                ?.let { found = it; return@walk }

            obj.optJSONObject("carouselHeaderRenderer")
                ?.opt("avatar")
                ?.let(::findImageUrl)
                ?.let { found = it; return@walk }

            // Current WEB/TV page headers commonly use:
            // pageHeaderRenderer -> content -> pageHeaderViewModel -> image ->
            // decoratedAvatarViewModel -> avatar -> avatarViewModel -> image -> sources[]
            obj.optJSONObject("pageHeaderRenderer")
                ?.opt("content")
                ?.let(::findAvatarLikeUrl)
                ?.let { found = it; return@walk }

            obj.optJSONObject("pageHeaderViewModel")
                ?.let(::findAvatarLikeUrl)
                ?.let { found = it; return@walk }

            obj.optJSONObject("decoratedAvatarViewModel")
                ?.let(::findImageUrl)
                ?.let { found = it; return@walk }

            obj.optJSONObject("avatarViewModel")
                ?.let(::findImageUrl)
                ?.let { found = it; return@walk }

            // Renderer names move often. Restrict the generic fallback to avatar/profile/channel
            // thumbnail branches so banners and video thumbnails cannot win by being larger.
            val keys = obj.keys()
            while (keys.hasNext() && found == null) {
                val key = keys.next()
                if (!key.contains("avatar", ignoreCase = true) &&
                    !key.contains("channelThumbnail", ignoreCase = true) &&
                    !key.contains("profile", ignoreCase = true)) continue
                findImageUrl(obj.opt(key))?.let { found = it }
            }
        }
        return found
    }

    private fun findAvatarLikeUrl(node: Any?): String? {
        var found: String? = null
        fun scan(value: Any?, branchLooksLikeAvatar: Boolean) {
            if (found != null) return
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext() && found == null) {
                        val key = keys.next()
                        val child = value.opt(key)
                        val avatarBranch = branchLooksLikeAvatar ||
                            key.contains("avatar", ignoreCase = true) ||
                            key.contains("profile", ignoreCase = true) ||
                            key.contains("channelThumbnail", ignoreCase = true)
                        if (avatarBranch) {
                            val candidate = findImageUrl(child)
                            if (!candidate.isNullOrBlank()) {
                                found = candidate
                                return
                            }
                        }
                        scan(child, avatarBranch)
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) scan(value.opt(i), branchLooksLikeAvatar)
            }
        }
        scan(node, false)
        return found
    }

    /**
     * Reads both legacy Thumbnail objects (`thumbnails`) and newer ViewModel images (`sources`).
     * The latter is what current pageHeader/avatarViewModel responses use.
     */
    private fun findImageUrl(node: Any?): String? {
        var bestUrl: String? = null
        var bestArea = -1L

        fun considerArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val rawUrl = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val url = normalizeImageUrl(rawUrl)
                val width = item.optLong("width", 0L)
                val height = item.optLong("height", 0L)
                val area = width * height
                if (bestUrl == null || area >= bestArea) {
                    bestUrl = url
                    bestArea = area
                }
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
        return bestUrl
    }

    private fun normalizeImageUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    private fun findThumbnailUrl(node: Any?): String? = findImageUrl(node)

    private fun firstThumbnail(thumbObj: JSONObject?): String? {
        val thumbs = thumbObj?.optJSONArray("thumbnails") ?: return null
        var bestUrl: String? = null
        var bestArea = -1L
        for (i in 0 until thumbs.length()) {
            val thumb = thumbs.optJSONObject(i) ?: continue
            val url = thumb.optString("url").takeIf { it.isNotBlank() }?.let(::normalizeImageUrl) ?: continue
            val width = thumb.optLong("width", 0L)
            val height = thumb.optLong("height", 0L)
            val area = width * height
            if (bestUrl == null || area >= bestArea) {
                bestUrl = url
                bestArea = area
            }
        }
        return bestUrl
    }

    private data class ClientProfile(
        val name: String,
        val numericName: String,
        val version: String,
        val userAgent: String
    )

    companion object {
        private val tokenMutex = Mutex()
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val TV_CLIENT = ClientProfile(
            name = "TVHTML5",
            numericName = "7",
            version = "7.20240701.00.00",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        )
        /** The client whose chip tokens are used; its responses carry the chip cloud. */
        private val CHIP_CLIENT get() = ANDROID_CLIENT
        // Keep this isolated so it can be bumped without touching the parser/request logic.
        private val ANDROID_CLIENT = ClientProfile(
            name = "ANDROID",
            numericName = "3",
            version = "21.26.364",
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
        )
        /** Bound on an unbounded continuation chain; far beyond any realistic subscription count. */
        private const val MAX_CHANNEL_PAGES = 20
        private val channelAvatarCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun defaultClient(): OkHttpClient =
            SharedHttp.builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
