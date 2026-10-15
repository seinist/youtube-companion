@file:Suppress("DEPRECATION")
package de.uni.youtubeaa.car

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaSessionManager
import androidx.media.session.MediaButtonReceiver
import androidx.media.utils.MediaConstants
import de.uni.youtubeaa.car.hybrid.BrowseFacade
import de.uni.youtubeaa.car.hybrid.BrowseOutcome
import de.uni.youtubeaa.car.hybrid.FeedStore
import de.uni.youtubeaa.car.hybrid.HybridChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

// Browse-Baum: Root -> Startseite, Abos, Suche, Verlauf.
// Abos -> Kanäle -> Videos.
// Datenquelle: BrowseFacade (TV-Session/InnerTube + anonyme öffentliche Daten).
//
// Läuft außerdem als Foreground-Service mit MediaStyle-Notification, sobald irgendwo
// (Handy-Player oder AA-Projektion) etwas abgespielt wird – siehe YouTubeMediaSession, das
// NowPlayingBus beobachtet und Metadaten/PlaybackState dieser Session mitführt. So ist die
// Wiedergabe sowohl über die Handy-Benachrichtigung als auch über das Media-Widget im Auto
// steuerbar (Play/Pause/Weiter/Zurück).
class YouTubeMediaBrowserService : MediaBrowserServiceCompat() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var browseFacade: BrowseFacade
    private lateinit var settingsRepository: SettingsRepository

    /**
     * Last list sent per network-backed browse level. Android Auto reloads a level whenever the
     * user returns to it; serving this list keeps every row where it was. Subscriptions and the
     * other mergeable levels refresh behind it (see [refreshInBackground]). Startseite does not:
     * its recommendations are not a stable list, so a background merge adds and removes rows.
     */
    private class ShownList(val items: List<MediaItem>, val loadedAtElapsedMs: Long)
    private val shownLists = HashMap<String, ShownList>()
    private val backgroundRefreshes = HashSet<String>()
    /**
     * Android Auto can issue onLoadChildren for the same level again before the first call
     * returned (reconnect, re-subscribe). Before [shownLists] has an entry that raced a second,
     * independent fetch against a feed (Home, Popular, ...) whose recommendations are not
     * deterministic between requests, so the two calls returned different snapshots and the
     * later one silently overwrote the rows Android Auto had just rendered: items flickered in
     * and out and taps landed on whatever row happened to be there a moment ago. Joining
     * concurrent first-loads of the same content id onto one fetch keeps every caller consistent.
     */
    private val pendingLoads = HashMap<String, Deferred<MutableList<MediaItem>>>()
    private var shownListsAccount: String? = null
    /** Bumped per list by its "Aktualisieren" row, so the row id is stable between reloads. */
    private val refreshGenerations = HashMap<String, Int>()
    /** Wall-clock time of the last successful load per list, shown in its refresh row. */
    private val loadedAtWallMs = HashMap<String, Long>()
    /** Lists whose last refresh failed; the refresh row says so until one succeeds. */
    private val failedRefreshes = HashSet<String>()
    /**
     * Startseite and Abos come from the process-wide [FeedStore], with the phone's rules. This
     * remembers the state each list was last sent with, so Android Auto is told to reload exactly
     * when the store changed it afterwards (silent refresh, "more", or the phone's own actions).
     */
    private lateinit var feedStore: FeedStore<MediaDescriptionCompat>
    private val sentFeedStates = HashMap<String, FeedStore.FeedState<MediaDescriptionCompat>>()
    private lateinit var mediaSessionHolder: YouTubeMediaSession
    private lateinit var mediaController: MediaControllerCompat
    private var isForeground = false
    private var metadataRevision = 0L
    private val notificationUpdates = NotificationUpdateGate()

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            postNotification(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            metadataRevision++
            postNotification(mediaController.playbackState)
        }
    }

    override fun onCreate() {
        super.onCreate()
        browseFacade = BrowseFacade(applicationContext)
        feedStore = browseFacade.feedStore()
        observeFeed(ID_HOME, feedStore.home)
        observeFeed(ID_SUBSCRIPTIONS, feedStore.subscriptions)
        settingsRepository = SettingsRepository(applicationContext)
        mediaSessionHolder = YouTubeMediaSession(applicationContext)
        sessionToken = mediaSessionHolder.mediaSession.sessionToken
        mediaController = MediaControllerCompat(this, sessionToken!!)
        mediaController.registerCallback(controllerCallback)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSessionHolder.mediaSession, intent)
        // Muss unmittelbar nach startForegroundService() erfolgen (sonst
        // ForegroundServiceDidNotStartInTimeException ab Android 12) – der eigentliche
        // PlaybackState kommt oft erst kurz danach asynchron über NowPlayingBus rein, daher hier
        // unbedingt starten und nicht über postNotification()/dessen Stop-Zweig laufen lassen.
        startForeground(NOTIFICATION_ID, buildNotification(mediaController.playbackState))
        isForeground = true
        notificationUpdates.reset()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaController.unregisterCallback(controllerCallback)
        serviceJob.cancel()
        mediaSessionHolder.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_channel_playback),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun postNotification(state: PlaybackStateCompat?) {
        // A restored "last session" preview is only meant for the AA player on reconnect; it must
        // not post (or keep) a phone notification for playback that is not actually loaded.
        val stateCode = if (mediaSessionHolder.isRestoredPreview) PlaybackStateCompat.STATE_NONE
            else state?.state ?: PlaybackStateCompat.STATE_NONE
        // Transient focus loss sets isPlaying=false while ExoPlayer still intends to play.
        // Do not demote that active session to a background service during navigation/speech.
        val keepForeground = stateCode == PlaybackStateCompat.STATE_PLAYING ||
            stateCode == PlaybackStateCompat.STATE_BUFFERING ||
            (stateCode == PlaybackStateCompat.STATE_PAUSED &&
                VideoSessionBridge.controller?.snapshot()?.playWhenReady == true)
        if (!notificationUpdates.accept(stateCode, metadataRevision, keepForeground)) return

        if (keepForeground) {
            val notification = buildNotification(state, ongoing = true)
            if (!isForeground) {
                startForeground(NOTIFICATION_ID, notification)
                isForeground = true
            } else if (canPostNotifications()) {
                try {
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
                } catch (_: SecurityException) {
                    // Permission may be revoked between the check and notify().
                }
            }
        } else if (stateCode == PlaybackStateCompat.STATE_PAUSED) {
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                isForeground = false
            }
            if (canPostNotifications()) {
                try {
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
                } catch (_: SecurityException) {
                    // Permission may be revoked between the check and notify().
                }
            }
        } else {
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
            } else {
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            }
            stopSelf()
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(state: PlaybackStateCompat?, ongoing: Boolean = false): Notification {
        val description = mediaController.metadata?.description
        val isPlaying = state?.state == PlaybackStateCompat.STATE_PLAYING ||
            (ongoing && VideoSessionBridge.controller?.snapshot()?.playWhenReady == true)
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_brand)
            .setContentTitle(description?.title ?: getString(R.string.app_name))
            .setContentText(description?.subtitle)
            .setLargeIcon(description?.iconBitmap)
            .setContentIntent(contentIntent)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notif_previous,
                    getString(R.string.notif_previous),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            )
            .addAction(
                NotificationCompat.Action(
                    if (isPlaying) R.drawable.ic_pause_on_primary else R.drawable.ic_play_on_primary,
                    getString(if (isPlaying) R.string.notif_pause else R.string.notif_play),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        if (isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
                    )
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notif_next,
                    getString(R.string.notif_next),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing || isPlaying)
            .build()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        val ownClient = clientUid == applicationInfo.uid
        val trustedClient = MediaSessionManager.getSessionManager(this).isTrustedForMediaControl(
            MediaSessionManager.RemoteUserInfo(
                clientPackageName,
                -1,
                clientUid
            )
        )
        if (!ownClient && !trustedClient) return null
        // The hint lives on the root: it is how Android Auto lays out this node's children.
        // Default is the compact list; cards follow the same switch as the projected browse.
        val extras = Bundle().apply {
            val style = browseContentStyle()
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        serviceScope.launch {
            // A tapped "Aktualisieren" row carries the list it belongs to, so the same loaders
            // run again with the browse caches bypassed.
            val refreshTarget = parentId.takeIf { it.startsWith(REFRESH_PREFIX) }
                ?.removePrefix(REFRESH_PREFIX)
                ?.substringAfter(':')
                ?.takeIf { it.isNotBlank() }
            val contentId = refreshTarget ?: parentId
            dropShownListsOnAccountChange()
            val items = try {
                when {
                    contentId == ID_HOME || contentId.startsWith(HOME_MORE_PREFIX) -> {
                        val state = when {
                            refreshTarget != null -> { bumpRefreshGeneration(contentId); feedStore.refreshHome() }
                            contentId == ID_HOME -> feedStore.loadHome()
                            else -> feedStore.loadMoreHome()
                        }
                        sentFeedStates[ID_HOME] = state
                        homeItems(state)
                    }
                    contentId == ID_SUBSCRIPTIONS || contentId.startsWith(SUBSCRIPTIONS_MORE_PREFIX) -> {
                        val current = feedStore.subscriptions.value
                        val state = when {
                            refreshTarget != null -> { bumpRefreshGeneration(contentId); feedStore.refreshSubscriptions() }
                            contentId == ID_SUBSCRIPTIONS -> feedStore.loadSubscriptions().also {
                                serviceScope.launch { feedStore.revalidateSubscriptions() }
                            }
                            // Same as the phone's row: a partial list without more pages reloads.
                            current.partial && !current.hasMore || !current.loaded -> feedStore.refreshSubscriptions()
                            else -> feedStore.loadMoreSubscriptions()
                        }
                        sentFeedStates[ID_SUBSCRIPTIONS] = state
                        subscriptionItems(state)
                    }
                    refreshTarget != null -> {
                        // Explicit refresh: the one path that replaces a list instead of merging.
                        bumpRefreshGeneration(contentId)
                        val fresh = try {
                            loadChildrenFor(contentId, forceRefresh = true)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "refresh failed for $contentId", e)
                            mutableListOf()
                        }
                        if (fresh.any { it.isPlayable } || shownLists[contentId] == null) {
                            failedRefreshes.remove(contentId)
                            fresh.also { remember(contentId, it) }
                        } else {
                            // Failed or empty: keep the list and say so instead of an empty page.
                            failedRefreshes.add(contentId)
                            shownLists.getValue(contentId).items.toMutableList()
                        }
                    }
                    isMergeable(contentId) -> shownLists[contentId]?.let { shown ->
                        if (isStale(contentId, shown)) refreshInBackground(contentId)
                        shown.items.toMutableList()
                    } ?: loadOrJoin(contentId) { loadChildrenFor(contentId, forceRefresh = false) }
                        .also { remember(contentId, it) }
                    else -> loadChildrenFor(contentId, forceRefresh = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onLoadChildren failed for $parentId", e)
                mutableListOf()
            }
            if (refreshTarget != null) {
                // The list the user came from would otherwise keep showing the stale page.
                notifyChildrenChanged(contentId)
            }
            // A "more" level extended its parent list; Android Auto shows it on the way back.
            if (contentId.startsWith(HOME_MORE_PREFIX)) notifyChildrenChanged(ID_HOME)
            if (contentId.startsWith(SUBSCRIPTIONS_MORE_PREFIX)) notifyChildrenChanged(ID_SUBSCRIPTIONS)
            // The queue follows what is actually shown, never a background fetch for another list.
            if (contentId != ROOT_ID) updateBrowseQueue(items.filter { it.isPlayable })
            result.sendResult(withRefreshAction(contentId, items))
        }
    }

    private fun observeFeed(id: String, flow: StateFlow<FeedStore.FeedState<MediaDescriptionCompat>>) {
        serviceScope.launch {
            flow.collect { state ->
                val sent = sentFeedStates[id] ?: return@collect
                if (state != sent) notifyChildrenChanged(id)
            }
        }
    }

    private fun bumpRefreshGeneration(contentId: String) {
        refreshGenerations[contentId] = (refreshGenerations[contentId] ?: 0) + 1
    }

    private fun homeItems(state: FeedStore.FeedState<MediaDescriptionCompat>): MutableList<MediaItem> {
        val items = state.videos.mapNotNull { it.toPlayableMediaItem() }.toMutableList()
        if (items.isNotEmpty() && (state.hasMore || state.partial)) {
            items += browsableItem(HOME_MORE_PREFIX + java.util.UUID.randomUUID(),
                getString(if (state.partial && !state.hasMore) R.string.browse_feed_retry else R.string.browse_feed_more))
        }
        if (items.isEmpty()) (state.needsSignIn ?: state.error)?.let { items += messageItem(it) }
        return items
    }

    private fun subscriptionItems(state: FeedStore.FeedState<MediaDescriptionCompat>): MutableList<MediaItem> {
        // As on the phone: subscribed channels first, then the chronological feed.
        val items = state.channels.map { it.toBrowsableMediaItem() }.toMutableList()
        items += state.videos.mapNotNull { it.toPlayableMediaItem() }
        when {
            state.needsSignIn != null -> items += messageItem(state.needsSignIn)
            state.hasMore || state.partial || state.error != null ->
                items += browsableItem(SUBSCRIPTIONS_MORE_PREFIX + java.util.UUID.randomUUID(), getString(
                    if (state.hasMore && !state.partial) R.string.browse_feed_more else R.string.browse_feed_retry))
        }
        return items
    }

    /** A non-playable row that says why a list is empty instead of showing nothing. */
    private fun messageItem(message: String): MediaItem =
        browsableItem(MESSAGE_PREFIX + message.hashCode(), message)

    /**
     * Runs [loader] for [contentId], or joins the fetch already running for it. Both this
     * function's mutation of [pendingLoads] and every call site run on [serviceScope]'s Main
     * dispatcher, so the check-then-insert below never races with another onLoadChildren call.
     */
    private suspend fun loadOrJoin(
        contentId: String,
        loader: suspend () -> MutableList<MediaItem>
    ): MutableList<MediaItem> {
        pendingLoads[contentId]?.let { return it.await() }
        val deferred = serviceScope.async { loader() }
        pendingLoads[contentId] = deferred
        try {
            return deferred.await()
        } finally {
            pendingLoads.remove(contentId)
        }
    }

    private fun isMergeable(contentId: String) =
        contentId == ID_POPULAR || contentId == ID_SEARCH || contentId.startsWith(CHANNEL_PREFIX)

    private fun isStale(contentId: String, shown: ShownList): Boolean {
        return android.os.SystemClock.elapsedRealtime() - shown.loadedAtElapsedMs > LIST_STALE_MS
    }

    private fun remember(contentId: String, items: List<MediaItem>) {
        if (isMergeable(contentId) && items.any { it.isPlayable }) {
            shownLists[contentId] = ShownList(items.toList(), android.os.SystemClock.elapsedRealtime())
            loadedAtWallMs[contentId] = System.currentTimeMillis()
        }
    }

    private fun dropShownListsOnAccountChange() {
        val account = browseFacade.sessionStore().getTokens()?.refreshToken
        if (account != shownListsAccount) {
            shownListsAccount = account
            shownLists.clear()
        }
    }

    /**
     * Fetches [contentId] again and merges it into the shown list: rows keep their order, new
     * videos are inserted (new uploads on top), and Android Auto is only told to reload when
     * something was actually added. A failed or empty fetch leaves the list untouched.
     */
    private fun refreshInBackground(contentId: String) {
        if (!backgroundRefreshes.add(contentId)) return
        serviceScope.launch {
            var changed = false
            try {
                // Not forceRefresh: that restarts the shared paging session (phone, car UI and
                // this browser) and drops pages loaded with "more". The facade's own TTL decides.
                val fresh = loadChildrenFor(contentId, forceRefresh = false)
                val shown = shownLists[contentId] ?: return@launch
                if (fresh.none { it.isPlayable }) {
                    // Like the phone's silent refresh: a failed fetch keeps the page as it is.
                    if (failedRefreshes.add(contentId)) changed = true
                    return@launch
                }
                if (failedRefreshes.remove(contentId)) changed = true
                val merged = mergeShown(shown.items, fresh)
                shownLists[contentId] = ShownList(merged, android.os.SystemClock.elapsedRealtime())
                // Only a visible difference reloads the page. Notifying after every refresh made
                // Android Auto rebuild the list (and reset its scroll) once a minute on Abos.
                if (visibleKeys(merged) != visibleKeys(shown.items)) {
                    loadedAtWallMs[contentId] = System.currentTimeMillis()
                    changed = true
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (failedRefreshes.add(contentId)) changed = true
                Log.w(TAG, "background refresh failed for $contentId", e)
            } finally {
                backgroundRefreshes.remove(contentId)
                if (changed) notifyChildrenChanged(contentId)
            }
        }
    }

    private fun mergeShown(shown: List<MediaItem>, fresh: List<MediaItem>): List<MediaItem> {
        // Browsable rows ahead of the videos (subscribed channels) and behind them ("more")
        // come from the fresh list; only the videos are merged.
        val header = fresh.takeWhile { !it.isPlayable }
        val trailer = fresh.drop(header.size).filter { !it.isPlayable }
        val freshVideos = fresh.filter { it.isPlayable }
        val key: (MediaItem) -> String = { it.mediaId.orEmpty() }
        val videos = StableListMerge.trimVanished(
            StableListMerge.merge(shown.filter { it.isPlayable }, freshVideos, key),
            freshVideos,
            maxOf(MAX_MERGED_ITEMS, freshVideos.size),
            key
        )
        return header + videos + trailer
    }

    private fun visibleKeys(items: List<MediaItem>) = items.map {
        listOf(it.mediaId, it.description.title?.toString(), it.description.subtitle?.toString(),
            it.description.iconUri?.toString())
    }

    private suspend fun loadChildrenFor(parentId: String, forceRefresh: Boolean): MutableList<MediaItem> =
        when {
            parentId == ROOT_ID -> loadRoot()
            parentId == ID_POPULAR -> loadPopular(forceRefresh)
            parentId == ID_SHORTS -> loadShorts(forceRefresh)
            parentId == ID_HISTORY -> loadHistory()
            parentId == ID_SEARCH -> loadSearchLanding(forceRefresh)
            parentId == ID_PLAYLIST -> loadPlaylist()
            parentId.startsWith(CHANNEL_PREFIX) ->
                loadChannelVideos(parentId.removePrefix(CHANNEL_PREFIX), forceRefresh)
            else -> mutableListOf()
        }

    /**
     * Android Auto's browser has no toolbar of its own, so every list gets an "Aktualisieren" row.
     */
    private fun withRefreshAction(parentId: String, items: MutableList<MediaItem>): MutableList<MediaItem> {
        // A "more" level is one step of a shared pager: re-running it would page further instead
        // of reloading. Refresh belongs to the subscriptions list itself.
        if (parentId == ROOT_ID || parentId.startsWith(SUBSCRIPTIONS_MORE_PREFIX) ||
            parentId.startsWith(HOME_MORE_PREFIX)) return items
        // Stable between reloads (a random id per load made Android Auto see a changed list each
        // time); a new generation after each use keeps AA from serving its cached refresh level.
        items.add(0, browsableItem(
            REFRESH_PREFIX + (refreshGenerations[parentId] ?: 0) + ":" + parentId,
            refreshTitle(parentId)
        ))
        return items
    }

    private fun refreshTitle(parentId: String): String = when {
        parentId == ID_HOME || parentId == ID_SUBSCRIPTIONS -> {
            val state = if (parentId == ID_HOME) feedStore.home.value else feedStore.subscriptions.value
            when {
                state.refreshFailed -> getString(R.string.browse_refresh_failed)
                state.updatedAtMs != 0L -> getString(R.string.browse_refresh_at,
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(state.updatedAtMs)))
                else -> getString(R.string.browse_refresh)
            }
        }
        parentId in failedRefreshes -> getString(R.string.browse_refresh_failed)
        else -> loadedAtWallMs[parentId]?.let {
            getString(R.string.browse_refresh_at,
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)))
        } ?: getString(R.string.browse_refresh)
    }

    // Sprachsuche in Android Auto ruft onSearch() mit der erkannten Query auf.
    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaItem>>) {
        result.detach()
        serviceScope.launch {
            val items = try {
                when (val res = browseFacade.search(query)) {
                    is BrowseOutcome.Ok -> res.data.mapNotNull { it.toPlayableMediaItem() }.toMutableList()
                    is BrowseOutcome.Error,
                    is BrowseOutcome.NeedsSignIn -> mutableListOf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "search failed for query=$query", e)
                mutableListOf()
            }
            updateBrowseQueue(items)
            result.sendResult(items)
        }
    }

    private fun loadRoot(): MutableList<MediaItem> {
        // Keep the native Android Auto media browser aligned with the phone tab order.
        // Search remains available through Android Auto's search affordance rather than as a tab.
        val style = browseContentStyle()
        return mutableListOf(
            browsableItem(ID_HOME, getString(R.string.browse_root_home), style, style),
            browsableItem(ID_SUBSCRIPTIONS, getString(R.string.browse_subscriptions), style, style),
            browsableItem(ID_SHORTS, getString(R.string.browse_shorts), style, style),
            browsableItem(ID_POPULAR, getString(R.string.browse_search_popular), style, style),
            browsableItem(ID_HISTORY, getString(R.string.browse_history), style, style),
            browsableItem(ID_PLAYLIST, getString(R.string.browse_my_playlist), style, style)
        )
    }

    private fun loadPlaylist(): MutableList<MediaItem> {
        val entries = UserPlaylistStore(applicationContext).getAll()
        val items = entries.map { entry ->
            MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(VIDEO_PREFIX + entry.videoId)
                    .setTitle(entry.title)
                    .setSubtitle(entry.channelTitle)
                    .setIconUri(entry.thumbnailUrl?.let { android.net.Uri.parse(it) })
                    .build(),
                MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()
        return items
    }

    private suspend fun loadPopular(forceRefresh: Boolean = false): MutableList<MediaItem> {
        return when (val result = browseFacade.popular(forceRefresh)) {
            is BrowseOutcome.Ok -> result.data.mapNotNull { it.toPlayableMediaItem() }.toMutableList()
            is BrowseOutcome.Error,
            is BrowseOutcome.NeedsSignIn -> mutableListOf()
        }
    }

    private suspend fun loadShorts(forceRefresh: Boolean = false): MutableList<MediaItem> {
        return when (val result = browseFacade.subscriptionShorts(forceRefresh)) {
            is BrowseOutcome.Ok -> result.data.mapNotNull { it.toPlayableMediaItem() }.toMutableList()
            is BrowseOutcome.Error,
            is BrowseOutcome.NeedsSignIn -> mutableListOf()
        }
    }

    private suspend fun loadChannelVideos(channelId: String, forceRefresh: Boolean = false): MutableList<MediaItem> {
        return when (val result = browseFacade.channelVideos(channelId, forceRefresh)) {
            is BrowseOutcome.Ok -> result.data.mapNotNull { it.toPlayableMediaItem() }.toMutableList()
            is BrowseOutcome.Error,
            is BrowseOutcome.NeedsSignIn -> mutableListOf()
        }
    }

    private suspend fun loadHistory(): MutableList<MediaItem> {
        if (!settingsRepository.isHistoryEnabled()) return mutableListOf()
        val items = WatchHistoryStore(applicationContext)
            .getAll()
            .mapNotNull { it.toDescription().toPlayableMediaItem() }
            .toMutableList()
        return items
    }

    private suspend fun loadSearchLanding(forceRefresh: Boolean = false): MutableList<MediaItem> {
        // Freitextsuche läuft über onSearch(); Einstieg = öffentliche Trends/Popular.
        return when (val result = browseFacade.popular(forceRefresh)) {
            is BrowseOutcome.Ok -> result.data.mapNotNull { it.toPlayableMediaItem() }.toMutableList()
            is BrowseOutcome.Error,
            is BrowseOutcome.NeedsSignIn -> mutableListOf()
        }
    }

    private fun MediaDescriptionCompat.toPlayableMediaItem(): MediaItem? {
        val rawId = mediaId?.removePrefix(VIDEO_PREFIX)?.takeIf { it.isNotBlank() } ?: return null
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(VIDEO_PREFIX + rawId)
            .setTitle(this.title ?: rawId)
            .setSubtitle(this.subtitleWithAge())
            .setDescription(this.description)
            .setIconBitmap(this.iconBitmap)
            .setIconUri(this.iconUri)
            .setMediaUri(this.mediaUri)
            .setExtras(this.extras)
            .build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    private fun browseContentStyle(): Int =
        if (settingsRepository.isCarBrowseCardsEnabled())
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        else MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

    private fun HybridChannel.toBrowsableMediaItem(): MediaItem {
        val style = browseContentStyle()
        val extras = Bundle().apply {
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
        }
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(CHANNEL_PREFIX + channelId)
            .setTitle(title)
            .setIconUri(thumbnailUrl?.let { Uri.parse(it) })
            .setExtras(extras)
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    private fun updateBrowseQueue(items: List<MediaItem>) {
        mediaSessionHolder.setBrowseQueue(items.map { it.description })
    }

    private fun browsableItem(
        id: String,
        title: String,
        contentStyleBrowsable: Int? = null,
        contentStylePlayable: Int? = null
    ): MediaItem {
        val extras = Bundle()
        contentStyleBrowsable?.let {
            extras.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it)
        }
        contentStylePlayable?.let {
            extras.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it)
        }
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setExtras(if (extras.isEmpty) null else extras)
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    companion object {
        const val ROOT_ID = "youtube_root"
        const val ID_HOME = "home"
        const val ID_SUBSCRIPTIONS = "subscriptions"
        private const val SUBSCRIPTIONS_MORE_PREFIX = "subscriptions:more:"
        private const val HOME_MORE_PREFIX = "home:more:"
        private const val MESSAGE_PREFIX = "message:"
        const val ID_POPULAR = "popular"
        const val ID_SHORTS = "shorts"
        const val ID_SEARCH = "search"
        const val ID_HISTORY = "history"
        const val ID_PLAYLIST = "playlist"
        const val CHANNEL_PREFIX = "channel:"
        private const val REFRESH_PREFIX = "refresh:"
        private const val LIST_STALE_MS = 5 * 60_000L
        private const val MAX_MERGED_ITEMS = 200
        const val VIDEO_PREFIX = "video:"
        private const val TAG = "YouTubeMediaBrowser"
        private const val NOTIFICATION_CHANNEL_ID = "media_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
