package de.uni.youtubeaa.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Data-saver signal, mirroring the official app: on a metered connection (mobile data, phone
 * hotspot) quality stays at the user's mobile-data cap instead of spending the plan on pixels
 * nobody sees.
 */
object NetworkPolicy {
    private const val CACHE_MAX_AGE_MS = 5_000L

    @Volatile private var cachedMetered = false
    @Volatile private var cachedAtElapsedMs = 0L

    fun isMetered(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val metered = try {
            manager.isActiveNetworkMetered
        } catch (_: SecurityException) {
            false
        }
        remember(metered)
        return metered
    }

    /**
     * Cheap variant for hot paths (LoadControl runs on the playback thread many times a
     * second): refreshes the binder call at most every few seconds.
     */
    fun isMeteredCached(context: Context): Boolean {
        val age = SystemClock.elapsedRealtime() - cachedAtElapsedMs
        if (cachedAtElapsedMs != 0L && age in 0 until CACHE_MAX_AGE_MS) return cachedMetered
        return isMetered(context)
    }

    internal fun remember(metered: Boolean) {
        cachedMetered = metered
        cachedAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun mobileDataQuality(context: Context): String = SettingsRepository(context).getMobileDataQuality()

    internal fun meteredFrom(capabilities: NetworkCapabilities): Boolean {
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        ) return false
        return true
    }
}

/**
 * Follows the default network while a player screen is alive and reports metered changes on
 * the main thread (Wi-Fi -> mobile data mid-video). Call [start]/[stop] from the owning
 * lifecycle; [stop] always unregisters the system callback.
 */
class MeteredNetworkWatcher(
    context: Context,
    private val onMeteredChanged: (Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastMetered: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val metered = NetworkPolicy.meteredFrom(networkCapabilities)
            NetworkPolicy.remember(metered)
            mainHandler.post { deliver(metered) }
        }
    }

    val isMetered: Boolean
        get() = lastMetered ?: NetworkPolicy.isMetered(appContext)

    fun start() {
        if (registered || manager == null) return
        lastMetered = NetworkPolicy.isMetered(appContext)
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: RuntimeException) {
            // SecurityException / TooManyRequestsException: data saver is best effort.
            HeadUnitDiagnostics.warning("NetworkPolicy", "network callback unavailable: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun deliver(metered: Boolean) {
        if (!registered || metered == lastMetered) return
        lastMetered = metered
        HeadUnitDiagnostics.event("NetworkPolicy", "metered-changed metered=$metered")
        onMeteredChanged(metered)
    }
}
