package de.uni.youtubeaa.car.hybrid

import de.uni.youtubeaa.car.StableListMerge
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that decides what Startseite and Abos show, for every surface in the process
 * (phone, projected car UI, Android Auto browser). The rules are the phone's, which is the
 * reference behaviour:
 *
 * - Startseite is loaded once and then stays the list the user is reading. It only grows by
 *   "more" (appended, nothing moves) and is only replaced by an explicit refresh.
 * - Abos show the remembered feed at once. Older than [SUBSCRIPTION_VISIBLE_TTL_MS], the feed is
 *   fetched silently and replaces the list only if something visible changed; a failed silent
 *   fetch changes nothing. A filter chip is an explicit request and never refreshed silently.
 * - The channel strip is loaded once and kept; only an explicit refresh fetches it again, and a
 *   failed fetch never removes it.
 * - "More" only appends; a failed page keeps the list and marks it partial so the row offers a
 *   retry.
 * - Unlike the phone used to, a silent refresh after "more" keeps the pages loaded below the
 *   first one: only the first page is replaced, the rest stays where it is.
 *
 * Each feed is serialized by its own mutex, so concurrent callers (AA re-subscribing, the phone
 * prefetching) join one fetch instead of racing snapshots against each other.
 */
class FeedStore<V>(
    private val source: Source<V>,
    private val key: (V) -> String,
    private val visibleKey: (V) -> Any?,
    private val now: () -> Long = System::currentTimeMillis
) {
    interface Source<V> {
        /** Identifies the signed-in account; a change drops every remembered list. */
        fun account(): String?
        suspend fun home(forceRefresh: Boolean, loadMore: Boolean): BrowseOutcome<List<V>>
        suspend fun subscriptionFeed(forceRefresh: Boolean, loadMore: Boolean, chipToken: String?): BrowseOutcome<List<V>>
        suspend fun subscriptionChannels(forceRefresh: Boolean): BrowseOutcome<List<HybridChannel>>
    }

    data class FeedState<V>(
        val videos: List<V> = emptyList(),
        val channels: List<HybridChannel> = emptyList(),
        val hasMore: Boolean = false,
        val partial: Boolean = false,
        /** The list could not be loaded at all; shown instead of an empty page. */
        val error: String? = null,
        val needsSignIn: String? = null,
        /** The last refresh failed while an older list stays on screen. */
        val refreshFailed: Boolean = false,
        /** Filter the videos belong to; null is the unfiltered feed. */
        val chip: FeedChip? = null,
        /** Server time of the feed data, drives the silent refresh. 0 = never loaded. */
        val fetchedAtMs: Long = 0L,
        /** Wall time the visible list last changed. */
        val updatedAtMs: Long = 0L,
        val loaded: Boolean = false,
        /** Size of the first page within [videos]; everything after it came from "more". */
        val firstPageSize: Int = 0
    )

    private val homeState = MutableStateFlow(FeedState<V>())
    private val subscriptionState = MutableStateFlow(FeedState<V>())
    val home: StateFlow<FeedState<V>> = homeState
    val subscriptions: StateFlow<FeedState<V>> = subscriptionState

    private val homeMutex = Mutex()
    private val subscriptionMutex = Mutex()
    private var account: String? = null
    /** Set once the channel strip was answered (even empty), as the phone remembers it. */
    private var channelsLoaded = false

    // ---- Startseite ----

    /** Startseite as the user left it; fetched only the first time. */
    suspend fun loadHome(): FeedState<V> = homeMutex.withLock {
        checkAccount()
        if (!homeState.value.loaded) applyHome(source.home(forceRefresh = false, loadMore = false), replace = true)
        homeState.value
    }

    suspend fun loadMoreHome(): FeedState<V> = homeMutex.withLock {
        checkAccount()
        applyHome(source.home(forceRefresh = false, loadMore = true), replace = false)
        homeState.value
    }

    suspend fun refreshHome(): FeedState<V> = homeMutex.withLock {
        checkAccount()
        applyHome(source.home(forceRefresh = true, loadMore = false), replace = true)
        homeState.value
    }

    private fun applyHome(outcome: BrowseOutcome<List<V>>, replace: Boolean) {
        val old = homeState.value
        homeState.value = when (outcome) {
            is BrowseOutcome.Ok -> {
                val videos = if (replace || old.videos.isEmpty()) outcome.data
                else StableListMerge.appendNew(old.videos, outcome.data, key)
                old.copy(videos = videos, hasMore = outcome.hasMore, partial = outcome.partial,
                    error = null, needsSignIn = null, refreshFailed = false, fetchedAtMs = outcome.fetchedAtMs,
                    updatedAtMs = if (visibleKeys(videos) != visibleKeys(old.videos)) now() else old.updatedAtMs,
                    loaded = true)
            }
            // A list on screen stays; only an empty page shows the failure.
            is BrowseOutcome.Error ->
                if (old.videos.isNotEmpty()) old.copy(refreshFailed = replace, partial = !replace || old.partial)
                else old.copy(error = outcome.message, loaded = false)
            is BrowseOutcome.NeedsSignIn -> FeedState(needsSignIn = outcome.message)
        }
    }

    // ---- Abos ----

    /**
     * Abos as last shown; the first call fetches channels and feed together. Call
     * [revalidateSubscriptions] afterwards (not awaited by the UI) for the silent refresh.
     */
    suspend fun loadSubscriptions(): FeedState<V> = subscriptionMutex.withLock {
        checkAccount()
        val old = subscriptionState.value
        if (old.loaded && channelsLoaded) return@withLock old
        coroutineScope {
            val channels = async { if (!channelsLoaded) source.subscriptionChannels(false) else null }
            val feed = async { if (!old.loaded) source.subscriptionFeed(false, false, old.chip?.token) else null }
            applyChannels(channels.await())
            feed.await()?.let { applyFeed(it, chip = old.chip, replace = true, silent = false) }
        }
        subscriptionState.value
    }

    /** The phone's silent refresh: only after the TTL, only the unfiltered feed, only on change. */
    suspend fun revalidateSubscriptions(): FeedState<V> {
        // A running load or "more" is newer than anything this would fetch.
        if (!subscriptionMutex.tryLock()) return subscriptionState.value
        try {
            checkAccount()
            val old = subscriptionState.value
            if (!old.loaded || old.chip != null) return old
            val age = now() - old.fetchedAtMs
            if (old.fetchedAtMs != 0L && age in 0 until SUBSCRIPTION_VISIBLE_TTL_MS) return old
            applyFeed(source.subscriptionFeed(true, false, null), chip = null, replace = true, silent = true)
            return subscriptionState.value
        } finally {
            subscriptionMutex.unlock()
        }
    }

    suspend fun loadMoreSubscriptions(): FeedState<V> = subscriptionMutex.withLock {
        checkAccount()
        val old = subscriptionState.value
        when (val outcome = source.subscriptionFeed(false, true, old.chip?.token)) {
            is BrowseOutcome.Ok -> applyFeed(outcome, chip = old.chip, replace = false, silent = false)
            else -> subscriptionState.value = old.copy(partial = true)
        }
        subscriptionState.value
    }

    /** Explicit refresh (pull-to-refresh, "Aktualisieren"): channels and the current filter. */
    suspend fun refreshSubscriptions(): FeedState<V> = subscriptionMutex.withLock {
        checkAccount()
        val chip = subscriptionState.value.chip
        coroutineScope {
            val channels = async { source.subscriptionChannels(true) }
            val feed = async { source.subscriptionFeed(true, false, chip?.token) }
            applyChannels(channels.await())
            applyFeed(feed.await(), chip = chip, replace = true, silent = false, explicit = true)
        }
        subscriptionState.value
    }

    /** Switches to a filter chip; a chip without token is the unfiltered feed. */
    suspend fun selectChip(chip: FeedChip?): FeedState<V> = subscriptionMutex.withLock {
        checkAccount()
        val target = chip?.takeIf { it.token != null }
        if (target?.title == subscriptionState.value.chip?.title && subscriptionState.value.loaded) {
            return@withLock subscriptionState.value
        }
        applyFeed(source.subscriptionFeed(false, false, target?.token), chip = target, replace = true, silent = false)
        subscriptionState.value
    }

    private fun applyChannels(outcome: BrowseOutcome<List<HybridChannel>>?) {
        if (outcome is BrowseOutcome.Ok) channelsLoaded = true
        if (outcome is BrowseOutcome.Ok && outcome.data.isNotEmpty()) {
            val old = subscriptionState.value
            if (outcome.data != old.channels) subscriptionState.value = old.copy(channels = outcome.data, updatedAtMs = now())
        }
    }

    private fun applyFeed(
        outcome: BrowseOutcome<List<V>>, chip: FeedChip?, replace: Boolean, silent: Boolean, explicit: Boolean = false
    ) {
        val old = subscriptionState.value
        val sameFilter = old.chip?.title == chip?.title
        subscriptionState.value = when (outcome) {
            is BrowseOutcome.Ok -> {
                val keepMore = silent && sameFilter && old.videos.size > old.firstPageSize
                val videos = when {
                    !replace && sameFilter && old.videos.isNotEmpty() -> StableListMerge.appendNew(old.videos, outcome.data, key)
                    keepMore -> {
                        // New first page on top, the pages from "more" stay below it.
                        val fresh = outcome.data.mapTo(HashSet(), key)
                        outcome.data + old.videos.drop(old.firstPageSize).filter { key(it) !in fresh }
                    }
                    else -> outcome.data
                }
                val firstPageSize = when {
                    !replace && sameFilter && old.videos.isNotEmpty() -> old.firstPageSize
                    else -> outcome.data.size
                }
                // The paging flags of a silent first-page fetch say nothing about the pages below.
                val hasMore = if (keepMore) old.hasMore else outcome.hasMore
                val partial = if (keepMore) old.partial else outcome.partial
                val changed = !sameFilter || visibleKeys(videos) != visibleKeys(old.videos) ||
                    old.hasMore != hasMore || old.partial != partial
                if (silent && !changed) {
                    // Same content: keep the shown list (and its identity), only note the new age.
                    old.copy(fetchedAtMs = outcome.fetchedAtMs, refreshFailed = false)
                } else {
                    old.copy(videos = videos, hasMore = hasMore, partial = partial,
                        error = null, needsSignIn = null, refreshFailed = false, chip = chip,
                        fetchedAtMs = outcome.fetchedAtMs, updatedAtMs = if (changed) now() else old.updatedAtMs,
                        loaded = true, firstPageSize = firstPageSize)
                }
            }
            is BrowseOutcome.Error -> when {
                silent -> old
                // Only the same filter's list may stand in; another filter's would be plainly wrong.
                sameFilter && old.videos.isNotEmpty() ->
                    old.copy(partial = true, refreshFailed = explicit || old.refreshFailed)
                else -> old.copy(videos = emptyList(), chip = chip, hasMore = false, partial = false,
                    error = outcome.message, loaded = false)
            }
            is BrowseOutcome.NeedsSignIn -> FeedState(needsSignIn = outcome.message)
        }
    }

    // ---- shared ----

    /** Takes over repaired metadata (title, thumbnail) in both lists without reordering them. */
    fun patch(update: (V) -> V) {
        fun FeedState<V>.patched(): FeedState<V> {
            val videos = videos.map(update)
            return if (visibleKeys(videos) == visibleKeys(this.videos)) this else copy(videos = videos)
        }
        homeState.value = homeState.value.patched()
        subscriptionState.value = subscriptionState.value.patched()
    }

    private fun visibleKeys(items: List<V>) = items.map(visibleKey)

    private fun checkAccount() {
        val current = source.account()
        if (current != account) {
            account = current
            channelsLoaded = false
            homeState.value = FeedState()
            subscriptionState.value = FeedState()
        }
    }

    companion object {
        /** Same age as the phone's silent refresh threshold. */
        const val SUBSCRIPTION_VISIBLE_TTL_MS = 60_000L
    }
}
