package de.uni.youtubeaa.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val CACHE_DIR_NAME = "thumbnail_cache_v1"
private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
private const val NEGATIVE_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
private const val DEFAULT_DISK_CACHE_BYTES = 100L * 1024L * 1024L
private const val MAX_DOWNLOAD_BYTES = 12L * 1024L * 1024L
private const val PRUNE_INTERVAL_MS = 15L * 60L * 1000L

/**
 * Central thumbnail loader shared by phone UI, media session and projection UI.
 *
 * It keeps decoded bitmaps in a small memory LRU and downloaded image bytes in app cache storage.
 * Disk entries expire automatically after [CACHE_TTL_MS], failed candidate URLs are remembered for
 * a short period (important for YouTube videos without maxres artwork), and old entries are pruned
 * when the cache grows beyond the configured cache size.
 */
object ThumbnailImageLoader {
    @Volatile
    private var cacheDir: File? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var maxDiskCacheBytes: Long = DEFAULT_DISK_CACHE_BYTES

    @Volatile
    private var lastPruneAtMs: Long = 0L

    private val memoryCache = object : LruCache<String, Bitmap>(memoryCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    // Fixed locks cannot be evicted while another waiter still uses the same URL.
    private val keyLocks = Array(64) { Mutex() }
    // Back to four: the head-unit video decodes in this same process, and eight parallel
    // thumbnail downloads feeding full-size JPEG decodes competed with it for CPU while the
    // phone list was open during car playback.
    private val networkGate = Semaphore(4)
    private val projectionNetworkGate = Semaphore(2)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val projectionDecodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val httpClient = SharedHttp.builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        maxDiskCacheBytes = configuredCacheBytes(applicationContext)
        if (cacheDir != null) return
        synchronized(this) {
            if (cacheDir == null) {
                cacheDir = File(applicationContext.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
            }
        }
    }

    fun peek(url: String?, quality: String = "auto", targetPx: Int = 1280): Bitmap? {
        if (url.isNullOrBlank()) return null
        val size = ThumbnailSizing.bucket(targetPx)
        return ThumbnailSizing.candidates(url, quality, targetPx)
            .firstNotNullOfOrNull { memoryCache.get("$it#$size") }
    }

    suspend fun load(url: String?, quality: String = "auto", targetPx: Int = 1280): Bitmap? {
        if (url.isNullOrBlank()) return null
        peek(url, quality, targetPx)?.let { return it }
        val dispatcher = if (CarPlaybackQos.isProjectedVideoVisible()) projectionDecodeDispatcher else decodeDispatcher
        return withContext(dispatcher) {
            if (!CarPlaybackQos.isProjectedVideoVisible()) pruneCacheIfNeeded()
            ThumbnailSizing.candidates(url, quality, targetPx).firstNotNullOfOrNull { candidate ->
                currentCoroutineContext().ensureActive()
                loadCandidate(candidate, ThumbnailSizing.bucket(targetPx))
            }
        }
    }

    private suspend fun loadCandidate(url: String, size: Int): Bitmap? {
        val memoryKey = "$url#$size"
        memoryCache.get(memoryKey)?.let { return it }
        val key = sha256(url)
        return keyLocks[(url.hashCode() and Int.MAX_VALUE) % keyLocks.size].withLock {
            memoryCache.get(memoryKey)?.let { return@withLock it }
            val dir = cacheDir
            val imageFile = dir?.let { File(it, "$key.img") }
            val missFile = dir?.let { File(it, "$key.miss") }
            val now = System.currentTimeMillis()
            if (missFile != null && missFile.exists()) {
                if (now - missFile.lastModified() <= NEGATIVE_CACHE_TTL_MS) return@withLock null
                missFile.delete()
            }
            if (imageFile != null && imageFile.exists()) {
                if (now - imageFile.lastModified() <= CACHE_TTL_MS) {
                    decodeFile(imageFile, size)?.let { bitmap ->
                        currentCoroutineContext().ensureActive()
                        memoryCache.put(memoryKey, bitmap)
                        return@withLock bitmap
                    }
                }
                imageFile.delete()
            }
            val gate = if (CarPlaybackQos.isProjectedVideoVisible()) projectionNetworkGate else networkGate
            when (val result = gate.withPermit { download(url) }) {
                DownloadResult.Missing -> { markMiss(missFile); null }
                DownloadResult.Failure -> null
                is DownloadResult.Success -> {
                    currentCoroutineContext().ensureActive()
                    val bitmap = decodeBytes(result.bytes, size) ?: return@withLock null
                    currentCoroutineContext().ensureActive()
                    if (imageFile != null) {
                        writeAtomically(imageFile, result.bytes)
                        missFile?.delete()
                    }
                    memoryCache.put(memoryKey, bitmap)
                    bitmap
                }
            }
        }
    }

    private sealed interface DownloadResult {
        data class Success(val bytes: ByteArray) : DownloadResult
        data object Missing : DownloadResult
        data object Failure : DownloadResult
    }

    private suspend fun download(url: String): DownloadResult {
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
        } catch (_: IllegalArgumentException) {
            return DownloadResult.Failure
        }
        return try {
            val response = httpClient.newCall(request).awaitBytes(MAX_DOWNLOAD_BYTES.toInt())
            when {
                response.code == 404 || response.code == 410 -> DownloadResult.Missing
                !response.isSuccessful -> DownloadResult.Failure
                else -> DownloadResult.Success(response.bytes)
            }
        } catch (_: IOException) {
            DownloadResult.Failure
        }
    }

