@file:Suppress("DEPRECATION")
package de.uni.youtubeaa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import de.uni.youtubeaa.car.CarPlaybackQos
import de.uni.youtubeaa.car.StableListMerge
import de.uni.youtubeaa.car.hybrid.BrowseFacade
import de.uni.youtubeaa.car.hybrid.BrowseOutcome
import de.uni.youtubeaa.car.hybrid.HybridChannel
import de.uni.youtubeaa.car.BrowseTab
import de.uni.youtubeaa.car.CarPlaybackState
import de.uni.youtubeaa.car.RemoteCastBus
import de.uni.youtubeaa.car.SettingsRepository
import de.uni.youtubeaa.car.UserPlaylistStore
import de.uni.youtubeaa.car.WatchHistoryStore
import de.uni.youtubeaa.car.loadThumbnailBitmap
import de.uni.youtubeaa.car.bindThumbnail
import de.uni.youtubeaa.car.clearThumbnailBinding
import de.uni.youtubeaa.car.ThumbnailImageLoader
import de.uni.youtubeaa.car.channelId
import de.uni.youtubeaa.car.publishedAtMs
import de.uni.youtubeaa.car.subtitleWithAge
import de.uni.youtubeaa.car.hybrid.FeedChip
import de.uni.youtubeaa.car.hybrid.FeedStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.uni.youtubeaa.car.setBrowseExtras
import de.uni.youtubeaa.car.toDescription
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * YouTube direkt vom Smartphone aus durchsuchen und ansehen ("von der App aus YouTube sehen"):
 * dieselben Bereiche wie in der AA-Projektion (Startseite, Abos → Kanäle → Videos,
 * Verlauf, Beliebt, Suche), gespiegelt von [BrowseCarFragment].
 * Jedes Ergebnis kann per [RemoteCastBus] auf das Android-Auto-Display geschickt werden (nur
 * möglich, solange die Projektion aktiv ist) oder alternativ direkt auf dem Handy abgespielt
 * werden ([VideoPlayerActivity]).
 */
class YouTubeBrowseActivity : AppCompatActivity() {

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private lateinit var resultsScroll: RecyclerView
    private lateinit var browseAdapter: PhoneBrowseAdapter
    private lateinit var browseLayoutManager: GridLayoutManager
    private lateinit var layoutToggleButton: ImageButton
    private var phoneBrowseColumns = 1
    private val browseRows = mutableListOf<PhoneBrowseRow>()
    private var displayedVideos: List<MediaDescriptionCompat> = emptyList()
    private var renderGeneration = 0
    private lateinit var statusText: TextView
    private lateinit var stateCard: View
    private lateinit var stateProgress: ProgressBar
    private lateinit var stateTitle: TextView
    private lateinit var stateMessage: TextView
    private lateinit var stateAction: MaterialButton
    private lateinit var searchInput: EditText
    private lateinit var headerRow: View
    private lateinit var headerTitle: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pagerStage: View
    private lateinit var prevPage: View
    private lateinit var nextPage: View
    private lateinit var prevContainer: LinearLayout
    private lateinit var nextContainer: LinearLayout
    private lateinit var browseFacade: BrowseFacade
    private lateinit var settings: SettingsRepository

    private var screen: Screen = Screen.Home
    private var loadingIndicatorJob: Job? = null
    private var contentLoadJob: Job? = null
    private var lastSearchQuery: String = ""
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false
    private var swipeDragging = false
    private var swipeFramePosted = false
    private var pageSettling = false
    private var deferredContent: (() -> Unit)? = null
    private var displayedTab: BrowseTab? = null
    private data class RenderedPage(
        val data: List<MediaDescriptionCompat>, val rows: List<PhoneBrowseRow>, val scroll: android.os.Parcelable?
    )
    private val renderedPages = linkedMapOf<BrowseTab, RenderedPage>()
    private var lastAccountKey: String? = null
    private var hasResumed = false
    private var browseVisible = true

    private fun applyPhoneBrowseLayout(preserveScroll: Boolean) {
        if (!::browseAdapter.isInitialized || !::browseLayoutManager.isInitialized || !::settings.isInitialized) return
        val cards = settings.isPhoneBrowseCardsEnabled()
        val density = resources.displayMetrics.density
        val widthPx = resultsScroll.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val widthDp = widthPx / density
        val columns = if (cards)
            (widthDp / PHONE_CARD_MIN_WIDTH_DP).toInt().coerceIn(1, MAX_BROWSE_COLUMNS)
        else 1

        val modeChanged = browseAdapter.videoCards != cards
        val columnsChanged = browseLayoutManager.spanCount != columns
        val position = if (preserveScroll && (modeChanged || columnsChanged))
            browseLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0) else -1
        val offset = if (position >= 0) browseLayoutManager.findViewByPosition(position)?.top ?: 0 else 0

        phoneBrowseColumns = columns
        if (columnsChanged) browseLayoutManager.spanCount = columns
        if (modeChanged) browseAdapter.setVideoCards(cards)

        layoutToggleButton.setImageResource(if (cards) R.drawable.ic_view_list else R.drawable.ic_view_grid)
        layoutToggleButton.contentDescription = getString(if (cards) R.string.browse_show_list else R.string.browse_show_cards)

        if (position >= 0) resultsScroll.post {
            if (!isFinishing && !isDestroyed) browseLayoutManager.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun afterPageMotion(action: () -> Unit) {
        if (swipeDragging || pageSettling) deferredContent = action else action()
    }

    private fun submitBrowseRows(resetScroll: Boolean = false, restore: android.os.Parcelable? = null) {
        val generation = ++renderGeneration
        browseAdapter.submitList(browseRows.toList()) {
            if (generation != renderGeneration) return@submitList
            if (restore != null) resultsScroll.layoutManager?.onRestoreInstanceState(restore)
            else if (resetScroll) resultsScroll.scrollToPosition(0)
        }
    }

    private fun saveRenderedPage() {
        val tab = displayedTab ?: return
        val data = prefetchedTabs[tab] ?: return
        if (data !== displayedVideos || !isTopLevelTabActive(tab) || stateCard.visibility == View.VISIBLE) return
        renderedPages[tab] = RenderedPage(data, browseRows.toList(), resultsScroll.layoutManager?.onSaveInstanceState())
    }

    private fun restoreRenderedPage(tab: BrowseTab): Boolean {
        val cached = renderedPages[tab] ?: return false
        if (cached.data !== prefetchedTabs[tab]) { renderedPages.remove(tab); return false }
        screen = screenForTab(tab)
        browseRows.clear()
        browseRows.addAll(cached.rows)
        displayedVideos = cached.data
        if (tab == BrowseTab.SUBSCRIPTIONS) updateSubscriptionAction()
        displayedTab = tab
        headerRow.visibility = View.GONE
        hideState()
        swipeRefresh.isRefreshing = false
        submitBrowseRows(restore = cached.scroll)
        return true
    }

    private fun screenForTab(tab: BrowseTab): Screen = when (tab) {
        BrowseTab.HOME -> Screen.Home
        BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> Screen.Subscriptions
        BrowseTab.POPULAR -> Screen.Popular
        BrowseTab.SHORTS -> Screen.Shorts
        BrowseTab.HISTORY -> Screen.History
        BrowseTab.PLAYLIST -> Screen.Playlist
    }

    private var pendingSwipeTranslationX = 0f
    private var swipeStartTranslationX = 0f
    private var swipeVelocityTracker: VelocityTracker? = null
    private var swipeChildCancelled = false
    private val swipeTouchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val swipeMinFlingVelocity: Int by lazy { ViewConfiguration.get(this).scaledMinimumFlingVelocity }
    private val swipeMaxFlingVelocity: Int by lazy { ViewConfiguration.get(this).scaledMaximumFlingVelocity }
    private val swipeInterpolator = DecelerateInterpolator(1.6f)
    private val prefetchedTabs = mutableMapOf<BrowseTab, List<MediaDescriptionCompat>>()
    private val prefetchedSubscriptionChannels = mutableMapOf<BrowseTab, List<HybridChannel>>()
    private val prefetchJobs = mutableMapOf<BrowseTab, Job>()
    private var subscriptionFeedStoredAtMs: Long = 0L
    private var subscriptionFeedOutcome: BrowseOutcome.Ok<List<MediaDescriptionCompat>>? = null
    /**
     * Startseite and Abos data comes from the process-wide FeedStore, which carries this screen's
     * rules for all surfaces; the fields here only hold what this screen rendered from it.
     */
    private val feedStore get() = browseFacade.feedStore()
    /** Selected subscriptions filter chip; null means the unfiltered feed. */
    private val selectedChip: FeedChip? get() = feedStore.subscriptions.value.chip
    /** Filter (chip title, null = unfiltered) that [subscriptionFeedOutcome] belongs to. */
    private var subscriptionFeedChipTitle: String? = null
    /** Last Home outcome, so a page restored from prefetchedTabs keeps its "load more" row. */
    private var homeOutcome: BrowseOutcome.Ok<List<MediaDescriptionCompat>>? = null
    private var subscriptionLoadingMore = false
    private var subscriptionSilentRefreshJob: Job? = null
    private var subscriptionChannelJob: Job? = null

    private val visibleBrowseTabs = listOf(
        BrowseTab.HOME,
        BrowseTab.SUBSCRIPTIONS,
        BrowseTab.SHORTS,
        BrowseTab.POPULAR,
        BrowseTab.HISTORY,
        BrowseTab.PLAYLIST
    )

    /** Hide action text on narrow widths; keep icons. */
    private val showActionLabels: Boolean
        get() = resources.configuration.screenWidthDp >= 400

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_browse)
        applySystemBarInsets()
        title = getString(R.string.browse_youtube_title)

