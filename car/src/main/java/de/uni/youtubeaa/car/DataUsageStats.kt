package de.uni.youtubeaa.car

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.util.Locale

/**
 * Bytes actually downloaded by the players, split by track type and metered/unmetered network.
 * Persisted lightly (throttled SharedPreferences writes) so the totals survive restarts and can
 * be compared with Android's per-app data usage. Shown in phone Settings → Diagnostics and
 * written to the head-unit log.
 */
object DataUsageStats {
    enum class Kind(val key: String) { VIDEO("video"), AUDIO("audio"), MUXED("muxed"), OTHER("other") }

    data class Snapshot(
        val sinceMs: Long,
        /** [Kind] → (metered bytes, unmetered bytes). */
        val bytes: Map<Kind, Pair<Long, Long>>
    ) {
        val meteredTotal: Long get() = bytes.values.sumOf { it.first }
        val unmeteredTotal: Long get() = bytes.values.sumOf { it.second }
        val total: Long get() = meteredTotal + unmeteredTotal
    }

    private const val PREFS = "data_usage_stats"
    private const val KEY_SINCE = "since_ms"
    private const val PERSIST_INTERVAL_MS = 30_000L
    private const val LOG_EVERY_BYTES = 25L * 1024L * 1024L

    private val lock = Any()
    private val metered = LongArray(Kind.entries.size)
    private val unmetered = LongArray(Kind.entries.size)
    private var sinceMs = 0L
    private var loaded = false
    private var lastPersistElapsedMs = 0L
    private var bytesSinceLog = 0L

    /**
     * Pure mapping to the counter bucket. Progressive loads report an unknown track type, so the
     * googlevideo `mime=` query parameter (`audio/webm`, `video/mp4` …) decides there; muxed
     * itags such as 18 carry a video mime and count as video.
     */
    internal fun kindOf(trackType: Int, dataType: Int, mimeParam: String?): Kind = when {
        dataType == C.DATA_TYPE_MANIFEST || dataType == C.DATA_TYPE_DRM -> Kind.OTHER
        trackType == C.TRACK_TYPE_VIDEO -> Kind.VIDEO
        trackType == C.TRACK_TYPE_AUDIO -> Kind.AUDIO
        // Muxed HLS segments report the default (mixed) type.
        trackType == C.TRACK_TYPE_DEFAULT -> Kind.MUXED
        mimeParam?.startsWith("audio/") == true -> Kind.AUDIO
        mimeParam?.startsWith("video/") == true -> Kind.VIDEO
        else -> Kind.OTHER
    }

    fun record(context: Context, kind: Kind, bytes: Long, onMetered: Boolean) {
        if (bytes <= 0L) return
        val appContext = context.applicationContext
        var logLine: String? = null
        synchronized(lock) {
            ensureLoaded(appContext)
            val index = kind.ordinal
            if (onMetered) metered[index] += bytes else unmetered[index] += bytes
            bytesSinceLog += bytes
            val now = SystemClock.elapsedRealtime()
            if (now - lastPersistElapsedMs >= PERSIST_INTERVAL_MS) {
                lastPersistElapsedMs = now
                persistLocked(appContext)
            }
            if (bytesSinceLog >= LOG_EVERY_BYTES) {
                bytesSinceLog = 0L
                logLine = describeLocked()
            }
        }
        logLine?.let { HeadUnitDiagnostics.event("DataUsage", it) }
    }

    fun snapshot(context: Context): Snapshot = synchronized(lock) {
        ensureLoaded(context.applicationContext)
        Snapshot(
            sinceMs = sinceMs,
            bytes = Kind.entries.associateWith { metered[it.ordinal] to unmetered[it.ordinal] }
        )
    }

    fun reset(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            ensureLoaded(appContext)
            metered.fill(0L)
            unmetered.fill(0L)
            sinceMs = System.currentTimeMillis()
            bytesSinceLog = 0L
            persistLocked(appContext)
        }
        HeadUnitDiagnostics.event("DataUsage", "reset")
    }

    fun flush(context: Context) {
        synchronized(lock) {
            if (!loaded) return
            persistLocked(context.applicationContext)
        }
    }

    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
        else String.format(Locale.US, "%.1f MB", mb)
    }

    private fun describeLocked(): String = buildString {
        append("totals")
        Kind.entries.forEach { kind ->
            append(' ').append(kind.key).append("Metered=").append(formatBytes(metered[kind.ordinal]))
            append(' ').append(kind.key).append("Unmetered=").append(formatBytes(unmetered[kind.ordinal]))
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Kind.entries.forEach { kind ->
            metered[kind.ordinal] = prefs.getLong("${kind.key}_metered", 0L)
            unmetered[kind.ordinal] = prefs.getLong("${kind.key}_unmetered", 0L)
        }
        sinceMs = prefs.getLong(KEY_SINCE, 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
        loaded = true
    }

    private fun persistLocked(context: Context) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        Kind.entries.forEach { kind ->
            editor.putLong("${kind.key}_metered", metered[kind.ordinal])
            editor.putLong("${kind.key}_unmetered", unmetered[kind.ordinal])
        }
        editor.putLong(KEY_SINCE, sinceMs).apply()
    }

    /** Counts completed, canceled and failed loads: all of them used the network. */
    @OptIn(markerClass = [UnstableApi::class])
    internal class Listener(private val context: Context) : AnalyticsListener {
        private fun count(info: LoadEventInfo, data: MediaLoadData) {
            if (info.bytesLoaded <= 0L) return
            val mime = runCatching { info.uri.getQueryParameter("mime") }.getOrNull()
            record(
                context,
                kindOf(data.trackType, data.dataType, mime),
                info.bytesLoaded,
                NetworkPolicy.isMeteredCached(context)
            )
        }

        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) =
            count(loadEventInfo, mediaLoadData)

        override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) =
            count(loadEventInfo, mediaLoadData)

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) = count(loadEventInfo, mediaLoadData)
    }
}
