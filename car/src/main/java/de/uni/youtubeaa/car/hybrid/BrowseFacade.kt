@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car.hybrid

import android.content.Context
import android.support.v4.media.MediaDescriptionCompat
import de.uni.youtubeaa.car.BrowseListCache
import de.uni.youtubeaa.car.WatchHistoryStore
import de.uni.youtubeaa.car.VideoMetadataRefetcher
import de.uni.youtubeaa.car.HeadUnitDiagnostics
import de.uni.youtubeaa.car.isShort
import de.uni.youtubeaa.car.newestFirst
import de.uni.youtubeaa.car.publishedAtMs
import de.uni.youtubeaa.car.toDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed class BrowseOutcome<out T> {
    data class Ok<T>(
        val data: T, val fromPersonal: Boolean = false,
        val hasMore: Boolean = false, val partial: Boolean = false,
        val fetchedAtMs: Long = System.currentTimeMillis()
    ) : BrowseOutcome<T>()
    data class NeedsSignIn(val message: String) : BrowseOutcome<Nothing>()
    data class Error(val message: String) : BrowseOutcome<Nothing>()
}

/**
 * Routes public browse to NewPipeExtractor and personal tabs to InnerTube (TV session).
 */
class BrowseFacade(private val context: Context) {

    private val appContext = context.applicationContext
    private val dependencies = getDependencies(appContext)
    private val sessionStore get() = dependencies.sessionStore
    private val authClient get() = dependencies.authClient
    private val anonymous get() = dependencies.anonymous
    private val innerTube get() = dependencies.innerTube
    private val channelAvatarCache get() = dependencies.channelAvatarCache
    private val metadataRefetcher get() = dependencies.metadataRefetcher

    private class Dependencies(context: Context) {
        val sessionStore = YtSessionStore(context)
        val authClient = YtTvAuthClient()
        val anonymous = AnonymousBrowseRepository()
        val innerTube = InnerTubeClient(sessionStore, authClient)
        val channelAvatarCache = ChannelAvatarCache(context)
        val metadataRefetcher = VideoMetadataRefetcher()
        val subscriptionMutex = Mutex()
        val avatarGate = Semaphore(2)
        val avatarLocks = Array(32) { Mutex() }
    }

    fun isTvSignedIn(): Boolean = sessionStore.isSignedIn()

    fun hasPersonalAccess(): Boolean = isTvSignedIn()

    suspend fun refreshTvAccountProfile(): YtTvTokens? = withContext(Dispatchers.IO) {
        val current = sessionStore.getTokens() ?: return@withContext null
        try {
            val direct = try {
                innerTube.getAccountProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Exception) {
                null
            }
            val fallback = if (direct?.title.isNullOrBlank()) {
                authClient.fetchChannelProfile(innerTube.validAccessToken())
            } else null
            val profile = YtTvAuthClient.ChannelProfile(
                direct?.title ?: fallback?.title, direct?.avatarUrl ?: fallback?.avatarUrl
            )
            sessionStore.updateProfile(current.refreshToken, profile)
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            sessionStore.getTokens()?.takeIf { it.refreshToken == current.refreshToken }
        }
    }

    fun accountDisplayName(): String? =
        if (isTvSignedIn()) AccountProfileParser.displayName(sessionStore.getTokens()?.accountLabel) else null

    suspend fun search(query: String, forceRefresh: Boolean = false): BrowseOutcome<List<MediaDescriptionCompat>> =
        try {
            BrowseOutcome.Ok(anonymous.search(query, forceRefresh))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            BrowseOutcome.Error(e.message ?: "Suche fehlgeschlagen")
        }

