package de.uni.youtubeaa

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.uni.youtubeaa.car.NowPlaying
import de.uni.youtubeaa.car.NowPlayingBus
import de.uni.youtubeaa.car.VideoSessionBridge
import de.uni.youtubeaa.car.hybrid.BrowseFacade
import de.uni.youtubeaa.car.loadThumbnailBitmap
import de.uni.youtubeaa.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var browseFacade: BrowseFacade

    private var lastThumbnailUrl: String? = null
    private var lastDurationMs: Long = 0L
    private var isSeeking = false
    private var profileJob: kotlinx.coroutines.Job? = null
    private var avatarJob: kotlinx.coroutines.Job? = null
    private var profileAccountKey: String? = null
    private var lastProfileAttemptMs = 0L
    private var lastAvatarUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        browseFacade = BrowseFacade(applicationContext)

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.homeBrowseButton.setOnClickListener { openBrowse(YouTubeBrowseActivity.START_HOME) }
        binding.subscriptionsBrowseButton.setOnClickListener { openBrowse(YouTubeBrowseActivity.START_SUBSCRIPTIONS) }
        binding.historyBrowseButton.setOnClickListener { openBrowse(YouTubeBrowseActivity.START_HISTORY) }
        binding.popularBrowseButton.setOnClickListener { openBrowse(YouTubeBrowseActivity.START_POPULAR) }
        binding.playlistBrowseButton.setOnClickListener { openBrowse(YouTubeBrowseActivity.START_PLAYLIST) }
        binding.searchBrowseButton.setOnClickListener { openBrowse(YouTubeBrowseActivity.START_SEARCH) }

        findViewById<View>(R.id.nav_home).setOnClickListener { openBrowse(YouTubeBrowseActivity.START_HOME) }
        findViewById<View>(R.id.nav_subscriptions).setOnClickListener { openBrowse(YouTubeBrowseActivity.START_SUBSCRIPTIONS) }
        findViewById<View>(R.id.nav_shorts).setOnClickListener { openBrowse(YouTubeBrowseActivity.START_SHORTS) }
        findViewById<View>(R.id.nav_you).isSelected = true

        binding.youtubeAccountName.setOnClickListener {
            lastProfileAttemptMs = 0L
            updatePersonalNavigation()
        }
        setupNowPlayingControls()
        observeNowPlaying()
    }

    override fun onResume() {
        super.onResume()
        updatePersonalNavigation()
    }

    private fun openBrowse(startScreen: String) {
        startActivity(
            Intent(this, YouTubeBrowseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(YouTubeBrowseActivity.EXTRA_START_SCREEN, startScreen)
        )
    }

    private fun updatePersonalNavigation() {
        val hasPersonalAccess = browseFacade.hasPersonalAccess()
        binding.subscriptionsBrowseButton.visibility = if (hasPersonalAccess) View.VISIBLE else View.GONE

        val tvTokens = browseFacade.sessionStore().getTokens()
        val accountKey = tvTokens?.refreshToken
        if (accountKey != profileAccountKey) {
            profileJob?.cancel()
            profileAccountKey = accountKey
            lastProfileAttemptMs = 0L
        }
        binding.youtubeAccountName.text = browseFacade.accountDisplayName()
            ?: getString(when {
                !hasPersonalAccess -> R.string.nav_you
                lastProfileAttemptMs == 0L -> R.string.account_name_loading
                else -> R.string.account_name_unavailable
            })
        binding.youtubeSectionSubtitle.text = getString(
            if (hasPersonalAccess) R.string.home_youtube_personalized else R.string.home_youtube_anonymous
        )
        renderAccountAvatar(if (browseFacade.isTvSignedIn()) tvTokens?.accountAvatarUrl else null)

        val now = android.os.SystemClock.elapsedRealtime()
        if (hasPersonalAccess && profileJob?.isActive != true &&
            (lastProfileAttemptMs == 0L || now - lastProfileAttemptMs >= 60_000L)) {
            lastProfileAttemptMs = now
            profileJob = lifecycleScope.launch {
                browseFacade.refreshTvAccountProfile()
                val latestKey = browseFacade.sessionStore().getTokens()?.refreshToken
                if (latestKey != accountKey) return@launch
                binding.youtubeAccountName.text = browseFacade.accountDisplayName()
                    ?: getString(R.string.account_name_unavailable)
                renderAccountAvatar(browseFacade.sessionStore().getTokens()?.accountAvatarUrl)
            }
        }
    }

    private fun renderAccountAvatar(url: String?) {
        if (lastAvatarUrl == url && url != null) return
        lastAvatarUrl = url
        avatarJob?.cancel()
        binding.youtubeAccountAvatar.setImageResource(R.drawable.ic_person)
        binding.youtubeAccountAvatar.setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        if (url.isNullOrBlank()) return
        avatarJob = lifecycleScope.launch {
            val bitmap = loadThumbnailBitmap(url, targetPx = 96)
            if (bitmap != null && lastAvatarUrl == url) {
                binding.youtubeAccountAvatar.setPadding(0, 0, 0, 0)
                binding.youtubeAccountAvatar.setImageBitmap(bitmap)
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    /**
     * Nutzt VideoSessionBridge (dasselbe In-Process-Singleton, das auch Lenkrad/Sprachsteuerung
     * bedient), statt einer eigenen MediaController-Verbindung zum MediaBrowserService.
     */
    private fun setupNowPlayingControls() {
        binding.nowPlayingCard.setOnClickListener {
            if (NowPlayingBus.state.value?.controllable != true) return@setOnClickListener
            startActivity(
                Intent(this, VideoPlayerActivity::class.java)
                    .putExtra(VideoPlayerActivity.EXTRA_RESUME_SESSION, true)
            )
        }
        binding.nowPlayingPlayPause.setOnClickListener {
            val controller = VideoSessionBridge.controller ?: return@setOnClickListener
            if (controller.snapshot()?.playWhenReady ?: controller.isPlaying()) controller.pause() else controller.play()
        }
        binding.nowPlayingNext.setOnClickListener {
            VideoSessionBridge.controller?.skipToNext()
        }
        binding.nowPlayingPrevious.setOnClickListener {
            VideoSessionBridge.controller?.skipToPrevious()
        }
        binding.nowPlayingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeeking = false
                if (lastDurationMs > 0) {
                    val positionMs = lastDurationMs * seekBar.progress / seekBar.max
                    VideoSessionBridge.controller?.seekTo(positionMs)
                }
            }
        })
    }

    private fun observeNowPlaying() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NowPlayingBus.state.collect { nowPlaying ->
                    renderNowPlaying(nowPlaying)
                }
            }
        }
    }

    private fun renderNowPlaying(nowPlaying: NowPlaying?) {
        if (nowPlaying == null) {
            binding.nowPlayingCard.visibility = View.GONE
            binding.nowPlayingEmptyText.visibility = View.VISIBLE
            lastThumbnailUrl = null
            lastDurationMs = 0L
            return
        }
        binding.nowPlayingCard.visibility = View.VISIBLE
        binding.nowPlayingEmptyText.visibility = View.GONE

        binding.nowPlayingTitle.text = nowPlaying.title
        binding.nowPlayingChannel.apply {
            text = nowPlaying.channelTitle
            visibility = if (nowPlaying.channelTitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        lastDurationMs = nowPlaying.durationMs
        val seekBar = binding.nowPlayingSeekBar
        if (nowPlaying.durationMs > 0) {
            seekBar.visibility = View.VISIBLE
            seekBar.isEnabled = nowPlaying.controllable
            if (!isSeeking) {
                seekBar.progress = ((nowPlaying.positionMs * seekBar.max) / nowPlaying.durationMs)
                    .toInt().coerceIn(0, seekBar.max)
            }
        } else {
            seekBar.visibility = View.GONE
        }

        binding.nowPlayingControls.visibility = if (nowPlaying.controllable) View.VISIBLE else View.GONE
        binding.nowPlayingStatus.visibility = if (nowPlaying.isBuffering) View.VISIBLE else View.GONE
        binding.nowPlayingPlayPause.isEnabled = nowPlaying.controllable && !nowPlaying.isBuffering
        binding.nowPlayingPlayPause.alpha = if (binding.nowPlayingPlayPause.isEnabled) 1f else 0.6f
        binding.nowPlayingPlayPause.setImageResource(
            if (nowPlaying.playWhenReady) R.drawable.ic_pause_on_primary else R.drawable.ic_play_on_primary
        )
        binding.nowPlayingPlayPause.contentDescription = getString(
            if (nowPlaying.playWhenReady) R.string.now_playing_pause else R.string.now_playing_play
        )

        val url = nowPlaying.thumbnailUrl
        if (url != lastThumbnailUrl) {
            lastThumbnailUrl = url
            binding.nowPlayingThumbnail.setImageDrawable(null)
            if (url != null) {
                lifecycleScope.launch {
                    val bitmap = loadThumbnailBitmap(url, targetPx = 192)
                    if (lastThumbnailUrl == url) binding.nowPlayingThumbnail.setImageBitmap(bitmap)
                }
            }
        }
    }
}
