package de.uni.youtubeaa.car.hybrid

/**
 * Turns InnerTube's relative upload labels ("vor 3 Tagen", "3 days ago") into an approximate
 * timestamp. Feed cards carry no absolute date, so this is the only ordering signal the personal
 * subscription feed has. The result is deliberately coarse — it only has to rank cards against
 * each other, never to be displayed.
 */
internal object RelativeTimeText {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private val units: List<Pair<Set<String>, Long>> = listOf(
        setOf("sekunde", "sekunden", "second", "seconds") to 1_000L,
        setOf("minute", "minuten", "minutes") to MINUTE,
        setOf("stunde", "stunden", "hour", "hours") to HOUR,
        setOf("tag", "tage", "tagen", "day", "days") to DAY,
        setOf("woche", "wochen", "week", "weeks") to 7 * DAY,
        setOf("monat", "monate", "monaten", "month", "months") to 30 * DAY,
        setOf("jahr", "jahre", "jahren", "year", "years") to 365 * DAY
    )

    private const val AMOUNT = "(\\d+|einem|einer|einen|a|an)"
    // Only explicit age phrasing counts. A bare "24 Stunden" can be a channel ("ZDF 24 Stunden")
    // or a title, and was read as an upload age.
    private val germanAge = Regex("\\bvor\\s+$AMOUNT\\s*(\\p{L}+)")
    private val englishAge = Regex("\\b$AMOUNT\\s*(\\p{L}+)\\s+ago\\b")

    /** True for labels this can rank, so callers can pick the right text out of a card. */
    fun looksLikeAge(text: String?): Boolean = ageMillis(text) != null

    /** Approximate epoch milliseconds, or null when [text] carries no age. */
    fun parse(text: String?, nowMs: Long = System.currentTimeMillis()): Long? =
        ageMillis(text)?.let { (nowMs - it).coerceAtLeast(0L) }

    private fun ageMillis(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase()
        // A card line reads e.g. "1,2 Mio. Aufrufe · vor 3 Tagen"; only the phrase anchored on
        // "vor"/"ago" is taken, whatever else the line contains.
        for (match in germanAge.findAll(lower) + englishAge.findAll(lower)) {
            val amount = match.groupValues[1].toLongOrNull() ?: 1L
            val word = match.groupValues[2]
            val millis = units.firstOrNull { word in it.first }?.second ?: continue
            return amount * millis
        }
        return null
    }
}
