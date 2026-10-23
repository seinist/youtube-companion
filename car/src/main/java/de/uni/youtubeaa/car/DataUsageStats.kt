package de.uni.youtubeaa.car

import android.content.Context
import android.net.TrafficStats
import android.os.Process
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
 * Bytes this app put through the network, persisted lightly (throttled SharedPreferences writes,
 * flushed when an Activity stops, the player pauses or is released) so the totals survive
 * restarts and can be compared with Android's per-app data usage:
 * - [Kind]: what the players downloaded (Media3 load events), split by track type;
 * - [Subsystem]: everything that went through OkHttp ([SharedHttp]), split by purpose;
 * - UID total: [TrafficStats] for the whole app UID (includes WebView and anything uncounted).
 * Each is split into metered/unmetered by the network at the time it was counted. Shown in phone
 * Settings → Diagnostics and written to the head-unit log.
 */
object DataUsageStats {
    enum class Kind(val key: String) { VIDEO("video"), AUDIO("audio"), MUXED("muxed"), OTHER("other") }

    /** OkHttp traffic by purpose; see [SharedHttp.builder]. */
    enum class Subsystem(val key: String) {
        SEARCH("net_search"), FEED("net_feed"), IMAGES("net_images"), EXTRACTOR("net_extractor"), OTHER("net_other")
    }

    data class Snapshot(
        val sinceMs: Long,
        /** [Kind] → (metered bytes, unmetered bytes). */
        val bytes: Map<Kind, Pair<Long, Long>>,
        /** [Subsystem] → (metered bytes, unmetered bytes). */
        val network: Map<Subsystem, Pair<Long, Long>> = emptyMap(),
        /** Whole app UID (rx + tx) → (metered, unmetered); null when TrafficStats is unsupported. */
        val uidTotal: Pair<Long, Long>? = null
    ) {
        val meteredTotal: Long get() = bytes.values.sumOf { it.first }
        val unmeteredTotal: Long get() = bytes.values.sumOf { it.second }
        val total: Long get() = meteredTotal + unmeteredTotal
        val networkTotal: Long get() = network.values.sumOf { it.first + it.second }
    }

    private const val PREFS = "data_usage_stats"
    private const val KEY_SINCE = "since_ms"
    private const val KEY_UID_METERED = "uid_metered"
    private const val KEY_UID_UNMETERED = "uid_unmetered"
    private const val KEY_UID_LAST = "uid_last_bytes"
    private const val KEY_UID_BOOT = "uid_last_boot_ms"
    private const val PERSIST_INTERVAL_MS = 10_000L
    private const val LOG_EVERY_BYTES = 25L * 1024L * 1024L
    /** Two boot-time estimates closer than this are the same boot. */
    private const val BOOT_TOLERANCE_MS = 60_000L

    private val lock = Any()
    private val metered = LongArray(Kind.entries.size)
    private val unmetered = LongArray(Kind.entries.size)
    private val netMetered = LongArray(Subsystem.entries.size)
    private val netUnmetered = LongArray(Subsystem.entries.size)
    private var uidMetered = 0L
    private var uidUnmetered = 0L
    private var uidLastBytes = -1L
    private var uidLastBootMs = 0L
    private var sinceMs = 0L
    private var loaded = false
    private var lastPersistElapsedMs = 0L
    private var bytesSinceLog = 0L
    @Volatile private var appContext: Context? = null

    /** Called once from Application.onCreate; enables network counting without a Context. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Pure mapping to the counter bucket, by Media3's own track type first:
     * - manifests/DRM are overhead;
     * - `TRACK_TYPE_VIDEO` / `TRACK_TYPE_AUDIO` (DASH, HLS audio renditions) are taken as is;
     * - `TRACK_TYPE_DEFAULT` is HLS's main variant wrapper: video-only when the playlist has
     *   separate audio renditions ([hlsVariantsAreVideoOnly], YouTube), otherwise muxed;
     * - progressive loads report no track type; their format MIME or, failing that, the
     *   googlevideo `mime=` query decides (muxed itags such as 18 carry a video mime).
     */
    internal fun kindOf(
        trackType: Int,
        dataType: Int,
        mimeParam: String?,
        formatMime: String? = null,
        hlsVariantsAreVideoOnly: Boolean = false
    ): Kind = when {
        dataType == C.DATA_TYPE_MANIFEST || dataType == C.DATA_TYPE_DRM -> Kind.OTHER
        trackType == C.TRACK_TYPE_VIDEO -> Kind.VIDEO
        trackType == C.TRACK_TYPE_AUDIO -> Kind.AUDIO
        trackType == C.TRACK_TYPE_DEFAULT -> if (hlsVariantsAreVideoOnly) Kind.VIDEO else Kind.MUXED
        formatMime?.startsWith("audio/") == true -> Kind.AUDIO
        formatMime?.startsWith("video/") == true -> Kind.VIDEO
        mimeParam?.startsWith("audio/") == true -> Kind.AUDIO
        mimeParam?.startsWith("video/") == true -> Kind.VIDEO
        else -> Kind.OTHER
    }

