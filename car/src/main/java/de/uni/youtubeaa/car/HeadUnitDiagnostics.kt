package de.uni.youtubeaa.car

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Persistent diagnostics for Android-Auto projection/head-unit failures.
 *
 * Logs are kept in app-private storage, rotated to avoid unbounded growth, and can be exported
 * from the phone Settings screen. Logging must never be allowed to crash playback itself.
 */
object HeadUnitDiagnostics {
    private const val TAG = "YTAA-HU"
    private const val DIR_NAME = "head_unit_logs"
    private const val ACTIVE_FILE = "head-unit.log"
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_ARCHIVES = 4

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ytaa-diagnostics").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var installedHandler = false

    private val timestamp = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            ensureDir()
            if (!installedHandler) {
                installUncaughtExceptionHandler()
                installedHandler = true
            }
        }
        event("Diagnostics", "initialized pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")
    }

    fun event(component: String, message: String) {
        write("I", component, message, null)
    }

    fun warning(component: String, message: String, error: Throwable? = null) {
        write("W", component, message, error)
    }

    fun error(component: String, message: String, error: Throwable? = null) {
        write("E", component, message, error)
    }

    fun listLogFiles(context: Context? = appContext): List<File> {
        val ctx = context ?: return emptyList()
        return File(ctx.filesDir, DIR_NAME)
            .listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun clear(context: Context? = appContext) {
        val ctx = context ?: return
        flushPendingWrites()
        synchronized(lock) {
            File(ctx.filesDir, DIR_NAME).listFiles()?.forEach { runCatching { it.delete() } }
        }
        event("Diagnostics", "logs cleared by user")
    }

    /** Creates a shareable ZIP in cache; caller exposes it through FileProvider. */
    fun createExportZip(context: Context): File {
        initialize(context)
        event("Diagnostics", "export requested")
        flushPendingWrites()
        val exportDir = File(context.cacheDir, "diagnostics_export").apply { mkdirs() }
        exportDir.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(exportDir, "youtube-aa-head-unit-logs-${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("device-info.txt"))
            zip.write(deviceInfo(context).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            listLogFiles(context).forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    private fun write(level: String, component: String, message: String, error: Throwable?) {
        val line = runCatching { buildString {
            append(requireNotNull(timestamp.get()).format(Date()))
            append(' ')
            append(level)
            append(" [")
            append(Thread.currentThread().name)
            append("] ")
            append(component)
            append(": ")
            append(message.replace('\n', ' '))
            if (error != null) {
                append('\n')
                append(stackTrace(error))
            }
            append('\n')
        } }.getOrElse {
            "${System.currentTimeMillis()} $level [${Thread.currentThread().name}] $component: $message\n"
        }

        when (level) {
            "E" -> Log.e(TAG, "$component: $message", error)
            "W" -> Log.w(TAG, "$component: $message", error)
            else -> Log.i(TAG, "$component: $message")
        }

        val ctx = appContext ?: return
        val persist = {
            persistLine(ctx, line)
        }
        if (component == "FATAL") persist() else runCatching { writer.execute(persist) }
    }

    private fun persistLine(ctx: Context, line: String) {
        runCatching {
            synchronized(lock) {
                val dir = ensureDir(ctx)
                rotateIfNeeded(dir, line.toByteArray(Charsets.UTF_8).size.toLong())
                File(dir, ACTIVE_FILE).appendText(line, Charsets.UTF_8)
            }
        }.onFailure {
            Log.e(TAG, "Failed to persist diagnostics", it)
        }
    }

    private fun flushPendingWrites() {
        runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) }
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                error(
                    "FATAL",
                    "uncaught exception thread=${thread.name} class=${throwable.javaClass.name} message=${throwable.message}",
                    throwable
                )
            } catch (_: Throwable) {
                // Never mask the original fatal exception.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun ensureDir(context: Context? = appContext): File {
        val ctx = requireNotNull(context) { "HeadUnitDiagnostics not initialized" }
        return File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
    }

    private fun rotateIfNeeded(dir: File, incomingBytes: Long) {
        val active = File(dir, ACTIVE_FILE)
        if (!active.exists() || active.length() + incomingBytes <= MAX_FILE_BYTES) return

        File(dir, "head-unit.$MAX_ARCHIVES.log").delete()
        for (i in MAX_ARCHIVES - 1 downTo 1) {
            val source = File(dir, "head-unit.$i.log")
            if (source.exists()) source.renameTo(File(dir, "head-unit.${i + 1}.log"))
        }
        active.renameTo(File(dir, "head-unit.1.log"))
    }

    private fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    private fun deviceInfo(context: Context): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return buildString {
            appendLine("package=${context.packageName}")
            appendLine("versionName=${packageInfo?.versionName.orEmpty()}")
            appendLine("versionCode=${packageInfo?.longVersionCode ?: 0L}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("fingerprint=${Build.FINGERPRINT}")
            appendLine("generated=${requireNotNull(timestamp.get()).format(Date())}")
        }
    }
}
