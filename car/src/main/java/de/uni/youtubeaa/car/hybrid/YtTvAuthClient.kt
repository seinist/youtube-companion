package de.uni.youtubeaa.car.hybrid

import de.uni.youtubeaa.car.AppText
import de.uni.youtubeaa.car.DataUsageStats
import de.uni.youtubeaa.car.R
import de.uni.youtubeaa.car.SharedHttp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.UnknownHostException
import java.util.UUID
import de.uni.youtubeaa.car.BuildConfig
import java.util.concurrent.TimeUnit

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSec: Int,
    val expiresInSec: Int
)

/**
 * YouTube TV device OAuth **only via www.youtube.com** (no oauth2.googleapis.com),
 * so networks that block Google API hosts can still complete login.
 */
class YtTvAuthClient(
    private val http: OkHttpClient = defaultClient()
) {

    data class ClientIdentity(val clientId: String, val clientSecret: String)

    suspend fun resolveClientIdentity(): ClientIdentity = withContext(Dispatchers.IO) {
        scrapeTvClientIdentity()
            ?: configuredClientIdentity()
            ?: throw IllegalStateException(AppText.get(R.string.auth_error_client_identity))
    }

    private fun configuredClientIdentity(): ClientIdentity? {
        val clientId = BuildConfig.YOUTUBE_TV_CLIENT_ID.trim()
        val clientSecret = BuildConfig.YOUTUBE_TV_CLIENT_SECRET.trim()
        return if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
            ClientIdentity(clientId, clientSecret)
        } else {
            null
        }
    }

    suspend fun requestDeviceCode(
        client: ClientIdentity
    ): DeviceCodeResponse = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("client_id", client.clientId)
                .put(
                    "scope",
                    "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
                )
                .put("device_id", UUID.randomUUID().toString().replace("-", "").take(32))
                .put("device_model", "ytlr::")
                .toString()
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(DEVICE_CODE_URL)
                .post(payload)
                .header("Content-Type", "application/json")
                .header("User-Agent", TV_UA)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/tv")
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("device/code failed: HTTP ${response.code} $text")
                }
                parseDeviceCode(text)
            }
        } catch (e: UnknownHostException) {
            throw IllegalStateException(
                AppText.get(R.string.auth_error_dns, e.message.orEmpty()),
                e
            )
        }
    }

    suspend fun pollForTokens(
        device: DeviceCodeResponse,
        client: ClientIdentity,
        timeoutMs: Long = device.expiresInSec * 1000L,
        onStatus: (suspend (String) -> Unit)? = null
    ): YtTvTokens = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var intervalMs = device.intervalSec * 1000L
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            attempts++
            val result = try {
                pollOnce(device.deviceCode, client)
            } catch (e: UnknownHostException) {
                // Transient DNS hiccups (e.g. switching back from the browser) shouldn't
                // abort a login that may already be confirmed server-side — keep polling
                // until the deadline instead of failing on the first blip.
                null
            }
            onStatus?.invoke(
                if (result == null) AppText.get(R.string.auth_status_dns_retry, attempts)
                else AppText.get(R.string.auth_status_waiting, attempts)
            )
            val outcome = result ?: PollResult.Pending
            when (outcome) {
                is PollResult.Pending -> Unit
                is PollResult.SlowDown -> intervalMs += 2000L
                is PollResult.Denied -> throw IllegalStateException(AppText.get(R.string.auth_error_denied, outcome.reason))
                is PollResult.Success -> return@withContext outcome.tokens
            }
        }
        throw IllegalStateException(AppText.get(R.string.auth_error_timeout))
    }

    private fun pollOnce(deviceCode: String, client: ClientIdentity): PollResult {
        val payload = JSONObject()
            .put("client_id", client.clientId)
            .put("client_secret", client.clientSecret)
            .put("code", deviceCode)
            .put("grant_type", "http://oauth.net/grant_type/device/1.0")
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(payload)
            .header("Content-Type", "application/json")
            .header("User-Agent", TV_UA)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/tv")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return parsePollResponse(text, client) ?: PollResult.Pending
        }
    }

    private fun parsePollResponse(text: String, client: ClientIdentity): PollResult? {
        if (text.isBlank()) return PollResult.Pending
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return PollResult.Pending
        val error = json.optString("error")
        when (error) {
            "authorization_pending" -> return PollResult.Pending
            "slow_down" -> return PollResult.SlowDown
            "access_denied", "expired_token" -> return PollResult.Denied(error)
        }
        if (!json.has("access_token")) {
            return if (error.isNotBlank()) PollResult.Denied(error) else null
        }
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token")
        if (refresh.isBlank()) {
            return PollResult.Denied(AppText.get(R.string.auth_error_no_refresh_token))
        }
        val expiresIn = json.optLong("expires_in", 3600L)
        return PollResult.Success(
            YtTvTokens(
                accessToken = access,
                refreshToken = refresh,
                expiryEpochMs = System.currentTimeMillis() + expiresIn * 1000L,
                clientId = client.clientId,
                clientSecret = client.clientSecret
            )
        )
    }

    private fun parseDeviceCode(text: String): DeviceCodeResponse {
        val json = JSONObject(text)
        // Prefer youtube.com activate URL when Google API DNS is blocked on-device.
        val rawUrl = json.optString("verification_url", "https://www.google.com/device")
        val verificationUrl = when {
            rawUrl.contains("google.com/device") -> "https://www.google.com/device"
            else -> rawUrl
        }
        return DeviceCodeResponse(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = verificationUrl,
            intervalSec = json.optInt("interval", 5).coerceAtLeast(3),
            expiresInSec = json.optInt("expires_in", 1800)
        )
    }

    /**
     * Best-effort real channel name for the account that just signed in, so the app can show
     * who is linked instead of a generic placeholder. Unlike the rest of this class this needs
     * www.googleapis.com — only called after login already succeeded, so a blocked host here
     * just means the caller keeps its fallback label rather than the login itself failing.
     */
    data class ChannelProfile(val title: String?, val avatarUrl: String?)

    suspend fun fetchChannelProfile(accessToken: String): ChannelProfile? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
                .header("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val snippet = JSONObject(response.body?.string().orEmpty())
                    .optJSONArray("items")?.optJSONObject(0)
                    ?.optJSONObject("snippet") ?: return@withContext null
                val thumbs = snippet.optJSONObject("thumbnails")
                val avatar = listOf("maxres", "standard", "high", "medium", "default")
                    .firstNotNullOfOrNull { key ->
                        thumbs?.optJSONObject(key)?.optString("url")?.takeIf { it.isNotBlank() }
                    }
                ChannelProfile(
                    title = snippet.optString("title").takeIf { it.isNotBlank() },
                    avatarUrl = avatar
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchChannelProfile failed: ${e.message}")
            null
        }
    }

    private fun scrapeTvClientIdentity(): ClientIdentity? {
        return try {
            val request = Request.Builder()
                .url("https://www.youtube.com/tv")
                .header("User-Agent", TV_UA)
                .header("Referer", "https://www.youtube.com/tv")
                .header("Accept-Language", "en-US")
                .build()
            http.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                val regex = Regex(
                    """clientId:"(\d{10,}-[a-z0-9]+\.apps\.googleusercontent\.com)",F:"([^"]+)""""
                )
                val match = regex.find(html) ?: return null
                ClientIdentity(match.groupValues[1], match.groupValues[2])
            }
        } catch (e: Exception) {
            Log.w(TAG, "TV client scrape failed: ${e.message}")
            null
        }
    }

    suspend fun refresh(tokens: YtTvTokens): YtTvTokens = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("client_id", tokens.clientId)
                .put("client_secret", tokens.clientSecret)
                .put("refresh_token", tokens.refreshToken)
                .put("grant_type", "refresh_token")
                .toString()
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(payload)
                .header("Content-Type", "application/json")
                .header("User-Agent", TV_UA)
                .header("Origin", "https://www.youtube.com")
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(AppText.get(R.string.auth_error_refresh_failed, response.code, text))
                }
                val json = JSONObject(text)
                val expiresIn = json.optLong("expires_in", 3600L)
                tokens.copy(
                    accessToken = json.getString("access_token"),
                    expiryEpochMs = System.currentTimeMillis() + expiresIn * 1000L
                )
            }
        } catch (e: UnknownHostException) {
            throw IllegalStateException(AppText.get(R.string.auth_error_refresh_dns), e)
        }
    }

    private sealed class PollResult {
        data object Pending : PollResult()
        data object SlowDown : PollResult()
        data class Denied(val reason: String) : PollResult()
        data class Success(val tokens: YtTvTokens) : PollResult()
    }

    companion object {
        private const val TAG = "YtTvAuthClient"
        private const val TV_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        private const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
        private const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient =
            SharedHttp.builder(DataUsageStats.Subsystem.OTHER)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}
