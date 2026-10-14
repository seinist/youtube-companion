package de.uni.youtubeaa

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import de.uni.youtubeaa.car.NowPlaying
import de.uni.youtubeaa.car.NowPlayingBus
import de.uni.youtubeaa.car.PlaybackEngine
import de.uni.youtubeaa.car.PlaybackPresentation
import de.uni.youtubeaa.car.VideoSessionBridge
import de.uni.youtubeaa.car.loadThumbnailBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Docked mini-player bar, like the stock YouTube app's: whenever something is playing (audio-only
 * phone session or, while it's alive, the Android Auto projection), it shows across the browse
 * screens with a play/pause control and a tap target that reopens [VideoPlayerActivity] on the
 * same session instead of starting a new one.
 *
 * Reused by [MainActivity] and [YouTubeBrowseActivity] via `<include layout="@layout/view_mini_player"/>`.
 */
class MiniPlayerBar(private val activity: AppCompatActivity) {

    private val card: MaterialCardView = activity.findViewById(R.id.mini_player_bar)
    private val thumbnail: ShapeableImageView = activity.findViewById(R.id.mini_player_thumbnail)
    private val title: TextView = activity.findViewById(R.id.mini_player_title)
    private val channel: TextView = activity.findViewById(R.id.mini_player_channel)
    private val playPause: ImageButton = activity.findViewById(R.id.mini_player_play_pause)
    private val close: ImageButton = activity.findViewById(R.id.mini_player_close)

    private var lastThumbnailUrl: String? = null

    init {
        card.setOnClickListener {
            if (NowPlayingBus.state.value?.controllable != true) return@setOnClickListener
            activity.startActivity(
                Intent(activity, VideoPlayerActivity::class.java)
                    .putExtra(VideoPlayerActivity.EXTRA_RESUME_SESSION, true)
            )
        }
        playPause.setOnClickListener {
            val controller = VideoSessionBridge.controller ?: return@setOnClickListener
            if (controller.snapshot()?.playWhenReady ?: controller.isPlaying()) controller.pause() else controller.play()
        }
        close.setOnClickListener {
            val controller = VideoSessionBridge.controller
            if (controller != null) controller.stopPlayback() else PlaybackEngine.stopAndClear()
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // This bar has no progress display. Position-only ticks must not reset its
                // TextViews/drawables and invalidate the phone UI twice each second.
                NowPlayingBus.state.map {
                    it?.copy(positionMs = 0L, durationMs = 0L, queueIndex = 0, queueSize = 1)
                }.distinctUntilChanged().collect { render(it) }
            }
        }
    }

    private fun render(nowPlaying: NowPlaying?) {
        if (nowPlaying == null) {
            card.visibility = View.GONE
            lastThumbnailUrl = null
            return
        }
        card.visibility = View.VISIBLE
        playPause.isEnabled = nowPlaying.controllable
        title.text = nowPlaying.title
        channel.apply {
            text = nowPlaying.channelTitle
            visibility = if (nowPlaying.channelTitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        playPause.setImageResource(
            if (nowPlaying.playWhenReady) R.drawable.ic_pause_on_primary else R.drawable.ic_play_on_primary
        )
        playPause.contentDescription = activity.getString(
            if (nowPlaying.playWhenReady) R.string.now_playing_pause else R.string.now_playing_play
        )

        val url = nowPlaying.thumbnailUrl
        if (url != lastThumbnailUrl) {
            lastThumbnailUrl = url
            thumbnail.setImageDrawable(null)
            if (url != null) {
                activity.lifecycleScope.launch {
                    val bitmap = loadThumbnailBitmap(url, targetPx = 96)
                    if (lastThumbnailUrl == url) thumbnail.setImageBitmap(bitmap)
                }
            }
        }
    }
}