    private fun decodeFile(file: File, targetPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = ThumbnailSizing.sampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
        )
    } catch (_: RuntimeException) {
        file.delete()
        null
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            temp.outputStream().use { it.write(bytes) }
            if (!temp.renameTo(target)) {
                target.outputStream().use { it.write(bytes) }
                temp.delete()
            }
        } catch (_: IOException) {
            temp.delete()
        }
    }

    private fun markMiss(file: File?) {
        if (file == null) return
        try {
            if (!file.exists()) file.createNewFile()
            file.setLastModified(System.currentTimeMillis())
        } catch (_: IOException) {
            // Cache failures must never break image loading.
        }
    }

    private fun pruneCacheIfNeeded() {
        val dir = cacheDir ?: return
        val now = System.currentTimeMillis()
        if (now - lastPruneAtMs < PRUNE_INTERVAL_MS) return
        synchronized(this) {
            if (now - lastPruneAtMs < PRUNE_INTERVAL_MS) return
            lastPruneAtMs = now
        }
        appContext?.let { maxDiskCacheBytes = configuredCacheBytes(it) }
        val files = dir.listFiles()?.toList().orEmpty()

        files.filter { file ->
            val ttl = if (file.name.endsWith(".miss")) NEGATIVE_CACHE_TTL_MS else CACHE_TTL_MS
            now - file.lastModified() > ttl || (file.name.endsWith(".tmp") && now - file.lastModified() > 60_000L)
        }.forEach(File::delete)

        val liveImages = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var total = liveImages.sumOf { it.length() }
        if (total <= maxDiskCacheBytes) return

        for (file in liveImages) {
            if (total <= maxDiskCacheBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun memoryCacheSizeKb(): Int {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (maxKb / 16).coerceIn(4 * 1024, 24 * 1024)
    }

    private fun configuredCacheBytes(context: Context): Long =
        SettingsRepository(context).getCacheSizeMb().coerceIn(16, 1000).toLong() * 1024L * 1024L

    private fun decodeBytes(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = ThumbnailSizing.sampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
        )
    }

}

/** Defaults to full cover quality; list/thumbnail callers supply their display size. */
suspend fun loadThumbnailBitmap(url: String?, quality: String = "auto", targetPx: Int = 1280): Bitmap? =
    ThumbnailImageLoader.load(url, quality, targetPx)
