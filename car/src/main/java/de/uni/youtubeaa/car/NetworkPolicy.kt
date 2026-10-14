package de.uni.youtubeaa.car

import android.content.Context
import android.net.ConnectivityManager

/**
 * Data-saver signal, mirroring the official app: on a metered connection (mobile data, phone
 * hotspot) "Auto" quality stays low instead of spending the plan on pixels nobody sees.
 */
object NetworkPolicy {
    fun isMetered(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        return try {
            manager.isActiveNetworkMetered
        } catch (_: SecurityException) {
            false
        }
    }
}
