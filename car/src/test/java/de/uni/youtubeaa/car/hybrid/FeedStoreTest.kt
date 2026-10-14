package de.uni.youtubeaa.car.hybrid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedStoreTest {

    private data class Card(val id: String, val title: String = id)

    private class FakeSource : FeedStore.Source<Card> {
        var account: String? = "a"
        val home = ArrayDeque<BrowseOutcome<List<Card>>>()
        val feed = ArrayDeque<BrowseOutcome<List<Card>>>()
        var channels: BrowseOutcome<List<HybridChannel>> = BrowseOutcome.Ok(listOf(HybridChannel("c1", "C1")))
        var homeCalls = 0
        var feedCalls = 0
        var channelCalls = 0
        override fun account() = account
        override suspend fun home(forceRefresh: Boolean, loadMore: Boolean) = home.removeFirst().also { homeCalls++ }
        override suspend fun subscriptionFeed(forceRefresh: Boolean, loadMore: Boolean, chipToken: String?) =
            feed.removeFirst().also { feedCalls++ }
        override suspend fun subscriptionChannels(forceRefresh: Boolean) = channels.also { channelCalls++ }
    }

    private var clock = 1_000_000L
    private val source = FakeSource()
    private val store = FeedStore(source, key = { it.id }, visibleKey = { it }, now = { clock })

    private fun ok(vararg ids: String, hasMore: Boolean = false, at: Long = clock) =
        BrowseOutcome.Ok(ids.map { Card(it) }, hasMore = hasMore, fetchedAtMs = at)

    private fun ids(state: FeedStore.FeedState<Card>) = state.videos.map { it.id }

    @Test
    fun homeIsFetchedOnceAndThenStaysPut() = runBlocking {
        source.home += ok("a", "b")
        store.loadHome()
        assertEquals(listOf("a", "b"), ids(store.loadHome()))
        assertEquals(1, source.homeCalls)
    }

    @Test
    fun homeMoreOnlyAppends() = runBlocking {
        source.home += ok("a", "b", hasMore = true)
        source.home += ok("x", "b", "c")
        store.loadHome()
        val state = store.loadMoreHome()
        assertEquals(listOf("a", "b", "x", "c"), ids(state))
        assertFalse(state.hasMore)
    }

    @Test
    fun failedHomeRefreshKeepsList() = runBlocking {
        source.home += ok("a")
        source.home += BrowseOutcome.Error("weg")
        store.loadHome()
        val state = store.refreshHome()
        assertEquals(listOf("a"), ids(state))
        assertTrue(state.refreshFailed)
    }

    @Test
    fun silentRefreshWaitsForTtl() = runBlocking {
        source.feed += ok("a")
        store.loadSubscriptions()
        clock += FeedStore.SUBSCRIPTION_VISIBLE_TTL_MS - 1
        store.revalidateSubscriptions()
        assertEquals(1, source.feedCalls)
    }

    @Test
    fun unchangedSilentRefreshKeepsTheSameList() = runBlocking {
        source.feed += ok("a", "b")
        val before = store.loadSubscriptions().videos
        clock += FeedStore.SUBSCRIPTION_VISIBLE_TTL_MS
        source.feed += ok("a", "b")
        assertSame(before, store.revalidateSubscriptions().videos)
    }

    @Test
    fun changedSilentRefreshReplaces() = runBlocking {
        source.feed += ok("a", "b")
        store.loadSubscriptions()
        clock += FeedStore.SUBSCRIPTION_VISIBLE_TTL_MS
        source.feed += ok("new", "a", "b")
        assertEquals(listOf("new", "a", "b"), ids(store.revalidateSubscriptions()))
    }

    @Test
    fun failedSilentRefreshChangesNothing() = runBlocking {
        source.feed += ok("a")
        val before = store.loadSubscriptions()
        clock += FeedStore.SUBSCRIPTION_VISIBLE_TTL_MS
        source.feed += BrowseOutcome.Error("weg")
        assertEquals(before, store.revalidateSubscriptions())
    }

    @Test
    fun channelsAreLoadedOnceAndSurviveFailedRefresh() = runBlocking {
        source.feed += ok("a")
        store.loadSubscriptions()
        store.loadSubscriptions()
        assertEquals(1, source.channelCalls)
        source.channels = BrowseOutcome.Error("weg")
        source.feed += ok("a")
        assertEquals(listOf(HybridChannel("c1", "C1")), store.refreshSubscriptions().channels)
    }

    @Test
    fun failedMoreKeepsListAndMarksPartial() = runBlocking {
        source.feed += ok("a", hasMore = true)
        store.loadSubscriptions()
        source.feed += BrowseOutcome.Error("weg")
        val state = store.loadMoreSubscriptions()
        assertEquals(listOf("a"), ids(state))
        assertTrue(state.partial)
    }

    @Test
    fun chipIsNeverRefreshedSilently() = runBlocking {
        source.feed += ok("a")
        store.loadSubscriptions()
        source.feed += ok("m")
        store.selectChip(FeedChip("Musik", "t", false))
        clock += 10 * FeedStore.SUBSCRIPTION_VISIBLE_TTL_MS
        assertEquals(listOf("m"), ids(store.revalidateSubscriptions()))
        assertEquals(2, source.feedCalls)
    }

    @Test
    fun moreAppendsAndSilentRefreshKeepsLoadedPages() = runBlocking {
        source.feed += ok("a", "b", hasMore = true)
        store.loadSubscriptions()
        source.feed += ok("a", "b", "c", "d", hasMore = true)
        assertEquals(listOf("a", "b", "c", "d"), ids(store.loadMoreSubscriptions()))
        clock += FeedStore.SUBSCRIPTION_VISIBLE_TTL_MS
        source.feed += ok("new", "a")
        val state = store.revalidateSubscriptions()
        assertEquals(listOf("new", "a", "c", "d"), ids(state))
        assertTrue(state.hasMore)
    }

    @Test
    fun accountChangeDropsLists() = runBlocking {
        source.home += ok("a")
        store.loadHome()
        source.account = "b"
        source.home += ok("z")
        assertEquals(listOf("z"), ids(store.loadHome()))
    }
}
