@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaDescriptionCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.uni.youtubeaa.car.hybrid.BrowseFacade
import de.uni.youtubeaa.car.hybrid.BrowseOutcome
import de.uni.youtubeaa.car.hybrid.FeedChip
import de.uni.youtubeaa.car.hybrid.FeedStore
import de.uni.youtubeaa.car.hybrid.HybridChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RowKind {
    SECTION,
    CHANNELS,
    CHANNEL,
    VIDEO,
    ACTION
}

private data class BrowseRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val kind: RowKind,
    val actionLabel: String? = null,
    val showAction: Boolean = false,
    val channels: List<HybridChannel> = emptyList()
)

/**
 * Projection browse UI: Startseite, Abos → Kanäle → Videos,
 * plus Verlauf / Beliebt. Selecting a video asks [VideoHost] to play.
 */
class BrowseCarFragment : Fragment() {

    private var titleViewRef: TextView? = null
    private var statusViewRef: TextView? = null
    private var statusRowRef: View? = null
    private var progressViewRef: ProgressBar? = null
    private var backButtonRef: View? = null
    private var adapterRef: BrowseAdapter? = null
    private var listViewRef: RecyclerView? = null
    private var gridLayoutManager: GridLayoutManager? = null
    private var layoutToggleRef: ImageButton? = null
    private lateinit var layoutSettings: SettingsRepository
    private val titleView get() = requireNotNull(titleViewRef)
    private val statusView get() = requireNotNull(statusViewRef)
    private val statusRow get() = requireNotNull(statusRowRef)
    private val progressView get() = requireNotNull(progressViewRef)
    private val backButton get() = requireNotNull(backButtonRef)
    private val adapter get() = requireNotNull(adapterRef)
    private val listView get() = requireNotNull(listViewRef)
    private lateinit var playlistStore: UserPlaylistStore
    private lateinit var browseFacade: BrowseFacade
    private var pageLoadJob: Job? = null
    private var channelLoadJob: Job? = null
    private var repairBatchJob: Job? = null
    private val pendingRepairs = LinkedHashMap<String, MediaDescriptionCompat>()
    // Browse state lives beside the fragment: VideoCarActivity creates a new BrowseCarFragment
    // after every playback, which used to drop all pages and reload every list from scratch.
    // Startseite and Abos themselves live in the process-wide FeedStore (phone rules, shared
    // with the phone and the Android Auto browser); only the other tabs are cached here.
    private lateinit var feedStore: FeedStore<MediaDescriptionCompat>
    private var lastAccountKey: String?
        get() = shared.lastAccountKey
        set(value) { shared.lastAccountKey = value }

    private fun accountKey(): String? = browseFacade.sessionStore().getTokens()?.refreshToken

    private fun applyBrowseLayout(preserveScroll: Boolean = true) {
        val recycler = listViewRef ?: return
        val grid = gridLayoutManager ?: return
        val listAdapter = adapterRef ?: return
        if (!::layoutSettings.isInitialized) return

        val cards = layoutSettings.isCarBrowseCardsEnabled()
        val density = resources.displayMetrics.density
        val widthPx = recycler.width.takeIf { it > 0 }
            ?: (resources.configuration.screenWidthDp * density).toInt()
        val widthDp = widthPx / density
        val columns = if (cards)
            (widthDp / CAR_CARD_MIN_WIDTH_DP).toInt().coerceIn(1, MAX_BROWSE_COLUMNS)
        else 1

        val modeChanged = listAdapter.videoCards != cards
        val columnsChanged = grid.spanCount != columns
        val position = if (preserveScroll && (modeChanged || columnsChanged))
            grid.findFirstVisibleItemPosition().coerceAtLeast(0) else -1
        val offset = if (position >= 0) grid.findViewByPosition(position)?.top ?: 0 else 0

        listAdapter.videoImageTargetPx =
            (widthPx / columns.coerceAtLeast(1) - (12 * density).toInt()).coerceAtLeast(136)
        if (columnsChanged) grid.spanCount = columns
        if (modeChanged) listAdapter.setVideoCards(cards)

        layoutToggleRef?.apply {
            setImageResource(if (cards) R.drawable.ic_car_view_list else R.drawable.ic_car_view_grid)
            contentDescription = getString(if (cards) R.string.browse_show_list else R.string.browse_show_cards)
        }
        if (position >= 0) recycler.post {
            if (listViewRef != null) grid.scrollToPositionWithOffset(position, offset)
        }
    }

    private var displayedScreen: Screen? = null
    private var screen: Screen = Screen.Root
    private var currentQueue: List<MediaDescriptionCompat> = emptyList()

    private data class CachedPage(
        val rows: List<BrowseRow>,
        val queue: List<MediaDescriptionCompat>,
        val loadedAtMs: Long,
        /** Subscriptions filter chip the page was loaded with; pages of different filters never merge. */
        val filter: String? = null
    )

    private class SharedState {
        val pageCache = mutableMapOf<BrowseTab, CachedPage>()
        val scrollPositions = mutableMapOf<BrowseTab, Pair<Int, Int>>()
        var lastAccountKey: String? = null
        var lastTab: BrowseTab? = null
    }