    suspend fun home(
        forceRefresh: Boolean = false,
        loadMore: Boolean = false
    ): BrowseOutcome<List<MediaDescriptionCompat>> =
        withContext(Dispatchers.IO) {
            // Signed-in TV sessions can provide the real personalized YouTube Home feed.
            if (isTvSignedIn()) {
                if (!forceRefresh && !loadMore) {
                    // The whole outcome is cached, not just the items: a cache hit must not drop
                    // the "load more" affordance the live page reported.
                    BrowseListCache.get<BrowseOutcome.Ok<List<MediaDescriptionCompat>>>(
                        BrowseListCache.KEY_HOME_PERSONAL
                    )?.let { cached -> return@withContext cached }
                }
                try {
                    // forceRefresh opens a new recommendation session. Callers that only want to
                    // freshen a list the user is already reading must not pass it: that session
                    // is shared by the phone, the projected browse, and the Android Auto browser.
                    val page = innerTube.getHomeFeed(loadMore = loadMore && !forceRefresh)
                    if (page.items.isNotEmpty()) {
                        logDateCoverage("home", page.items)
                        val outcome = BrowseOutcome.Ok(page.items.newestFirst(), fromPersonal = true,
                            hasMore = page.hasMore, partial = page.partial)
                        BrowseListCache.put(BrowseListCache.KEY_HOME_PERSONAL, outcome)
                        return@withContext outcome
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    // Fall through to public content; Home must not become an unusable empty tab.
                }
            }
            try {
                var publicItems = anonymous.getPopular(forceRefresh)
                if (publicItems.isEmpty()) {
                    publicItems = anonymous.search("trending", forceRefresh = true)
                }
                BrowseOutcome.Ok(publicItems)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                BrowseOutcome.Error(e.message ?: "Startseite konnte nicht geladen werden")
            }
        }

    suspend fun popular(forceRefresh: Boolean = false): BrowseOutcome<List<MediaDescriptionCompat>> =
        try {
            var items = anonymous.getPopular(forceRefresh)
            if (items.isEmpty()) items = anonymous.search("trending", forceRefresh = true)
            BrowseOutcome.Ok(items)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            BrowseOutcome.Error(e.message ?: "Beliebt fehlgeschlagen")
        }

    suspend fun channelVideos(
        channelId: String,
        forceRefresh: Boolean = false
    ): BrowseOutcome<List<MediaDescriptionCompat>> =
        try {
            BrowseOutcome.Ok(anonymous.getChannelVideos(channelId, forceRefresh).newestFirst())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            BrowseOutcome.Error(e.message ?: "Kanal fehlgeschlagen")
        }

    private fun logDateCoverage(feed: String, items: List<MediaDescriptionCompat>) {
        HeadUnitDiagnostics.event(
            "BrowseFacade",
            "feed-dates feed=$feed dated=${items.count { it.publishedAtMs() > 0L }}/${items.size}"
        )
    }

    suspend fun history(): BrowseOutcome<List<MediaDescriptionCompat>> =
        BrowseOutcome.Ok(WatchHistoryStore(appContext).getAll().map { it.toDescription() })

    suspend fun subscriptionChannels(forceRefresh: Boolean = false): BrowseOutcome<List<HybridChannel>> =
        withContext(Dispatchers.IO) {
            if (isTvSignedIn()) {
                if (!forceRefresh) {
                    BrowseListCache.get<List<HybridChannel>>(BrowseListCache.KEY_CHANNELS)
                        ?.let { return@withContext BrowseOutcome.Ok(it, fromPersonal = true) }
                }
                try {
                    val channels = channelAvatarCache.enrich(innerTube.getSubscriptionChannels())
                    // An empty strip is never worth pinning for 15 minutes.
                    if (channels.isNotEmpty()) BrowseListCache.put(BrowseListCache.KEY_CHANNELS, channels)
                    return@withContext BrowseOutcome.Ok(channels, fromPersonal = true)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    return@withContext BrowseOutcome.Error(
                        failure.message ?: "Abos konnten nicht geladen werden")
                }
            }
            BrowseOutcome.NeedsSignIn(SIGN_IN_MSG)
        }

    suspend fun channelAvatar(channelId: String): String? = withContext(Dispatchers.IO) {
        channelAvatarCache.get(channelId)?.let { return@withContext it }
        if (!isTvSignedIn()) return@withContext null
        dependencies.avatarLocks[(channelId.hashCode() and Int.MAX_VALUE) % dependencies.avatarLocks.size].withLock {
            channelAvatarCache.get(channelId)?.let { return@withLock it }
            try {
                dependencies.avatarGate.withPermit { innerTube.getChannelAvatar(channelId) }
                    ?.also { channelAvatarCache.put(channelId, it) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null
            }
        }
    }

    private data class CachedSubscriptionFeed(
        val account: String, val outcome: BrowseOutcome.Ok<List<MediaDescriptionCompat>>,
        val chips: List<FeedChip> = emptyList(),
        /** Shorts of the same pages; they have their own tab and no upload date to sort by. */
        val shorts: List<MediaDescriptionCompat> = emptyList()
    )

    /** Filter chips of the last loaded subscriptions feed ("Alle", "Heute", "Videos", ...). */
    fun subscriptionChips(): List<FeedChip> = innerTube.subscriptionChips()

    suspend fun subscriptionFeed(
        forceRefresh: Boolean = false, loadMore: Boolean = false, chipToken: String? = null
    ): BrowseOutcome<List<MediaDescriptionCompat>> = withContext(Dispatchers.IO) {
        dependencies.subscriptionMutex.withLock {
            val account = sessionStore.getTokens()?.refreshToken
            if (account != null) {
                // Only the unfiltered feed is cached; a chip is an explicit request for its page.
                if (!forceRefresh && !loadMore && chipToken == null) {
                    // Read with the long stale TTL so the entry survives for [cachedSubscriptionFeed];
                    // only the age check below decides whether it is still fresh.
                    BrowseListCache.get<CachedSubscriptionFeed>(
                        BrowseListCache.KEY_SUBSCRIPTION_FEED_PERSONAL, ttlMs = SUBSCRIPTION_FEED_STALE_MS
                    )?.takeIf { it.account == account &&
                        System.currentTimeMillis() - it.outcome.fetchedAtMs < SUBSCRIPTION_FEED_TTL_MS
                    }?.let { cached ->
                        innerTube.restoreChips(account, cached.chips)
                        return@withLock cached.outcome
                    }
                }
                try {
                    val page = innerTube.getSubscriptionFeed(
                        loadMore = loadMore && !forceRefresh, chipToken = chipToken)
                    // Shorts go to their own tab. The rest is newest first; cards without a
                    // parsable date keep YouTube's order behind them.
                    val (shorts, videos) = page.items.partition { it.isShort() }
                    val feed = videos.newestFirst()
                    logDateCoverage("subscriptions", feed)
                    check(sessionStore.getTokens()?.refreshToken == account) { "Konto wurde gewechselt" }
                    val outcome = BrowseOutcome.Ok(feed, fromPersonal = true, hasMore = page.hasMore,
                        partial = page.partial, fetchedAtMs = page.fetchedAtMs)
                    if (chipToken == null) {
                        BrowseListCache.put(BrowseListCache.KEY_SUBSCRIPTION_FEED_PERSONAL,
                            CachedSubscriptionFeed(account, outcome, page.chips, shorts))
                    }
                    return@withLock outcome
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    return@withLock BrowseOutcome.Error("Abo-Feed konnte nicht geladen werden. Bitte erneut versuchen.")
                }
            }
            // Without a TV session a chip token is from a previous login and cannot apply.
            if (chipToken != null) return@withLock BrowseOutcome.Error("Filter nicht mehr verfügbar. Bitte „Alle“ wählen.")
            BrowseOutcome.NeedsSignIn(SIGN_IN_MSG)
        }
    }

    /**
     * Shorts of the subscriptions feed (the Abos list leaves them out). They come with the same
     * feed pages, so a refresh reloads the feed; otherwise the last pages' shorts are reused.
     */
    suspend fun subscriptionShorts(forceRefresh: Boolean = false): BrowseOutcome<List<MediaDescriptionCompat>> {
        val account = sessionStore.getTokens()?.refreshToken ?: return BrowseOutcome.NeedsSignIn(SIGN_IN_MSG)
        fun cached() = BrowseListCache.get<CachedSubscriptionFeed>(
            BrowseListCache.KEY_SUBSCRIPTION_FEED_PERSONAL, ttlMs = SUBSCRIPTION_FEED_STALE_MS
        )?.takeIf { it.account == account }
        if (!forceRefresh) cached()?.let { return BrowseOutcome.Ok(it.shorts, fromPersonal = true, fetchedAtMs = it.outcome.fetchedAtMs) }
        return when (val feed = subscriptionFeed(forceRefresh = forceRefresh)) {
            is BrowseOutcome.Ok -> BrowseOutcome.Ok(cached()?.shorts.orEmpty(), fromPersonal = true, fetchedAtMs = feed.fetchedAtMs)
            is BrowseOutcome.NeedsSignIn -> feed
            is BrowseOutcome.Error -> BrowseOutcome.Error("Shorts konnten nicht geladen werden. Bitte erneut versuchen.")
        }
    }

    /**
     * Last unfiltered subscriptions feed of the current account, even when no longer fresh, so a
     * screen can show it at once and replace it when the network answers (as the official app).
     */
    fun cachedSubscriptionFeed(): BrowseOutcome.Ok<List<MediaDescriptionCompat>>? {
        val account = sessionStore.getTokens()?.refreshToken ?: return null
        return BrowseListCache.get<CachedSubscriptionFeed>(
            BrowseListCache.KEY_SUBSCRIPTION_FEED_PERSONAL, ttlMs = SUBSCRIPTION_FEED_STALE_MS
        )?.takeIf { it.account == account }?.outcome
    }

    suspend fun repairMetadataIfNeeded(item: MediaDescriptionCompat): MediaDescriptionCompat =
        metadataRefetcher.repairIfNeeded(item)

    fun sessionStore(): YtSessionStore = sessionStore

    /** The process-wide list state of Startseite and Abos; see [FeedStore]. */
    fun feedStore(): FeedStore<MediaDescriptionCompat> = sharedFeedStore ?: synchronized(BrowseFacade) {
        sharedFeedStore ?: FeedStore(
            source = object : FeedStore.Source<MediaDescriptionCompat> {
                override fun account() = sessionStore.getTokens()?.refreshToken
                override suspend fun home(forceRefresh: Boolean, loadMore: Boolean) = this@BrowseFacade.home(forceRefresh, loadMore)
                override suspend fun subscriptionFeed(forceRefresh: Boolean, loadMore: Boolean, chipToken: String?) =
                    this@BrowseFacade.subscriptionFeed(forceRefresh, loadMore, chipToken)
                override suspend fun subscriptionChannels(forceRefresh: Boolean) = this@BrowseFacade.subscriptionChannels(forceRefresh)
            },
            key = { it.mediaId.orEmpty() },
            visibleKey = { listOf(it.mediaId, it.title?.toString(), it.subtitle?.toString(), it.iconUri?.toString()) }
        ).also { sharedFeedStore = it }
    }

    fun authClient(): YtTvAuthClient = authClient

    companion object {
        @Volatile
        private var sharedDependencies: Dependencies? = null
        @Volatile
        private var sharedFeedStore: FeedStore<MediaDescriptionCompat>? = null

        private fun getDependencies(context: Context): Dependencies =
            sharedDependencies ?: synchronized(this) {
                sharedDependencies ?: Dependencies(context.applicationContext).also {
                    sharedDependencies = it
                }
            }

        private const val SUBSCRIPTION_FEED_TTL_MS = 60_000L
        /** How long an outdated feed may still be shown while the fresh one loads. */
        private const val SUBSCRIPTION_FEED_STALE_MS = 30 * 60_000L

        const val SIGN_IN_MSG =
            "Für Abos & persönlichen Feed: YouTube-Konto verknüpfen (TV-Code). Suche & Trends gehen anonym."
    }
}
