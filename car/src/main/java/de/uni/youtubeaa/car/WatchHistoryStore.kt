@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** One locally persisted watch-history item including its last VOD playback position. */
data class WatchHistoryEntry(
    val videoId: String,
    val title: String,
    val channelTitle: String?,
    val thumbnailUrl: String?,
    val addedAtMs: Long = System.currentTimeMillis(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

/**
 * Lokaler Wiedergabeverlauf (YouTube Data API liefert keinen Watch-History-Endpunkt).
 * Neueste Einträge zuerst; Duplikate wandern beim erneuten Start nach oben.
 *
 * Zusätzlich werden Position und Dauer gespeichert. Alte watch_history_v1-Einträge ohne diese
 * Felder bleiben kompatibel und werden als 0/0 eingelesen.
 */
class WatchHistoryStore(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun getAll(): List<WatchHistoryEntry> {
        synchronized(cacheLock) {
            cachedEntries?.let { return it.toList() }
            val parsed = parse(prefs.getString(KEY, null))
            cachedEntries = parsed
            return parsed.toList()
        }
    }

    private fun parse(raw: String?): List<WatchHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("videoId").takeIf { it.isNotBlank() } ?: continue
                    add(
                        WatchHistoryEntry(
                            videoId = id,
                            title = obj.optString("title").ifBlank { id },
                            channelTitle = obj.optString("channelTitle").takeIf { it.isNotBlank() },
                            thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                            addedAtMs = obj.optLong("addedAtMs", 0L),
                            positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
                            durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(videoId: String): WatchHistoryEntry? = getAll().firstOrNull { it.videoId == videoId }

    /**
     * Add/move a video to the top of history. Existing progress is deliberately preserved until
     * the player reports a newer position, so opening an item from history cannot erase its resume
     * point before ExoPlayer has even prepared the stream.
     */
    fun record(entry: WatchHistoryEntry) {
        val next = getAll().toMutableList()
        val previous = next.firstOrNull { it.videoId == entry.videoId }
        next.removeAll { it.videoId == entry.videoId }
        next.add(
            0,
            entry.copy(
                addedAtMs = System.currentTimeMillis(),
                positionMs = if (entry.positionMs > 0L || entry.durationMs > 0L) {
                    entry.positionMs.coerceAtLeast(0L)
                } else {
                    previous?.positionMs ?: 0L
                },
                durationMs = if (entry.durationMs > 0L) entry.durationMs else previous?.durationMs ?: 0L
            )
        )
        while (next.size > MAX_ENTRIES) {
            next.removeAt(next.lastIndex)
        }
        save(next)
    }

    fun recordFromDescription(video: MediaDescriptionCompat) {
        val id = video.mediaId ?: return
        record(
            WatchHistoryEntry(
                videoId = id,
                title = video.title?.toString() ?: id,
                channelTitle = video.subtitle?.toString(),
                thumbnailUrl = video.iconUri?.toString()
            )
        )
    }

    /**
     * Persist the current playhead without reordering the history entry. If no entry exists yet,
     * create one so late/headless playback paths cannot lose progress.
     */
    fun updateProgress(video: MediaDescriptionCompat, positionMs: Long, durationMs: Long) {
        val id = video.mediaId ?: return
        val current = getAll().toMutableList()
        val index = current.indexOfFirst { it.videoId == id }
        val safePosition = positionMs.coerceAtLeast(0L)
        val safeDuration = durationMs.coerceAtLeast(0L)
        if (index >= 0) {
            val old = current[index]
            current[index] = old.copy(
                title = video.title?.toString()?.takeIf { it.isNotBlank() } ?: old.title,
                channelTitle = video.subtitle?.toString() ?: old.channelTitle,
                thumbnailUrl = video.iconUri?.toString() ?: old.thumbnailUrl,
                positionMs = safePosition,
                durationMs = if (safeDuration > 0L) safeDuration else old.durationMs
            )
            save(current)
        } else {
            record(
                WatchHistoryEntry(
                    videoId = id,
                    title = video.title?.toString() ?: id,
                    channelTitle = video.subtitle?.toString(),
                    thumbnailUrl = video.iconUri?.toString(),
                    positionMs = safePosition,
                    durationMs = safeDuration
                )
            )
        }
    }

    fun clear() {
        writeGeneration.incrementAndGet()
        synchronized(cacheLock) { cachedEntries = emptyList() }
        synchronized(persistenceLock) {
            prefs.edit().remove(KEY).apply()
        }
    }

    fun toMediaDescriptions(): List<MediaDescriptionCompat> = getAll().map { it.toDescription() }

    private fun save(entries: List<WatchHistoryEntry>) {
        val snapshot = entries.toList()
        val generation = writeGeneration.incrementAndGet()
        synchronized(cacheLock) { cachedEntries = snapshot }
        // JSON encoding a 100-item history on the main thread caused periodic playback spikes.
        // Preserve immediate in-process visibility via the cache, then serialize only the newest
        // queued snapshot. The generation also prevents an older write resurrecting a cleared
        // history after clear().
        persistenceExecutor.execute {
            if (generation != writeGeneration.get()) return@execute
            val array = JSONArray()
            snapshot.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("videoId", entry.videoId)
                        put("title", entry.title)
                        put("channelTitle", entry.channelTitle ?: "")
                        put("thumbnailUrl", entry.thumbnailUrl ?: "")
                        put("addedAtMs", entry.addedAtMs)
                        put("positionMs", entry.positionMs.coerceAtLeast(0L))
                        put("durationMs", entry.durationMs.coerceAtLeast(0L))
                    }
                )
            }
            synchronized(persistenceLock) {
                if (generation == writeGeneration.get()) {
                    prefs.edit().putString(KEY, array.toString()).apply()
                }
            }
        }
    }

    companion object {
        private const val KEY = "watch_history_v1"
        private val cacheLock = Any()
        @Volatile private var cachedEntries: List<WatchHistoryEntry>? = null
        private val writeGeneration = AtomicLong(0L)
        private val persistenceLock = Any()
        private val persistenceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "watch-history-writer").apply { isDaemon = true }
        }
        private const val MAX_ENTRIES = 100
        private const val MIN_RESUME_MS = 5_000L
        private const val END_RESTART_WINDOW_MS = 10_000L

        const val EXTRA_HISTORY_POSITION_MS = "de.uni.youtubeaa.extra.HISTORY_POSITION_MS"
        const val EXTRA_HISTORY_DURATION_MS = "de.uni.youtubeaa.extra.HISTORY_DURATION_MS"

        /**
         * Resume only meaningful VOD positions. Very early positions and effectively completed
         * videos restart at 0 instead of reopening on the final frame.
         */
        fun normalizeResumePosition(positionMs: Long, durationMs: Long): Long {
            val position = positionMs.coerceAtLeast(0L)
            val duration = durationMs.coerceAtLeast(0L)
            if (position < MIN_RESUME_MS) return 0L
            if (duration > 0L) {
                val clamped = position.coerceAtMost(duration)
                val remaining = duration - clamped
                if (remaining <= END_RESTART_WINDOW_MS) {
                    return 0L
                }
                return clamped
            }
            return position
        }

        fun isHistoryDescription(video: MediaDescriptionCompat): Boolean =
            video.extras?.containsKey(EXTRA_HISTORY_POSITION_MS) == true

        fun resumePositionFromDescription(video: MediaDescriptionCompat): Long {
            val extras = video.extras ?: return 0L
            return normalizeResumePosition(
                extras.getLong(EXTRA_HISTORY_POSITION_MS, 0L),
                extras.getLong(EXTRA_HISTORY_DURATION_MS, 0L)
            )
        }
    }
}

fun WatchHistoryEntry.toDescription(): MediaDescriptionCompat {
    val extras = Bundle().apply {
        putLong(EXTRA_PUBLISHED_AT_MS, addedAtMs)
        putLong(WatchHistoryStore.EXTRA_HISTORY_POSITION_MS, positionMs.coerceAtLeast(0L))
        putLong(WatchHistoryStore.EXTRA_HISTORY_DURATION_MS, durationMs.coerceAtLeast(0L))
    }
    return MediaDescriptionCompat.Builder()
        .setMediaId(videoId)
        .setTitle(title)
        .setSubtitle(channelTitle)
        .setIconUri(thumbnailUrl?.let { Uri.parse(it) })
        .setExtras(extras)
        .build()
}
