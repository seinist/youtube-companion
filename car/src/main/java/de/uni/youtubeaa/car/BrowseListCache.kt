package de.uni.youtubeaa.car

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory TTL cache for YouTube browse lists (shared by phone + AA in one process).
 * Pull-to-refresh / forceRefresh bypasses and replaces entries.
 */
object BrowseListCache {

    private const val DEFAULT_TTL_MS = 15 * 60 * 1000L

    private data class Entry(val storedAtMs: Long, val value: Any)

    private val map = ConcurrentHashMap<String, Entry>()

    const val KEY_FEED = "feed"
    const val KEY_CHANNELS = "channels"
    const val KEY_POPULAR = "popular"
    /** Personalized InnerTube (TV-session) home recommendations — distinct from KEY_POPULAR's
     *  anonymous public trending list. */
    const val KEY_HOME_PERSONAL = "home_personal"
    /** InnerTube (TV-session) subscriptions feed. Kept separate from KEY_FEED, which caches the
     *  legacy Data-API path's raw PlaylistItem shape rather than MediaDescriptionCompat. */
    const val KEY_SUBSCRIPTION_FEED_PERSONAL = "subscription_feed_personal"

    fun channelKey(channelId: String) = "channel:$channelId"

    fun searchKey(query: String) = "search:${query.trim().lowercase()}"

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, ttlMs: Long = DEFAULT_TTL_MS): T? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.storedAtMs > ttlMs) {
            map.remove(key, entry)
            return null
        }
        return entry.value as? T
    }

    fun put(key: String, value: Any) {
        map[key] = Entry(System.currentTimeMillis(), value)
    }

    fun invalidate(key: String? = null) {
        if (key == null) map.clear() else map.remove(key)
    }
}
