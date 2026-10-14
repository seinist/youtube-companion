package de.uni.youtubeaa.car.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeTimeTextTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun parsesGermanAges() {
        assertEquals(now - 3 * day, RelativeTimeText.parse("vor 3 Tagen", now))
        assertEquals(now - hour, RelativeTimeText.parse("vor 1 Stunde", now))
        assertEquals(now - 2 * 7 * day, RelativeTimeText.parse("vor 2 Wochen", now))
    }

    @Test
    fun parsesEnglishAges() {
        assertEquals(now - 5 * 60_000L, RelativeTimeText.parse("5 minutes ago", now))
        assertEquals(now - 2 * 365 * day, RelativeTimeText.parse("Streamed 2 years ago", now))
    }

    @Test
    fun skipsLeadingCountsAndFindsTheAge() {
        // A tile's second line reads "1,2 Mio. Aufrufe · vor 3 Tagen".
        assertEquals(now - 3 * day, RelativeTimeText.parse("1,2 Mio. Aufrufe · vor 3 Tagen", now))
        assertEquals(now - 4 * hour, RelativeTimeText.parse("12K views · 4 hours ago", now))
    }

    @Test
    fun channelNamesAndTitlesAreNotAges() {
        // Without "vor"/"ago" a number and unit is a name, not an upload age.
        assertNull(RelativeTimeText.parse("ZDF 24 Stunden", now))
        assertNull(RelativeTimeText.parse("60 Minutes", now))
        assertNull(RelativeTimeText.parse("Ich habe 100 Tage überlebt", now))
        // A combined line still yields the anchored age, not the channel's number.
        assertEquals(now - 3 * 365 * day, RelativeTimeText.parse("ZDF 24 Stunden · vor 3 Jahren", now))
    }

    @Test
    fun parsesSpelledOutSingulars() {
        assertEquals(now - hour, RelativeTimeText.parse("vor einer Stunde", now))
        assertEquals(now - hour, RelativeTimeText.parse("an hour ago", now))
    }

    @Test
    fun ignoresTextWithoutAnAge() {
        assertNull(RelativeTimeText.parse("Jetzt live", now))
        assertNull(RelativeTimeText.parse("1,2 Mio. Aufrufe", now))
        assertNull(RelativeTimeText.parse(null, now))
        assertNull(RelativeTimeText.parse("", now))
    }

    @Test
    fun newerLabelsSortAfterOlderOnes() {
        val newer = RelativeTimeText.parse("vor 2 Stunden", now)!!
        val older = RelativeTimeText.parse("vor 2 Tagen", now)!!
        assertTrue(newer > older)
    }

    @Test
    fun looksLikeAgeMatchesParse() {
        assertTrue(RelativeTimeText.looksLikeAge("vor 7 Monaten"))
        assertTrue(!RelativeTimeText.looksLikeAge("Alle"))
    }
}