        resultsScroll = findViewById(R.id.browse_results_scroll)
        statusText = findViewById(R.id.browse_status_text)
        stateCard = findViewById(R.id.browse_state_card)
        stateProgress = findViewById(R.id.browse_state_progress)
        stateTitle = findViewById(R.id.browse_state_title)
        stateMessage = findViewById(R.id.browse_state_message)
        stateAction = findViewById(R.id.browse_state_action)
        searchInput = findViewById(R.id.search_input)
        headerRow = findViewById(R.id.browse_header_row)
        headerTitle = findViewById(R.id.browse_header_title)
        browseFacade = BrowseFacade(this)
        settings = SettingsRepository(this)
        observeFeedStore()
        browseAdapter = PhoneBrowseAdapter(lifecycleScope,
            bindVideo = { row, video, playlist -> bindVideoRow(row, video, displayedVideos, playlist) },
            bindChannel = { image, channel -> bindChannelThumbnail(image, channel.thumbnailUrl, channel.channelId) },
            openChannel = { loadChannelVideos(it.channelId, it.title) },
            repair = { browseFacade.repairMetadataIfNeeded(it) },
            onRepaired = ::applyRepairedVideo)
        browseLayoutManager = GridLayoutManager(this, 1).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (browseAdapter.isVideoPosition(position)) 1 else spanCount
            }
        }
        resultsScroll.layoutManager = browseLayoutManager
        resultsScroll.adapter = browseAdapter
        resultsScroll.itemAnimator = null
        layoutToggleButton = findViewById(R.id.browse_layout_toggle)
        layoutToggleButton.setOnClickListener {
            settings.setPhoneBrowseCardsEnabled(!settings.isPhoneBrowseCardsEnabled())
            applyPhoneBrowseLayout(preserveScroll = true)
        }
        resultsScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPhoneBrowseLayout(preserveScroll = true)
        }
        resultsScroll.post { applyPhoneBrowseLayout(preserveScroll = false) }
        swipeRefresh = findViewById(R.id.browse_swipe_refresh)
        pagerStage = findViewById(R.id.browse_pager_stage)
        prevPage = findViewById(R.id.browse_prev_page)
        nextPage = findViewById(R.id.browse_next_page)
        prevContainer = findViewById(R.id.browse_prev_container)
        nextContainer = findViewById(R.id.browse_next_container)
        swipeRefresh.setColorSchemeResources(R.color.md_theme_primary)
        pagerStage.post { renderNeighborPages() }
        swipeRefresh.setOnRefreshListener { refreshCurrent(forceRefresh = true) }


        val searchPanel = findViewById<View>(R.id.search_panel)
        findViewById<View>(R.id.browse_search_toggle).setOnClickListener {
            val opening = searchPanel.visibility != View.VISIBLE
            searchPanel.visibility = if (opening) View.VISIBLE else View.GONE
            if (opening) {
                searchInput.requestFocus()
                searchInput.post {
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        findViewById<View>(R.id.browse_settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.search_button).setOnClickListener { runSearch(forceRefresh = true) }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.tab_home).setOnClickListener { selectBrowseTab(BrowseTab.HOME) }
        findViewById<Button>(R.id.tab_subscriptions).setOnClickListener { selectBrowseTab(BrowseTab.SUBSCRIPTIONS) }
        MiniPlayerBar(this)
        findViewById<Button>(R.id.tab_history).setOnClickListener { selectBrowseTab(BrowseTab.HISTORY) }
        findViewById<Button>(R.id.tab_popular).setOnClickListener { selectBrowseTab(BrowseTab.POPULAR) }
        findViewById<Button>(R.id.tab_playlist).setOnClickListener { selectBrowseTab(BrowseTab.PLAYLIST) }
        findViewById<Button>(R.id.browse_back_button).setOnClickListener { navigateBack() }

        findViewById<View>(R.id.nav_home).setOnClickListener { selectBrowseTab(BrowseTab.HOME) }
        findViewById<View>(R.id.nav_subscriptions).setOnClickListener { selectBrowseTab(BrowseTab.SUBSCRIPTIONS) }
        findViewById<View>(R.id.nav_shorts).setOnClickListener { selectBrowseTab(BrowseTab.SHORTS) }
        findViewById<View>(R.id.nav_you).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        if (savedInstanceState != null) {
            restoreBrowseState(savedInstanceState)
        } else {
            openInitialScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.getStringExtra(EXTRA_START_SCREEN)) {
            START_SUBSCRIPTIONS -> selectBrowseTab(BrowseTab.SUBSCRIPTIONS)
            START_HISTORY -> selectBrowseTab(BrowseTab.HISTORY)
            START_POPULAR -> selectBrowseTab(BrowseTab.POPULAR)
            START_SHORTS -> selectBrowseTab(BrowseTab.SHORTS)
            START_PLAYLIST -> selectBrowseTab(BrowseTab.PLAYLIST)
            START_SEARCH -> openInitialScreen()
            else -> selectBrowseTab(BrowseTab.HOME)
        }
    }

    override fun onResume() {
        super.onResume()
        browseVisible = true
        applyPhoneBrowseLayout(preserveScroll = true)
        val accountKey = browseFacade.sessionStore().getTokens()?.refreshToken
        if (hasResumed && accountKey != lastAccountKey) {
            contentLoadJob?.cancel()
            subscriptionSilentRefreshJob?.cancel()
            subscriptionChannelJob?.cancel()
            subscriptionFeedOutcome = null
            subscriptionFeedChipTitle = null
            subscriptionFeedStoredAtMs = 0L
            prefetchJobs.values.toList().forEach { it.cancel() }
            prefetchJobs.clear()
            prefetchedTabs.clear()
            // Paging flags and the chip filter belong to the previous account.
            homeOutcome = null
            prefetchedSubscriptionChannels.clear()
            renderedPages.clear()
            de.uni.youtubeaa.car.BrowseListCache.invalidate()
            refreshCurrent(forceRefresh = true)
        } else if (hasResumed && screen == Screen.History) loadHistory()
        else if (hasResumed && screen == Screen.Playlist) loadPlaylist()
        else if (hasResumed && screen == Screen.Subscriptions) refreshSubscriptionsSilentlyIfStale()
        lastAccountKey = accountKey
        hasResumed = true
        if (browseRows.isEmpty() && contentLoadJob?.isActive != true && stateCard.visibility != View.VISIBLE) refreshCurrent(forceRefresh = false)
        scheduleNeighborPrefetch()
    }

    override fun onStop() {
        browseVisible = false
        prefetchJobs.values.toList().forEach { it.cancel() }
        prefetchJobs.clear()
        subscriptionSilentRefreshJob?.cancel()
        swipeTracking = false
        swipeRefresh.isEnabled = true
        finishVelocityTracking()
        resetPageSwipe(immediate = true)
        super.onStop()
    }

    override fun onDestroy() {
        finishVelocityTracking()
        resultsScroll.adapter = null
        renderedPages.clear()
        deferredContent = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCREEN, when (screen) {
            is Screen.Home -> START_HOME
            is Screen.Subscriptions -> START_SUBSCRIPTIONS
            is Screen.ChannelVideos -> STATE_CHANNEL
            is Screen.History -> START_HISTORY
            is Screen.Popular -> START_POPULAR
            is Screen.Shorts -> START_SHORTS
            is Screen.Search -> START_SEARCH
            is Screen.Playlist -> START_PLAYLIST
        })
        if (screen is Screen.ChannelVideos) {
            outState.putString(STATE_CHANNEL_ID, (screen as Screen.ChannelVideos).channelId)
            outState.putString(STATE_CHANNEL_TITLE, headerTitle.text?.toString().orEmpty())
        }
        outState.putString(STATE_SEARCH_QUERY, lastSearchQuery)
    }

    private fun restoreBrowseState(state: Bundle) {
        lastSearchQuery = state.getString(STATE_SEARCH_QUERY).orEmpty()
        if (lastSearchQuery.isNotBlank()) searchInput.setText(lastSearchQuery)
        when (state.getString(STATE_SCREEN)) {
            START_SUBSCRIPTIONS -> loadSubscriptions()
            START_SUBSCRIPTION_FEED -> loadSubscriptions()
            STATE_CHANNEL -> {
                val channelId = state.getString(STATE_CHANNEL_ID).orEmpty()
                val channelTitle = state.getString(STATE_CHANNEL_TITLE).orEmpty()
                if (channelId.isNotBlank()) loadChannelVideos(channelId, channelTitle) else loadSubscriptions()
            }
            START_HISTORY -> loadHistory()
            START_POPULAR -> loadPopular()
            START_SHORTS -> loadShorts()
            START_PLAYLIST -> loadPlaylist()
            START_SEARCH -> if (lastSearchQuery.isNotBlank()) runSearch() else loadHome()
            else -> loadHome()
        }
    }

    private fun openInitialScreen() {
        when (intent.getStringExtra(EXTRA_START_SCREEN)) {
            START_SUBSCRIPTIONS -> loadSubscriptions()
            START_SUBSCRIPTION_FEED -> loadSubscriptions()
            START_HISTORY -> loadHistory()
            START_POPULAR -> loadPopular()
            START_SHORTS -> loadShorts()
            START_PLAYLIST -> loadPlaylist()
            START_SEARCH -> {
                loadHome()
                findViewById<View>(R.id.search_panel).visibility = View.VISIBLE
                searchInput.requestFocus()
                searchInput.post {
                    val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            else -> loadHome()
        }
    }

    private fun currentTab(): BrowseTab = when (screen) {
        is Screen.Home, is Screen.Search -> BrowseTab.HOME
        is Screen.Subscriptions, is Screen.ChannelVideos -> BrowseTab.SUBSCRIPTIONS
        is Screen.History -> BrowseTab.HISTORY
        is Screen.Popular -> BrowseTab.POPULAR
        is Screen.Shorts -> BrowseTab.SHORTS
        is Screen.Playlist -> BrowseTab.PLAYLIST
    }

    private fun isTopLevelTabActive(tab: BrowseTab): Boolean = when (tab) {
        BrowseTab.HOME -> screen == Screen.Home
        BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> screen == Screen.Subscriptions
        BrowseTab.HISTORY -> screen == Screen.History
        BrowseTab.POPULAR -> screen == Screen.Popular
        BrowseTab.SHORTS -> screen == Screen.Shorts
        BrowseTab.PLAYLIST -> screen == Screen.Playlist
    }

    private fun switchTab(delta: Int, animatePage: Boolean = false) {
        val currentIndex = visibleBrowseTabs.indexOf(currentTab()).let { if (it >= 0) it else 0 }
        val nextIndex = (currentIndex + delta).coerceIn(0, visibleBrowseTabs.lastIndex)
        if (nextIndex == currentIndex) {
            resetPageSwipe()
            return
        }

        val targetTab = visibleBrowseTabs[nextIndex]

        if (!animatePage || pagerStage.width <= 0) {
            selectBrowseTab(targetTab)
            resetPageSwipe(immediate = true)
            return
        }

        pageSettling = true
        val direction = if (delta > 0) -1f else 1f
        val target = direction * pagerStage.width
        val neighbor = if (delta > 0) nextPage else prevPage
        val hasPreparedNeighbor = neighbor.visibility == View.VISIBLE
        val remaining = kotlin.math.abs(target - swipeRefresh.translationX) / pagerStage.width.coerceAtLeast(1)
        val duration = (85L + (remaining * 95L).toLong()).coerceIn(85L, 180L)
        swipeFramePosted = false
        swipeRefresh.animate().cancel()
        prevPage.animate().cancel()
        nextPage.animate().cancel()

        if (hasPreparedNeighbor) {
            neighbor.animate()
                .translationX(0f)
                .setInterpolator(swipeInterpolator)
                .setDuration(duration)
                .start()
        }
        swipeRefresh.animate()
            .translationX(target)
            .setInterpolator(swipeInterpolator)
            .setDuration(duration)
            .withEndAction {
                // Commit exactly the page the user just swiped to. Do not call loadHome()/
                // loadSubscriptions()/... here: those methods show a loading state and then rebuild
                // the page asynchronously, which caused the visible post-swipe flash/jank.
                // selectBrowseTab() consumes the already-prefetched tab state synchronously.
                pageSettling = false
                deferredContent = null
                selectBrowseTab(targetTab, allowFetch = false, updateSelection = false)
                resetPageSwipe(immediate = true)
                updateTabSelection()
            }
            .start()
    }

    /**
     * Single source of truth for tab selection. Both chip taps and horizontal swipes use this path.
     * A normal tab change never refreshes data that is already cached; only pull-to-refresh passes
     * forceRefresh=true through the dedicated load methods.
     */
    private fun selectBrowseTab(tab: BrowseTab, allowFetch: Boolean = true, updateSelection: Boolean = true) {
        if (pageSettling) return
        saveRenderedPage()
        contentLoadJob?.cancel()
        subscriptionChannelJob?.cancel()
        subscriptionLoadingMore = false
        swipeRefresh.isRefreshing = false
        if (tab == BrowseTab.HISTORY) { loadHistory(); return }
        if (tab == BrowseTab.PLAYLIST) { loadPlaylist(updateSelection); return }
        if (restoreRenderedPage(tab)) {
            if (updateSelection) updateTabSelection()
            if (tab == BrowseTab.SUBSCRIPTIONS) {
                refreshSubscriptionsSilentlyIfStale()
                loadSubscriptionChannelsIfNeeded()
            }
            return
        }
        val cachedVideos = prefetchedTabs[tab]
        if (cachedVideos != null) {
            loadingIndicatorJob?.cancel()
            headerRow.visibility = View.GONE
            when (tab) {
                BrowseTab.HOME -> {
                    screen = Screen.Home
                    showVideoList(cachedVideos, paging = homeOutcome)
                }
                BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> {
                    screen = Screen.Subscriptions
                    val channels = prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS].orEmpty()
                    showSubscriptionsPage(BrowseOutcome.Ok(channels), subscriptionFeedOutcome ?: BrowseOutcome.Ok(cachedVideos, fetchedAtMs = subscriptionFeedStoredAtMs))
                    refreshSubscriptionsSilentlyIfStale()
                    loadSubscriptionChannelsIfNeeded()
                }
                BrowseTab.HISTORY -> {
                    if (!settings.isHistoryEnabled()) {
                        loadHistory()
                        return
                    }
                    screen = Screen.History
                    showVideoList(cachedVideos)
                }
                BrowseTab.POPULAR -> {
                    screen = Screen.Popular
                    showVideoList(cachedVideos)
                }
                BrowseTab.SHORTS -> {
                    screen = Screen.Shorts
                    showVideoList(cachedVideos)
                }
                BrowseTab.PLAYLIST -> {
                    // Local data: rebuild off-screen during a swipe, but do not move/re-render the
                    // neighboring pager pages until the swipe commit has completed.
                    loadPlaylist(updateSelection = updateSelection)
                    return
                }
            }
            if (updateSelection) updateTabSelection()
            return
        }

        if (!allowFetch || prefetchJobs[tab]?.isActive == true) {
            // A swipe can finish before background prefetch completes. Keep the selected tab state
            // coherent, then let the in-flight prefetch populate it instead of issuing a duplicate
            // request. If no prefetch is running, fall through to the regular loader.
            val inFlight = prefetchJobs[tab]?.isActive == true
            if (inFlight) {
                when (tab) {
                    BrowseTab.HOME -> screen = Screen.Home
                    BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> screen = Screen.Subscriptions
                    BrowseTab.HISTORY -> screen = Screen.History
                    BrowseTab.POPULAR -> screen = Screen.Popular
                    BrowseTab.SHORTS -> screen = Screen.Shorts
                    BrowseTab.PLAYLIST -> screen = Screen.Playlist
                }
                if (updateSelection) updateTabSelection()
                clearResults()
                showLoading()
                contentLoadJob = lifecycleScope.launch {
                    prefetchJobs[tab]?.join()
                    if (isActive && browseVisible && isTopLevelTabActive(tab)) afterPageMotion {
                        if (isTopLevelTabActive(tab)) selectBrowseTab(tab, allowFetch = true)
                    }
                }
                return
            }
        }

        when (tab) {
            BrowseTab.HOME -> loadHome()
            BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> loadSubscriptions()
            BrowseTab.HISTORY -> loadHistory()
            BrowseTab.POPULAR -> loadPopular()
            BrowseTab.SHORTS -> loadShorts()
            BrowseTab.PLAYLIST -> loadPlaylist()
        }
    }

    private fun resetPageSwipe(immediate: Boolean = false) {
        swipeFramePosted = false
        pendingSwipeTranslationX = 0f
        swipeRefresh.animate().cancel()
        prevPage.animate().cancel()
        nextPage.animate().cancel()
        swipeDragging = false
        val width = pagerStage.width.toFloat()
        fun finishReset() {
            swipeRefresh.translationX = 0f
            prevPage.translationX = -width
            nextPage.translationX = width
            pageSettling = false
            setPagerHardwareLayers(false)
            val pending = deferredContent
            deferredContent = null
            pending?.invoke()
            renderNeighborPages()
        }
        if (immediate) finishReset() else {
            pageSettling = true
            prevPage.animate().translationX(-width).setDuration(105L).start()
            nextPage.animate().translationX(width).setDuration(105L).start()
            swipeRefresh.animate().translationX(0f).setInterpolator(swipeInterpolator)
                .setDuration(105L).withEndAction { finishReset() }.start()
        }
    }

    private fun schedulePageTranslation(translationX: Float) {
        pendingSwipeTranslationX = translationX
        if (swipeFramePosted) return
        swipeFramePosted = true
        pagerStage.postOnAnimation {
            if (!swipeFramePosted || pageSettling || !swipeDragging) return@postOnAnimation
            val dx = pendingSwipeTranslationX
            val width = pagerStage.width.coerceAtLeast(1).toFloat()
            swipeRefresh.translationX = dx
            if (dx < 0f) {
                nextPage.translationX = width + dx
                prevPage.translationX = -width
            } else if (dx > 0f) {
                prevPage.translationX = -width + dx
                nextPage.translationX = width
            } else {
                prevPage.translationX = -width
                nextPage.translationX = width
            }
            swipeFramePosted = false
        }
    }

    private fun scheduleNeighborPrefetch() {
        if (!browseVisible || CarPlaybackQos.isProjectedVideoVisible()) return
        val currentIndex = visibleBrowseTabs.indexOf(currentTab()).coerceAtLeast(0)
        val neighbors = listOfNotNull(
            visibleBrowseTabs.getOrNull(currentIndex - 1),
            visibleBrowseTabs.getOrNull(currentIndex + 1)
        )
        neighbors.forEach { tab ->
            if (prefetchedTabs.containsKey(tab) || prefetchJobs[tab]?.isActive == true) return@forEach
            prefetchJobs[tab] = lifecycleScope.launch {
                val data = loadPreviewData(tab)
                if (data != null) {
                    prefetchedTabs[tab] = data
                    renderNeighborPages()
                }
                prefetchJobs.remove(tab)
            }
        }
        renderNeighborPages()
    }

    private suspend fun loadPreviewData(tab: BrowseTab): List<MediaDescriptionCompat>? {
        fun outcomeData(outcome: BrowseOutcome<List<MediaDescriptionCompat>>): List<MediaDescriptionCompat>? =
            (outcome as? BrowseOutcome.Ok)?.data
        return when (tab) {
            BrowseTab.HOME -> {
                val outcome = feedStore.loadHome().asOutcome()
                // Keep the paging flags with the prefetched list, or Home restored from the
                // prefetch would lose its "load more" row.
                if (outcome is BrowseOutcome.Ok) homeOutcome = outcome
                outcomeData(outcome)
            }
            BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> {
                // The prefetch only knows the unfiltered feed; with a chip active it would
                // replace the filtered list behind the highlighted chip.
                if (selectedChip != null) return prefetchedTabs[tab]
                val state = feedStore.loadSubscriptions()
                if (state.channels.isNotEmpty()) prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS] = state.channels
                val outcome = state.asOutcome()
                if (outcome is BrowseOutcome.Ok) rememberSubscriptionFeed(outcome, chipTitle = null)
                outcomeData(outcome)
            }
            BrowseTab.POPULAR -> outcomeData(browseFacade.popular(forceRefresh = false))
            BrowseTab.SHORTS -> outcomeData(browseFacade.subscriptionShorts(forceRefresh = false))
            BrowseTab.HISTORY -> WatchHistoryStore(this).getAll().map { it.toDescription() }
            BrowseTab.PLAYLIST -> UserPlaylistStore(this).getAll().map { it.toDescription() }
        }
    }

    private fun renderNeighborPages() {
        if (!::pagerStage.isInitialized || pagerStage.width <= 0 || swipeDragging || pageSettling) return
        val currentIndex = visibleBrowseTabs.indexOf(currentTab()).coerceAtLeast(0)
        renderPreviewPage(
            prevPage,
            prevContainer,
            visibleBrowseTabs.getOrNull(currentIndex - 1),
            -pagerStage.width.toFloat()
        )
        renderPreviewPage(
            nextPage,
            nextContainer,
            visibleBrowseTabs.getOrNull(currentIndex + 1),
            pagerStage.width.toFloat()
        )
    }

    private fun renderPreviewPage(
        page: View,
        container: LinearLayout,
        tab: BrowseTab?,
        restingTranslation: Float
    ) {
        page.animate().cancel()
        page.translationX = restingTranslation
        if (tab == null) {
            page.visibility = View.INVISIBLE
            page.tag = null
            container.removeAllViews()
            return
        }
        val data = prefetchedTabs[tab]
        page.visibility = View.VISIBLE
        val previous = page.tag as? Pair<*, *>
        if (previous?.first == tab && previous.second === data) return
        page.tag = tab to data
        if (data == null) {
            page.visibility = View.VISIBLE
            container.removeAllViews()
            val loading = TextView(this).apply {
                text = getString(R.string.browse_loading)
                setTextColor(getColor(R.color.md_theme_onSurfaceVariant))
                textSize = 14f
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }
            container.addView(loading)
            return
        }
        page.visibility = View.VISIBLE
        container.removeAllViews()
        if (data.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.browse_empty)
                setTextColor(getColor(R.color.md_theme_onSurfaceVariant))
                textSize = 14f
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }
            container.addView(empty)
            return
        }
        val previewQueue = data
        val inflater = LayoutInflater.from(this)
        data.take(3).forEach { video ->
            val row = inflater.inflate(videoBrowseLayout(), container, false)
            bindVideoRow(row, video, previewQueue, preview = true)
            container.addView(row)
        }
    }

    private fun isInsideHorizontalScroller(root: View, x: Float, y: Float): Boolean {
        fun contains(view: View): Boolean {
            if (view.visibility != View.VISIBLE) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return x >= location[0] && x <= location[0] + view.width &&
                y >= location[1] && y <= location[1] + view.height
        }
        fun walk(view: View): Boolean {
            val horizontal = view is HorizontalScrollView ||
                (view is RecyclerView && (view.layoutManager as? LinearLayoutManager)?.orientation == RecyclerView.HORIZONTAL)
            if (view !== root && horizontal && contains(view)) return true
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) if (walk(view.getChildAt(i))) return true
            }
            return false
        }
        return walk(root)
    }


    private fun setPagerHardwareLayers(enabled: Boolean) {
        val layer = if (enabled) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        swipeRefresh.setLayerType(layer, null)
        prevPage.setLayerType(layer, null)
        nextPage.setLayerType(layer, null)
    }

    private fun cancelChildGesture(ev: MotionEvent) {
        if (swipeChildCancelled) return
        swipeChildCancelled = true
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun finishVelocityTracking() {
        swipeVelocityTracker?.recycle()
        swipeVelocityTracker = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val page = pagerStage
        if (pageSettling) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishVelocityTracking()
                swipeVelocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                swipeChildCancelled = false
                page.animate().cancel()
                swipeRefresh.animate().cancel()
                prevPage.animate().cancel()
                nextPage.animate().cancel()
                swipeFramePosted = false
                pendingSwipeTranslationX = swipeRefresh.translationX
                swipeStartTranslationX = swipeRefresh.translationX
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeDragging = false
                val bounds = android.graphics.Rect()
                page.getGlobalVisibleRect(bounds)
                swipeTracking = bounds.contains(ev.rawX.toInt(), ev.rawY.toInt()) &&
                    !isInsideHorizontalScroller(page, ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                swipeVelocityTracker?.addMovement(ev)
                if (swipeTracking) {
                    val dx = ev.x - swipeDownX
                    val dy = ev.y - swipeDownY
                    if (!swipeDragging && abs(dy) > swipeTouchSlop && abs(dx) <= abs(dy) * 1.35f) {
                        swipeTracking = false
                        return super.dispatchTouchEvent(ev)
                    }
                    if (!swipeDragging && abs(dx) > swipeTouchSlop && abs(dx) > abs(dy) * 1.6f) {
                        swipeDragging = true
                        swipeRefresh.isEnabled = false
                        cancelChildGesture(ev)
                        setPagerHardwareLayers(true)
                    }
                    if (swipeDragging) {
                        val currentIndex = visibleBrowseTabs.indexOf(currentTab()).coerceAtLeast(0)
                        val atLeftEdge = currentIndex == 0 && dx > 0f
                        val atRightEdge = currentIndex == visibleBrowseTabs.lastIndex && dx < 0f
                        val resistance = if (atLeftEdge || atRightEdge) 0.24f else 1f
                        schedulePageTranslation(swipeStartTranslationX + dx * resistance)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                swipeVelocityTracker?.addMovement(ev)
                if (swipeTracking && swipeDragging) {
                    val dx = ev.x - swipeDownX
                    swipeVelocityTracker?.computeCurrentVelocity(1000, swipeMaxFlingVelocity.toFloat())
                    val vx = swipeVelocityTracker?.xVelocity ?: 0f
                    val distanceThreshold = page.width * 0.16f
                    val flingThreshold = maxOf(swipeMinFlingVelocity * 5f, 650f * resources.displayMetrics.density)
                    val fling = abs(vx) >= flingThreshold && abs(dx) >= page.width * 0.06f
                    val direction = when {
                        fling -> if (vx < 0f) +1 else -1
                        abs(dx) >= distanceThreshold -> if (dx < 0f) +1 else -1
                        else -> 0
                    }
                    swipeTracking = false
                    swipeDragging = false
                    swipeRefresh.isEnabled = true
                    finishVelocityTracking()
                    if (direction != 0) switchTab(direction, animatePage = true) else resetPageSwipe()
                    return true
                }
                swipeTracking = false
                swipeDragging = false
                swipeRefresh.isEnabled = true
                finishVelocityTracking()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                swipeTracking = false
                swipeDragging = false
                swipeRefresh.isEnabled = true
                finishVelocityTracking()
                resetPageSwipe()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshCurrent(forceRefresh: Boolean) {
        when (val s = screen) {
            is Screen.Home -> loadHome(forceRefresh)
            is Screen.Subscriptions -> loadSubscriptions(forceRefresh)
            is Screen.ChannelVideos -> loadChannelVideos(s.channelId, headerTitle.text?.toString().orEmpty(), forceRefresh)
            is Screen.History -> {
                loadHistory()
                swipeRefresh.isRefreshing = false
            }
            is Screen.Popular -> loadPopular(forceRefresh)
            is Screen.Shorts -> loadShorts(forceRefresh)
            is Screen.Playlist -> {
                loadPlaylist()
                swipeRefresh.isRefreshing = false
            }
            is Screen.Search -> {
                if (lastSearchQuery.isNotBlank()) runSearch(forceRefresh, lastSearchQuery)
                else swipeRefresh.isRefreshing = false
            }
        }
    }


    private fun updateTabSelection() {
        val active = currentTab()
        findViewById<View>(R.id.nav_home).isSelected = active == BrowseTab.HOME || active == BrowseTab.POPULAR
        findViewById<View>(R.id.nav_you).isSelected = active == BrowseTab.HISTORY || active == BrowseTab.PLAYLIST
        findViewById<View>(R.id.browse_tabs_scroll).visibility =
            if (active == BrowseTab.SUBSCRIPTIONS || active == BrowseTab.SHORTS) View.GONE else View.VISIBLE
        findViewById<View>(R.id.nav_subscriptions).isSelected = active == BrowseTab.SUBSCRIPTIONS
        findViewById<View>(R.id.nav_shorts).isSelected = active == BrowseTab.SHORTS
        scheduleNeighborPrefetch()
        val tabs = listOf(
            R.id.tab_home to BrowseTab.HOME,
            R.id.tab_subscriptions to BrowseTab.SUBSCRIPTIONS,
            R.id.tab_history to BrowseTab.HISTORY,
            R.id.tab_popular to BrowseTab.POPULAR,
            R.id.tab_playlist to BrowseTab.PLAYLIST
        )
        var selectedView: View? = null
        for ((id, tab) in tabs) {
            val button = findViewById<MaterialButton>(id)
            val visible = when (active) {
                BrowseTab.HOME, BrowseTab.POPULAR -> tab == BrowseTab.HOME || tab == BrowseTab.POPULAR
                BrowseTab.HISTORY, BrowseTab.PLAYLIST -> tab == BrowseTab.HISTORY || tab == BrowseTab.PLAYLIST
                else -> false
            }
            button.visibility = if (visible) View.VISIBLE else View.GONE
            val selected = tab == active
            val bg = if (selected) R.color.md_theme_onSurface else R.color.md_theme_secondaryContainer
            val fg = if (selected) R.color.md_theme_surface else R.color.md_theme_onSecondaryContainer
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(bg))
            button.setTextColor(getColor(fg))
            button.alpha = if (selected) 1f else 0.9f
            button.isSelected = selected
            button.elevation = 0f
            if (selected) selectedView = button
        }
        // Keep the active chip in view inside the horizontal tab scroller.
        val chip = selectedView ?: return
        chip.post {
            val scroller = findViewById<android.widget.HorizontalScrollView>(R.id.browse_tabs_scroll)
            val target = (chip.left - (scroller.width - chip.width) / 2).coerceAtLeast(0)
            scroller.smoothScrollTo(target, 0)
        }
    }


    private fun navigateBack() {
        when (screen) {
            is Screen.ChannelVideos -> loadSubscriptions()
            else -> Unit
        }
    }

    private fun loadPlaylist(updateSelection: Boolean = true) {
        contentLoadJob?.cancel()
        loadingIndicatorJob?.cancel()
        screen = Screen.Playlist
        if (updateSelection) updateTabSelection()
        headerRow.visibility = View.GONE
        clearResults()
        val store = UserPlaylistStore(this)
        val entries = store.getAll()
        prefetchedTabs[BrowseTab.PLAYLIST] = entries.map { it.toDescription() }
        if (entries.isEmpty()) {
            showEmptyState(
                message = getString(R.string.browse_playlist_empty),
                actionLabel = getString(R.string.browse_search_videos)
            ) {
                searchInput.requestFocus()
                searchInput.post {
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            return
        }
        hideState()
        val descriptions = entries.map { it.toDescription() }
        prefetchedTabs[BrowseTab.PLAYLIST] = descriptions
        fun addActionRow(label: String, onClick: () -> Unit) {
            browseRows += PhoneBrowseRow.Action("playlist:$label", label, click = onClick)
        }

        addActionRow(getString(R.string.browse_playlist_play_all)) {
            val first = descriptions.first()
            startActivity(
                Intent(this, VideoPlayerActivity::class.java)
                    .putExtra(VideoPlayerActivity.EXTRA_VIDEO, first)
                    .putParcelableArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE, ArrayList(descriptions))
            )
        }
        addActionRow(getString(R.string.browse_playlist_cast_all)) {
            val first = descriptions.first()
            val message = when (
                RemoteCastBus.cast(
                    first,
                    descriptions,
                    this,
                    startPositionMs = WatchHistoryStore.resumePositionFromDescription(first),
                    onProjectionFailed = ::showCastFailedToast
                )
            ) {
                RemoteCastBus.CastResult.Queued -> R.string.browse_cast_queued
                RemoteCastBus.CastResult.ParkBlocked -> R.string.browse_cast_park_blocked
                RemoteCastBus.CastResult.Rejected -> R.string.browse_cast_unavailable
            }
            Toast.makeText(this, getString(message), Toast.LENGTH_LONG).show()
        }
        addActionRow(getString(R.string.browse_playlist_clear)) {
            store.clear()
            loadPlaylist()
        }

        addVideoRows(descriptions, inPlaylist = true)
    }

    private fun videoBrowseLayout(): Int =
        if (settings.isPhoneBrowseCardsEnabled()) R.layout.item_youtube_video_card else R.layout.item_youtube_video

    private fun showOutcomeVideos(
        outcome: BrowseOutcome<List<MediaDescriptionCompat>>,
        cacheTab: BrowseTab? = null,
        append: Boolean = false
    ) {
        loadingIndicatorJob?.cancel()
        when (outcome) {
            is BrowseOutcome.Ok -> {
                if (!append) cacheTab?.let { prefetchedTabs[it] = outcome.data }
                if (cacheTab == BrowseTab.HOME) homeOutcome = outcome
                // Home pages like the subscriptions feed; without this its continuations were
                // fetched but never reachable from the list.
                showVideoList(outcome.data, paging = outcome.takeIf { cacheTab == BrowseTab.HOME }, append = append)
                scheduleNeighborPrefetch()
            }
            is BrowseOutcome.NeedsSignIn -> {
                clearResults()
                showState(
                    title = getString(R.string.browse_link_account),
                    message = outcome.message,
                    actionLabel = getString(R.string.browse_sign_in_action),
                    onAction = ::showLoginChooser
                )
            }
            is BrowseOutcome.Error -> showError(outcome.message)
        }
    }

    private fun bindChannelThumbnail(image: ImageView, thumbnailUrl: String?, channelId: String) {
        image.bindThumbnail(lifecycleScope, thumbnailUrl, settings.getThumbnailQuality(), dp(56),
            placeholder = R.drawable.ic_person, resolveUrl = { browseFacade.channelAvatar(channelId) })
    }

    private fun showSubscriptionsPage(
        channelsOutcome: BrowseOutcome<List<HybridChannel>>,
        feedOutcome: BrowseOutcome<List<MediaDescriptionCompat>>,
        resetScroll: Boolean = false
    ) {
        loadingIndicatorJob?.cancel()

        if (channelsOutcome is BrowseOutcome.NeedsSignIn && feedOutcome !is BrowseOutcome.Ok) {
            showOutcomeVideos(BrowseOutcome.NeedsSignIn(channelsOutcome.message))
            return
        }
        if (feedOutcome is BrowseOutcome.NeedsSignIn) {
            showOutcomeVideos(BrowseOutcome.NeedsSignIn(feedOutcome.message))
            return
        }
        if (channelsOutcome is BrowseOutcome.Error && feedOutcome is BrowseOutcome.Error) {
            showError(feedOutcome.message)
            return
        }

        val channels = (channelsOutcome as? BrowseOutcome.Ok)?.data.orEmpty()
        // On failure only the remembered feed of the same filter may stand in; another filter's
        // (or the unfiltered) list under the newly highlighted chip would be plainly wrong.
        val usableFeed = (feedOutcome as? BrowseOutcome.Ok)
            ?: subscriptionFeedOutcome?.takeIf { subscriptionFeedChipTitle == selectedChip?.title }
                ?.copy(partial = true)
        val feed = usableFeed?.data.orEmpty()
        prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS] = channels
        prefetchedTabs[BrowseTab.SUBSCRIPTIONS] = feed
        if (usableFeed != null) rememberSubscriptionFeed(usableFeed)
        browseRows.clear()
        displayedVideos = feed

        if (channels.isEmpty() && feed.isEmpty() && usableFeed?.hasMore != true && usableFeed?.partial != true) {
            val message = (feedOutcome as? BrowseOutcome.Error)?.message
                ?: (channelsOutcome as? BrowseOutcome.Error)?.message
            if (message != null) showError(message) else showEmptyState()
            return
        }

        hideState()
        if (channels.isNotEmpty()) browseRows += PhoneBrowseRow.Channels(channels)
        val chips = browseFacade.subscriptionChips()
        if (chips.isNotEmpty()) {
            browseRows += PhoneBrowseRow.Chips(chips, selectedChip?.title ?: chips.firstOrNull()?.title) { chip ->
                selectSubscriptionChip(chip)
            }
        }
        browseRows += PhoneBrowseRow.Header(getString(R.string.browse_subscriptions_latest))
        browseRows += feed.map { PhoneBrowseRow.Video(it) }
        updateSubscriptionAction()
        if (usableFeed == null && feedOutcome is BrowseOutcome.Error) {
            browseRows += PhoneBrowseRow.Action("feed:retry", getString(R.string.browse_feed_retry), click = {
                // Retry the filter that failed; a plain reload would drop the chip.
                loadSubscriptions(forceRefresh = true)
            })
        }
        displayedTab = BrowseTab.SUBSCRIPTIONS
        submitBrowseRows(resetScroll)
        swipeRefresh.isRefreshing = false
    }

    private fun rememberSubscriptionFeed(
        outcome: BrowseOutcome.Ok<List<MediaDescriptionCompat>>,
        chipTitle: String? = selectedChip?.title
    ) {
        subscriptionFeedOutcome = outcome
        subscriptionFeedChipTitle = chipTitle
        subscriptionFeedStoredAtMs = outcome.fetchedAtMs
        prefetchedTabs[BrowseTab.SUBSCRIPTIONS] = outcome.data
        renderedPages.remove(BrowseTab.SUBSCRIPTIONS)
    }

    private fun FeedStore.FeedState<MediaDescriptionCompat>.asOutcome(): BrowseOutcome<List<MediaDescriptionCompat>> = when {
        videos.isNotEmpty() || (loaded && error == null && needsSignIn == null) ->
            BrowseOutcome.Ok(videos, fromPersonal = true, hasMore = hasMore, partial = partial,
                fetchedAtMs = if (fetchedAtMs != 0L) fetchedAtMs else System.currentTimeMillis())
        needsSignIn != null -> BrowseOutcome.NeedsSignIn(needsSignIn.orEmpty())
        else -> BrowseOutcome.Error(error ?: getString(R.string.browse_empty))
    }

    private fun FeedStore.FeedState<MediaDescriptionCompat>.channelOutcome(): BrowseOutcome<List<HybridChannel>> =
        BrowseOutcome.Ok(channels)

    /**
     * The store also changes when the car UI or the Android Auto browser pages or refreshes.
     * The open list follows it, but only when the content differs from what is shown.
     */
    private fun observeFeedStore() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    feedStore.home.collect { state ->
                        if (screen != Screen.Home || contentLoadJob?.isActive == true || !state.loaded) return@collect
                        if (state.videos == prefetchedTabs[BrowseTab.HOME]) return@collect
                        val outcome = state.asOutcome() as? BrowseOutcome.Ok ?: return@collect
                        // "More" elsewhere only appended; a refresh elsewhere replaced the list.
                        val shown = prefetchedTabs[BrowseTab.HOME].orEmpty()
                        val appended = shown.isNotEmpty() && state.videos.take(shown.size) == shown
                        afterPageMotion { if (screen == Screen.Home) showOutcomeVideos(outcome, BrowseTab.HOME, append = appended) }
                    }
                }
                launch {
                    feedStore.subscriptions.collect { state ->
                        if (screen != Screen.Subscriptions || contentLoadJob?.isActive == true || !state.loaded) return@collect
                        if (state.videos == subscriptionFeedOutcome?.data &&
                            state.channels == prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS]) return@collect
                        afterPageMotion {
                            if (screen == Screen.Subscriptions) showSubscriptionsPage(state.channelOutcome(), state.asOutcome())
                        }
                    }
                }
            }
        }
    }

    private fun refreshSubscriptionsSilentlyIfStale() {
        if (CarPlaybackQos.isProjectedVideoVisible()) return
        // A filtered feed is an explicit request and not cached. Refreshing would fetch the
        // unfiltered feed under a still highlighted chip and restart the chip's paging session.
        if (selectedChip != null) return
        val ageMs = System.currentTimeMillis() - subscriptionFeedStoredAtMs
        if (subscriptionFeedStoredAtMs != 0L && ageMs in 0 until SUBSCRIPTION_VISIBLE_TTL_MS) return
        if (subscriptionSilentRefreshJob?.isActive == true || contentLoadJob?.isActive == true) return
        subscriptionSilentRefreshJob = lifecycleScope.launch {
            // The store applies the TTL and keeps the list when nothing visible changed.
            val fresh = feedStore.revalidateSubscriptions().asOutcome()
            if (!isActive || fresh !is BrowseOutcome.Ok) return@launch
            val oldData = prefetchedTabs[BrowseTab.SUBSCRIPTIONS].orEmpty()
            fun visibleKeys(items: List<MediaDescriptionCompat>) = items.map {
                listOf(it.mediaId, it.title?.toString(), it.subtitle?.toString(), it.iconUri?.toString())
            }
            val changed = visibleKeys(oldData) != visibleKeys(fresh.data) ||
                subscriptionFeedOutcome?.hasMore != fresh.hasMore || subscriptionFeedOutcome?.partial != fresh.partial
            val displayed = if (changed) fresh else fresh.copy(data = oldData)
            rememberSubscriptionFeed(displayed)
            if (changed && screen == Screen.Subscriptions) {
                val channels = prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS].orEmpty()
                afterPageMotion {
                    if (screen == Screen.Subscriptions && subscriptionFeedOutcome === displayed) showSubscriptionsPage(BrowseOutcome.Ok(channels), displayed)
                    renderNeighborPages()
                }
            }
        }
    }

    private fun updateSubscriptionAction() {
        browseRows.removeAll { it.key == "feed:more" }
        val outcome = subscriptionFeedOutcome ?: return
        if (!outcome.hasMore && !outcome.partial) return
        val label = when {
            subscriptionLoadingMore -> R.string.browse_loading
            outcome.partial -> R.string.browse_feed_retry
            else -> R.string.browse_feed_more
        }
        browseRows += PhoneBrowseRow.Action("feed:more", getString(label), !subscriptionLoadingMore) {
            if (outcome.partial && !outcome.hasMore) loadSubscriptions(forceRefresh = true)
            else loadMoreSubscriptions()
        }
    }

    private fun loadMoreSubscriptions() {
        if (contentLoadJob?.isActive == true) return
        subscriptionSilentRefreshJob?.cancel()
        subscriptionLoadingMore = true
        updateSubscriptionAction()
        submitBrowseRows()
        contentLoadJob = lifecycleScope.launch {
            try {
                val outcome = feedStore.loadMoreSubscriptions().asOutcome()
                if (!isActive || screen != Screen.Subscriptions) return@launch
                if (outcome is BrowseOutcome.Ok) {
                    rememberSubscriptionFeed(outcome)
                    afterPageMotion {
                        if (screen == Screen.Subscriptions && subscriptionFeedOutcome === outcome) {
                            showSubscriptionsPage(BrowseOutcome.Ok(prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS].orEmpty()), outcome)
                        }
                    }
                } else {
                    subscriptionFeedOutcome = subscriptionFeedOutcome?.copy(partial = true)
                }
            } finally {
                subscriptionLoadingMore = false
                if (screen == Screen.Subscriptions) {
                    updateSubscriptionAction()
                    submitBrowseRows()
                }
            }
        }
    }

    private fun addVideoRows(descriptions: List<MediaDescriptionCompat>, inPlaylist: Boolean = false) {
        displayedVideos = descriptions
        browseRows += descriptions.map { PhoneBrowseRow.Video(it, inPlaylist) }
        submitBrowseRows()
    }

    private fun clearResults() {
        browseRows.clear()
        displayedVideos = emptyList()
        displayedTab = null
        submitBrowseRows()
    }

    private fun applyRepairedVideo(source: MediaDescriptionCompat, updated: MediaDescriptionCompat) {
        if (displayedVideos.none { it === source }) return
        displayedVideos = displayedVideos.map { if (it === source) updated else it }
        prefetchedTabs.keys.toList().forEach { tab ->
            val data = prefetchedTabs[tab].orEmpty()
            if (data.any { it === source }) {
                prefetchedTabs[tab] = data.map { if (it === source) updated else it }
                renderedPages.remove(tab)
            }
        }
        if (isTopLevelTabActive(currentTab())) prefetchedTabs[displayedTab]?.let { displayedVideos = it }
        subscriptionFeedOutcome?.let { old ->
            if (old.data.any { it === source }) subscriptionFeedOutcome = old.copy(data = old.data.map { if (it === source) updated else it })
        }
        feedStore.patch { if (it === source) updated else it }
        for (index in browseRows.indices) {
            val row = browseRows[index]
            if (row is PhoneBrowseRow.Video && row.description === source) browseRows[index] = row.copy(description = updated)
        }
        submitBrowseRows()
    }

    private fun runSearch(forceRefresh: Boolean = false, queryOverride: String? = null) {
        val query = (queryOverride ?: searchInput.text?.toString())?.trim().orEmpty()
        if (query.isBlank()) {
            swipeRefresh.isRefreshing = false
            return
        }
        lastSearchQuery = query
        currentFocus?.let { focused ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        val targetScreen = Screen.Search
        screen = targetScreen
        updateTabSelection()
        headerRow.visibility = View.GONE
        showLoading()
        contentLoadJob?.cancel()
        contentLoadJob = lifecycleScope.launch {
            val outcome = browseFacade.search(query, forceRefresh)
            if (!isActive || screen != targetScreen) return@launch
            afterPageMotion { if (screen == targetScreen) showOutcomeVideos(outcome) }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun loadHome(forceRefresh: Boolean = false, loadMore: Boolean = false) {
        // A second non-explicit load used to replace the visible recommendations, so rows
        // appeared and disappeared while Startseite stayed open. Pull-to-refresh replaces;
        // "Mehr" only appends.
        if (!forceRefresh && !loadMore && screen == Screen.Home && browseRows.any { it is PhoneBrowseRow.Video }) {
            swipeRefresh.isRefreshing = false
            return
        }
        val targetScreen = Screen.Home
        screen = targetScreen
        updateTabSelection()
        headerRow.visibility = View.GONE
        if (!loadMore) showLoading()
        contentLoadJob?.cancel()
        contentLoadJob = lifecycleScope.launch {
            // Persönliche YouTube-Empfehlungen bei TV-Login, sonst robuster öffentlicher Fallback.
            val outcome = when {
                forceRefresh -> feedStore.refreshHome()
                loadMore -> feedStore.loadMoreHome()
                else -> feedStore.loadHome()
            }.asOutcome()
            if (!isActive || screen != targetScreen) return@launch
            afterPageMotion {
                if (screen == targetScreen) showOutcomeVideos(outcome, BrowseTab.HOME, append = loadMore && !forceRefresh)
            }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun loadSubscriptionChannelsIfNeeded(forceRefresh: Boolean = false) {
        if (!forceRefresh && (prefetchedSubscriptionChannels.containsKey(BrowseTab.SUBSCRIPTIONS) || subscriptionChannelJob?.isActive == true)) return
        subscriptionChannelJob?.cancel()
        subscriptionChannelJob = lifecycleScope.launch {
            // An explicit refresh fetches the channels together with the feed in the store.
            val channels = feedStore.loadSubscriptions().channelOutcome()
            if (!isActive) return@launch
            if (channels is BrowseOutcome.Ok) {
                prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS] = channels.data
                if (screen == Screen.Subscriptions) afterPageMotion {
                    if (screen == Screen.Subscriptions) subscriptionFeedOutcome?.let { showSubscriptionsPage(channels, it) }
                }
                // Visible video avatars may now be resolvable without another channel-index fetch.
                for (i in 0 until resultsScroll.childCount) {
                    val row = resultsScroll.getChildAt(i)
                    val holder = resultsScroll.getChildViewHolder(row) as? PhoneBrowseAdapter.Holder
                    val video = (holder?.row as? PhoneBrowseRow.Video)?.description ?: continue
                    bindVideoChannelAvatar(row.findViewById(R.id.video_channel_avatar), video)
                }
            }
        }
    }

    /** Switches the subscriptions feed to a filter chip, or back to the unfiltered feed. */
    private fun selectSubscriptionChip(chip: FeedChip) {
        if (chip.title == selectedChip?.title) return
        loadSubscriptions(forceRefresh = false, chip = chip)
    }

    private fun loadSubscriptions(forceRefresh: Boolean = false, chip: FeedChip? = null) {
        subscriptionSilentRefreshJob?.cancel()
        prefetchJobs.remove(BrowseTab.SUBSCRIPTIONS)?.cancel()
        val targetScreen = Screen.Subscriptions
        screen = targetScreen
        updateTabSelection()
        headerRow.visibility = View.GONE
        showLoading()
        contentLoadJob?.cancel()
        loadSubscriptionChannelsIfNeeded(forceRefresh)
        contentLoadJob = lifecycleScope.launch {
            val state = when {
                chip != null -> feedStore.selectChip(chip)
                // A refresh reloads what is shown, active filter included.
                forceRefresh -> feedStore.refreshSubscriptions()
                // Reaching Abos through the tab is the unfiltered feed again.
                selectedChip != null -> feedStore.selectChip(null)
                else -> feedStore.loadSubscriptions()
            }
            if (state.channels.isNotEmpty()) prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS] = state.channels
            val feed = state.asOutcome()
            if (!isActive || screen != targetScreen) return@launch
            afterPageMotion {
                if (screen == targetScreen) showSubscriptionsPage(
                    BrowseOutcome.Ok(prefetchedSubscriptionChannels[BrowseTab.SUBSCRIPTIONS].orEmpty()), feed,
                    resetScroll = forceRefresh)
            }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun loadChannelVideos(channelId: String, channelTitle: String, forceRefresh: Boolean = false) {
        val targetScreen = Screen.ChannelVideos(channelId)
        screen = targetScreen
        updateTabSelection()
        headerRow.visibility = View.VISIBLE
        headerTitle.text = channelTitle
        showLoading()
        contentLoadJob?.cancel()
        contentLoadJob = lifecycleScope.launch {
            val outcome = browseFacade.channelVideos(channelId, forceRefresh)
            if (!isActive || screen != targetScreen) return@launch
            afterPageMotion { if (screen == targetScreen) showOutcomeVideos(outcome) }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun loadHistory() {
        contentLoadJob?.cancel()
        loadingIndicatorJob?.cancel()
        if (!SettingsRepository(this).isHistoryEnabled()) {
            screen = Screen.History
            updateTabSelection()
            headerRow.visibility = View.GONE
            clearResults()
            showState(
                title = getString(R.string.browse_tab_history),
                message = getString(R.string.browse_history_disabled),
                actionLabel = getString(R.string.browse_open_settings)
            ) { startActivity(Intent(this, SettingsActivity::class.java)) }
            return
        }
        screen = Screen.History
        updateTabSelection()
        headerRow.visibility = View.GONE
        val entries = WatchHistoryStore(this).getAll()
        val descriptions = entries.map { it.toDescription() }
        prefetchedTabs[BrowseTab.HISTORY] = descriptions
        showVideoList(descriptions)
    }

    private fun loadPopular(forceRefresh: Boolean = false) {
        val targetScreen = Screen.Popular
        screen = targetScreen
        updateTabSelection()
        headerRow.visibility = View.GONE
        showLoading()
        contentLoadJob?.cancel()
        contentLoadJob = lifecycleScope.launch {
            val outcome = browseFacade.popular(forceRefresh)
            if (!isActive || screen != targetScreen) return@launch
            afterPageMotion { if (screen == targetScreen) showOutcomeVideos(outcome, BrowseTab.POPULAR) }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun loadShorts(forceRefresh: Boolean = false) {
        val targetScreen = Screen.Shorts
        screen = targetScreen
        updateTabSelection()
        headerRow.visibility = View.GONE
        showLoading()
        contentLoadJob?.cancel()
        contentLoadJob = lifecycleScope.launch {
            val outcome = browseFacade.subscriptionShorts(forceRefresh)
            if (!isActive || screen != targetScreen) return@launch
            afterPageMotion { if (screen == targetScreen) showOutcomeVideos(outcome, BrowseTab.SHORTS) }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun hideState() {
        stateCard.visibility = View.GONE
        statusText.visibility = View.GONE
        statusText.text = ""
        stateAction.setOnClickListener(null)
    }

    private fun showState(
        title: String,
        message: String,
        loading: Boolean = false,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        statusText.visibility = View.GONE
        stateCard.visibility = View.VISIBLE
        stateProgress.visibility = if (loading) View.VISIBLE else View.GONE
        stateTitle.text = title
        stateMessage.text = message
        stateMessage.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (actionLabel != null && onAction != null) {
            stateAction.visibility = View.VISIBLE
            stateAction.text = actionLabel
            stateAction.setOnClickListener { onAction.invoke() }
        } else {
            stateAction.visibility = View.GONE
            stateAction.text = ""
            stateAction.setOnClickListener(null)
        }
    }

    private fun showEmptyState(
        message: String = getString(R.string.browse_state_empty_message),
        actionLabel: String = getString(R.string.browse_retry),
        onAction: () -> Unit = { refreshCurrent(forceRefresh = true) }
    ) {
        showState(
            title = getString(R.string.browse_state_empty_title),
            message = message,
            actionLabel = actionLabel,
            onAction = onAction
        )
    }

    private fun showLoading() {
        loadingIndicatorJob?.cancel()
        // No secondary loading card: SwipeRefreshLayout is the single loading indicator and the
        // previous page stays visible until the replacement data is ready.
        hideState()
        if (displayedTab != currentTab() || !isTopLevelTabActive(currentTab())) clearResults()
        // If content is already on screen, refresh silently (no spinner) so the view stays
        // smooth; only show the spinner when the screen is actually empty.
        if (browseRows.isEmpty()) {
            swipeRefresh.isRefreshing = true
        }
    }

    private fun showError(message: String) {
        if (browseRows.isNotEmpty() && displayedTab == currentTab()) {
            swipeRefresh.isRefreshing = false
            com.google.android.material.snackbar.Snackbar.make(resultsScroll,
                message.ifBlank { getString(R.string.browse_error) }, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction(R.string.browse_retry) { refreshCurrent(forceRefresh = true) }.show()
            return
        }
        clearResults()
        showState(
            title = getString(R.string.browse_state_error_title),
            message = message.ifBlank { getString(R.string.browse_error) },
            actionLabel = getString(R.string.browse_retry)
        ) { refreshCurrent(forceRefresh = true) }
        swipeRefresh.isRefreshing = false
    }

    private fun showCastFailedToast() {
        Toast.makeText(this, getString(R.string.browse_cast_unavailable), Toast.LENGTH_LONG).show()
    }

    private fun showLoginChooser() {
        startActivity(Intent(this, TvLoginActivity::class.java))
    }

    private fun showNotLoggedIn() {
        clearResults()
        showState(
            title = getString(R.string.browse_link_account),
            message = getString(R.string.browse_not_logged_in),
            actionLabel = getString(R.string.browse_sign_in_action),
            onAction = ::showLoginChooser
        )
        swipeRefresh.isRefreshing = false
    }

    private fun showVideoList(
        descriptions: List<MediaDescriptionCompat>,
        paging: BrowseOutcome.Ok<*>? = null,
        append: Boolean = false
    ) {
        val existing = if (append) browseRows.filterIsInstance<PhoneBrowseRow.Video>().map { it.description } else emptyList()
        val videos = if (append && existing.isNotEmpty())
            StableListMerge.appendNew(existing, descriptions) { it.mediaId.orEmpty() }
        else descriptions
        if (append) {
            prefetchedTabs[BrowseTab.HOME] = videos
            renderedPages.remove(BrowseTab.HOME)
        }
        val reset = !append && displayedTab != currentTab()
        browseRows.clear()
        displayedVideos = videos
        if (videos.isEmpty()) {
            submitBrowseRows()
            showEmptyState()
            return
        }
        hideState()
        browseRows += videos.map { PhoneBrowseRow.Video(it) }
        if (paging != null && (paging.hasMore || paging.partial)) {
            browseRows += PhoneBrowseRow.Action("home:more",
                getString(if (paging.partial) R.string.browse_feed_retry else R.string.browse_feed_more)) {
                // A failed page keeps its token, so retrying is just loading more; only a
                // failure with nothing left to continue needs a full reload (as on Abos).
                if (paging.partial && !paging.hasMore) loadHome(forceRefresh = true) else loadHome(loadMore = true)
            }
        }
        displayedTab = currentTab().takeIf(::isTopLevelTabActive)
        submitBrowseRows(resetScroll = reset)
        swipeRefresh.isRefreshing = false
    }

    private fun bindVideoRow(
        row: View,
        video: MediaDescriptionCompat,
        queue: List<MediaDescriptionCompat>,
        inPlaylist: Boolean = false,
        preview: Boolean = false
    ) {
        row.findViewById<TextView>(R.id.video_title).text = video.title
        row.findViewById<TextView>(R.id.video_channel).apply {
            val line = video.subtitleWithAge()
            text = line
            visibility = if (line.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val avatar = row.findViewById<ImageView>(R.id.video_channel_avatar)
        if (preview) { avatar.clearThumbnailBinding(); avatar.setImageResource(R.drawable.ic_person) }
        else bindVideoChannelAvatar(avatar, video)

        val thumbnail = row.findViewById<ImageView>(R.id.video_thumbnail)
        // Decode size is a stable bucket, not the view's measured width: the first bind happens
        // before layout (width 0) and would miss the memory cache. Cards share one bucket per
        // column; the compact list thumb is 128dp.
        val viewportWidth = resultsScroll.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val cards = settings.isPhoneBrowseCardsEnabled()
        val size = if (cards)
            (viewportWidth / phoneBrowseColumns.coerceAtLeast(1)).coerceAtMost(1280)
        else (128 * resources.displayMetrics.density).toInt().coerceIn(256, 512)
        if (preview) {
            thumbnail.clearThumbnailBinding()
            thumbnail.setImageBitmap(ThumbnailImageLoader.peek(video.iconUri?.toString(), settings.getThumbnailQuality(), size))
        } else thumbnail.bindThumbnail(lifecycleScope, video.iconUri?.toString(), settings.getThumbnailQuality(), size)

        row.findViewById<View>(R.id.video_more_button).setOnClickListener {
            showVideoActions(video, displayedVideos.ifEmpty { queue }, inPlaylist)
        }

        // Like the YouTube feed: the card itself is the primary playback action.
        row.setOnClickListener { openVideoDefault(video, displayedVideos.ifEmpty { queue }) }
    }

    /**
     * Primary card action must react immediately. Only route to Android Auto when its projection
     * receiver is already registered; otherwise open the phone player without waiting for a car
     * connection/projection handshake. Explicit "Auto" actions may still request projection.
     */

    private fun bindVideoChannelAvatar(image: ImageView, video: MediaDescriptionCompat) {
        val known = findKnownSubscriptionChannel(video.subtitle?.toString()?.trim().orEmpty())
        val id = video.channelId() ?: known?.channelId
        image.bindThumbnail(lifecycleScope, known?.thumbnailUrl, settings.getThumbnailQuality(), dp(40),
            placeholder = R.drawable.ic_person,
            resolveUrl = if (id != null) ({ browseFacade.channelAvatar(id) }) else null)
    }

    private fun findKnownSubscriptionChannel(title: String): HybridChannel? {
        if (title.isBlank()) return null
        return prefetchedSubscriptionChannels.values
            .asSequence()
            .flatten()
            .firstOrNull { it.title.equals(title, ignoreCase = true) }
    }

    private fun resolvePlaybackSelection(
        video: MediaDescriptionCompat,
        queue: List<MediaDescriptionCompat>
    ): Pair<MediaDescriptionCompat, List<MediaDescriptionCompat>> {
        if (screen == Screen.Subscriptions) {
            val current = subscriptionFeedOutcome?.data.orEmpty()
            val selected = current.firstOrNull { it.mediaId == video.mediaId }
            if (selected != null) return selected to current
        }
        if (screen != Screen.History && !WatchHistoryStore.isHistoryDescription(video)) {
            return video to queue
        }
        val freshQueue = WatchHistoryStore(this).toMediaDescriptions()
        val freshVideo = freshQueue.firstOrNull { it.mediaId == video.mediaId } ?: video
        return freshVideo to freshQueue.ifEmpty { queue }
    }

    private fun openVideoDefault(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) {
        val (playVideo, playQueue) = resolvePlaybackSelection(video, queue)
        val routeToCar = RemoteCastBus.isAvailable() || CarPlaybackState.isActive()
        if (!routeToCar) {
            openVideoOnPhone(playVideo, playQueue)
            return
        }

        when (
            RemoteCastBus.cast(
                video = playVideo,
                queue = playQueue,
                context = this,
                startPositionMs = WatchHistoryStore.resumePositionFromDescription(playVideo),
                onProjectionFailed = {
                    // A stale native-AA marker can survive briefly after disconnect. If opening
                    // the projection fails, clear it and fall back to the handset instead of
                    // swallowing the user's tap.
                    CarPlaybackState.clearAll()
                    if (!isFinishing && !isDestroyed) {
                        runOnUiThread { openVideoOnPhone(playVideo, playQueue) }
                    }
                }
            )
        ) {
            RemoteCastBus.CastResult.Queued -> Unit
            RemoteCastBus.CastResult.ParkBlocked ->
                Toast.makeText(this, getString(R.string.browse_cast_park_blocked), Toast.LENGTH_LONG).show()
            RemoteCastBus.CastResult.Rejected ->
                Toast.makeText(this, getString(R.string.browse_cast_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    private fun openVideoOnPhone(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) {
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_VIDEO, video)
                .putParcelableArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE, ArrayList(queue))
        )
    }

    private fun castVideo(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>) {
        val (playVideo, playQueue) = resolvePlaybackSelection(video, queue)
        val message = when (
            RemoteCastBus.cast(
                video = playVideo,
                queue = playQueue,
                context = this,
                startPositionMs = WatchHistoryStore.resumePositionFromDescription(playVideo),
                onProjectionFailed = ::showCastFailedToast
            )
        ) {
            RemoteCastBus.CastResult.Queued -> R.string.browse_cast_queued
            RemoteCastBus.CastResult.ParkBlocked -> R.string.browse_cast_park_blocked
            RemoteCastBus.CastResult.Rejected -> R.string.browse_cast_unavailable
        }
        Toast.makeText(this, getString(message), Toast.LENGTH_LONG).show()
    }

    private fun showVideoActions(
        video: MediaDescriptionCompat,
        queue: List<MediaDescriptionCompat>,
        inPlaylist: Boolean
    ) {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.bottom_sheet_video_actions)
        val sheet = requireNotNull(dialog.findViewById<View>(R.id.video_actions_sheet))

        sheet.findViewById<TextView>(R.id.action_sheet_title).text = video.title
        sheet.findViewById<TextView>(R.id.action_sheet_subtitle).apply {
            text = video.subtitle
            visibility = if (video.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val items = sheet.findViewById<LinearLayout>(R.id.action_sheet_items)
        fun addAction(labelRes: Int, iconRes: Int, action: () -> Unit) {
            val item = layoutInflater.inflate(R.layout.item_video_action, items, false)
            item.findViewById<TextView>(R.id.action_label).apply {
                setText(labelRes)
                setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
            }
            item.setOnClickListener {
                dialog.dismiss()
                action()
            }
            items.addView(item)
        }

        addAction(R.string.browse_menu_watch_phone, R.drawable.ic_phone) {
            val (selected, currentQueue) = resolvePlaybackSelection(video, queue)
            openVideoOnPhone(selected, currentQueue)
        }
        addAction(R.string.browse_menu_cast_car, R.drawable.ic_cast) {
            castVideo(video, queue)
        }
        addAction(
            if (inPlaylist) R.string.browse_menu_remove_playlist else R.string.browse_menu_add_playlist,
            if (inPlaylist) R.drawable.ic_playlist_remove else R.drawable.ic_playlist_add
        ) {
            toggleVideoInPlaylist(video, inPlaylist)
        }
        addAction(R.string.browse_menu_share, R.drawable.ic_share) {
            shareVideo(video)
        }
        addAction(R.string.browse_menu_copy_link, R.drawable.ic_content_copy) {
            copyVideoLink(video)
        }
        addAction(R.string.browse_menu_open_browser, R.drawable.ic_open_browser) {
            openVideoInBrowser(video)
        }

        dialog.show()
    }

    private fun toggleVideoInPlaylist(video: MediaDescriptionCompat, inPlaylist: Boolean) {
        val id = video.mediaId ?: return
        val store = UserPlaylistStore(this)
        if (inPlaylist) {
            store.remove(id)
            Toast.makeText(this, getString(R.string.browse_playlist_removed), Toast.LENGTH_SHORT).show()
            loadPlaylist()
        } else {
            val added = store.addFromDescription(video)
            Toast.makeText(
                this,
                getString(if (added) R.string.browse_playlist_added else R.string.browse_playlist_already),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun videoUrl(video: MediaDescriptionCompat): String? =
        video.mediaId?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }

    private fun shareVideo(video: MediaDescriptionCompat) {
        val url = videoUrl(video) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.browse_menu_share)))
    }

    private fun copyVideoLink(video: MediaDescriptionCompat) {
        val url = videoUrl(video) ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube", url))
        Toast.makeText(this, getString(R.string.browse_link_copied), Toast.LENGTH_SHORT).show()
    }

    private fun openVideoInBrowser(video: MediaDescriptionCompat) {
        val url = videoUrl(video) ?: return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private sealed class Screen {
        data object Home : Screen()
        data object Subscriptions : Screen()
        data class ChannelVideos(val channelId: String) : Screen()
        data object History : Screen()
        data object Popular : Screen()
        data object Shorts : Screen()
        data object Search : Screen()
        data object Playlist : Screen()
    }

    companion object {
        private const val VIDEO_ROW_BATCH_SIZE = 6
        private const val SUBSCRIPTION_VISIBLE_TTL_MS = 60_000L
        private const val PHONE_CARD_MIN_WIDTH_DP = 180f
        private const val MAX_BROWSE_COLUMNS = 4

        const val EXTRA_START_SCREEN = "start_screen"
        const val START_HOME = "home"
        const val START_SUBSCRIPTIONS = "subscriptions"
        const val START_SUBSCRIPTION_FEED = "subscription_feed"
        const val START_HISTORY = "history"
        const val START_POPULAR = "popular"
        const val START_SHORTS = "shorts"
        const val START_PLAYLIST = "playlist"

        const val START_SEARCH = "search"

        private const val STATE_SCREEN = "browse_state_screen"
        private const val STATE_CHANNEL = "channel"
        private const val STATE_CHANNEL_ID = "browse_state_channel_id"
        private const val STATE_CHANNEL_TITLE = "browse_state_channel_title"
        private const val STATE_SEARCH_QUERY = "browse_state_search_query"
    }
}
