@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Lightweight metadata repair for handoffs/cards that only carry a YouTube id.
 * Uses YouTube's public oEmbed response, so it does not consume Data API quota or require login.
 */
class VideoMetadataRefetcher(
    private val http: OkHttpClient = SharedHttp.builder(DataUsageStats.Subsystem.OTHER)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    suspend fun repairIfNeeded(source: MediaDescriptionCompat): MediaDescriptionCompat = withContext(Dispatchers.IO) {
        val videoId = source.mediaId?.trim().orEmpty()
        if (!YOUTUBE_ID.matches(videoId) || !needsRepair(source, videoId)) return@withContext source
        cache[videoId]?.let { return@withContext merge(source, it) }
        repairLocks[(videoId.hashCode() and Int.MAX_VALUE) % repairLocks.size].withLock {
            cache[videoId]?.let { return@withLock merge(source, it) }
            val selectedGate = if (CarPlaybackQos.isProjectedVideoVisible()) projectionGate else gate
            val fetched = selectedGate.withPermit { fetch(videoId) } ?: return@withLock source
            cache[videoId] = fetched
            merge(source, fetched)
        }
    }

    fun needsRepair(source: MediaDescriptionCompat, videoId: String = source.mediaId.orEmpty()): Boolean {
        val title = source.title?.toString()?.trim().orEmpty()
        return title.isBlank() || title == videoId || (source.subtitle.isNullOrBlank() && source.iconUri == null)
    }

    private suspend fun fetch(videoId: String): MediaDescriptionCompat? {
        return try {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val encoded = URLEncoder.encode(watchUrl, StandardCharsets.UTF_8.name())
            val request = Request.Builder()
                .url("https://www.youtube.com/oembed?url=$encoded&format=json")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            http.newCall(request).awaitBytes(256 * 1024).let { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.bytes.toString(Charsets.UTF_8))
                val title = json.optString("title").takeIf { it.isNotBlank() }
                val author = json.optString("author_name").takeIf { it.isNotBlank() }
                val thumb = json.optString("thumbnail_url").takeIf { it.isNotBlank() }
                if (title == null && author == null && thumb == null) return null
                MediaDescriptionCompat.Builder()
                    .setMediaId(videoId)
                    .setTitle(title ?: videoId)
                    .setSubtitle(author)
                    .setIconUri(thumb?.let(Uri::parse))
                    .build()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun merge(original: MediaDescriptionCompat, fetched: MediaDescriptionCompat): MediaDescriptionCompat {
        val videoId = original.mediaId ?: fetched.mediaId
        val originalTitle = original.title?.toString()?.trim().orEmpty()
        val title = if (originalTitle.isBlank() || originalTitle == videoId) fetched.title else original.title
        val extras = original.extras?.let(::Bundle)
        return MediaDescriptionCompat.Builder()
            .setMediaId(videoId)
            .setTitle(title)
            .setSubtitle(original.subtitle?.takeUnless { it.isBlank() } ?: fetched.subtitle)
            .setDescription(original.description ?: fetched.description)
            .setIconBitmap(original.iconBitmap ?: fetched.iconBitmap)
            .setIconUri(original.iconUri ?: fetched.iconUri)
            .setMediaUri(original.mediaUri ?: fetched.mediaUri)
            .setExtras(extras)
            .build()
    }

    companion object {
        private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val gate = Semaphore(3)
        private val projectionGate = Semaphore(1)
        private val repairLocks = Array(32) { Mutex() }
        private val cache = ConcurrentHashMap<String, MediaDescriptionCompat>()
    }
}
