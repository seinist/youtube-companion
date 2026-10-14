package de.uni.youtubeaa.car

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player

/** Status is updated by player events, never by a second position polling loop. */
class PlaybackStatusView(root: View, retry: () -> Unit) {
    private val card = root.findViewById<View>(R.id.playback_status_card)
    private val label = root.findViewById<TextView>(R.id.playback_status_label)
    private val progress = root.findViewById<ProgressBar>(R.id.playback_status_progress)
    private val action = root.findViewById<View>(R.id.playback_status_retry)
    private var explicit = false
    private var lastDisplay: Triple<String, Boolean, Boolean>? = null
    init { action.setOnClickListener { retry() } }

    fun loading(message: String) { explicit = true; show(message, busy = true) }
    fun error(message: String) { explicit = true; show(message, retry = true) }
    fun clear() { explicit = false; lastDisplay = null; card.visibility = View.GONE }
    fun update(player: Player) {
        if (explicit) return
        val message = when {
            player.playerError != null -> R.string.playback_status_error
            player.playbackState == Player.STATE_BUFFERING -> R.string.playback_status_buffering
            player.playbackState != Player.STATE_READY -> null
            !player.playWhenReady -> R.string.playback_status_paused
            player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> R.string.playback_status_interrupted
            else -> null
        }
        if (message == null) { lastDisplay = null; card.visibility = View.GONE }
        else show(label.context.getString(message), busy = player.playbackState == Player.STATE_BUFFERING,
            retry = player.playerError != null)
    }
    private fun show(message: String, busy: Boolean = false, retry: Boolean = false) {
        val next = Triple(message, busy, retry)
        if (lastDisplay == next) return
        lastDisplay = next
        label.text = message
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        action.visibility = if (retry) View.VISIBLE else View.GONE
        card.visibility = View.VISIBLE
    }
}
