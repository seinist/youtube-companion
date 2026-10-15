package de.uni.youtubeaa.car.hybrid

import org.json.JSONArray
import org.json.JSONObject

/** Only account renderers may identify the user; recommended channels are never candidates. */
internal object AccountProfileParser {
    /** "YouTube-Konto verknüpft" is a placeholder label older versions stored, not a name; keep it German. */
    fun displayName(value: String?): String? = value?.trim()?.takeUnless {
        it.isEmpty() || it == "null" || it == "YouTube-Konto verknüpft"
    }

    fun parse(root: JSONObject): YtTvAuthClient.ChannelProfile? {
        var active: JSONObject? = null
        var selected: JSONObject? = null
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("activeAccountHeaderRenderer")?.let { active = it }
                    for (key in arrayOf("accountItemRenderer", "accountItem")) {
                        value.optJSONObject(key)?.takeIf { it.optBoolean("isSelected") }?.let { selected = it }
                    }
                    value.keys().forEach { key -> walk(value.opt(key)) }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(root)
        val account = active ?: selected ?: return null
        val title = displayName(text(account.opt("accountName")))
            ?: displayName(text(account.opt("channelHandle")))
        val images = account.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
        var avatar: String? = null
        if (images != null) for (i in 0 until images.length()) {
            images.optJSONObject(i)?.optString("url")?.takeIf { it.startsWith("https://") || it.startsWith("//") }
                ?.let { avatar = if (it.startsWith("//")) "https:$it" else it }
        }
        return if (title != null || avatar != null) YtTvAuthClient.ChannelProfile(title, avatar) else null
    }

    private fun text(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> value.optString("simpleText").takeIf { it.isNotBlank() }
            ?: value.optJSONArray("runs")?.let { runs ->
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }
        else -> null
    }
}
