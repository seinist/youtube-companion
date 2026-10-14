package de.uni.youtubeaa.car

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * AA projection settings — same SharedPreferences as the phone [preferences.xml].
 * Tap a row to cycle its value.
 */
class SettingsCarFragment : Fragment() {

    private lateinit var settings: SettingsRepository
    private lateinit var adapter: SettingsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_car, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        CarUi.bind(view)
        settings = SettingsRepository(requireContext())
        view.findViewById<View>(R.id.settings_back).setOnClickListener {
            (context as? VideoHost)?.showBrowse()
        }
        val listAdapter = SettingsAdapter { row -> onRowClicked(row); refresh() }
        adapter = listAdapter
        view.findViewById<RecyclerView>(R.id.settings_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = listAdapter
        }
        refresh()
    }

    private fun refresh() {
        adapter.submit(buildRows())
    }

    private fun buildRows(): List<SettingsRow> = listOf(
        SettingsRow(
            id = ID_DEFAULT_TAB,
            title = getString(R.string.settings_default_tab),
            value = labelFor(settings.getDefaultTab(), DEFAULT_TAB_LABELS)
        ),
        SettingsRow(
            id = ID_BROWSE_LAYOUT,
            title = getString(R.string.settings_browse_layout),
            value = if (settings.isCarBrowseCardsEnabled()) "Karten" else "Liste"
        ),
        SettingsRow(
            id = ID_MODE,
            title = getString(R.string.settings_playback_mode_car),
            value = labelFor(settings.getPlaybackMode(), MODE_LABELS)
        ),
        SettingsRow(
            id = ID_QUALITY,
            title = getString(R.string.settings_quality),
            value = labelFor(settings.getCarVideoQuality(), QUALITY_LABELS)
        ),
        SettingsRow(
            id = ID_SPEED,
            title = getString(R.string.settings_speed),
            value = "${settings.getPlaybackSpeed()}×"
        ),
        SettingsRow(
            id = ID_AUDIO_LANG,
            title = getString(R.string.settings_audio_language),
            value = labelFor(settings.getAudioLanguage(), AUDIO_LANG_LABELS)
        ),
        SettingsRow(
            id = ID_AUDIO_BITRATE,
            title = getString(R.string.settings_audio_bitrate),
            value = labelFor(settings.getAudioBitrate(), AUDIO_BITRATE_LABELS)
        ),
        SettingsRow(
            id = ID_BACKEND,
            title = getString(R.string.settings_backend),
            value = labelFor(settings.getVideoBackend(), BACKEND_LABELS)
        ),
        SettingsRow(
            id = ID_SURFACE,
            title = getString(R.string.settings_surface),
            value = labelFor(settings.getCarVideoSurface(), SURFACE_LABELS)
        ),
        SettingsRow(
            id = ID_AUTOPLAY,
            title = getString(R.string.settings_autoplay),
            value = onOff(settings.isAutoplayEnabled())
        ),
        SettingsRow(
            id = ID_PARKED,
            title = getString(R.string.settings_require_parked),
            value = onOff(settings.isRequireParked())
        ),
        SettingsRow(
            id = ID_CACHE,
            title = getString(R.string.settings_cache),
            value = cacheLabel(settings.getCacheSizeMbValue())
        ),
        SettingsRow(
            id = ID_HISTORY,
            title = getString(R.string.settings_history),
            value = onOff(settings.isHistoryEnabled())
        )
    )

    private fun onRowClicked(row: SettingsRow) {
        when (row.id) {
            ID_DEFAULT_TAB -> settings.setDefaultTab(nextDefaultTab(settings.getDefaultTab()))
            ID_BROWSE_LAYOUT -> settings.setCarBrowseCardsEnabled(!settings.isCarBrowseCardsEnabled())
            ID_QUALITY -> settings.cycleCarVideoQuality()
            ID_SPEED -> settings.cyclePlaybackSpeed()
            ID_MODE -> settings.cyclePlaybackMode()
            ID_AUDIO_LANG -> settings.cycleAudioLanguage()
            ID_AUDIO_BITRATE -> settings.cycleAudioBitrate()
            ID_BACKEND -> settings.cycleVideoBackend()
            ID_SURFACE -> settings.cycleCarVideoSurface()
            ID_AUTOPLAY -> settings.setAutoplayEnabled(!settings.isAutoplayEnabled())
            ID_PARKED -> settings.setRequireParked(!settings.isRequireParked())
            ID_CACHE -> settings.cycleCacheSize()
            ID_HISTORY -> settings.setHistoryEnabled(!settings.isHistoryEnabled())
        }
    }

    private fun cacheLabel(value: String): String = "$value MB"

    private fun nextDefaultTab(current: String): String {
        val values = listOf("home", "subscriptions", "popular", "history", "playlist")
        val i = values.indexOf(current).let { if (it < 0) 0 else it }
        return values[(i + 1) % values.size]
    }

    private fun onOff(value: Boolean): String = if (value) "An" else "Aus"

    private fun labelFor(value: String, map: Map<String, String>): String =
        map[value] ?: value

    private data class SettingsRow(val id: String, val title: String, val value: String)

    private class SettingsAdapter(
        private val onClick: (SettingsRow) -> Unit
    ) : ListAdapter<SettingsRow, SettingsAdapter.Holder>(DIFF) {

        fun submit(rows: List<SettingsRow>) {
            submitList(rows.toList())
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_setting_car, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position), onClick)
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.setting_title)
            private val subtitle: TextView = itemView.findViewById(R.id.setting_value)

            fun bind(row: SettingsRow, onClick: (SettingsRow) -> Unit) {
                title.text = row.title
                subtitle.text = row.value
                itemView.setOnClickListener { onClick(row) }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<SettingsRow>() {
                override fun areItemsTheSame(oldItem: SettingsRow, newItem: SettingsRow): Boolean =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: SettingsRow, newItem: SettingsRow): Boolean =
                    oldItem == newItem
            }
        }
    }

    companion object {
        private const val ID_DEFAULT_TAB = "default_tab"
        private const val ID_BROWSE_LAYOUT = "browse_layout"
        private const val ID_QUALITY = "quality"
        private const val ID_SPEED = "speed"
        private const val ID_MODE = "mode"
        private const val ID_AUDIO_LANG = "audio_lang"
        private const val ID_AUDIO_BITRATE = "audio_bitrate"
        private const val ID_BACKEND = "backend"
        private const val ID_SURFACE = "surface"
        private const val ID_AUTOPLAY = "autoplay"
        private const val ID_PARKED = "parked"
        private const val ID_CACHE = "cache"
        private const val ID_HISTORY = "history"

        private val DEFAULT_TAB_LABELS = mapOf(
            "home" to "Home",
            "subscriptions" to "Abos",
            "subscription_feed" to "Abos",
            "popular" to "Beliebt",
            "history" to "Verlauf",
            "playlist" to "Gespeichert"
        )
        private val MODE_LABELS = mapOf(
            "video" to "Video",
            "audio" to "Nur Audio"
        )
        private val QUALITY_LABELS = mapOf(
            "auto" to "Auto",
            "1080" to "1080p",
            "720" to "720p",
            "480" to "480p",
            "360" to "360p",
            "240" to "240p",
            "144" to "144p"
        )
        private val AUDIO_LANG_LABELS = mapOf(
            "auto" to "Auto",
            "device" to "Gerät",
            "original" to "Original",
            "de" to "Deutsch",
            "en" to "Englisch"
        )
        private val AUDIO_BITRATE_LABELS = mapOf(
            "auto" to "Auto",
            "high" to "Hoch",
            "medium" to "Mittel",
            "low" to "Niedrig"
        )
        private val BACKEND_LABELS = mapOf(
            "exoplayer" to "ExoPlayer",
            "webview" to "WebView"
        )
        private val SURFACE_LABELS = mapOf(
            "surface" to "SurfaceView (Performance)",
            "texture" to "TextureView (Kompatibilität)"
        )
    }
}
