package de.uni.youtubeaa.car

import android.content.Context
import androidx.preference.PreferenceManager

// Liest/schreibt die Einstellungen aus der Smartphone-App (app/src/main/res/xml/preferences.xml).
// Beide Module laufen im selben App-Prozess, daher genügt der Standard-Preferences-Store
// (PreferenceManager.getDefaultSharedPreferences) ohne zusätzliche IPC.
class SettingsRepository(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun getDefaultTab(): String = prefs.getString(KEY_DEFAULT_TAB, "home") ?: "home"

    fun setDefaultTab(value: String) = putString(KEY_DEFAULT_TAB, value)

    fun isAutoplayEnabled(): Boolean = prefs.getBoolean(KEY_AUTOPLAY, false)

    /** Phone-only hard audio mode. Does not affect the head-unit playback mode. */
    fun isPhoneAudioOnlyEnabled(): Boolean = prefs.getBoolean(KEY_PHONE_AUDIO_ONLY, false)

    fun setPhoneAudioOnlyEnabled(value: Boolean) = putBoolean(KEY_PHONE_AUDIO_ONLY, value)

    fun setAutoplayEnabled(value: Boolean) = putBoolean(KEY_AUTOPLAY, value)

    fun getVideoQuality(): String = prefs.getString(KEY_VIDEO_QUALITY, "auto") ?: "auto"

    fun setVideoQuality(value: String) = putString(KEY_VIDEO_QUALITY, value)

    /** Head-unit-specific video quality; intentionally separate from the phone preference. */
    fun getCarVideoQuality(): String = prefs.getString(KEY_CAR_VIDEO_QUALITY, "auto") ?: "auto"

    fun setCarVideoQuality(value: String) = putString(KEY_CAR_VIDEO_QUALITY, value)

    /** Height cap on metered networks for phone and head unit: `360`/`480`/`720`/`unlimited`. */
    fun getMobileDataQuality(): String =
        DataSaverPolicy.normalizeMobileQuality(prefs.getString(KEY_MOBILE_DATA_QUALITY, DataSaverPolicy.DEFAULT_MOBILE_QUALITY))

    fun setMobileDataQuality(value: String) =
        putString(KEY_MOBILE_DATA_QUALITY, DataSaverPolicy.normalizeMobileQuality(value))

    fun cycleMobileDataQuality(): String =
        cycle(KEY_MOBILE_DATA_QUALITY, DataSaverPolicy.MOBILE_QUALITY_VALUES, getMobileDataQuality())

    /** Phone player: system/overlay Back in video mode stops playback instead of continuing as audio. */
    fun isBackStopsPlayback(): Boolean = prefs.getBoolean(KEY_BACK_STOPS_PLAYBACK, true)

    fun setBackStopsPlayback(value: Boolean) = putBoolean(KEY_BACK_STOPS_PLAYBACK, value)

    /** Minutes after which a background live stream stops (`30`/`60`/`120`/`off`). */
    fun getLiveAutoStop(): String =
        LiveAutoStopPolicy.normalize(prefs.getString(KEY_LIVE_AUTO_STOP, LiveAutoStopPolicy.DEFAULT_MINUTES))

    fun setLiveAutoStop(value: String) = putString(KEY_LIVE_AUTO_STOP, LiveAutoStopPolicy.normalize(value))

    fun cycleLiveAutoStop(): String = cycle(KEY_LIVE_AUTO_STOP, LiveAutoStopPolicy.VALUES, getLiveAutoStop())

    /** Audio offset relative to video, used exclusively by the head-unit video renderer. */
    fun getCarLipSyncMs(): Int = CarLipSync.clamp(prefs.getInt(KEY_CAR_LIP_SYNC_MS, 0))

    fun setCarLipSyncMs(value: Int) {
        prefs.edit().putInt(KEY_CAR_LIP_SYNC_MS, CarLipSync.clamp(value)).apply()
    }

    fun getThumbnailQuality(): String = prefs.getString(KEY_THUMBNAIL_QUALITY, "auto") ?: "auto"

    fun setThumbnailQuality(value: String) = putString(KEY_THUMBNAIL_QUALITY, value)

    /** Phone browse layout. Kept separate from AA because the usable widths differ substantially. */
    fun isPhoneBrowseCardsEnabled(): Boolean = prefs.getBoolean(KEY_PHONE_BROWSE_CARDS, false)

    fun setPhoneBrowseCardsEnabled(value: Boolean) = putBoolean(KEY_PHONE_BROWSE_CARDS, value)

    /** Projected AA browse layout. Cards use a responsive 1..4-column grid. */
    fun isCarBrowseCardsEnabled(): Boolean = prefs.getBoolean(KEY_CAR_BROWSE_CARDS, false)

    fun setCarBrowseCardsEnabled(value: Boolean) = putBoolean(KEY_CAR_BROWSE_CARDS, value)

    fun getCacheSizeMb(): Int = prefs.getString(KEY_CACHE_SIZE_MB, "100")?.toIntOrNull() ?: 100

    /** Preference string value (`50`/`100`/`500`/`1000`), matching preferences.xml entryValues. */
    fun getCacheSizeMbValue(): String {
        val stored = prefs.getString(KEY_CACHE_SIZE_MB, "100") ?: "100"
        return if (stored in CACHE_SIZE_VALUES) stored else "100"
    }

    fun setCacheSizeMb(value: String) = putString(KEY_CACHE_SIZE_MB, value)

    fun cycleCacheSize(): String = cycle(KEY_CACHE_SIZE_MB, CACHE_SIZE_VALUES, getCacheSizeMbValue())

    fun isHistoryEnabled(): Boolean = prefs.getBoolean(KEY_HISTORY_ENABLED, true)

    fun setHistoryEnabled(value: Boolean) = putBoolean(KEY_HISTORY_ENABLED, value)

    /** When true, projection UI shows a parked-only warning before video. */
    fun isRequireParked(): Boolean = prefs.getBoolean(KEY_REQUIRE_PARKED, true)

    fun setRequireParked(value: Boolean) = putBoolean(KEY_REQUIRE_PARKED, value)

    /** `exoplayer` | `webview`. Legacy `auto` is migrated to native ExoPlayer so WebView is
     * never entered implicitly during AA playback; it remains an explicit compatibility choice. */
    fun getVideoBackend(): String {
        val stored = prefs.getString(KEY_VIDEO_BACKEND, "exoplayer") ?: "exoplayer"
        val normalized = if (stored == "webview") "webview" else "exoplayer"
        if (stored != normalized) putString(KEY_VIDEO_BACKEND, normalized)
        return normalized
    }

    fun setVideoBackend(value: String) = putString(KEY_VIDEO_BACKEND, value)

    /** `surface` (preferred) or `texture` (compatibility fallback for unusual projection hosts). */
    fun getCarVideoSurface(): String {
        val stored = prefs.getString(KEY_CAR_VIDEO_SURFACE, "surface") ?: "surface"
        val normalized = if (stored == "texture") "texture" else "surface"
        if (stored != normalized) putString(KEY_CAR_VIDEO_SURFACE, normalized)
        return normalized
    }

    fun setCarVideoSurface(value: String) = putString(
        KEY_CAR_VIDEO_SURFACE,
        if (value == "texture") "texture" else "surface"
    )

    /** `auto` | `device` | `original` | language code (`de`, `en`, …). */
    fun getAudioLanguage(): String = prefs.getString(KEY_AUDIO_LANGUAGE, "original") ?: "original"

    fun setAudioLanguage(value: String) = putString(KEY_AUDIO_LANGUAGE, value)

    /** `auto` | `high` | `medium` | `low`. */
    fun getAudioBitrate(): String = prefs.getString(KEY_AUDIO_BITRATE, "auto") ?: "auto"

    fun setAudioBitrate(value: String) = putString(KEY_AUDIO_BITRATE, value)

    /** Playback speed as string, e.g. `1`, `1.25`. */
    fun getPlaybackSpeed(): Float =
        prefs.getString(KEY_PLAYBACK_SPEED, "1")?.toFloatOrNull()?.coerceIn(0.25f, 2.0f) ?: 1f

    fun setPlaybackSpeed(value: String) = putString(KEY_PLAYBACK_SPEED, value)

    /** `video` | `audio` — audio-only uses audio stream + audio player UI. */
    fun getPlaybackMode(): String = prefs.getString(KEY_PLAYBACK_MODE, "video") ?: "video"

    fun setPlaybackMode(value: String) = putString(KEY_PLAYBACK_MODE, value)

    fun isAudioOnly(): Boolean = getPlaybackMode() == "audio"

    /** Shared AA playback preference. When enabled the projected head unit starts items with
     * an audio-only source and must not be blocked by the parked-video gate. */
    fun isCarAudioModeEnabled(): Boolean = isAudioOnly()

    fun cyclePlaybackMode(): String = cycle(KEY_PLAYBACK_MODE, PLAYBACK_MODE_VALUES, getPlaybackMode())

    fun cycleCarVideoQuality(): String =
        cycle(KEY_CAR_VIDEO_QUALITY, CAR_VIDEO_QUALITY_VALUES, getCarVideoQuality())

    fun cycleAudioLanguage(): String = cycle(KEY_AUDIO_LANGUAGE, AUDIO_LANGUAGE_VALUES, getAudioLanguage())

    fun cycleAudioBitrate(): String = cycle(KEY_AUDIO_BITRATE, AUDIO_BITRATE_VALUES, getAudioBitrate())

    fun cyclePlaybackSpeed(): String {
        val current = prefs.getString(KEY_PLAYBACK_SPEED, "1") ?: "1"
        return cycle(KEY_PLAYBACK_SPEED, PLAYBACK_SPEED_VALUES, current)
    }

    fun cycleVideoBackend(): String = cycle(KEY_VIDEO_BACKEND, VIDEO_BACKEND_VALUES, getVideoBackend())

    fun cycleCarVideoSurface(): String = cycle(
        KEY_CAR_VIDEO_SURFACE,
        CAR_VIDEO_SURFACE_VALUES,
        getCarVideoSurface()
    )

    private fun cycle(key: String, values: List<String>, current: String): String {
        val index = values.indexOf(current).let { if (it < 0) 0 else (it + 1) % values.size }
        val next = values[index]
        putString(key, next)
        return next
    }

    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        // Müssen exakt den android:key-Werten in app/src/main/res/xml/preferences.xml entsprechen.
        const val KEY_DEFAULT_TAB = "default_tab"
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_PHONE_AUDIO_ONLY = "phone_audio_only"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_CAR_VIDEO_QUALITY = "car_video_quality"
        const val KEY_MOBILE_DATA_QUALITY = "mobile_data_quality"
        const val KEY_BACK_STOPS_PLAYBACK = "back_stops_playback"
        const val KEY_LIVE_AUTO_STOP = "live_auto_stop"
        const val KEY_CAR_LIP_SYNC_MS = "car_lip_sync_ms"
        const val KEY_THUMBNAIL_QUALITY = "thumbnail_quality"
        const val KEY_PHONE_BROWSE_CARDS = "phone_browse_cards"
        const val KEY_CAR_BROWSE_CARDS = "car_browse_cards"
        const val KEY_CACHE_SIZE_MB = "cache_size_mb"
        const val KEY_HISTORY_ENABLED = "history_enabled"
        const val KEY_REQUIRE_PARKED = "require_parked"
        const val KEY_VIDEO_BACKEND = "video_backend"
        const val KEY_CAR_VIDEO_SURFACE = "car_video_surface"
        const val KEY_AUDIO_LANGUAGE = "audio_language"
        const val KEY_AUDIO_BITRATE = "audio_bitrate"
        const val KEY_PLAYBACK_SPEED = "playback_speed"
        const val KEY_PLAYBACK_MODE = "playback_mode"

        val VIDEO_QUALITY_VALUES = listOf("auto", "2160", "1440", "1080", "720", "480", "360", "240", "144")
        val CAR_VIDEO_QUALITY_VALUES = listOf("auto", "1080", "720", "480", "360", "240", "144")
        val AUDIO_LANGUAGE_VALUES = listOf("original", "device", "de", "en", "auto")
        val AUDIO_BITRATE_VALUES = listOf("auto", "high", "medium", "low")
        val PLAYBACK_SPEED_VALUES = listOf("0.25", "0.5", "0.75", "1", "1.25", "1.5", "1.75", "2")
        val VIDEO_BACKEND_VALUES = listOf("exoplayer", "webview")
        val CAR_VIDEO_SURFACE_VALUES = listOf("surface", "texture")
        val PLAYBACK_MODE_VALUES = listOf("video", "audio")
        val CACHE_SIZE_VALUES = listOf("50", "100", "500", "1000")
    }
}
