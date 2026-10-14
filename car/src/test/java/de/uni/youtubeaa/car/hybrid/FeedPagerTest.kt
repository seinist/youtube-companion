package de.uni.youtubeaa.car.hybrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeedPagerTest {
    @Test fun failedContinuationRetainsVideosAndRetriesSameToken() = runBlocking {
        var fail = true
        val requests = mutableListOf<String?>()
        val pager = FeedPager<String>({ it }) { token ->
            requests += token
            when (token) {
                null -> FeedPager.Page(listOf("first"), listOf("next"))
                else -> if (fail) error("offline") else FeedPager.Page(listOf("second"), emptyList())
            }
        }
        val partial = pager.load(2)
        assertEquals(listOf("first"), partial.items)
        assertTrue(partial.partial && partial.hasMore)
        fail = false
        val complete = pager.load(2)
        assertEquals(listOf("first", "second"), complete.items)
        assertFalse(complete.partial || complete.hasMore)
        assertEquals(listOf(null, "next", "next"), requests)
    }

    @Test fun loadingBeyondSixPagesDoesNotLoseContinuation() = runBlocking {
        val pager = FeedPager<String>({ it }) { token ->
            val page = token?.toInt() ?: 0
            FeedPager.Page(listOf("video$page"), if (page < 9) listOf("${page + 1}") else emptyList())
        }
        repeat(4) { assertTrue(pager.load(2).hasMore) }
        val all = pager.load(2)
        assertEquals(10, all.items.size)
        assertEquals("video9", all.items.last())
        assertFalse(all.hasMore)
    }

    @Test fun duplicateAndRepeatedTokensTerminateWithoutDuplicateVideos() = runBlocking {
        var calls = 0
        val pager = FeedPager<String>({ it }) { token ->
            calls++
            FeedPager.Page(if (token == null) listOf("one") else listOf("one", "two"), listOf("same", "same"))
        }
        val all = pager.load(20)
        assertEquals(2, calls)
        assertEquals(listOf("one", "two"), all.items)
        assertFalse(all.hasMore)
    }

    @Test fun initialFailureRemainsRetryable() = runBlocking {
        var fail = true
        val pager = FeedPager<String>({ it }) { _ ->
            if (fail) error("offline")
            FeedPager.Page(listOf("recovered"), emptyList())
        }
        assertTrue(pager.load(1).partial)
        fail = false
        assertEquals(listOf("recovered"), pager.load(1).items)
    }

    @Test fun cancellationPropagatesAndDoesNotConsumeToken() = runBlocking {
        var cancel = true
        val pager = FeedPager<String>({ it }) { token ->
            if (token == null) FeedPager.Page(listOf("one"), listOf("next"))
            else {
                if (cancel) throw CancellationException("tab changed")
                FeedPager.Page(listOf("two"), emptyList())
            }
        }
        try { pager.load(2); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(listOf("one"), pager.snapshot().items)
        assertFalse(pager.snapshot().partial)
        cancel = false
        assertEquals(listOf("one", "two"), pager.load(1).items)
    }

    @Test fun legitimateEmptyFeedFinishesWithoutError() = runBlocking {
        val pager = FeedPager<String>({ it }) { FeedPager.Page(emptyList(), emptyList()) }
        val all = pager.load(2)
        assertTrue(all.items.isEmpty())
        assertFalse(all.partial || all.hasMore)
    }
}
