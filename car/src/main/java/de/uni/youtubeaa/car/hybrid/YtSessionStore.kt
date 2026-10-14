@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car.hybrid

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

data class YtTvTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiryEpochMs: Long,
    val clientId: String,
    val clientSecret: String,
    val accountLabel: String? = null,
    val accountAvatarUrl: String? = null
) {
    fun isAccessExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= expiryEpochMs - 60_000L
}

/**
 * Encrypted storage for YouTube TV OAuth tokens (no Google Cloud Console client).
 */
class YtSessionStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = createEncryptedPreferences()

    private fun createEncryptedPreferences(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (first: Exception) {
        if (first !is GeneralSecurityException && first !is IOException) throw first
        appContext.deleteSharedPreferences(PREFS_NAME)
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (retry: Exception) {
            throw IllegalStateException("YtSessionStore init failed", retry)
        }
    }

    fun isSignedIn(): Boolean = getTokens()?.refreshToken?.isNotBlank() == true

    fun getTokens(): YtTvTokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val clientId = prefs.getString(KEY_CLIENT_ID, null) ?: return null
        val clientSecret = prefs.getString(KEY_CLIENT_SECRET, null) ?: return null
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        if (refresh.isBlank()) return null
        return YtTvTokens(
            accessToken = access,
            refreshToken = refresh,
            expiryEpochMs = expiry,
            clientId = clientId,
            clientSecret = clientSecret,
            accountLabel = prefs.getString(KEY_LABEL, null),
            accountAvatarUrl = prefs.getString(KEY_AVATAR, null)
        )
    }

    fun saveIfCurrent(expectedRefreshToken: String, tokens: YtTvTokens): Boolean = synchronized(WRITE_LOCK) {
        val latest = getTokens()?.takeIf { it.refreshToken == expectedRefreshToken } ?: return@synchronized false
        save(tokens.copy(accountLabel = latest.accountLabel, accountAvatarUrl = latest.accountAvatarUrl))
        true
    }

    fun updateProfile(expectedRefreshToken: String, profile: YtTvAuthClient.ChannelProfile?): YtTvTokens? = synchronized(WRITE_LOCK) {
        val latest = getTokens()?.takeIf { it.refreshToken == expectedRefreshToken } ?: return@synchronized null
        val updated = latest.copy(
            accountLabel = AccountProfileParser.displayName(profile?.title) ?: AccountProfileParser.displayName(latest.accountLabel),
            accountAvatarUrl = profile?.avatarUrl ?: latest.accountAvatarUrl
        )
        save(updated)
        updated
    }

    fun save(tokens: YtTvTokens) = synchronized(WRITE_LOCK) {
        if (getTokens()?.refreshToken != tokens.refreshToken) de.uni.youtubeaa.car.BrowseListCache.invalidate()
        // apply() updates the in-memory preferences immediately and persists off the UI thread.
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRY, tokens.expiryEpochMs)
            .putString(KEY_CLIENT_ID, tokens.clientId)
            .putString(KEY_CLIENT_SECRET, tokens.clientSecret)
            .putString(KEY_LABEL, tokens.accountLabel)
            .putString(KEY_AVATAR, tokens.accountAvatarUrl)
            .apply()
    }

    fun clear() = synchronized(WRITE_LOCK) {
        de.uni.youtubeaa.car.BrowseListCache.invalidate()
        prefs.edit().clear().apply()
    }

    companion object {
        private val WRITE_LOCK = Any()
        private const val PREFS_NAME = "yt_tv_session"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_EXPIRY = "expiry"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_LABEL = "label"
        private const val KEY_AVATAR = "avatar"
    }
}
