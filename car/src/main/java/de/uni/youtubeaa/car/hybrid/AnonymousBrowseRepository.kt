@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car.hybrid

import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import de.uni.youtubeaa.car.AppText
import de.uni.youtubeaa.car.BrowseListCache
import de.uni.youtubeaa.car.R
import de.uni.youtubeaa.car.StreamResolver
import de.uni.youtubeaa.car.ThumbnailSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Public YouTube browse via NewPipeExtractor — no Google account / Console.
 */
class AnonymousBrowseRepository {

    init {
        // Ensure extractor HTTP is initialized (same downloader as StreamResolver).
        StreamResolver()
    }

    suspend fun search(query: String, forceRefresh: Boolean = false): List<MediaDescriptionCompat> =
        withContext(Dispatchers.IO) {
            val key = BrowseListCache.searchKey("anon:$query")
            if (!forceRefresh) BrowseListCache.get<List<MediaDescriptionCompat>>(key)?.let { return@withContext it }
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            val items = extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toDescriptionOrNull() }
            if (items.isNotEmpty()) BrowseListCache.put(key, items)
            items
        }

    suspend fun getPopular(forceRefresh: Boolean = false): List<MediaDescriptionCompat> =
        withContext(Dispatchers.IO) {
            val key = "anon:${BrowseListCache.KEY_POPULAR}"
            if (!forceRefresh) BrowseListCache.get<List<MediaDescriptionCompat>>(key)?.let { return@withContext it }
            val extractor = ServiceList.YouTube.kioskList.defaultKioskExtractor
            extractor.fetchPage()
            val items = extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toDescriptionOrNull() }
            if (items.isNotEmpty()) BrowseListCache.put(key, items)
            items
        }

    suspend fun getChannelVideos(
        channelUrlOrId: String,
        forceRefresh: Boolean = false
    ): List<MediaDescriptionCompat> = withContext(Dispatchers.IO) {
        val url = if (channelUrlOrId.startsWith("http")) {
            channelUrlOrId
        } else {
            "https://www.youtube.com/channel/$channelUrlOrId"
        }
        val key = "anon:${BrowseListCache.channelKey(url)}"
        if (!forceRefresh) BrowseListCache.get<List<MediaDescriptionCompat>>(key)?.let { return@withContext it }
        val channel = ServiceList.YouTube.getChannelExtractor(url)
        channel.fetchPage()
        val tabs = channel.tabs
        if (tabs.isEmpty()) return@withContext emptyList()
        val videosTab = tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
            ?: throw IllegalStateException(AppText.get(R.string.browse_error_no_video_tab))
        val tab = ServiceList.YouTube.getChannelTabExtractor(videosTab)
        tab.fetchPage()
        val items = tab.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toDescriptionOrNull() }
        if (items.isNotEmpty()) BrowseListCache.put(key, items)
        items
    }

    private fun StreamInfoItem.toDescriptionOrNull(): MediaDescriptionCompat? {
        val rawUrl = url ?: return null
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val id = when {
            uri.host.equals("youtu.be", ignoreCase = true) -> uri.lastPathSegment
            !uri.getQueryParameter("v").isNullOrBlank() -> uri.getQueryParameter("v")
            uri.pathSegments.firstOrNull() in setOf("shorts", "live", "embed") ->
                uri.pathSegments.getOrNull(1)
            else -> null
        }?.takeIf { it.length >= 6 }
            ?: return null
        // Smallest listed size that still fills the largest view; hq720 is then resized by name.
        val thumb = ThumbnailSizing.pickSource(
            thumbnails.map { Triple(it.url, it.width.coerceAtLeast(0), it.height.coerceAtLeast(0)) }
        )
        return MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(name ?: id)
            .setSubtitle(uploaderName)
            .setIconUri(thumb?.let { Uri.parse(it) })
            .build()
    }
}
