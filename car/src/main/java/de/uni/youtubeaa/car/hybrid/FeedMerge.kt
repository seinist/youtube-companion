package de.uni.youtubeaa.car.hybrid

/**
 * Merges the subscription feeds that several InnerTube clients return for the same request.
 *
 * The first feed with content is YouTube's own ordering and is kept exactly as delivered.
 * Re-sorting it by the coarse relative ages ("vor 2 Tagen") made the order depend on which cards
 * each client happened to return, so every refresh reshuffled the list. Cards only another
 * client knows are inserted at the position their age fits; cards without a readable age are
 * appended. The result depends only on the responses, never on when they were parsed.
 *
 * Pure and generic so it can be unit-tested without Android's Bundle-backed descriptions.
 */
internal object FeedMerge {

    fun <T> merge(
        feeds: List<List<T>>,
        alreadyShown: Set<String>,
        key: (T) -> String,
        /** Upload time in epoch ms, or 0 when the card's age could not be read. */
        dateOf: (T) -> Long
    ): List<T> {
        val primaryIndex = feeds.indexOfFirst { feed -> feed.any { key(it) !in alreadyShown } }
        if (primaryIndex < 0) return emptyList()

        // When a client lists a video without a date that another client dates, keep the dated
        // copy (the position still comes from the primary feed).
        val datedCopy = HashMap<String, T>()
        feeds.flatten().forEach { item -> if (dateOf(item) > 0L) datedCopy.putIfAbsent(key(item), item) }

        val result = ArrayList<T>()
        val seen = HashSet<String>(alreadyShown)
        feeds[primaryIndex].forEach { item ->
            val id = key(item)
            if (seen.add(id)) result += if (dateOf(item) > 0L) item else datedCopy[id] ?: item
        }

        feeds.forEachIndexed { index, feed ->
            if (index == primaryIndex) return@forEachIndexed
            feed.forEach { candidate ->
                val id = key(candidate)
                if (!seen.add(id)) return@forEach
                val item = datedCopy[id] ?: candidate
                val date = dateOf(item)
                if (date <= 0L) {
                    result += item
                } else {
                    val ages = effectiveAges(result, dateOf)
                    val at = ages.indexOfFirst { it < date }
                    if (at < 0) result += item else result.add(at, item)
                }
            }
        }
        return result
    }

    /**
     * Ordering age per position: undated cards (live now, premieres, Shorts) take the age of the
     * card above them, or "newest" when they lead the list, so insertions respect them.
     */
    private fun <T> effectiveAges(items: List<T>, dateOf: (T) -> Long): List<Long> {
        var previous = Long.MAX_VALUE
        return items.map { item ->
            val own = dateOf(item)
            if (own > 0L) previous = own
            if (own > 0L) own else previous
        }
    }
}
