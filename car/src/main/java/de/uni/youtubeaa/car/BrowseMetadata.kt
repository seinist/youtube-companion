@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat

const val EXTRA_PUBLISHED_AT_MS = "de.uni.youtubeaa.extra.PUBLISHED_AT_MS"
const val EXTRA_CHANNEL_ID = "de.uni.youtubeaa.extra.CHANNEL_ID"
const val EXTRA_IS_SHORT = "de.uni.youtubeaa.extra.IS_SHORT"

fun MediaDescriptionCompat.publishedAtMs(): Long = extras?.getLong(EXTRA_PUBLISHED_AT_MS, 0L) ?: 0L
fun MediaDescriptionCompat.isShort(): Boolean = extras?.getBoolean(EXTRA_IS_SHORT, false) ?: false
fun MediaDescriptionCompat.channelId(): String? = extras?.getString(EXTRA_CHANNEL_ID)?.takeIf { it.startsWith("UC") }

/**
 * All values share one Bundle. MediaDescriptionCompat.Builder has no extras getter, so separate
 * setters would each install a fresh Bundle and silently drop the other one's value.
 */
fun MediaDescriptionCompat.Builder.setBrowseExtras(
    publishedAtMs: Long? = null,
    channelId: String? = null,
    isShort: Boolean = false
): MediaDescriptionCompat.Builder {
    val bundle = Bundle()
    if (isShort) bundle.putBoolean(EXTRA_IS_SHORT, true)
    if (publishedAtMs != null && publishedAtMs > 0L) bundle.putLong(EXTRA_PUBLISHED_AT_MS, publishedAtMs)
    if (!channelId.isNullOrBlank() && channelId.startsWith("UC")) bundle.putString(EXTRA_CHANNEL_ID, channelId)
    if (!bundle.isEmpty) setExtras(bundle)
    return this
}

/**
 * Newest first by [publishedAtMs]; items without a date keep their relative order behind the
 * dated ones, so a partly unlabelled page still looks like the source's own ordering.
 */
fun List<MediaDescriptionCompat>.newestFirst(): List<MediaDescriptionCompat> =
    withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<MediaDescriptionCompat>> { it.value.publishedAtMs() > 0L }
                .thenByDescending { it.value.publishedAtMs() }
                .thenBy { it.index }
        )
        .map { it.value }


/** "3 days ago" like the YouTube cards, in the app language; null without a known date. */
fun relativePublishedLabel(publishedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
    if (publishedAtMs <= 0L) return null
    val elapsed = (nowMs - publishedAtMs).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    val week = 7L * day
    val month = 30L * day
    val year = 365L * day
    fun plural(id: Int, n: Long): String = n.toInt().let { AppText.plural(id, it, it) }
    return when {
        elapsed < minute -> AppText.get(R.string.age_just_now)
        elapsed < hour -> AppText.get(R.string.age_minutes, (elapsed / minute).toInt())
        elapsed < day -> AppText.get(R.string.age_hours, (elapsed / hour).toInt())
        elapsed < week -> plural(R.plurals.age_days, elapsed / day)
        elapsed < month -> plural(R.plurals.age_weeks, elapsed / week)
        elapsed < year -> plural(R.plurals.age_months, elapsed / month)
        else -> plural(R.plurals.age_years, elapsed / year)
    }
}

/** Channel line of a card or list row: "Channel · 3 days ago" (either part may be missing). */
fun MediaDescriptionCompat.subtitleWithAge(): String? =
    listOfNotNull(
        subtitle?.toString()?.takeIf { it.isNotBlank() },
        relativePublishedLabel(publishedAtMs())
    ).joinToString(" · ").ifBlank { null }
