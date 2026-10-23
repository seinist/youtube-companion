package de.uni.youtubeaa.car

import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.Locale
import okhttp3.Request as OkHttpRequest
import okhttp3.Response as OkHttpResponse
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse

// Stream-URL-Extraktion via NewPipeExtractor (siehe Task-Brief: "NewPipeExtractor oder
// vergleichbar"). Die YouTube Data API v3 liefert nur Metadaten, keine abspielbaren
// Stream-URLs – NewPipeExtractor liest die tatsächlichen Stream-URLs von der Watch-Seite.
class StreamResolver {

    companion object {
        private const val TAG = "StreamResolver"
        private const val MAX_CACHED_EXTRACTORS = 6
        private val manifestClient by lazy { SharedHttp.builder(DataUsageStats.Subsystem.EXTRACTOR).build() }
        private const val MAX_VIDEO_CANDIDATE_LOGS = 16
        /** Signed URLs live for hours; refetch well before that so a cache hit is never stale. */
        private const val EXTRACTOR_MAX_AGE_MS = 30 * 60_000L

        private class CachedExtractor(val extractor: StreamExtractor, val fetchedAtElapsedMs: Long)

        // Video <-> Audio-only umschalten ruft für dieselbe videoId kurz hintereinander erneut auf —
        // ohne Cache holt jeder Aufruf die komplette YouTube-Watch-Seite neu (der langsamste Schritt
        // in der Auflösung), was den Moduswechsel spürbar "pausieren" lässt. Process-wide, because
        // every player has its own resolver: the native AA audio player, the projected player and
        // the phone player then share one page fetch, so "continue as video" after audio starts
        // without the 1.5-2.5 s refetch. LinkedHashMap, damit alte Einträge verdrängt werden.
        private val extractorCache = LinkedHashMap<String, CachedExtractor>()

        /** One lock per videoId: a prefetch and the real resolve share a single page fetch. */
        private val fetchLocks = HashMap<String, Any>()
        private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Warm the watch-page cache before the user taps (browse rows, next queue item). The page
         * fetch is 1.5-2.5 s of every start in head-unit logs; the official app hides it the same
         * way. Failures are ignored, the real resolve simply fetches again.
         */
        fun prefetch(context: android.content.Context, videoIds: List<String>) {
            // Data saver: on mobile data a watch page per visible row is spent before any tap.
            if (NetworkPolicy.isMeteredCached(context)) return
            for (videoId in videoIds.distinct()) {
                if (videoId.isBlank() || isCached(videoId)) continue
                prefetchScope.launch {
                    try {
                        StreamResolver().fetchExtractor(videoId)
                    } catch (e: Exception) {
                        Log.d(TAG, "prefetch failed videoId=$videoId", e)
                    }
                }
            }
        }

        private fun isCached(videoId: String): Boolean = synchronized(extractorCache) {
            val cached = extractorCache[videoId] ?: return false
            SystemClock.elapsedRealtime() - cached.fetchedAtElapsedMs < EXTRACTOR_MAX_AGE_MS
        }

        /** NewPipe reports YouTube audio ABR in kbit/s (for example 160), some services use bit/s. */
        internal fun audioBitrateBitsPerSecond(reported: Int): Int = when {
            reported <= 0 -> 0
            reported < 10_000 -> reported.toLong().times(1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else -> reported
        }
    }

    data class AudioPrefs(
        val language: String = "original",
        val bitrate: String = "auto",
        val preferHardware: Boolean = false
    )

    /** Drop cached, signed YouTube URLs before retrying after a player/source error. */
    fun invalidate(videoId: String) {
        synchronized(extractorCache) { extractorCache.remove(videoId) }
    }

    init {
        if (NewPipe.getDownloader() == null) {
            NewPipe.init(OkHttpDownloader())
        }
    }

    private fun fetchExtractor(videoId: String): StreamExtractor {
        val lock = synchronized(fetchLocks) { fetchLocks.getOrPut(videoId) { Any() } }
        return try {
            synchronized(lock) { fetchExtractorLocked(videoId) }
        } finally {
            synchronized(fetchLocks) { if (fetchLocks[videoId] === lock) fetchLocks.remove(videoId) }
        }
    }

    private fun fetchExtractorLocked(videoId: String): StreamExtractor {
        val now = SystemClock.elapsedRealtime()
        synchronized(extractorCache) {
            val cached = extractorCache[videoId]
            if (cached != null && now - cached.fetchedAtElapsedMs < EXTRACTOR_MAX_AGE_MS) return cached.extractor
            extractorCache.remove(videoId)
        }
        val extractor = ServiceList.YouTube.getStreamExtractor(
            "https://www.youtube.com/watch?v=$videoId"
        )
        extractor.fetchPage()
        HeadUnitDiagnostics.event(
            "StreamResolver",
            "page-fetched videoId=$videoId ms=${SystemClock.elapsedRealtime() - now}"
        )
        synchronized(extractorCache) {
            if (extractorCache.size >= MAX_CACHED_EXTRACTORS && !extractorCache.containsKey(videoId)) {
                extractorCache.remove(extractorCache.keys.first())
            }
            extractorCache[videoId] = CachedExtractor(extractor, now)
        }
        return extractor
    }

    suspend fun resolveAudioStreamUrl(
        videoId: String,
        audioPrefs: AudioPrefs = AudioPrefs()
    ): Result = withContext(Dispatchers.IO) {
        try {
            val extractor = fetchExtractor(videoId)
            val live = isLiveOrPostLive(extractor.streamType)

            // Only progressive HTTP audio URLs are safe as a single MediaItem.
            // DASH/HLS itag "content" URLs are not standalone files and cause SOURCE_ERROR.
            val progressiveAudio = extractor.audioStreams.filter {
                it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            }
            val durationMs = durationMsOf(extractor)
            val best = pickBestAudio(progressiveAudio, audioPrefs)
            if (best != null) {
                val streamUrl = best.content.takeIf { it.isNotBlank() }
                if (streamUrl != null) {
                    return@withContext Result.Success(
                        streamUrl = streamUrl,
                        isLive = live,
                        pureAudio = true,
                        durationMs = durationMs
                    )
                }
            }

            // Live has no progressive audio. YouTube's HLS master lists the audio as separate
            // renditions: playing that audio playlist directly loads no video at all (~1 MB/min
            // instead of the 144p variant plus audio).
            if (live) {
                liveAudioRendition(extractor, preferLow = audioPrefs.bitrate == "low")?.let { url ->
                    HeadUnitDiagnostics.event("StreamResolver", "audio videoId=$videoId source=hls-audio-rendition")
                    return@withContext Result.Success(
                        streamUrl = url,
                        isLive = true,
                        pureAudio = true,
                        durationMs = durationMs
                    )
                }
            }

            // Fallback: HLS/DASH manifest with the video track disabled (or, for HLS that turns
            // out to be truly muxed, pinned to the lowest rung).
            resolveManifestUrl(extractor, live)?.let {
                return@withContext Result.Success(
                    streamUrl = it.url,
                    isLive = live,
                    pureAudio = false,
                    durationMs = durationMs,
                    layout = it.layout
                )
            }

            Result.Error(AppText.get(R.string.stream_error_no_audio))
        } catch (e: IOException) {
            Result.Error(AppText.get(R.string.stream_error_network))
        } catch (e: ExtractionException) {
            Result.Error(AppText.get(R.string.stream_error_extraction, e.message.orEmpty()))
        } catch (e: Exception) {
            // NewPipeExtractor can throw undeclared RuntimeExceptions (NPE, JSON/regex parse
            // errors, ...) when YouTube changes page structure. Uncaught, this would crash the
            // whole process on this coroutine — including the AA projection (same process).
            Log.e(TAG, "Unexpected error while extracting the audio stream", e)
            Result.Error(AppText.get(R.string.stream_error_extraction, e.message.orEmpty()))
        }
    }

    /**
     * Resolves a playable video stream for AA projection / phone playback.
     * Prefers muxed (audio+video) streams; otherwise returns separate video (+ best audio) URLs.
     * Live streams use HLS/DASH manifests ([VideoResult.Manifest]).
     * [quality]: `auto` or an explicit vertical resolution such as `2160`, `1440`, `1080`, `720`, ….
     * [maxFps]: projected frame-rate ceiling; higher rates are used only when no stream at or below it exists.
     * [preferAdaptive]: prefer HLS/DASH after callers applied a size/FPS/codec/bitrate budget to
     *   Media3. When false or no manifest exists, progressive candidates are ranked by their
     *   advertised total bitrate.
     */
    suspend fun resolveVideoStream(
        videoId: String,
        quality: String = "auto",
        audioPrefs: AudioPrefs = AudioPrefs(),
        maxFps: Int? = null,
        excludedStreamIds: Set<String> = emptySet(),
        requireHardware: Boolean = false,
        videoBudget: CarVideoPolicy.Budget? = null,
        preferAdaptive: Boolean = false
    ): VideoResult =
        withContext(Dispatchers.IO) {
            try {
                val extractor = fetchExtractor(videoId)
                val live = isLiveOrPostLive(extractor.streamType)
                val effectiveMaxFps = videoBudget?.maxSourceFps ?: maxFps
                val maxVideoBitrate = videoBudget?.maxVideoBitrate
                    ?: DataSaverPolicy.maxVideoBitrateForQuality(quality)

                // Live / post-live almost never have usable progressive itags — use HLS/DASH first.
                if (live) {
                    resolveManifestUrl(extractor, live = true)?.let {
                        HeadUnitDiagnostics.event("StreamResolver", "video videoId=$videoId source=manifest-live layout=${it.layout} quality=$quality maxFps=$effectiveMaxFps")
                        return@withContext VideoResult.Manifest(it.url, isLive = true, layout = it.layout)
                    }
                }

                // The caller applies the bitrate and codec budget to Media3 before this source is
                // loaded. Live playback already returned above.
                if (preferAdaptive) {
                    resolveManifestUrl(extractor, live = false)?.let {
                        HeadUnitDiagnostics.event(
                            "StreamResolver",
                            "video videoId=$videoId source=manifest-adaptive layout=${it.layout} quality=$quality " +
                                "maxFps=$effectiveMaxFps maxVideoBitrate=$maxVideoBitrate"
                        )
                        return@withContext VideoResult.Manifest(it.url, isLive = false, layout = it.layout)
                    }
                }

                // getVideoStreams() = muxed A/V; getVideoOnlyStreams() = video without audio.
                // .isVideoOnly (field access) is deprecated in NewPipeExtractor; isVideoOnly() (the
                // method) is not — same value, Kotlin's property sugar just picks the wrong one.
                val muxed = preferProgressive(extractor.videoStreams.filter { it.isUrl && !it.isVideoOnly() })
                val videoOnly = preferProgressive(extractor.videoOnlyStreams.filter { it.isUrl })
                // Ignore non-URL entries here. Passing a manifest/content placeholder into
                // MergingMediaSource produces SOURCE_ERROR even though video resolution succeeded.
                val bestAudio = pickBestAudio(extractor.audioStreams.filter { it.isUrl }, audioPrefs)
                // Muxed progressive formats only ever carry YouTube's original audio track (no
                // per-language dubs). If the preference actually resolves to a dubbed/alternate
                // track, a muxed stream would silently ignore it — which is exactly what made
                // switching between video and audio-only play different languages for the same
                // video. Only trust muxed when it would match (or there's no separate-video
                // alternative at all).
                val muxedMatchesAudioPref = bestAudio == null ||
                    bestAudio.audioTrackType == null ||
                    bestAudio.audioTrackType == AudioTrackType.ORIGINAL

                data class Candidate(val stream: VideoStream, val muxed: Boolean)

                val separateAudioBitrate = bestAudio?.averageBitrate
                    ?.let(::audioBitrateBitsPerSecond) ?: 0
                fun estimatedTotalBitrate(candidate: Candidate): Int {
                    val videoBitrate = candidate.stream.bitrate.takeIf { it > 0 } ?: return 0
                    if (candidate.muxed || separateAudioBitrate <= 0) return videoBitrate
                    return videoBitrate.toLong().plus(separateAudioBitrate)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }

                // Rank muxed and video-only streams together. The old two-stage selection picked
                // a muxed stream before comparing its HW suitability with video-only itags.
                val allowMuxed = muxedMatchesAudioPref || videoOnly.isEmpty()
                val allCandidates = buildList {
                    if (allowMuxed) addAll(muxed.map { Candidate(it, muxed = true) })
                    addAll(videoOnly.map { Candidate(it, muxed = false) })
                }.filterNot { candidate -> streamKey(candidate.stream) in excludedStreamIds }

                val hwFiltered = if (requireHardware) {
                    // Extractor codec strings are only hints. The container parsed by Media3 can
                    // expose a different AVC profile/level (seen in real logs), so do not reject
                    // an itag here merely because the hint fails an exact profile/rate precheck.
                    // Require a hardware-backed codec *family* at this stage; the custom Media3
                    // renderer validates the actual Format and enforces hardware at runtime.
                    allCandidates.filter { candidate ->
                        val stream = candidate.stream
                        val family = VideoStreamChoice.familyOf(stream.codec)
                        // Size/rate information from YouTube is reliable enough to reject work
                        // the hardware cannot sustain; only profile/level is deliberately ignored
                        // because the extractor hint can differ from the parsed container Format.
                        HardwareVideoCodecs.decodes(
                            family, stream.width, stream.height,
                            videoBudget?.effectiveDecodeFps(stream.fps) ?: stream.fps
                        )
                    }
                } else {
                    allCandidates
                }

                // Treat the cap as a preference here: if YouTube exposes no known stream below
                // it, retain the candidates and let playback continue. Media3 applies the same
                // cap to adaptive manifests (soft on phone, strict on the head unit).
                val bitratePreferred = hwFiltered.filter { candidate ->
                    val bitrate = candidate.stream.bitrate
                    bitrate <= 0 || maxVideoBitrate == Int.MAX_VALUE || bitrate <= maxVideoBitrate
                }.ifEmpty { hwFiltered }

                HeadUnitDiagnostics.event(
                    "StreamResolver",
                    "video-candidates videoId=$videoId count=${hwFiltered.size} bitratePreferred=${bitratePreferred.size} quality=$quality " +
                        "maxFps=$effectiveMaxFps projectionFps=${videoBudget?.projectionFps} " +
                        "maxVideoBitrate=$maxVideoBitrate audioBitrate=$separateAudioBitrate " +
                        "speed=${videoBudget?.playbackSpeed} thermal=${videoBudget?.thermalStatus} " +
                        "requireHardware=$requireHardware excluded=${excludedStreamIds.size}"
                )
                bitratePreferred.take(MAX_VIDEO_CANDIDATE_LOGS).forEach { candidate ->
                    val stream = candidate.stream
                    val capability = hardwareCapability(stream, videoBudget)
                    val family = VideoStreamChoice.familyOf(stream.codec)
                    val effectiveDecodeFps = videoBudget?.effectiveDecodeFps(stream.fps) ?: stream.fps
                    val sizeRateHardware = HardwareVideoCodecs.decodes(
                        family, stream.width, stream.height, effectiveDecodeFps
                    )
                    HeadUnitDiagnostics.event(
                        "StreamResolver",
                        "video-candidate videoId=$videoId source=${if (candidate.muxed) "muxed" else "separate"} " +
                            "itag=${stream.itag} id=${streamKey(stream)} size=${stream.width}x${stream.height} " +
                            "fps=${stream.fps} effectiveDecodeFps=$effectiveDecodeFps videoBitrate=${stream.bitrate} " +
                            "estimatedTotalBitrate=${estimatedTotalBitrate(candidate)} codec=${stream.codec} " +
                            "delivery=${stream.deliveryMethod} hardwareSizeRate=$sizeRateHardware " +
                            "hardwareExactHint=${capability.supported} " +
                            "decoderHint=${capability.decoderName ?: "none"}"
                    )
                }

                val selected = VideoStreamChoice.pick(
                    bitratePreferred,
                    quality,
                    widthOf = { it.stream.width },
                    heightOf = { it.stream.height },
                    codecOf = { it.stream.codec },
                    fpsOf = { it.stream.fps },
                    hardwareDecodes = { family, codec, width, height, fps ->
                        HardwareVideoCodecs.decodes(
                            family, codec, width, height,
                            videoBudget?.effectiveDecodeFps(fps) ?: fps
                        )
                    },
                    hardwareLikelyDecodes = { family, width, height, fps ->
                        HardwareVideoCodecs.decodes(
                            family, width, height,
                            videoBudget?.effectiveDecodeFps(fps) ?: fps
                        )
                    },
                    maxFps = effectiveMaxFps,
                    // Source type is deliberately late in the score: HW capability and requested
                    // quality beat the convenience of a muxed stream.
                    sourceCostOf = { if (it.muxed) 0 else 1 },
                    bitrateOf = { estimatedTotalBitrate(it) },
                    withinBudgetOf = videoBudget?.let { budget ->
                        { candidate -> budget.fits(candidate.stream.width, candidate.stream.height) }
                    }
                )

                if (selected != null && selected.muxed) {
                    val selectedMuxed = selected.stream
                    val totalBitrate = estimatedTotalBitrate(selected)
                    val url = selectedMuxed.content.takeIf { it.isNotBlank() }
                        ?: return@withContext VideoResult.Error(AppText.get(R.string.stream_error_muxed_url_missing))
                    logVideoSelection(
                        videoId, "muxed", selectedMuxed, quality, effectiveMaxFps,
                        maxVideoBitrate, totalBitrate, videoBudget
                    )
                    return@withContext VideoResult.Muxed(
                        url,
                        selectedMuxed.height,
                        streamInfo(selectedMuxed, "muxed", totalBitrate)
                    )
                }

                if (selected != null) {
                    val selectedVideo = selected.stream
                    val totalBitrate = estimatedTotalBitrate(selected)
                    val videoUrl = selectedVideo.content.takeIf { it.isNotBlank() }
                        ?: return@withContext VideoResult.Error(AppText.get(R.string.stream_error_video_url_missing))
                    val audioUrl = bestAudio?.content?.takeIf { it.isNotBlank() }
                    logVideoSelection(
                        videoId, "separate", selectedVideo, quality, effectiveMaxFps,
                        maxVideoBitrate, totalBitrate, videoBudget
                    )
                    return@withContext VideoResult.Separate(
                        videoUrl,
                        audioUrl,
                        selectedVideo.height,
                        streamInfo(selectedVideo, "separate", totalBitrate)
                    )
                }

                // If no progressive candidate survives (including after exhausting all HW itags),
                // try the adaptive manifest before accepting software. Media3 can inspect the real
                // manifest Formats and select a HW-safe adaptive track set there.
                resolveManifestUrl(extractor, live)?.let {
                    HeadUnitDiagnostics.event(
                        "StreamResolver",
                        "video videoId=$videoId source=manifest-fallback layout=${it.layout} quality=$quality maxFps=$effectiveMaxFps " +
                            "afterHardwareExhaustion=$requireHardware"
                    )
                    return@withContext VideoResult.Manifest(it.url, isLive = live, layout = it.layout)
                }

                if (requireHardware) {
                    HeadUnitDiagnostics.warning(
                        "StreamResolver",
                        "video videoId=$videoId no-hardware-candidate quality=$quality " +
                            "excluded=${excludedStreamIds.joinToString()} manifest=false"
                    )
                    return@withContext VideoResult.Error(AppText.get(R.string.stream_error_no_hardware_video))
                }

                VideoResult.Error(AppText.get(R.string.stream_error_no_video))
            } catch (e: IOException) {
                VideoResult.Error(AppText.get(R.string.stream_error_network))
            } catch (e: ExtractionException) {
                VideoResult.Error(AppText.get(R.string.stream_error_extraction, e.message.orEmpty()))
            } catch (e: Exception) {
                // See resolveAudioStreamUrl: NewPipeExtractor isn't guaranteed to wrap every
                // failure in ExtractionException; an uncaught throw here crashes the shared
                // process (phone UI and AA projection both go down together).
                Log.e(TAG, "Unexpected error while extracting the video stream", e)
                VideoResult.Error(AppText.get(R.string.stream_error_extraction, e.message.orEmpty()))
            }
        }

    private fun logVideoSelection(
        videoId: String,
        source: String,
        stream: VideoStream,
        quality: String,
        maxFps: Int?,
        maxVideoBitrate: Int,
        estimatedTotalBitrate: Int,
        videoBudget: CarVideoPolicy.Budget?
    ) {
        val capability = hardwareCapability(stream, videoBudget)
        val family = VideoStreamChoice.familyOf(stream.codec)
        val effectiveDecodeFps = videoBudget?.effectiveDecodeFps(stream.fps) ?: stream.fps
        val sizeRateHardware = HardwareVideoCodecs.decodes(
            family, stream.width, stream.height, effectiveDecodeFps
        )
        HeadUnitDiagnostics.event(
            "StreamResolver",
            "video-selected videoId=$videoId source=$source itag=${stream.itag} id=${streamKey(stream)} " +
                "client=${streamClient(stream.content)} " +
                "quality=$quality effectiveQuality=${videoBudget?.effectiveQuality ?: quality} " +
                "size=${stream.width}x${stream.height} fps=${stream.fps} effectiveDecodeFps=$effectiveDecodeFps " +
                "videoBitrate=${stream.bitrate} estimatedTotalBitrate=$estimatedTotalBitrate " +
                "maxVideoBitrate=$maxVideoBitrate codec=${stream.codec} delivery=${stream.deliveryMethod} " +
                "hardwareSizeRate=$sizeRateHardware hardwareExactHint=${capability.supported} " +
                "decoderHint=${capability.decoderName ?: "none"} maxFps=$maxFps"
        )
    }

    /** InnerTube client that issued a googlevideo URL (`c=` parameter), for diagnostics. */
    private fun streamClient(url: String?): String =
        url?.let { Regex("[?&]c=([A-Z_]+)").find(it)?.groupValues?.get(1) } ?: "?"

    private fun hardwareCapability(
        stream: VideoStream,
        videoBudget: CarVideoPolicy.Budget? = null
    ): HardwareVideoCodecs.Capability = HardwareVideoCodecs.capability(
        VideoStreamChoice.familyOf(stream.codec),
        stream.codec,
        stream.width,
        stream.height,
        videoBudget?.effectiveDecodeFps(stream.fps) ?: stream.fps
    )

    private fun streamKey(stream: VideoStream): String =
        stream.id.takeIf { it.isNotBlank() } ?: stream.itag.toString()

    private fun streamInfo(stream: VideoStream, source: String, estimatedTotalBitrate: Int): VideoResult.StreamInfo {
        val capability = hardwareCapability(stream)
        return VideoResult.StreamInfo(
            id = streamKey(stream),
            itag = stream.itag,
            source = source,
            codecHint = stream.codec,
            width = stream.width,
            height = stream.height,
            fps = stream.fps,
            bitrate = stream.bitrate,
            estimatedTotalBitrate = estimatedTotalBitrate,
            hardwareHint = capability.supported,
            decoderHint = capability.decoderName
        )
    }


    private class ManifestUrl(val url: String, val layout: SourceLayout)

    /**
     * Live: HLS first (best ExoPlayer support for YT live), then DASH.
     * VOD: DASH when offered, else HLS. NewPipe rarely offers DASH for YouTube VOD, so this is
     * normally HLS. YouTube's HLS keeps audio in separate `EXT-X-MEDIA` renditions (video-only
     * variants), but that is only confirmed once Media3 has loaded the multivariant playlist:
     * HLS starts as [SourceLayout.HLS_PENDING] and [PlayerFactory.effectiveLayout] refines it.
     */
    private fun resolveManifestUrl(extractor: StreamExtractor, live: Boolean): ManifestUrl? {
        val hls = runCatching { extractor.hlsUrl }.getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { ManifestUrl(it, SourceLayout.HLS_PENDING) }
        val dash = runCatching { extractor.dashMpdUrl }.getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { ManifestUrl(it, SourceLayout.SEPARATE) }
        return if (live) hls ?: dash else dash ?: hls
    }

    /**
     * Audio-rendition playlist from the live HLS master (one small gzip'd request). Null when the
     * master cannot be read or lists no audio rendition; the caller falls back to the manifest.
     */
    private fun liveAudioRendition(extractor: StreamExtractor, preferLow: Boolean): String? {
        val masterUrl = runCatching { extractor.hlsUrl }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val request = OkHttpRequest.Builder().url(masterUrl).apply {
                PlaybackSources.requestHeaders(masterUrl).forEach { (key, value) -> header(key, value) }
            }.build()
            manifestClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                HlsLayout.pickAudioRendition(response.body.string(), masterUrl, preferLow)?.uri
            }
        }.onFailure { Log.w(TAG, "live audio rendition lookup failed", it) }.getOrNull()
    }

    /** NewPipe length is seconds; 0 / unknown → 0. */
    private fun durationMsOf(extractor: StreamExtractor): Long {
        val seconds = runCatching { extractor.length }.getOrNull() ?: return 0L
        return if (seconds > 0L) seconds * 1000L else 0L
    }

    private fun isLiveOrPostLive(type: StreamType): Boolean =
        type == StreamType.LIVE_STREAM ||
            type == StreamType.AUDIO_LIVE_STREAM ||
            type == StreamType.POST_LIVE_STREAM ||
            type == StreamType.POST_LIVE_AUDIO_STREAM

    /**
     * Prefer configured language / ORIGINAL, then bitrate preference.
     * Picking only by bitrate often selects an English dub on multilingual uploads.
     */
    internal fun pickBestAudio(
        streams: List<AudioStream>,
        prefs: AudioPrefs = AudioPrefs()
    ): AudioStream? {
        val preferred = preferProgressive(streams.filter { it.isUrl })
        if (preferred.isEmpty()) return null

        val langMode = prefs.language.lowercase(Locale.ROOT)
        val original = preferred.filter {
            it.audioTrackType == AudioTrackType.ORIGINAL || it.audioTrackType == null
        }
        val langFiltered = when (langMode) {
            // The default must never follow the device locale into a dubbed track. Keep "auto"
            // as a backwards-compatible alias for installs that already stored that old value.
            "original", "auto" -> original.ifEmpty { preferred }
            "device" -> {
                val matching = preferred.filter { it.matchesLanguage(preferredLanguageTags()) }
                matching.ifEmpty { original.ifEmpty { preferred } }
            }
            else -> {
                val tags = listOf(langMode)
                preferred.filter { it.matchesLanguage(tags) }.ifEmpty { original.ifEmpty { preferred } }
            }
        }

        val hardwarePreferred = if (prefs.preferHardware) {
            val hw = langFiltered.filter { stream ->
                HardwareAudioCodecs.decodes(audioDecoderMime(stream))
            }
            hw.ifEmpty { langFiltered }
        } else {
            langFiltered
        }
        val selected = pickByBitrate(hardwarePreferred, prefs.bitrate)
        if (prefs.preferHardware && selected != null) {
            val mime = audioDecoderMime(selected)
            HeadUnitDiagnostics.event(
                "StreamResolver",
                "audio-selected itag=${selected.itag} client=${streamClient(selected.content)} codec=${selected.codec} format=${selected.format?.mimeType} " +
                    "decoderMime=$mime hardware=${HardwareAudioCodecs.decodes(mime)} " +
                        "bitrateRaw=${selected.averageBitrate} bitrateBps=${audioBitrateBitsPerSecond(selected.averageBitrate)}"
            )
        }
        return selected
    }

    private fun audioDecoderMime(stream: AudioStream): String? =
        HardwareAudioCodecs.decoderMime(stream.codec, stream.format?.mimeType)

    private fun AudioStream.matchesLanguage(tags: List<String>): Boolean {
        val locale = audioLocale ?: return false
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.isBlank()) return false
        val tag = locale.toLanguageTag().lowercase(Locale.ROOT)
        return tags.any { pref ->
            tag == pref || language == pref || tag.startsWith("$pref-") || pref.startsWith("$language-")
        }
    }

    private fun pickByBitrate(streams: List<AudioStream>, bitrate: String): AudioStream? {
        if (streams.isEmpty()) return null
        // Prefer stable AAC (~128–160 kbps itag 140) over very low Opus when "auto":
        // tiny streams underrun more easily on flaky mobile networks.
        val sorted = streams.sortedBy { audioBitrateBitsPerSecond(it.averageBitrate) }
        return when (bitrate.lowercase(Locale.ROOT)) {
            "low" -> sorted.firstOrNull()
            "medium" -> sorted.getOrNull(sorted.size / 2) ?: sorted.lastOrNull()
            "high" -> sorted.lastOrNull()
            else -> {
                streams
                    .filter { audioBitrateBitsPerSecond(it.averageBitrate) in 96_000..192_000 }
                    .maxByOrNull { audioBitrateBitsPerSecond(it.averageBitrate) }
                    ?: streams.maxByOrNull { audioBitrateBitsPerSecond(it.averageBitrate) }
            }
        }
    }

    /** Prefer progressive HTTP; fall back to all URLs if YouTube only offers adaptive. */
    private fun <T : Stream> preferProgressive(streams: List<T>): List<T> {
        val progressive = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        return progressive.ifEmpty { streams }
    }

    private fun preferredLanguageTags(): List<String> {
        val tags = linkedSetOf<String>()
        val list = LocaleList.getDefault()
        for (i in 0 until list.size()) {
            val locale = list[i]
            val language = locale.language.lowercase(Locale.ROOT)
            if (language.isNotBlank()) tags += language
            tags += locale.toLanguageTag().lowercase(Locale.ROOT)
        }
        val fallback = Locale.getDefault()
        val language = fallback.language.lowercase(Locale.ROOT)
        if (language.isNotBlank()) tags += language
        tags += fallback.toLanguageTag().lowercase(Locale.ROOT)
        return tags.toList()
    }

    sealed class Result {
        /**
         * @param pureAudio true if URL is audio-only; false if A/V manifest (disable video tracks).
         * @param durationMs known content length from metadata (0 if unknown/live).
         */
        data class Success(
            val streamUrl: String,
            val isLive: Boolean = false,
            val pureAudio: Boolean = true,
            val durationMs: Long = 0L,
            /** How audio and video are packaged in [streamUrl]; drives the audio-only policy. */
            val layout: SourceLayout = if (pureAudio) SourceLayout.AUDIO_ONLY else SourceLayout.HLS_PENDING
        ) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class VideoResult {
        data class StreamInfo(
            val id: String,
            val itag: Int,
            val source: String,
            val codecHint: String?,
            val width: Int,
            val height: Int,
            val fps: Int,
            val bitrate: Int,
            val estimatedTotalBitrate: Int,
            val hardwareHint: Boolean,
            val decoderHint: String?
        )

        data class Muxed(
            val url: String,
            val height: Int,
            val info: StreamInfo? = null
        ) : VideoResult()
        data class Separate(
            val videoUrl: String,
            val audioUrl: String?,
            val height: Int,
            val info: StreamInfo? = null
        ) : VideoResult()
        /** HLS or DASH manifest (typical for live / some VODs). */
        data class Manifest(
            val url: String,
            val isLive: Boolean,
            val layout: SourceLayout = SourceLayout.HLS_PENDING
        ) : VideoResult()
        data class Error(val message: String) : VideoResult()
    }

    // Bindet NewPipeExtractors HTTP-Abstraktion (Downloader) an OkHttp, das im Projekt
    // bereits als Abhängigkeit vorhanden ist.
    private class OkHttpDownloader : Downloader() {
        private val client = SharedHttp.builder().build()

        override fun execute(request: NewPipeRequest): NewPipeResponse {
            val builder = OkHttpRequest.Builder().url(request.url())
            request.headers().forEach { (key, values) ->
                values.forEach { value -> builder.addHeader(key, value) }
            }

            when (val method = request.httpMethod()) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                else -> {
                    // Content-Type wird bereits über request.headers() gesetzt (siehe oben).
                    val body = (request.dataToSend() ?: ByteArray(0)).toRequestBody(null)
                    builder.method(method, body)
                }
            }

            val response: OkHttpResponse = client.newCall(builder.build()).execute()
            response.use {
                val responseBody = it.body?.string() ?: ""
                return NewPipeResponse(
                    it.code,
                    it.message,
                    it.headers.toMultimap(),
                    responseBody,
                    it.request.url.toString()
                )
            }
        }
    }
}
