package de.uni.youtubeaa.car

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Shared metrics for browse, settings and both playback modes. */
internal object CarUi {
    fun bind(root: View) {
        var applied: CarLayoutSpec? = null
        fun apply() {
            if (root.width <= 0 || root.height <= 0) return
            val density = root.resources.displayMetrics.density
            val spec = CarLayoutSpec.forViewport((root.width / density).toInt(), (root.height / density).toInt())
            if (spec == applied) return
            applied = spec
            fun px(dp: Int) = (dp * density).toInt()
            fun size(id: Int, width: Int? = null, height: Int? = null) {
                root.findViewById<View>(id)?.let { view ->
                    val params = view.layoutParams
                    if (width != null) params.width = px(width)
                    if (height != null) params.height = px(height)
                    view.layoutParams = params
                }
            }
            listOf(R.id.player_overlay_back, R.id.player_overlay_settings,
                R.id.audio_back, R.id.audio_settings,
                R.id.audio_prev, R.id.audio_next, R.id.browse_back, R.id.browse_search_button, R.id.browse_refresh_button,
                R.id.browse_settings_button, R.id.settings_back, R.id.player_settings_back,
                R.id.player_settings_close, androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_ffwd
            ).forEach { size(it, spec.touchDp, spec.touchDp) }
            listOf(R.id.audio_play_pause, androidx.media3.ui.R.id.exo_play_pause, R.id.player_mode, R.id.audio_switch_video).forEach { size(it, spec.playDp, spec.playDp) }
            listOf(R.id.car_toolbar, R.id.audio_toolbar, R.id.video_toolbar).forEach { size(it, height = spec.toolbarDp) }
            size(R.id.player_settings_panel, width = spec.panelDp)
            root.findViewById<View>(R.id.video_transport)?.let { transport ->
                (transport.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                    val timeline = 54 + maxOf(24, (20 * root.resources.configuration.fontScale).toInt())
                    val space = (root.height / density).toInt() - spec.toolbarDp - timeline - spec.playDp
                    params.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    params.topMargin = px(spec.toolbarDp + (space / 2).coerceAtLeast(0))
                    transport.layoutParams = params
                }
            }
            root.findViewById<View>(R.id.playback_status_card)?.let { card ->
                (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.topMargin = px(spec.toolbarDp + 12)
                    card.layoutParams = params
                }
            }
            root.findViewById<View>(R.id.audio_art_foreground)?.let { cover ->
                cover.visibility = if (spec.coverDp == 0) View.GONE else View.VISIBLE
                size(R.id.audio_art_foreground, spec.coverDp, spec.coverDp)
            }
            root.findViewById<View>(R.id.audio_content)?.let { content ->
                (content.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.topMargin = px(spec.toolbarDp + 4)
                    content.layoutParams = params
                }
            }
            root.findViewById<TextView>(R.id.audio_title)?.let { title ->
                title.maxLines = if (spec.compact) 1 else 2
                title.textSize = if (spec.compact) 18f else 22f
            }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        root.post { apply() }
    }
}