    /**
     * Pure purpose mapping for an OkHttp request: InnerTube/NewPipe search → search, list
     * endpoints (browse, kiosks, channels, feeds) → feed, watch-page/player/next and HLS master
 * playlists fetched by the resolver → extractor,
     * image hosts → images. [default] wins for clients with a fixed purpose.
     */
    internal fun subsystemOf(host: String, path: String, default: Subsystem?): Subsystem {
        default?.let { return it }
        val h = host.lowercase(Locale.ROOT)
        val p = path.lowercase(Locale.ROOT)
        return when {
            h.endsWith("ytimg.com") || h.endsWith("ggpht.com") || h.endsWith("googleusercontent.com") -> Subsystem.IMAGES
            h == "manifest.googlevideo.com" -> Subsystem.EXTRACTOR
            p.contains("/youtubei/v1/search") || p.startsWith("/results") -> Subsystem.SEARCH
            p.contains("/youtubei/v1/browse") || p.startsWith("/feed") || p.startsWith("/channel") ||
                p.startsWith("/@") || p.startsWith("/c/") || p.startsWith("/user") -> Subsystem.FEED
            p.contains("/youtubei/v1/player") || p.contains("/youtubei/v1/next") ||
                p.contains("/youtubei/v1/reel") || p.contains("/youtubei/v1/visitor_id") ||
                p.startsWith("/watch") || p.startsWith("/s/player") || p.startsWith("/sw.js") ||
                p.startsWith("/embed") || p.startsWith("/iframe_api") -> Subsystem.EXTRACTOR
            else -> Subsystem.OTHER
        }
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
            logLine = afterRecordLocked(appContext)
        }
        logLine?.let { HeadUnitDiagnostics.event("DataUsage", it) }
    }

    /** OkHttp bytes (request + response, as on the wire) for [subsystem]. No-op before [initialize]. */
    fun recordNetwork(subsystem: Subsystem, bytes: Long) {
        if (bytes <= 0L) return
        val context = appContext ?: return
        val onMetered = NetworkPolicy.isMeteredCached(context)
        var logLine: String? = null
        synchronized(lock) {
            ensureLoaded(context)
            if (onMetered) netMetered[subsystem.ordinal] += bytes else netUnmetered[subsystem.ordinal] += bytes
            logLine = afterRecordLocked(context)
        }
        logLine?.let { HeadUnitDiagnostics.event("DataUsage", it) }
    }

    private fun afterRecordLocked(context: Context): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPersistElapsedMs >= PERSIST_INTERVAL_MS) {
            lastPersistElapsedMs = now
            sampleUidLocked(context)
            persistLocked(context)
        }
        if (bytesSinceLog >= LOG_EVERY_BYTES) {
            bytesSinceLog = 0L
            return describeLocked()
        }
        return null
    }

    fun snapshot(context: Context): Snapshot = synchronized(lock) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        sampleUidLocked(appContext)
        Snapshot(
            sinceMs = sinceMs,
            bytes = Kind.entries.associateWith { metered[it.ordinal] to unmetered[it.ordinal] },
            network = Subsystem.entries.associateWith { netMetered[it.ordinal] to netUnmetered[it.ordinal] },
            uidTotal = if (uidLastBytes >= 0L) uidMetered to uidUnmetered else null
        )
    }

    fun reset(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            ensureLoaded(appContext)
            metered.fill(0L)
            unmetered.fill(0L)
            netMetered.fill(0L)
            netUnmetered.fill(0L)
            uidMetered = 0L
            uidUnmetered = 0L
            // New baseline: count from now on.
            uidLastBytes = uidBytesNow()
            uidLastBootMs = bootTimeMs()
            sinceMs = System.currentTimeMillis()
            bytesSinceLog = 0L
            persistLocked(appContext)
        }
        HeadUnitDiagnostics.event("DataUsage", "reset")
    }

    /** Persist now (Activity stopped, playback paused, player released, memory trim). */
    fun flush(context: Context) {
        synchronized(lock) {
            if (!loaded) return
            val appContext = context.applicationContext
            sampleUidLocked(appContext)
            persistLocked(appContext)
            lastPersistElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
        else String.format(Locale.US, "%.1f MB", mb)
    }

    /**
     * Pure UID delta: the counters restart at boot, and [TrafficStats] returns
     * [TrafficStats.UNSUPPORTED] (-1) on some devices. A different boot or a counter that went
     * backwards counts [current] from zero; no previous sample counts nothing (baseline only).
     */
    internal fun uidDelta(previous: Long, current: Long, sameBoot: Boolean): Long = when {
        current < 0L -> 0L
        previous < 0L -> 0L
        !sameBoot || current < previous -> current
        else -> current - previous
    }

    private fun uidBytesNow(): Long {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return -1L
        return rx + tx
    }

    private fun bootTimeMs(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Adds the UID traffic since the last sample to the bucket of the current network. */
    private fun sampleUidLocked(context: Context) {
        val current = uidBytesNow()
        if (current < 0L) return
        val boot = bootTimeMs()
        val sameBoot = uidLastBootMs != 0L && kotlin.math.abs(boot - uidLastBootMs) < BOOT_TOLERANCE_MS
        val delta = uidDelta(uidLastBytes, current, sameBoot)
        if (delta > 0L) {
            if (NetworkPolicy.isMeteredCached(context)) uidMetered += delta else uidUnmetered += delta
        }
        uidLastBytes = current
        uidLastBootMs = boot
    }

    private fun describeLocked(): String = buildString {
        append("totals")
        Kind.entries.forEach { kind ->
            append(' ').append(kind.key).append("Metered=").append(formatBytes(metered[kind.ordinal]))
            append(' ').append(kind.key).append("Unmetered=").append(formatBytes(unmetered[kind.ordinal]))
        }
        Subsystem.entries.forEach { sub ->
            append(' ').append(sub.key).append('=')
                .append(formatBytes(netMetered[sub.ordinal] + netUnmetered[sub.ordinal]))
        }
        append(" uidMetered=").append(formatBytes(uidMetered))
        append(" uidUnmetered=").append(formatBytes(uidUnmetered))
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Kind.entries.forEach { kind ->
            metered[kind.ordinal] = prefs.getLong("${kind.key}_metered", 0L)
            unmetered[kind.ordinal] = prefs.getLong("${kind.key}_unmetered", 0L)
        }
        Subsystem.entries.forEach { sub ->
            netMetered[sub.ordinal] = prefs.getLong("${sub.key}_metered", 0L)
            netUnmetered[sub.ordinal] = prefs.getLong("${sub.key}_unmetered", 0L)
        }
        uidMetered = prefs.getLong(KEY_UID_METERED, 0L)
        uidUnmetered = prefs.getLong(KEY_UID_UNMETERED, 0L)
        uidLastBytes = prefs.getLong(KEY_UID_LAST, -1L)
        uidLastBootMs = prefs.getLong(KEY_UID_BOOT, 0L)
        sinceMs = prefs.getLong(KEY_SINCE, 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
        loaded = true
    }

    private fun persistLocked(context: Context) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        Kind.entries.forEach { kind ->
            editor.putLong("${kind.key}_metered", metered[kind.ordinal])
            editor.putLong("${kind.key}_unmetered", unmetered[kind.ordinal])
        }
        Subsystem.entries.forEach { sub ->
            editor.putLong("${sub.key}_metered", netMetered[sub.ordinal])
            editor.putLong("${sub.key}_unmetered", netUnmetered[sub.ordinal])
        }
        editor.putLong(KEY_UID_METERED, uidMetered)
            .putLong(KEY_UID_UNMETERED, uidUnmetered)
            .putLong(KEY_UID_LAST, uidLastBytes)
            .putLong(KEY_UID_BOOT, uidLastBootMs)
        editor.putLong(KEY_SINCE, sinceMs).apply()
    }

    /**
     * Media3 reports cumulative bytes for a load task on error/retry/completion callbacks. Count
     * only the unseen delta or the diagnostics can claim substantially more traffic than the UID
     * actually used whenever a CDN request is retried.
     */
    internal class LoadByteDeltaTracker {
        private val lastBytesByTask = HashMap<Long, Long>()

        fun delta(loadTaskId: Long, bytesLoaded: Long, terminal: Boolean): Long {
            val current = bytesLoaded.coerceAtLeast(0L)
            val previous = lastBytesByTask[loadTaskId] ?: 0L
            val delta = (current - previous).coerceAtLeast(0L)
            if (terminal) lastBytesByTask.remove(loadTaskId)
            else lastBytesByTask[loadTaskId] = maxOf(previous, current)
            return delta
        }

        fun clear() = lastBytesByTask.clear()
    }

    /** Actual downloaded player bytes per minute of active playback, independent of resolution. */
    internal class PlaybackRateMeter {
        data class RateSnapshot(
            val playedMs: Long,
            val bytes: Map<Kind, Long>
        ) {
            val totalBytes: Long get() = bytes.values.sum()
            val bytesPerMinute: Double
                get() = if (playedMs > 0L) totalBytes.toDouble() * 60_000.0 / playedMs else 0.0
        }

        private val bytes = LongArray(Kind.entries.size)
        private var accumulatedPlayedMs = 0L
        private var playingSinceMs: Long? = null

        fun add(kind: Kind, byteCount: Long) {
            if (byteCount > 0L) bytes[kind.ordinal] += byteCount
        }

        fun onPlayingChanged(isPlaying: Boolean, nowMs: Long) {
            if (isPlaying) {
                if (playingSinceMs == null) playingSinceMs = nowMs
            } else {
                val started = playingSinceMs ?: return
                accumulatedPlayedMs += (nowMs - started).coerceAtLeast(0L)
                playingSinceMs = null
            }
        }

        fun snapshot(nowMs: Long): RateSnapshot {
            val activeMs = playingSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
            return RateSnapshot(
                playedMs = accumulatedPlayedMs + activeMs,
                bytes = Kind.entries.associateWith { bytes[it.ordinal] }
            )
        }
    }

    /**
     * Counts completed, canceled and failed loads: all of them used the network. Pausing and
     * releasing the player flush the totals, so a killed process loses at most the loads that
     * were still running (the UID total still sees those bytes).
     */
    @OptIn(markerClass = [UnstableApi::class])
    internal class Listener(
        private val context: Context,
        private val hlsVariantsAreVideoOnly: () -> Boolean = { false },
        private val diagnosticTag: String = "Player"
    ) : AnalyticsListener {
        private val loadDeltas = LoadByteDeltaTracker()
        private val rateMeter = PlaybackRateMeter()
        private var lastRateReportPlayedMs = 0L
        private var lastRateReportBytes = 0L

        private fun count(info: LoadEventInfo, data: MediaLoadData, terminal: Boolean) {
            val byteDelta = loadDeltas.delta(info.loadTaskId, info.bytesLoaded, terminal)
            if (byteDelta <= 0L) return
            val mime = runCatching { info.uri.getQueryParameter("mime") }.getOrNull()
            val format = data.trackFormat
            val kind =
                kindOf(
                    data.trackType,
                    data.dataType,
                    mime,
                    formatMime = format?.sampleMimeType,
                    hlsVariantsAreVideoOnly = data.trackType == C.TRACK_TYPE_DEFAULT && hlsVariantsAreVideoOnly()
                )
            record(
                context,
                kind,
                byteDelta,
                NetworkPolicy.isMeteredCached(context)
            )
            rateMeter.add(kind, byteDelta)
            reportRateIfDue(force = false)
        }

        private fun reportRateIfDue(force: Boolean) {
            val snapshot = rateMeter.snapshot(SystemClock.elapsedRealtime())
            if (snapshot.playedMs < MIN_RATE_SAMPLE_MS || snapshot.totalBytes <= 0L) return
            val enoughTime = snapshot.playedMs - lastRateReportPlayedMs >= RATE_REPORT_INTERVAL_MS
            val hasNewBytes = snapshot.totalBytes > lastRateReportBytes
            if (!enoughTime && !(force && hasNewBytes)) return
            lastRateReportPlayedMs = snapshot.playedMs
            lastRateReportBytes = snapshot.totalBytes
            val mibPerMinute = snapshot.bytesPerMinute / (1024.0 * 1024.0)
            HeadUnitDiagnostics.event(
                diagnosticTag,
                "traffic-rate playedMs=${snapshot.playedMs} media=${formatBytes(snapshot.totalBytes)} " +
                    "rateMiBPerMin=${String.format(Locale.US, "%.2f", mibPerMinute)} " +
                    Kind.entries.joinToString(" ") { kind ->
                        "${kind.key}=${formatBytes(snapshot.bytes[kind] ?: 0L)}"
                    }
            )
        }

        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) =
            count(loadEventInfo, mediaLoadData, terminal = true)

        override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) =
            count(loadEventInfo, mediaLoadData, terminal = true).also { reportRateIfDue(force = true) }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) = count(loadEventInfo, mediaLoadData, terminal = false)

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            rateMeter.onPlayingChanged(isPlaying, SystemClock.elapsedRealtime())
            if (!isPlaying) {
                reportRateIfDue(force = true)
                flush(context)
            }
        }

        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
            rateMeter.onPlayingChanged(false, SystemClock.elapsedRealtime())
            reportRateIfDue(force = true)
            loadDeltas.clear()
            flush(context)
        }

        private companion object {
            const val MIN_RATE_SAMPLE_MS = 10_000L
            const val RATE_REPORT_INTERVAL_MS = 60_000L
        }
    }
}
