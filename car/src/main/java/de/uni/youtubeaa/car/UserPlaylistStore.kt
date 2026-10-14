@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

data class PlaylistEntry(
    val videoId: String,
    val title: String,
    val channelTitle: String?,
    val thumbnailUrl: String?,
    val addedAtMs: Long = System.currentTimeMillis()
)

/**
 * Persistente App-Playlist („Meine Playlist“) in SharedPreferences.
 * Shared by phone browse UI and AA projection / media browser.
 */
class UserPlaylistStore(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun getAll(): List<PlaylistEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("videoId").takeIf { it.isNotBlank() } ?: continue
                    add(
                        PlaylistEntry(
                            videoId = id,
                            title = obj.optString("title").ifBlank { id },
                            channelTitle = obj.optString("channelTitle").takeIf { it.isNotBlank() },
                            thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                            addedAtMs = obj.optLong("addedAtMs", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun contains(videoId: String): Boolean = getAll().any { it.videoId == videoId }

    /** Upsert by videoId (keeps order: existing stays, new appends). */
    fun add(entry: PlaylistEntry): Boolean {
        val current = getAll().toMutableList()
        val index = current.indexOfFirst { it.videoId == entry.videoId }
        if (index >= 0) {
            current[index] = entry.copy(addedAtMs = current[index].addedAtMs)
            save(current)
            return false
        }
        current.add(entry)
        save(current)
        return true
    }

    fun addFromDescription(video: MediaDescriptionCompat): Boolean {
        val id = video.mediaId ?: return false
        return add(
            PlaylistEntry(
                videoId = id,
                title = video.title?.toString() ?: id,
                channelTitle = video.subtitle?.toString(),
                thumbnailUrl = video.iconUri?.toString()
            )
        )
    }

    fun remove(videoId: String): Boolean {
        val current = getAll()
        val next = current.filterNot { it.videoId == videoId }
        if (next.size == current.size) return false
        save(next)
        return true
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    fun move(fromIndex: Int, toIndex: Int) {
        val current = getAll().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        save(current)
    }

    fun toMediaDescriptions(): List<MediaDescriptionCompat> =
        getAll().map { it.toDescription() }

    private fun save(entries: List<PlaylistEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("videoId", entry.videoId)
                    put("title", entry.title)
                    put("channelTitle", entry.channelTitle ?: "")
                    put("thumbnailUrl", entry.thumbnailUrl ?: "")
                    put("addedAtMs", entry.addedAtMs)
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "user_playlist_v1"
    }
}

fun PlaylistEntry.toDescription(): MediaDescriptionCompat =
    MediaDescriptionCompat.Builder()
        .setMediaId(videoId)
        .setTitle(title)
        .setSubtitle(channelTitle)
        .setIconUri(thumbnailUrl?.let { Uri.parse(it) })
        .setBrowseExtras(publishedAtMs = addedAtMs)
        .build()
