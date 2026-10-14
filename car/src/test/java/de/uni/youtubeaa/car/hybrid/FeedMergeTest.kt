package de.uni.youtubeaa.car.hybrid

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedMergeTest {

    private data class Card(val id: String, val date: Long = 0L)

    private fun merge(feeds: List<List<Card>>, shown: Set<String> = emptySet()) =
        FeedMerge.merge(feeds, shown, key = { it.id }, dateOf = { it.date }).map { it.id }

    @Test
    fun primaryOrderIsKeptExactly() {
        // Coarse ages must not reorder YouTube's own feed (that was the reshuffling).
        val android = listOf(Card("a", 100), Card("b", 300), Card("c", 200))
        assertEquals(listOf("a", "b", "c"), merge(listOf(android, emptyList())))
    }

    @Test
    fun sameResponsesAlwaysGiveTheSameOrder() {
        val android = listOf(Card("a", 300), Card("b", 200))
        val tv = listOf(Card("x", 250), Card("a", 300), Card("y"))
        val first = merge(listOf(android, tv))
        repeat(5) { assertEquals(first, merge(listOf(android, tv))) }
    }

    @Test
    fun extraCardsAreFittedInByAge() {
        val android = listOf(Card("a", 300), Card("b", 100))
        val tv = listOf(Card("x", 200))
        assertEquals(listOf("a", "x", "b"), merge(listOf(android, tv)))
    }

    @Test
    fun undatedExtrasGoLastAndUndatedPrimaryCardsStayPut() {
        val android = listOf(Card("live"), Card("a", 300), Card("b", 100))
        val tv = listOf(Card("short"), Card("x", 500))
        // "live" leads YouTube's feed and keeps its place; x is newer than everything dated but
        // must not jump above the leading live card.
        assertEquals(listOf("live", "x", "a", "b", "short"), merge(listOf(android, tv)))
    }

    @Test
    fun datedCopyFromTheOtherClientWinsButKeepsThePrimaryPosition() {
        val android = listOf(Card("a"), Card("b", 100))
        val tv = listOf(Card("a", 400))
        val merged = FeedMerge.merge(listOf(android, tv), emptySet(), { it.id }, { it.date })
        assertEquals(listOf(Card("a", 400), Card("b", 100)), merged)
    }

    @Test
    fun alreadyShownCardsAreNotRepeated() {
        val android = listOf(Card("a", 300), Card("c", 100))
        assertEquals(listOf("c"), merge(listOf(android), shown = setOf("a")))
    }

    @Test
    fun fallsBackToTheNextFeedWhenThePrimaryIsEmpty() {
        val tv = listOf(Card("x", 100), Card("y", 300))
        assertEquals(listOf("x", "y"), merge(listOf(emptyList(), tv)))
    }
}
