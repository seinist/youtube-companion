@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists the last playback session (queue, item, position) independently of the watch history.
 *
 * The in-memory session (NowPlayingBus / PlaybackEngine) does not survive the process being
 * killed, which is common some time after Android Auto disconnects. The native AA player uses
 * this store to show the last item again on reconnect and to resume it on Play.
 */
class LastSessionStore(context: Context) {

    data class Session(
        val queue: List<MediaDescriptionCompat>,
        val queueIndex: Int,
        val positionMs: Long,
        val durationMs: Long
    ) {
        val current: MediaDescriptionCompat? get() = queue.getOrNull(queueIndex)

        /** Same rules as the watch history: tiny progress and finished items start over. */
        fun resumePositionMs(): Long = WatchHistoryStore.normalizeResumePosition(positionMs, durationMs)
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun save(queue: List<MediaDescriptionCompat>, queueIndex: Int, positionMs: Long, durationMs: Long = 0L) {
        val current = queue.getOrNull(queueIndex)?.mediaId?.takeIf { it.isNotBlank() } ?: return
        // Keep the stored queue small: enough neighbours for skip, without serialising a 50-item
        // feed every periodic checkpoint on the playback/UI thread.
        val start = (queueIndex - MAX_QUEUE / 2).coerceAtLeast(0)
        val window = queue.subList(start, (start + MAX_QUEUE).coerceAtMost(queue.size)).toList()
        val relativeIndex = window.indexOfFirst { it.mediaId == current }
        if (relativeIndex < 0) return
        val snapshot = Session(
            queue = window,
            queueIndex = relativeIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L)
        )
        val generation = writeGeneration.incrementAndGet()
        synchronized(cacheLock) {
            cacheInitialized = true
            cachedSession = snapshot
        }
        persistenceExecutor.execute {
            // Coalesce periodic checkpoints: if a newer position arrived, only encode/write that.
            if (generation != writeGeneration.get()) return@execute
            val json = encode(snapshot)
            synchronized(persistenceLock) {
                if (generation == writeGeneration.get()) prefs.edit().putString(KEY, json).apply()
            }
        }
    }

    fun load(): Session? {
        synchronized(cacheLock) {
            if (cacheInitialized) return cachedSession
            val parsed = parse(prefs.getString(KEY, null))
            cacheInitialized = true
            cachedSession = parsed
            return parsed
        }
    }

    fun clear() {
        writeGeneration.incrementAndGet()
        synchronized(cacheLock) {
            cacheInitialized = true
            cachedSession = null
        }
        synchronized(persistenceLock) {
            prefs.edit().remove(KEY).apply()
        }
    }

    private fun encode(session: Session): String {
        val items = JSONArray()
        session.queue.forEach { item ->
            val id = item.mediaId?.takeIf { it.isNotBlank() } ?: return@forEach
            items.put(JSONObject().apply {
                put("id", id)
                put("title", item.title?.toString() ?: id)
                item.subtitle?.toString()?.let { put("subtitle", it) }
                item.iconUri?.toString()?.let { put("icon", it) }
            })
        }
        return JSONObject().apply {
            put("items", items)
            put("currentId", session.current?.mediaId.orEmpty())
            put("positionMs", session.positionMs)
            put("durationMs", session.durationMs)
        }.toString()
    }

    private fun parse(raw: String?): Session? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val array = json.getJSONArray("items")
            val queue = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    add(
                        MediaDescriptionCompat.Builder()
                            .setMediaId(id)
                            .setTitle(obj.optString("title").ifBlank { id })
                            .setSubtitle(obj.optString("subtitle").takeIf { it.isNotBlank() })
                            .setIconUri(obj.optString("icon").takeIf { it.isNotBlank() }?.let(Uri::parse))
                            .build()
                    )
                }
            }
            val index = queue.indexOfFirst { it.mediaId == json.optString("currentId") }
            if (index < 0) null
            else Session(queue, index, json.optLong("positionMs", 0L), json.optLong("durationMs", 0L))
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val KEY = "last_playback_session_v1"
        const val MAX_QUEUE = 24
        val cacheLock = Any()
        @Volatile var cacheInitialized = false
        @Volatile var cachedSession: Session? = null
        val writeGeneration = AtomicLong(0L)
        val persistenceLock = Any()
        val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "last-session-writer").apply { isDaemon = true }
        }
    }
}
