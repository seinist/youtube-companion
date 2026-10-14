package de.uni.youtubeaa.car.hybrid

/**
 * One filter chip of the subscriptions feed ("Alle", "Heute", "Videos", "Shorts", ...).
 *
 * Public because both app surfaces render it, unlike the internal parser that produces it.
 * The selected chip usually carries no continuation [token] — it is the unfiltered feed.
 */
data class FeedChip(val title: String, val token: String?, val selected: Boolean)
