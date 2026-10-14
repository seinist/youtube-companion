package de.uni.youtubeaa.car

import org.junit.Assert.assertEquals
import org.junit.Test

class StableListMergeTest {

    private fun merge(old: List<String>, fresh: List<String>) =
        StableListMerge.merge(old, fresh) { it.substringBefore('#') }

    @Test fun `new uploads go above the old top`() =
        assertEquals(listOf("n1", "n2", "a", "b", "c"), merge(listOf("a", "b", "c"), listOf("n1", "n2", "a", "b", "c")))

    @Test fun `existing items keep their order when the fresh list reshuffles`() =
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "b", "c"), listOf("c", "a", "b")))

    @Test fun `new items sit before their next shared neighbour`() =
        assertEquals(listOf("a", "x", "b", "c"), merge(listOf("a", "b", "c"), listOf("a", "x", "b", "c")))

    @Test fun `paging results go below the old end`() =
        assertEquals(listOf("a", "b", "c", "d"), merge(listOf("a", "b"), listOf("a", "b", "c", "d")))

    @Test fun `vanished items stay and refreshed content replaces in place`() =
        assertEquals(listOf("a#new", "b"), merge(listOf("a#old", "b"), listOf("a#new")))

    @Test fun `empty old list takes the fresh list`() =
        assertEquals(listOf("a", "b"), merge(emptyList(), listOf("a", "b")))

    @Test fun `append keeps the shown list and only adds unknown keys at the end`() =
        assertEquals(listOf("a", "b", "x", "c"), StableListMerge.appendNew(listOf("a", "b"), listOf("b", "x", "c", "a")) { it })

    @Test fun `append drops nothing the fresh list no longer has`() =
        assertEquals(listOf("a", "b"), StableListMerge.appendNew(listOf("a", "b"), listOf("b"), { it }))

    @Test fun `append of an empty shown list is the fresh list`() =
        assertEquals(listOf("a", "b"), StableListMerge.appendNew(emptyList(), listOf("a", "b")) { it })

    @Test fun `trimming drops vanished items from the end but never fresh ones`() =
        assertEquals(
            listOf("a", "f1", "f2"),
            StableListMerge.trimVanished(listOf("a", "f1", "b", "f2", "c"), listOf("f1", "f2"), 3) { it }
        )
}