    /** Selected subscriptions filter chip; null means the unfiltered feed. */
    private val selectedChip: FeedChip? get() = feedStore.subscriptions.value.chip
    /** What the chip row currently shows; rebuilding identical buttons on every call was waste. */
    private var renderedChipKey: Pair<List<FeedChip>, String?>? = null
    private val pageCache get() = shared.pageCache
    private val scrollPositions get() = shared.scrollPositions

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_browse_car, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        CarUi.bind(view)
        titleViewRef = view.findViewById(R.id.browse_title)
        statusViewRef = view.findViewById(R.id.browse_status)
        statusRowRef = view.findViewById(R.id.browse_status_row)
        progressViewRef = view.findViewById(R.id.browse_progress)
        backButtonRef = view.findViewById(R.id.browse_back)
        playlistStore = UserPlaylistStore(requireContext())
        browseFacade = BrowseFacade(requireContext())
        feedStore = browseFacade.feedStore()
        layoutSettings = SettingsRepository(requireContext())
        val currentAccount = accountKey()
        if (currentAccount != lastAccountKey) {
            pageCache.clear(); scrollPositions.clear()
        }
        lastAccountKey = currentAccount
        val listAdapter = BrowseAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onClick = { onRowClicked(it) },
            onAction = { onRowAction(it) },
            repair = { browseFacade.repairMetadataIfNeeded(it) },
            onRepaired = ::applyRepairedVideo
        )
        adapterRef = listAdapter

        listViewRef = view.findViewById(R.id.browse_list)
        val grid = GridLayoutManager(requireContext(), 1)
        gridLayoutManager = grid
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (listAdapter.currentList.getOrNull(position)?.kind == RowKind.VIDEO) 1 else grid.spanCount
        }
        listView.layoutManager = grid
        listView.itemAnimator = null
        listView.adapter = listAdapter
        layoutToggleRef = view.findViewById(R.id.browse_layout_toggle)
        layoutToggleRef?.setOnClickListener {
            layoutSettings.setCarBrowseCardsEnabled(!layoutSettings.isCarBrowseCardsEnabled())
            applyBrowseLayout(preserveScroll = true)
        }
        listView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyBrowseLayout(preserveScroll = true)
        }
        listView.post { applyBrowseLayout(preserveScroll = false) }
        HorizontalSwipeDetector.attach(
            listView,
            onSwipeLeft = { switchTab(+1) },
            onSwipeRight = { switchTab(-1) }
        )
        backButton.setOnClickListener { navigateBack() }
        view.findViewById<ImageButton>(R.id.browse_search_button).setOnClickListener {
            videoHost()?.showSearch()
        }
        view.findViewById<ImageButton>(R.id.browse_settings_button).setOnClickListener {
            videoHost()?.showSettings()
        }
        view.findViewById<ImageButton>(R.id.browse_refresh_button).setOnClickListener { refreshCurrentScreen() }
        view.findViewById<Button>(R.id.car_tab_home).setOnClickListener { selectTopTab(BrowseTab.HOME) }
        view.findViewById<Button>(R.id.car_tab_subscriptions).setOnClickListener { selectTopTab(BrowseTab.SUBSCRIPTIONS) }
        view.findViewById<Button>(R.id.car_tab_shorts).setOnClickListener { selectTopTab(BrowseTab.SHORTS) }
        view.findViewById<Button>(R.id.car_tab_popular).setOnClickListener { selectTopTab(BrowseTab.POPULAR) }
        view.findViewById<Button>(R.id.car_tab_history).setOnClickListener { selectTopTab(BrowseTab.HISTORY) }
        view.findViewById<Button>(R.id.car_tab_playlist).setOnClickListener { selectTopTab(BrowseTab.PLAYLIST) }
        // Silent refreshes and paging (here, on the phone or in the AA browser) update the open list.
        viewLifecycleOwner.lifecycleScope.launch {
            feedStore.home.collect { if (screen == Screen.Home) renderHome(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            feedStore.subscriptions.collect { if (screen == Screen.Subscriptions) renderSubscriptions(it) }
        }
        showRoot()
    }

    override fun onResume() {
        super.onResume()
        applyBrowseLayout(preserveScroll = true)
        val currentAccount = accountKey()
        val tab = currentTab() ?: BrowseTab.HOME
        if (currentAccount != lastAccountKey) {
            lastAccountKey = currentAccount
            pageLoadJob?.cancel()
            pageCache.clear()
            scrollPositions.clear()
            channelLoadJob?.cancel()
            selectTopTab(tab, forceRefresh = true)
        } else if (tab == BrowseTab.SUBSCRIPTIONS && pageLoadJob?.isActive != true) {
            // The phone's silent refresh on return; the store checks the age itself.
            viewLifecycleOwner.lifecycleScope.launch { feedStore.revalidateSubscriptions() }
        }
    }

    override fun onDestroyView() {
        // Opening a video replaces this fragment; the next instance restores this position.
        // Channel and search screens map onto a tab but are not that tab's list.
        if (listViewRef != null && screen !is Screen.ChannelVideos && screen !is Screen.Search) {
            saveCurrentScrollPosition()
        }
        pageLoadJob?.cancel()
        channelLoadJob?.cancel()
        repairBatchJob?.cancel()
        pendingRepairs.clear()
        pageLoadJob = null
        listViewRef?.adapter = null
        adapterRef = null
        gridLayoutManager = null
        layoutToggleRef = null
        listViewRef = null
        titleViewRef = null
        statusViewRef = null
        statusRowRef = null
        progressViewRef = null
        backButtonRef = null
        super.onDestroyView()
    }

    private fun currentTab(): BrowseTab? = when (screen) {
        Screen.Root -> null
        is Screen.Home, is Screen.Search -> BrowseTab.HOME
        is Screen.Subscriptions, is Screen.ChannelVideos -> BrowseTab.SUBSCRIPTIONS
        is Screen.History -> BrowseTab.HISTORY
        is Screen.Popular -> BrowseTab.POPULAR
        is Screen.Shorts -> BrowseTab.SHORTS
        is Screen.Playlist -> BrowseTab.PLAYLIST
    }

    private fun switchTab(delta: Int) {
        val current = currentTab() ?: BrowseTab.HOME
        selectTopTab(current.adjacent(delta))
    }

    private fun showRoot() {
        // The projected app uses the same top-level tabs as the phone instead of a second
        // launcher-style root list. The configured default tab only chooses the initial tab.
        // Coming back from playback returns to the tab (and scroll position) the user left.
        val tab = shared.lastTab ?: when (SettingsRepository(requireContext()).getDefaultTab()) {
            "subscriptions", "subscription_feed" -> BrowseTab.SUBSCRIPTIONS
            "popular" -> BrowseTab.POPULAR
            "history" -> BrowseTab.HISTORY
            "playlist" -> BrowseTab.PLAYLIST
            else -> BrowseTab.HOME
        }
        selectTopTab(tab)
    }

    private fun selectTopTab(tab: BrowseTab, forceRefresh: Boolean = false) {
        saveCurrentScrollPosition()
        pageLoadJob?.cancel()
        val normalized = if (tab == BrowseTab.SUBSCRIPTION_FEED) BrowseTab.SUBSCRIPTIONS else tab
        shared.lastTab = normalized
        val cached = pageCache[normalized]?.takeIf { normalized != BrowseTab.HOME && normalized != BrowseTab.SUBSCRIPTIONS }
        if (!forceRefresh && cached != null) {
            renderCachedPage(normalized, cached)
            // Home is the snapshot already on screen. A stale reload merges YouTube's next
            // recommendation set in and trims what disappeared, so rows jump while the user reads.
            // The refresh button drops the page first and is the only replace path.
            if (normalized != BrowseTab.HOME && isStale(normalized, cached)) refreshTopTab(normalized)
            return
        }
        when (normalized) {
            BrowseTab.HOME -> loadHome(forceRefresh)
            BrowseTab.SUBSCRIPTIONS -> loadSubscriptions(forceRefresh)
            BrowseTab.POPULAR -> loadPopular(forceRefresh)
            BrowseTab.SHORTS -> loadShorts(forceRefresh)
            BrowseTab.HISTORY -> loadHistory()
            BrowseTab.PLAYLIST -> showPlaylist()
            BrowseTab.SUBSCRIPTION_FEED -> Unit
        }
    }


    /**
     * Reload the visible screen from the network, bypassing the browse caches. Covers the top
     * tabs as well as the channel and search screens reached from them.
     */
    private fun refreshCurrentScreen() {
        when (val current = screen) {
            is Screen.ChannelVideos ->
                loadChannelVideos(current.channelId, titleView.text.toString(), forceRefresh = true)
            is Screen.Search -> videoHost()?.refreshSearch(current.query)
            else -> {
                val tab = currentTab() ?: BrowseTab.HOME
                // Drop the rendered page too, otherwise selectTopTab would restore it unchanged.
                pageCache.remove(tab)
                refreshTopTab(tab)
            }
        }
    }

    private fun refreshTopTab(tab: BrowseTab) {
        when (tab) {
            BrowseTab.HOME -> loadHome(forceRefresh = true)
            // The 60 s stale refresh and the refresh button reload what is shown, so an active
            // filter chip stays applied instead of silently falling back to the unfiltered feed.
            BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> loadSubscriptions(forceRefresh = true)
            BrowseTab.POPULAR -> loadPopular(forceRefresh = true)
            BrowseTab.SHORTS -> loadShorts(forceRefresh = true)
            BrowseTab.HISTORY -> loadHistory()
            BrowseTab.PLAYLIST -> showPlaylist()
        }
    }

    private fun isStale(tab: BrowseTab, page: CachedPage): Boolean {
        val ttl = if (tab == BrowseTab.SUBSCRIPTIONS || tab == BrowseTab.SHORTS) 60_000L else 5 * 60_000L
        return SystemClock.elapsedRealtime() - page.loadedAtMs > ttl
    }

    private fun saveCurrentScrollPosition() {
        val tab = currentTab() ?: return
        val lm = listView.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos < 0) return
        val top = lm.findViewByPosition(pos)?.top ?: 0
        scrollPositions[tab] = pos to top
    }

    private fun renderCachedPage(tab: BrowseTab, page: CachedPage, restoreScroll: Boolean = true) {
        screen = when (tab) {
            BrowseTab.HOME -> Screen.Home
            BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> Screen.Subscriptions
            BrowseTab.POPULAR -> Screen.Popular
            BrowseTab.SHORTS -> Screen.Shorts
            BrowseTab.HISTORY -> Screen.History
            BrowseTab.PLAYLIST -> Screen.Playlist
        }
        backButton.visibility = View.GONE
        titleView.setText(
            when (tab) {
                BrowseTab.HOME -> R.string.browse_home
                BrowseTab.SUBSCRIPTIONS, BrowseTab.SUBSCRIPTION_FEED -> R.string.browse_subscriptions
                BrowseTab.POPULAR -> R.string.browse_search_popular
                BrowseTab.SHORTS -> R.string.browse_shorts
                BrowseTab.HISTORY -> R.string.browse_history
                BrowseTab.PLAYLIST -> R.string.browse_my_playlist
            }
        )
        currentQueue = page.queue
        updateTabSelection()
        if (page.rows.isEmpty() && (tab == BrowseTab.PLAYLIST || tab == BrowseTab.SHORTS)) {
            statusRow.visibility = View.VISIBLE
            progressView.visibility = View.GONE
            statusView.setText(if (tab == BrowseTab.SHORTS) R.string.browse_shorts_empty else R.string.browse_playlist_empty)
            adapter.submit(emptyList())
        } else {
            showRows(page.rows)
        }
        scrollPositions[tab]?.takeIf { restoreScroll }?.let { (position, offset) ->
            listView.post {
                (listView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, offset)
            }
        }
    }

    private fun cacheAndRender(
        tab: BrowseTab,
        rows: List<BrowseRow>,
        queue: List<MediaDescriptionCompat>,
        fetchedAtMs: Long? = null,
        filter: String? = null
    ) {
        val age = fetchedAtMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
        // Background reloads (stale refresh, paging, channel strip) merge into the page already
        // shown instead of replacing it, so rows never reshuffle under the user's finger. The
        // refresh button drops the page first and therefore still replaces it. History and the
        // playlist are local lists whose order itself is the content.
        val previous = pageCache[tab]?.takeIf {
            tab != BrowseTab.HISTORY && tab != BrowseTab.PLAYLIST && it.filter == filter
        }
        val loadedAtMs = SystemClock.elapsedRealtime() - age
        val page = when {
            previous == null -> CachedPage(rows, queue, loadedAtMs, filter)
            // Home paging only appends. Merging a new recommendation page inserts and deletes
            // rows the user is already looking at.
            tab == BrowseTab.HOME -> appendHomePage(previous, rows, queue, loadedAtMs, filter)
            else -> mergePage(previous, rows, queue, loadedAtMs, filter)
        }
        pageCache[tab] = page
        // An async fetch may finish after the user has already swiped elsewhere. Cache the
        // result, but never steal the visible tab back from the user.
        if (currentTab() == tab || (tab == BrowseTab.SUBSCRIPTIONS && currentTab() == BrowseTab.SUBSCRIPTION_FEED)) {
            renderCachedPage(tab, page, restoreScroll = false)
        }
    }

    private fun appendHomePage(
        previous: CachedPage,
        rows: List<BrowseRow>,
        queue: List<MediaDescriptionCompat>,
        loadedAtMs: Long,
        filter: String?
    ): CachedPage {
        val kept = previous.rows.filter { it.kind == RowKind.VIDEO }
        val videos = StableListMerge.appendNew(kept, rows.filter { it.kind == RowKind.VIDEO }) { it.id }
        val trailer = rows.filter { it.kind != RowKind.VIDEO }
        val descriptions = (previous.queue + queue).associateBy { it.mediaId }
        val mergedQueue = videos.mapNotNull { descriptions[it.id.removePrefix(VIDEO_PREFIX)] }
        return CachedPage(videos + trailer, mergedQueue, loadedAtMs, filter)
    }

    private fun mergePage(
        previous: CachedPage,
        rows: List<BrowseRow>,
        queue: List<MediaDescriptionCompat>,
        loadedAtMs: Long,
        filter: String?
    ): CachedPage {
        // Non-video rows (channel strip on top, "more"/retry actions below) come from the fresh
        // result; only the video rows are merged.
        val header = rows.takeWhile { it.kind != RowKind.VIDEO }
        val trailer = rows.drop(header.size).filter { it.kind != RowKind.VIDEO }
        val freshVideos = rows.filter { it.kind == RowKind.VIDEO }
        val videos = StableListMerge.trimVanished(
            StableListMerge.merge(previous.rows.filter { it.kind == RowKind.VIDEO }, freshVideos) { it.id },
            freshVideos,
            maxOf(MAX_MERGED_VIDEO_ROWS, freshVideos.size)
        ) { it.id }
        val descriptions = (previous.queue + queue).associateBy { it.mediaId }
        val mergedQueue = videos.mapNotNull { descriptions[it.id.removePrefix(VIDEO_PREFIX)] }
        return CachedPage(header + videos + trailer, mergedQueue, loadedAtMs, filter)
    }

    private fun updateTabSelection() {
        if (!isAdded || view == null) return
        // renderChips() hides the row itself on every screen but Abos, so this covers both.
        renderChips()
        val active = currentTab() ?: BrowseTab.HOME
        requireView().findViewById<View>(R.id.car_brand).visibility = if (backButton.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        listOf(
            R.id.car_tab_home to BrowseTab.HOME,
            R.id.car_tab_subscriptions to BrowseTab.SUBSCRIPTIONS,
            R.id.car_tab_shorts to BrowseTab.SHORTS,
            R.id.car_tab_popular to BrowseTab.POPULAR,
            R.id.car_tab_history to BrowseTab.HISTORY,
            R.id.car_tab_playlist to BrowseTab.PLAYLIST
        ).forEach { (id, tab) ->
            val button = requireView().findViewById<Button>(id)
            val selected = active == tab || (active == BrowseTab.SUBSCRIPTION_FEED && tab == BrowseTab.SUBSCRIPTIONS)
            button.isSelected = selected
            button.setBackgroundResource(if (selected) R.drawable.bg_car_chip_selected else R.drawable.bg_car_chip)
            button.setTextColor(requireContext().getColor(if (selected) R.color.car_surface else R.color.car_on_surface))
        }
    }

    private fun onRowClicked(row: BrowseRow) {
        when (row.kind) {
            RowKind.CHANNELS -> Unit
            RowKind.SECTION -> when (row.id) {
                ID_HOME -> selectTopTab(BrowseTab.HOME)
                ID_SUBSCRIPTIONS -> selectTopTab(BrowseTab.SUBSCRIPTIONS)
                ID_PLAYLIST -> selectTopTab(BrowseTab.PLAYLIST)
                ID_HISTORY -> selectTopTab(BrowseTab.HISTORY)
                ID_POPULAR -> selectTopTab(BrowseTab.POPULAR)
                ID_SETTINGS -> videoHost()?.showSettings()
                ID_PLAYLIST_PLAY_ALL -> playEntirePlaylist()
                ID_PLAYLIST_CLEAR -> {
                    playlistStore.clear()
                    showPlaylist()
                }
            }
            RowKind.CHANNEL -> loadChannelVideos(row.id.removePrefix(CHANNEL_PREFIX), row.title)
            RowKind.VIDEO -> {
                val videoId = row.id.removePrefix(VIDEO_PREFIX)
                val queue = when (screen) {
                    is Screen.Playlist -> playlistStore.toMediaDescriptions()
                    is Screen.History -> WatchHistoryStore(requireContext()).toMediaDescriptions()
                    else -> currentQueue
                }.ifEmpty {
                    listOf(
                        MediaDescriptionCompat.Builder()
                            .setMediaId(videoId)
                            .setTitle(row.title)
                            .setSubtitle(row.subtitle)
                            .setIconUri(row.imageUrl?.let { android.net.Uri.parse(it) })
                            .build()
                    )
                }
                val current = queue.firstOrNull { it.mediaId == videoId } ?: queue.first()
                videoHost()?.playVideo(current, queue)
            }
            RowKind.ACTION -> when (row.id) {
                ID_PLAYLIST_PLAY_ALL -> playEntirePlaylist()
                ID_PLAYLIST_CLEAR -> {
                    playlistStore.clear()
                    showPlaylist()
                }
                ID_SUBSCRIPTION_MORE -> loadSubscriptions(loadMore = true)
                ID_HOME_MORE -> loadHome(loadMore = true)
                ID_SUBSCRIPTION_RETRY -> refreshTopTab(BrowseTab.SUBSCRIPTIONS)
                ID_RETRY -> reloadCurrentScreen()
            }
        }
    }

    private fun onRowAction(row: BrowseRow) {
        when (row.kind) {
            RowKind.VIDEO -> {
                if (screen is Screen.Playlist) {
                    playlistStore.remove(row.id.removePrefix(VIDEO_PREFIX))
                    showPlaylist()
                } else {
                    val videoId = row.id.removePrefix(VIDEO_PREFIX)
                    val desc = currentQueue.firstOrNull { it.mediaId == videoId }
                        ?: MediaDescriptionCompat.Builder()
                            .setMediaId(videoId)
                            .setTitle(row.title)
                            .setSubtitle(row.subtitle)
                            .setIconUri(row.imageUrl?.let { android.net.Uri.parse(it) })
                            .build()
                    playlistStore.addFromDescription(desc)
                    statusRow.visibility = View.VISIBLE
                    progressView.visibility = View.GONE
                    statusView.setText(R.string.browse_playlist_added)
                }
            }
            else -> Unit
        }
    }

    private fun showPlaylist() {
        pageLoadJob?.cancel()
        screen = Screen.Playlist
        backButton.visibility = View.GONE
        titleView.setText(R.string.browse_my_playlist)
        updateTabSelection()
        val entries = playlistStore.getAll()
        if (entries.isEmpty()) {
            statusRow.visibility = View.VISIBLE
            progressView.visibility = View.GONE
            statusView.setText(R.string.browse_playlist_empty)
            cacheAndRender(BrowseTab.PLAYLIST, emptyList(), emptyList())
            return
        }
        statusRow.visibility = View.GONE
        currentQueue = playlistStore.toMediaDescriptions()
        val rows = mutableListOf(
            BrowseRow(ID_PLAYLIST_PLAY_ALL, getString(R.string.browse_playlist_play_all), kind = RowKind.ACTION),
            BrowseRow(ID_PLAYLIST_CLEAR, getString(R.string.browse_playlist_clear), kind = RowKind.ACTION)
        )
        entries.forEach { entry ->
            rows.add(
                BrowseRow(
                    id = VIDEO_PREFIX + entry.videoId,
                    title = entry.title,
                    subtitle = entry.channelTitle,
                    imageUrl = entry.thumbnailUrl,
                    kind = RowKind.VIDEO,
                    actionLabel = "−",
                    showAction = true
                )
            )
        }
        cacheAndRender(BrowseTab.PLAYLIST, rows, currentQueue)
    }

    private fun playEntirePlaylist() {
        val queue = playlistStore.toMediaDescriptions()
        val first = queue.firstOrNull() ?: return
        videoHost()?.playVideo(first, queue)
    }

    private fun navigateBack() {
        when (screen) {
            is Screen.ChannelVideos -> selectTopTab(BrowseTab.SUBSCRIPTIONS)
            is Screen.Search -> selectTopTab(BrowseTab.HOME)
            is Screen.Home,
            is Screen.Subscriptions,
            is Screen.History,
            is Screen.Popular,
            is Screen.Shorts,
            is Screen.Playlist,
            Screen.Root -> videoHost()?.exitBrowse()
        }
    }

    /** Called from [VideoCarActivity] AA search box after a submitted query. */
    fun showSearchResults(query: String, results: List<MediaDescriptionCompat>) {
        if (!isAdded) return
        pageLoadJob?.cancel()
        screen = Screen.Search(query)
        renderChips()
        backButton.visibility = View.VISIBLE
        titleView.text = getString(R.string.browse_search_results_for, query)
        val rows = results.mapNotNull { desc ->
            val videoId = desc.mediaId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BrowseRow(
                id = VIDEO_PREFIX + videoId,
                title = desc.title?.toString() ?: videoId,
                subtitle = desc.subtitleWithAge(),
                imageUrl = desc.iconUri?.toString(),
                kind = RowKind.VIDEO
            )
        }
        currentQueue = results
        showRows(rows)
    }

    private fun loadHome(forceRefresh: Boolean = false, loadMore: Boolean = false) {
        val entering = screen != Screen.Home
        if (loadMore) saveCurrentScrollPosition()
        screen = Screen.Home
        backButton.visibility = View.GONE
        titleView.setText(R.string.browse_home)
        updateTabSelection()
        // The remembered list appears at once, as on the phone; the store decides whether to fetch.
        val state = feedStore.home.value
        if (state.loaded && !forceRefresh) renderHome(state, restoreScroll = entering) else showLoading()
        pageLoadJob?.cancel()
        pageLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = when {
                forceRefresh -> feedStore.refreshHome()
                loadMore -> feedStore.loadMoreHome()
                else -> feedStore.loadHome()
            }
            if (screen == Screen.Home) renderHome(result, restoreScroll = entering && !state.loaded)
        }
    }

    private fun renderHome(state: FeedStore.FeedState<MediaDescriptionCompat>, restoreScroll: Boolean = false) {
        if (state.videos.isEmpty()) {
            (state.needsSignIn ?: state.error)?.let { showError(it) }
            return
        }
        val rows = videoRows(state.videos, withAge = false).toMutableList()
        if (state.hasMore || state.partial) {
            rows += BrowseRow(ID_HOME_MORE,
                getString(if (state.partial && !state.hasMore) R.string.browse_feed_retry else R.string.browse_feed_more),
                kind = RowKind.ACTION)
        }
        currentQueue = state.videos
        showFeedRows(BrowseTab.HOME, rows, restoreScroll)
    }

    private fun videoRows(items: List<MediaDescriptionCompat>, withAge: Boolean): List<BrowseRow> =
        items.mapNotNull { desc ->
            val videoId = desc.mediaId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BrowseRow(
                id = VIDEO_PREFIX + videoId,
                title = desc.title?.toString() ?: videoId,
                subtitle = if (withAge) desc.subtitleWithAge() else desc.subtitle?.toString(),
                imageUrl = desc.iconUri?.toString(),
                kind = RowKind.VIDEO
            )
        }

    /** Shows a store-backed list; the adapter's diff keeps the scroll position on updates. */
    private fun showFeedRows(tab: BrowseTab, rows: List<BrowseRow>, restoreScroll: Boolean) {
        showRows(rows)
        if (!restoreScroll) return
        scrollPositions[tab]?.let { (position, offset) ->
            listView.post {
                (listView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, offset)
            }
        }
    }

    /**
     * Subscriptions filter chips, mirroring the phone tab. Rebuilt on every feed render because
     * the set is small and only changes when YouTube changes it.
     */
    private fun renderChips() {
        if (!this::browseFacade.isInitialized) return
        val strip = view?.findViewById<LinearLayout>(R.id.car_chip_strip) ?: return
        val row = view?.findViewById<View>(R.id.car_chip_row) ?: return
        val chips = if (screen is Screen.Subscriptions) browseFacade.subscriptionChips() else emptyList()
        row.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        val selected = selectedChip?.title ?: chips.firstOrNull()?.title
        val key = chips to selected
        if (key == renderedChipKey && strip.childCount == chips.size) return
        renderedChipKey = key
        strip.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        chips.forEachIndexed { index, chip ->
            strip.addView(Button(requireContext(), null, 0, R.style.Widget_YouTubeCar_TextButton).apply {
                layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply {
                    if (index > 0) marginStart = dp(8)
                }
                text = chip.title
                isAllCaps = false
                minWidth = 0
                minimumWidth = dp(80)
                setPadding(dp(16), 0, dp(16), 0)
                isSelected = chip.title == selected
                setBackgroundResource(
                    if (chip.title == selected) R.drawable.bg_car_chip_selected else R.drawable.bg_car_chip)
                // Same contrast rule as the tab strip: the selected pill is filled with
                // car_on_surface, so its label must switch to car_surface to stay readable.
                setTextColor(requireContext().getColor(
                    if (chip.title == selected) R.color.car_surface else R.color.car_on_surface))
                setOnClickListener {
                    if (chip.title == selected) return@setOnClickListener
                    pageLoadJob?.cancel()
                    showLoading()
                    pageLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                        val state = feedStore.selectChip(chip)
                        if (screen == Screen.Subscriptions) renderSubscriptions(state)
                    }
                }
            })
        }
    }

    private fun loadSubscriptions(forceRefresh: Boolean = false, loadMore: Boolean = false) {
        val entering = screen != Screen.Subscriptions
        if (loadMore) saveCurrentScrollPosition()
        screen = Screen.Subscriptions
        backButton.visibility = View.GONE
        titleView.setText(R.string.browse_subscriptions)
        updateTabSelection()
        val state = feedStore.subscriptions.value
        if (state.loaded && !forceRefresh) renderSubscriptions(state, restoreScroll = entering) else showLoading()
        pageLoadJob?.cancel()
        pageLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = when {
                forceRefresh -> feedStore.refreshSubscriptions()
                // Same as the phone's row: a partial list without more pages reloads.
                loadMore && (state.partial && !state.hasMore || !state.loaded) -> feedStore.refreshSubscriptions()
                loadMore -> feedStore.loadMoreSubscriptions()
                else -> feedStore.loadSubscriptions()
            }
            if (screen != Screen.Subscriptions) return@launch
            renderSubscriptions(result, restoreScroll = entering && !state.loaded)
            if (!forceRefresh && !loadMore) feedStore.revalidateSubscriptions()
        }
    }

    private fun renderSubscriptions(state: FeedStore.FeedState<MediaDescriptionCompat>, restoreScroll: Boolean = false) {
        renderChips()
        if (state.videos.isEmpty() && state.channels.isEmpty()) {
            (state.needsSignIn ?: state.error)?.let { showError(it) }
            return
        }
        val rows = mutableListOf<BrowseRow>()
        if (state.channels.isNotEmpty()) {
            rows += BrowseRow("subscription_channels", "", kind = RowKind.CHANNELS, channels = state.channels)
        }
        rows += videoRows(state.videos, withAge = true)
        when {
            state.hasMore && !state.partial ->
                rows += BrowseRow(ID_SUBSCRIPTION_MORE, getString(R.string.browse_feed_more), kind = RowKind.ACTION)
            state.partial ->
                rows += BrowseRow(ID_SUBSCRIPTION_MORE, getString(R.string.browse_feed_retry), kind = RowKind.ACTION)
            state.error != null ->
                // Retries this filter; the generic retry reloads the tab and would drop the chip.
                rows += BrowseRow(ID_SUBSCRIPTION_RETRY, getString(R.string.browse_feed_retry), kind = RowKind.ACTION)
        }
        currentQueue = state.videos
        showFeedRows(BrowseTab.SUBSCRIPTIONS, rows, restoreScroll)
    }

    private fun applyRepairedVideo(updated: MediaDescriptionCompat) {
        val id = updated.mediaId ?: return
        if (currentQueue.none { it.mediaId == id }) return
        pendingRepairs[id] = updated
        if (repairBatchJob?.isActive == true) return
        repairBatchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(REPAIR_BATCH_WINDOW_MS)
            while (pendingRepairs.isNotEmpty()) {
                val batch = pendingRepairs.toMap()
                pendingRepairs.clear()
                applyRepairedBatch(batch)
                if (pendingRepairs.isNotEmpty()) delay(REPAIR_BATCH_WINDOW_MS)
            }
        }
    }

    private fun applyRepairedBatch(batch: Map<String, MediaDescriptionCompat>) {
        if (batch.isEmpty()) return
        fun patchDescription(item: MediaDescriptionCompat): MediaDescriptionCompat = batch[item.mediaId] ?: item
        fun patchRow(row: BrowseRow): BrowseRow {
            val id = row.id.removePrefix(VIDEO_PREFIX)
            val updated = batch[id] ?: return row
            return row.copy(
                title = updated.title?.toString() ?: row.title,
                subtitle = updated.subtitle?.toString(),
                imageUrl = updated.iconUri?.toString()
            )
        }
        currentQueue = currentQueue.map(::patchDescription)
        feedStore.patch(::patchDescription)
        pageCache.keys.toList().forEach { tab ->
            pageCache[tab]?.let { old ->
                pageCache[tab] = old.copy(rows = old.rows.map(::patchRow), queue = old.queue.map(::patchDescription))
            }
        }
        adapterRef?.submit(adapter.currentList.map(::patchRow))
    }

    private fun loadChannelVideos(channelId: String, title: String, forceRefresh: Boolean = false) {
        val targetScreen = Screen.ChannelVideos(channelId)
        screen = targetScreen
        renderChips()
        backButton.visibility = View.VISIBLE
        titleView.text = title
        showLoading()
        pageLoadJob?.cancel()
        pageLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = browseFacade.channelVideos(channelId, forceRefresh)
            if (screen != targetScreen) return@launch
            when (result) {
                is BrowseOutcome.Ok -> showVideoDescriptions(result.data)
                is BrowseOutcome.Error -> showError(result.message)
                is BrowseOutcome.NeedsSignIn -> showError(result.message)
            }
        }
    }

    private fun loadHistory() {
        pageLoadJob?.cancel()
        if (!SettingsRepository(requireContext()).isHistoryEnabled()) {
            showError(getString(R.string.browse_empty))
            return
        }
        screen = Screen.History
        backButton.visibility = View.GONE
        titleView.setText(R.string.browse_history)
        updateTabSelection()
        val entries = WatchHistoryStore(requireContext()).getAll()
        val rows = entries.map { entry ->
            BrowseRow(
                id = VIDEO_PREFIX + entry.videoId,
                title = entry.title,
                subtitle = entry.channelTitle,
                imageUrl = entry.thumbnailUrl,
                kind = RowKind.VIDEO
            )
        }
        currentQueue = entries.map { it.toDescription() }
        cacheAndRender(BrowseTab.HISTORY, rows, currentQueue)
    }

    private fun loadPopular(forceRefresh: Boolean = false) {
        val targetScreen = Screen.Popular
        screen = targetScreen
        backButton.visibility = View.GONE
        titleView.setText(R.string.browse_search_popular)
        updateTabSelection()
        showLoading()
        pageLoadJob?.cancel()
        pageLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = browseFacade.popular(forceRefresh)
            if (screen != targetScreen) return@launch
            when (result) {
                is BrowseOutcome.Ok -> cacheVideoDescriptions(BrowseTab.POPULAR, result.data)
                is BrowseOutcome.Error -> showError(result.message)
                is BrowseOutcome.NeedsSignIn -> showError(result.message)
            }
        }
    }

    private fun loadShorts(forceRefresh: Boolean = false) {
        val targetScreen = Screen.Shorts
        screen = targetScreen
        backButton.visibility = View.GONE
        titleView.setText(R.string.browse_shorts)
        updateTabSelection()
        showLoading()
        pageLoadJob?.cancel()
        pageLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = browseFacade.subscriptionShorts(forceRefresh)
            if (screen != targetScreen) return@launch
            when (result) {
                is BrowseOutcome.Ok -> cacheVideoDescriptions(BrowseTab.SHORTS, result.data)
                is BrowseOutcome.Error -> showError(result.message)
                is BrowseOutcome.NeedsSignIn -> showError(result.message)
            }
        }
    }

    private fun cacheVideoDescriptions(
        tab: BrowseTab,
        items: List<MediaDescriptionCompat>,
        hasMore: Boolean = false,
        partial: Boolean = false
    ) {
        val rows = items.mapNotNull { desc ->
            val videoId = desc.mediaId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BrowseRow(
                id = VIDEO_PREFIX + videoId,
                title = desc.title?.toString() ?: videoId,
                subtitle = desc.subtitle?.toString(),
                imageUrl = desc.iconUri?.toString(),
                kind = RowKind.VIDEO
            )
        }.toMutableList()
        if (hasMore || partial) {
            rows += BrowseRow(ID_HOME_MORE,
                getString(if (partial) R.string.browse_feed_retry else R.string.browse_feed_more),
                kind = RowKind.ACTION)
        }
        cacheAndRender(tab, rows, items)
    }

    private fun showVideoDescriptions(items: List<MediaDescriptionCompat>) {
        currentQueue = items
        val rows = items.mapNotNull { desc ->
            val videoId = desc.mediaId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BrowseRow(
                id = VIDEO_PREFIX + videoId,
                title = desc.title?.toString() ?: videoId,
                subtitle = desc.subtitle?.toString(),
                imageUrl = desc.iconUri?.toString(),
                kind = RowKind.VIDEO
            )
        }
        showRows(rows)
    }

    private fun showLoading() {
        if (displayedScreen != screen) { adapter.submit(emptyList()); currentQueue = emptyList() }
        if (displayedScreen == screen && adapter.itemCount > 0) {
            // Rows are already on screen: refresh quietly in the background instead of
            // blanking the list and showing a spinner, so the view stays smooth.
            return
        }
        statusRow.visibility = View.VISIBLE
        progressView.visibility = View.VISIBLE
        statusView.setText(R.string.browse_loading)
        adapter.submit(emptyList())
    }

    private fun showRows(rows: List<BrowseRow>) {
        displayedScreen = screen
        // The top rows are the likely taps; warming their watch pages saves 1.5-2.5 s per start.
        StreamResolver.prefetch(
            requireContext(),
            rows.asSequence()
                .filter { it.kind == RowKind.VIDEO && it.id.startsWith(VIDEO_PREFIX) }
                .map { it.id.removePrefix(VIDEO_PREFIX) }
                .take(PREFETCH_ROWS)
                .toList()
        )
        if (rows.isEmpty()) {
            statusRow.visibility = View.VISIBLE
            progressView.visibility = View.GONE
            statusView.setText(R.string.browse_empty)
            adapter.submit(
                listOf(
                    BrowseRow(
                        id = ID_RETRY,
                        title = getString(R.string.browse_retry),
                        kind = RowKind.ACTION
                    )
                )
            )
            return
        }
        statusRow.visibility = View.GONE
        progressView.visibility = View.GONE
        adapter.submit(
            rows.map { row ->
                if (row.kind == RowKind.VIDEO) {
                    row.copy(showAction = true, actionLabel = "+")
                } else {
                    row
                }
            }
        )
    }

    private fun reloadCurrentScreen() {
        when (val current = screen) {
            Screen.Root -> showRoot()
            Screen.Home -> selectTopTab(BrowseTab.HOME, forceRefresh = true)
            Screen.Subscriptions -> selectTopTab(BrowseTab.SUBSCRIPTIONS, forceRefresh = true)
            is Screen.ChannelVideos -> loadChannelVideos(current.channelId, titleView.text.toString())
            Screen.History -> loadHistory()
            Screen.Popular -> selectTopTab(BrowseTab.POPULAR, forceRefresh = true)
            Screen.Shorts -> selectTopTab(BrowseTab.SHORTS, forceRefresh = true)
            is Screen.Search -> showRoot()
            Screen.Playlist -> showPlaylist()
        }
    }

    private fun showError(message: String) {
        statusRow.visibility = View.VISIBLE
        progressView.visibility = View.GONE
        statusView.text = message.ifBlank { getString(R.string.browse_error) }
        val existing = if (displayedScreen == screen) adapter.currentList.filter { it.id != ID_RETRY } else emptyList()
        adapter.submit(existing + BrowseRow(ID_RETRY, getString(R.string.browse_retry), kind = RowKind.ACTION))
    }

    private fun videoHost(): VideoHost? = context as? VideoHost

    // MediaDescriptionCompat statt reiner Video-ID transportiert Titel/Thumbnail bis zum
    // ExoPlayerVideoFragment weiter, damit die Smartphone-App weiß, was auf dem Auto-Display läuft.
    private sealed class Screen {
        data object Root : Screen()
        data object Home : Screen()
        data object Subscriptions : Screen()
        data class ChannelVideos(val channelId: String) : Screen()
        data object History : Screen()
        data object Popular : Screen()
        data object Shorts : Screen()
        data class Search(val query: String) : Screen()
        data object Playlist : Screen()
    }

    private class BrowseAdapter(
        private val scope: CoroutineScope,
        private val onClick: (BrowseRow) -> Unit,
        private val onAction: (BrowseRow) -> Unit,
        private val repair: suspend (MediaDescriptionCompat) -> MediaDescriptionCompat,
        private val onRepaired: (MediaDescriptionCompat) -> Unit
    ) : ListAdapter<BrowseRow, BrowseAdapter.Holder>(DIFF) {

        var videoCards = false
            private set
        var videoImageTargetPx = 640
        init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        fun submit(rows: List<BrowseRow>) = submitList(rows.toList())
        fun setVideoCards(enabled: Boolean) {
            if (videoCards == enabled) return
            videoCards = enabled
            notifyDataSetChanged()
        }
        override fun getItemViewType(position: Int) = when {
            getItem(position).kind == RowKind.CHANNELS -> 1
            getItem(position).kind == RowKind.VIDEO && videoCards -> 2
            else -> 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = if (viewType == 1) RecyclerView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                itemAnimator = null
                adapter = CarChannelsAdapter(scope) { channel ->
                    onClick(BrowseRow(CHANNEL_PREFIX + channel.channelId, channel.title,
                        imageUrl = channel.thumbnailUrl, kind = RowKind.CHANNEL))
                }
            } else LayoutInflater.from(parent.context).inflate(if (viewType == 2) R.layout.item_car_video_card else R.layout.item_browse_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position), onClick, onAction, scope, videoImageTargetPx)
            if (holder.itemView.isAttachedToWindow) repairVisible(holder)
        }
        override fun onViewAttachedToWindow(holder: Holder) = repairVisible(holder)
        override fun onViewDetachedFromWindow(holder: Holder) { holder.repairJob?.cancel(); holder.repairJob = null }
        override fun onViewRecycled(holder: Holder) { onViewDetachedFromWindow(holder); holder.clear() }
        private fun repairVisible(holder: Holder) {
            val row = holder.boundRow ?: return
            if (row.kind != RowKind.VIDEO || holder.repairJob?.isActive == true) return
            holder.repairJob = scope.launch {
                val original = MediaDescriptionCompat.Builder().setMediaId(row.id.removePrefix(VIDEO_PREFIX))
                    .setTitle(row.title).setSubtitle(row.subtitle).setIconUri(row.imageUrl?.let(android.net.Uri::parse)).build()
                val updated = repair(original)
                if (isActive && holder.boundRow == row && updated !== original) onRepaired(updated)
            }
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView? = itemView.findViewById(R.id.row_cover)
            private val title: TextView? = itemView.findViewById(R.id.row_title)
            private val subtitle: TextView? = itemView.findViewById(R.id.row_subtitle)
            private val action: Button? = itemView.findViewById(R.id.row_action)
            var repairJob: Job? = null
            var boundRow: BrowseRow? = null
            fun clear() { cover?.clearThumbnailBinding(); boundRow = null }

            fun bind(
                row: BrowseRow,
                onClick: (BrowseRow) -> Unit,
                onAction: (BrowseRow) -> Unit,
                scope: CoroutineScope? = null,
                videoImageTargetPx: Int = 640
            ) {
                boundRow = row
                repairJob?.cancel()
                if (row.kind == RowKind.CHANNELS) {
                    ((itemView as RecyclerView).adapter as CarChannelsAdapter).submitList(row.channels)
                    return
                }
                val cover = cover ?: return
                val title = title ?: return
                val subtitle = subtitle ?: return
                val action = action ?: return
                cover.clearThumbnailBinding()
                val density = itemView.resources.displayMetrics.density
                val isChannel = row.kind == RowKind.CHANNEL
                val widthDp = if (isChannel) 76 else 136
                val heightDp = 76
                if (cover !is VideoThumbnailView) (cover.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.width = (widthDp * density).toInt()
                    params.height = (heightDp * density).toInt()
                    cover.layoutParams = params
                }
                cover.setBackgroundResource(if (isChannel) R.drawable.bg_car_avatar else R.drawable.bg_car_cover)
                cover.setImageDrawable(null)
                if (row.imageUrl.isNullOrBlank()) {
                    cover.visibility = if (cover is VideoThumbnailView) View.VISIBLE else View.GONE
                } else {
                    cover.visibility = View.VISIBLE
                    if (scope != null) cover.bindThumbnail(scope, row.imageUrl, targetPx = if (cover is VideoThumbnailView) videoImageTargetPx else (widthDp * density).toInt())
                }
                title.text = row.title
                if (row.subtitle.isNullOrBlank()) {
                    subtitle.visibility = View.GONE
                } else {
                    subtitle.visibility = View.VISIBLE
                    subtitle.text = row.subtitle
                }
                if (row.showAction) {
                    action.visibility = View.VISIBLE
                    action.text = row.actionLabel ?: "+"
                    action.setOnClickListener { onAction(row) }
                } else {
                    action.visibility = View.GONE
                    action.setOnClickListener(null)
                }
                itemView.setOnClickListener { onClick(row) }
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<BrowseRow>() {
                override fun areItemsTheSame(oldItem: BrowseRow, newItem: BrowseRow): Boolean =
                    oldItem.id == newItem.id && oldItem.kind == newItem.kind

                override fun areContentsTheSame(oldItem: BrowseRow, newItem: BrowseRow): Boolean =
                    oldItem == newItem
            }
        }
    }

    private val shared get() = sharedState

    companion object {
        /** Survives fragment instances (main thread only); cleared on account change. */
        private val sharedState = SharedState()
        private const val REPAIR_BATCH_WINDOW_MS = 200L
        /** Merging keeps rows a refresh no longer reports; bound the list a long drive builds. */
        private const val MAX_MERGED_VIDEO_ROWS = 200
        private const val ID_HOME = "home"
        private const val ID_SUBSCRIPTIONS = "subscriptions"
        private const val ID_SUBSCRIPTION_MORE = "subscriptions_more"
        private const val ID_HOME_MORE = "home_more"
        private const val ID_SUBSCRIPTION_RETRY = "subscriptions_retry"
        private const val ID_PLAYLIST = "playlist"
        private const val ID_PLAYLIST_PLAY_ALL = "playlist_play_all"
        private const val ID_PLAYLIST_CLEAR = "playlist_clear"
        private const val ID_SETTINGS = "settings"
        private const val ID_HISTORY = "history"
        private const val ID_POPULAR = "popular"
        private const val ID_RETRY = "retry"
        private const val CHANNEL_PREFIX = YouTubeMediaBrowserService.CHANNEL_PREFIX
        private const val VIDEO_PREFIX = YouTubeMediaBrowserService.VIDEO_PREFIX
        private const val PREFETCH_ROWS = 3
        private const val CAR_CARD_MIN_WIDTH_DP = 168f
        private const val MAX_BROWSE_COLUMNS = 4
    }
}
