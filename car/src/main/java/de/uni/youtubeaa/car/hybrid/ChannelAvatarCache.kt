package de.uni.youtubeaa.car.hybrid

import android.content.Context

/**
 * Small persistent cache for public channel avatar URLs.
 *
 * Channel artwork changes rarely, while resolving a missing FEchannels avatar requires an
 * additional InnerTube browse request. Keeping the URL across activity/process restarts prevents
 * a subscription strip from refetching every missing avatar on each visit.
 */
class ChannelAvatarCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(channelId: String, nowMs: Long = System.currentTimeMillis()): String? {
        if (!channelId.startsWith("UC")) return null
        val url = prefs.getString(urlKey(channelId), null)?.takeIf { it.isNotBlank() } ?: return null
        val storedAt = prefs.getLong(timeKey(channelId), 0L)
        if (storedAt <= 0L || nowMs - storedAt > TTL_MS) {
            prefs.edit().remove(urlKey(channelId)).remove(timeKey(channelId)).apply()
            return null
        }
        return url
    }

    fun put(channelId: String, url: String?, nowMs: Long = System.currentTimeMillis()) {
        if (!channelId.startsWith("UC") || url.isNullOrBlank()) return
        prefs.edit()
            .putString(urlKey(channelId), url)
            .putLong(timeKey(channelId), nowMs)
            .apply()
    }

    fun enrich(channels: List<HybridChannel>): List<HybridChannel> = channels.map { channel ->
        val direct = channel.thumbnailUrl?.takeIf { it.isNotBlank() }
        if (direct != null) {
            put(channel.channelId, direct)
            channel
        } else {
            val cached = get(channel.channelId)
            if (cached == null) channel else channel.copy(thumbnailUrl = cached)
        }
    }

    companion object {
        private const val PREFS_NAME = "youtube_channel_avatars"
        private const val TTL_MS = 30L * 24L * 60L * 60L * 1000L
        private fun urlKey(channelId: String) = "url:$channelId"
        private fun timeKey(channelId: String) = "time:$channelId"
    }
}
