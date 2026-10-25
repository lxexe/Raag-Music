package com.raagmusic.app.playback

import kotlinx.coroutines.CancellationException
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.raagmusic.app.data.youtube.toLibraryTrack
import androidx.media3.common.MediaMetadata
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.SettableFuture
import com.raagmusic.app.domain.*
import android.net.ConnectivityManager
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.raagmusic.app.RaagApplication
import com.raagmusic.app.MainActivity
import com.raagmusic.app.data.youtube.YoutubeTrackIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlaybackService : MediaLibraryService() {
    companion object {
        const val EXIT_APP = "com.raagmusic.app.EXIT_APP"
        const val RETRY_PLAYBACK = "com.raagmusic.app.RETRY_PLAYBACK"
    }
    private var session: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recoveryPolicy = PlaybackRecoveryPolicy(maxRetries = 2, baseDelayMillis = 350)
    private var warmJob: Job? = null
    private var warmReference: String? = null
    private var warmLimit = -1
    private var warmBytesReference: String? = null
    private var futureWarmJob: Job? = null
    private var futureReference: String? = null
    private var queuePreloader: QueuePreloader? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val networkRecovery = NetworkRecoveryGate()
    private fun suspendPreparation() {
        warmJob?.cancel(); warmJob = null; warmReference = null; warmBytesReference = null
        futureWarmJob?.cancel(); futureWarmJob = null; futureReference = null
        streamResolver?.setPreloading(null)
        exoPlayer?.let { player -> queuePreloader?.retain((0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).localConfiguration?.uri?.toString()
        }.toSet(), player.currentMediaItem?.localConfiguration?.uri?.toString()) }
    }
    private fun warmNext() {
        val player = exoPlayer ?: return
        if (preferences.videoMode == com.raagmusic.app.domain.VideoPlaybackMode.ALWAYS || player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }) {
            warmVideoNext(player); return
        }
        val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
        // Resolve and parse the next source as soon as the current song has a small safety buffer.
        // It is the exact source that ExoPlayer will consume, not a discarded second player.
        val upcoming = player.upcomingQueueIndices().firstOrNull()?.let { player.getMediaItemAt(it).localConfiguration?.uri?.toString() }
        val keepReady = player.playbackState == Player.STATE_BUFFERING && upcoming != null &&
            upcoming == warmReference && queuePreloader?.isReady(upcoming) == true
        val reference = upcoming?.takeIf { player.playWhenReady && (player.playbackState == Player.STATE_READY || keepReady) }
        val qualityLimit = limit()
        if (reference == warmReference && qualityLimit == warmLimit) {
            if (reference != null && queuePreloader?.isReady(reference) == true) warmFurther(reference)
            return
        }
        futureWarmJob?.cancel(); futureWarmJob = null; futureReference = null
        if (warmReference != current || warmBytesReference == current) warmJob?.cancel()
        if (qualityLimit != warmLimit && reference != current) reference?.let { streamResolver?.invalidate(it) }
        warmReference = reference
        warmLimit = qualityLimit
        queuePreloader?.retain((0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).localConfiguration?.uri?.toString()
        }.toSet(), current)
        streamResolver?.setPreloading(reference)
        if (reference == null) return
        warmJob = serviceScope.launch {
            try {
                while (warmReference == reference && player.playWhenReady && player.playbackState != Player.STATE_READY) delay(25)
                if (warmReference != reference || !player.playWhenReady) return@launch
                streamResolver?.warm(reference)
                fun safetyBuffer(): Long = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
                    ?.let { (it - player.currentPosition).coerceIn(0, 2_000) } ?: 2_000
                while (warmReference == reference && player.playWhenReady &&
                    (player.playbackState != Player.STATE_READY || player.bufferedPosition - player.currentPosition < safetyBuffer())) delay(50)
                if (warmReference != reference || !player.playWhenReady) return@launch
                // Bound wire read-ahead before parsing. Finite whole-file CDN responses can
                // otherwise fill a socket well beyond the 1.5-second sample-queue target.
                warmBytesReference = reference
                try {
                    val metered = getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
                    app.media.prefetch(androidx.media3.datasource.DataSpec(android.net.Uri.parse(reference)), metered,
                        streamResolver, maxBytes = if (metered) 64 * 1024L else 128 * 1024L)
                } finally { if (warmBytesReference == reference) warmBytesReference = null }
                if (warmReference == reference && player.playWhenReady) queuePreloader?.preload(reference)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Foreground resolution remains retryable. */ }
        }
    }
    /** Video preparation resolves only the next combined stream; no second decoder or audio fetch. */
    private fun warmVideoNext(player: ExoPlayer) {
        val next = player.upcomingQueueIndices().firstOrNull()?.let { player.getMediaItemAt(it).localConfiguration?.uri?.toString() }
            ?.takeIf { player.playWhenReady && player.playbackState == Player.STATE_READY }
        if (warmReference == next) return
        suspendPreparation()
        warmReference = next
        if (next == null) return
        warmJob = serviceScope.launch {
            try {
                delay(500)
                if (warmReference == next && player.playWhenReady) streamResolver?.warm(next)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Foreground playback resolves again. */ }
        }
    }
    /** One extra descriptor, no extra source/decoder/bytes, only with a healthy unmetered buffer. */
    private fun warmFurther(reference: String) {
        val player = exoPlayer ?: return
        val power = getSystemService(android.os.PowerManager::class.java)
        if (!player.isPlaying || reference != warmReference ||
            getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered || power.isPowerSaveMode ||
            player.bufferedPosition - player.currentPosition < 10_000) {
            futureWarmJob?.cancel(); futureWarmJob = null; futureReference = null
            return
        }
        val upcoming = player.upcomingQueueIndices()
        val immediate = upcoming.firstOrNull()?.let(player::getMediaItemAt) ?: return
        if (immediate.localConfiguration?.uri?.toString() != reference) return
        val future = upcoming.getOrNull(1)?.let(player::getMediaItemAt)
        val next = future?.localConfiguration?.uri?.toString()
        if (next != futureReference) { futureWarmJob?.cancel(); futureWarmJob = null; futureReference = null }
        if (future == null || next == null) return
        val currentDuration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        val nextDuration = immediate.mediaMetadata.extras?.getLong("durationSeconds")?.takeIf { it > 0 }?.times(1_000) ?: return
        val horizon = currentDuration - player.currentPosition + nextDuration + 30_000
        if (horizon >= PreparedAudioStreams.WARM_TTL_MS || next == futureReference) return
        futureWarmJob?.cancel(); futureReference = next
        futureWarmJob = serviceScope.launch {
            try {
                streamResolver?.warm(next, horizon)
                PlaybackTrace.mark(future.mediaId, "future_descriptor_ready")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Optional prediction never changes foreground playback. */ }
        }
    }
    private var recoveryJob: Job? = null
    private var exoPlayer: ExoPlayer? = null
    private var streamResolver: YoutubeStreamResolver? = null
    private var crossfade: CrossfadeController? = null
    private var recommendationQueue: RecommendationQueue? = null
    private var equalizer: PlaybackEqualizer? = null
    private var incomingEqualizer: PlaybackEqualizer? = null
    private fun extras(block: android.os.Bundle.() -> Unit) {
        session?.let { it.setSessionExtras(android.os.Bundle(it.sessionExtras).apply(block)) }
    }

    private lateinit var app: RaagApplication
    @Volatile private var preferences = ListeningPreferences()
    private var sleepTimer = RunningSleepTimer(SleepTimerSpec(), 0, 0)
    private var completingSleepTimer = false
    private fun configureSleepTimer(spec: SleepTimerSpec) {
        if (sleepTimer.spec == spec) return
        sleepTimer = RunningSleepTimer(spec, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        completingSleepTimer = false
        updateSleepTimer()
    }
    private fun finishSleepTimer(pause: Boolean = true) {
        val spec = sleepTimer.spec
        if (!spec.active || completingSleepTimer) return
        completingSleepTimer = true
        if (pause) exoPlayer?.pause()
        extras { putLong("sleepRemainingMs", 0); putBoolean("sleepEndOfTrack", false); putLong("sleepDeadlineEpochMs", 0) }
        save { app.features.update { if (it.sleepTimer == spec) it.copy(sleepTimer = SleepTimerSpec()) else it } }
    }
    private fun updateSleepTimer() {
        val spec = sleepTimer.spec
        if (!spec.active || completingSleepTimer) return
        val remaining = if (spec.finishTrackId != null) {
            val p = exoPlayer
            if (p == null || p.duration == C.TIME_UNSET) 0 else (p.duration - p.currentPosition).coerceAtLeast(0)
        } else sleepTimer.remainingMs(SystemClock.elapsedRealtime())
        extras { putLong("sleepRemainingMs", remaining); putBoolean("sleepEndOfTrack", spec.finishTrackId != null); putLong("sleepDeadlineEpochMs", spec.deadlineEpochMs) }
        if (spec.finishTrackId == null && remaining == 0L) finishSleepTimer()
    }
    private var ready = false
    private val listeningSession = ListeningSession()
    private var counted = false
    private var lastTick = SystemClock.elapsedRealtime()
    private var wasPlaying = false
    @Volatile private var observedId: String? = null
    private var accumulatedMs = 0L
    private var lastSnapshot: QueueSnapshot? = null
    private var historyEpoch = 0L
    private var requestedCollectionToken: String? = null

    private fun save(block: suspend () -> Unit) {
        app.persistenceScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                lastSnapshot = null // A later sample retries persistence without crashing playback.
                android.util.Log.w("RaagPlayback", "Couldn't persist playback state")
            }
        }
    }

    private fun record(id: String, ms: Long = 0, plays: Int = 0, skips: Int = 0, completions: Int = 0) {
        val epoch = historyEpoch
        val day = LocalDate.now()
        save { app.features.update { if (it.historyEpoch == epoch) it.record(id, day, ms, plays, skips, completions) else it } }
    }

    private fun limit(): Int {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val wifi = !connectivity.isActiveNetworkMetered
        return (if (wifi) preferences.wifiQuality else preferences.mobileQuality).maxBitrate
    }
    private fun track(item: MediaItem): Track? {
        val metadata = item.mediaMetadata
        val uri = item.localConfiguration?.uri ?: return null
        val source = if (uri.scheme == YoutubeStreamResolver.SCHEME) Source.YOUTUBE else if (uri.scheme == "content") Source.LOCAL else Source.DIRECT
        return Track(item.mediaId, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
            metadata.artworkUri?.toString().orEmpty(), if (source == Source.YOUTUBE) item.mediaId else uri.toString(), source,
            mood = metadata.extras?.getString("mood").orEmpty(),
            durationSeconds = metadata.extras?.getLong("durationSeconds") ?: 0,
            album = metadata.albumTitle?.toString().orEmpty(), genre = metadata.genre?.toString().orEmpty())
    }
    private fun sample() {
        val player = exoPlayer ?: return
        val now = SystemClock.elapsedRealtime()
        if (wasPlaying && observedId != null) {
            val heard = (now - lastTick).coerceIn(0, 10_000)
            listeningSession.heard(observedId, heard)
            if (preferences.analyticsEnabled) accumulatedMs += heard
        }
        lastTick = now
        wasPlaying = player.isPlaying
        val id = player.currentMediaItem?.mediaId
        if (observedId != id) { flushListening(); observedId = id }
    }
    private fun flushListening() {
        val id = observedId ?: return
        val ms = accumulatedMs
        accumulatedMs = 0
        if (ms > 0) record(id, ms)
    }
    private fun persist() {
        if (!ready) return
        val player = exoPlayer ?: return
        val snapshot = if (preferences.rememberQueue) QueueSnapshot(
            List(player.mediaItemCount) { track(player.getMediaItemAt(it)) }.filterNotNull(),
            player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0),
            player.shuffleModeEnabled, player.repeatMode, recommendationQueue?.enabled == true).sanitized() else QueueSnapshot()
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            save { app.features.update { it.copy(queue = snapshot) } }
        }
    }
    private fun <T> result(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try { future.set(block()) } catch (e: Exception) { future.setException(e) }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        return future
    }

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) { equalizer?.attach(audioSessionId) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newListen = recoveryPolicy.onTrackTransition(mediaItem?.mediaId, reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            val finishId = sleepTimer.spec.finishTrackId
            if (finishId != null) {
                val completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                if (completed || mediaItem?.mediaId != finishId) finishSleepTimer(pause = completed)
            }
            sample()
            flushListening()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                listeningSession.complete()?.let { record(it, completions = 1) }
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) listeningSession.earlySkipCurrent()?.let { record(it, skips = 1) }
            listeningSession.transition(mediaItem?.mediaId, reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            if (newListen) counted = false
            observedId = mediaItem?.mediaId
            recoveryJob?.cancel()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) recommendationQueue?.cancelPendingAdvance()
            sample()
            flushListening()
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId &&
                newPosition.positionMs < 1_000 && listeningSession.restartCompleted(newPosition.mediaItem?.mediaId)) counted = false
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId &&
                listeningSession.earlySkip(oldPosition.mediaItem?.mediaId)) {
                oldPosition.mediaItem?.mediaId?.let { id -> record(id, skips = 1) }
            }
        }
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playerError == null && session?.sessionExtras?.containsKey("failureKind") == true) extras {
                remove("failureKind"); remove("failureTitle"); remove("failureMessage"); remove("failureRetryAt"); remove("failureTrackId")
            }
            // Fade volume ticks need neither library writes nor widget broadcasts.
            if (events.size() == 1 && events.contains(Player.EVENT_VOLUME_CHANGED)) return
            if (sleepTimer.spec.finishTrackId != null && player.playbackState == Player.STATE_ENDED) finishSleepTimer()
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !player.playWhenReady) recommendationQueue?.cancelPendingAdvance()
            recommendationQueue?.sync()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queuePreloader?.prune(
                (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.toString() }.toSet())
            if (events.contains(Player.EVENT_TRACKS_CHANGED) && player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }) {
                crossfade?.configure(preferences.copy(transitionMode = TransitionMode.GAPLESS))
            }
            warmNext()
            sample()
            listeningSession.duration(player.currentMediaItem?.mediaId, player.duration)
            if (player.playbackState == Player.STATE_ENDED) listeningSession.complete()?.let { record(it, completions = 1) }
            if (player.isPlaying) player.currentMediaItem?.mediaId?.let(listeningSession::started)
            if (player.isPlaying && !counted) {
                counted = true
                player.currentMediaItem?.let { item ->
                    track(item)?.let { t -> save { app.library.update { library -> library.copy(
                        savedTracks = rememberTrackMetadata(library.savedTracks, listOf(t)),
                        recentIds = if (preferences.collectHistory) (listOf(t.id) + library.recentIds).distinct().take(100) else library.recentIds) } } }
                    record(item.mediaId, plays = 1)
                }
            }
            flushListening()
            persist()
            com.raagmusic.app.widget.RaagWidget.update(this@PlaybackService, player.currentMediaItem?.mediaMetadata?.title?.toString(),
                player.playWhenReady && player.playerError == null && player.playbackState != Player.STATE_IDLE,
                player.currentMediaItem?.mediaMetadata?.artist?.toString(), player.currentMediaItem?.mediaMetadata?.artworkUri?.toString())
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = exoPlayer ?: return
            val failure = playbackFailure(error).let {
                if (error.httpResponseCode() == 429) it.copy(retryAtEpochMillis = app.youtube.onPlaybackRateLimited()) else it
            }
            extras {
                putString("failureTrackId", player.currentMediaItem?.mediaId)
                putString("failureKind", failure.kind.name); putString("failureTitle", failure.title)
                putString("failureMessage", failure.message); putLong("failureRetryAt", failure.retryAtEpochMillis ?: 0)
            }
            recoveryJob?.cancel()
            listeningSession.failed(player.currentMediaItem?.mediaId)
            suspendPreparation()
            streamResolver?.invalidate(player.currentMediaItem?.localConfiguration?.uri?.toString(), rejected = error.httpResponseCode() == 403)
            val retryable = error.isRecoverableNetworkError()
            val retryDelay = recoveryPolicy.nextDelayMillis(retryable) ?: run {
                PlaybackTrace.mark(player.currentMediaItem?.mediaId.orEmpty(), if (retryable) "recovery_budget_exhausted" else "permanent_playback_error")
                return
            }
            val mediaId = player.currentMediaItem?.mediaId ?: return
            val reference = player.currentMediaItem?.localConfiguration?.uri
            val position = player.currentPosition.coerceAtLeast(0)
            val shouldResume = player.playWhenReady
            recoveryJob = serviceScope.launch {
                delay(retryDelay)
                // Navigation or Pause while waiting supersedes the recovery attempt.
                if (player.currentMediaItem?.mediaId != mediaId || player.currentMediaItem?.localConfiguration?.uri != reference ||
                    !shouldResume || !player.playWhenReady) return@launch
                restartCurrentSource(player, position)
                player.playWhenReady = true
            }
        }
    }

    /** Discard failed extractor/sample state as well as the URL lease, preserving queue and seek. */
    private fun restartCurrentSource(player: ExoPlayer, position: Long) {
        val item = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        if (item.localConfiguration?.uri?.scheme == YoutubeStreamResolver.SCHEME) {
            player.replaceMediaItem(index, item.buildUpon().setUri(YoutubeStreamResolver.mediaUri(item.mediaId)).build())
        }
        player.seekTo(index, position.coerceAtLeast(0))
        player.prepare()
    }

    override fun onCreate() {
        super.onCreate()
        app = application as RaagApplication
        val resolver = YoutubeStreamResolver(app.youtube, ::limit, app.media, onResolved = { id, bitrate ->
            serviceScope.launch {
                if (exoPlayer?.currentMediaItem?.mediaId == id) extras {
                    putString("bitrateTrackId", id); putInt("bitrate", bitrate ?: 0)
                }
            }
        }, videoMode = { preferences.videoMode }, videoQuality = { preferences.videoQuality }).also { streamResolver = it }
        val dataSource = ResolvingDataSource.Factory(app.media.playbackFactory(resolver), resolver)
        val tracedSource = androidx.media3.datasource.DataSource.Factory { TracedDataSource(dataSource.createDataSource()) }
        val mediaSources = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(tracedSource)
            .setContinueLoadingCheckIntervalBytes(32 * 1024)
            // Retries are owned here so a permanent 403 cannot trigger two competing retry loops.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
        val renderers = VisualizationRenderers(this) { stage -> observedId?.let { PlaybackTrace.mark(it, stage) } }
        val preloader = QueuePreloader(this, mediaSources, renderers) { reference ->
            serviceScope.launch { warmFurther(reference) }
        }.also { queuePreloader = it }
        val loadControl = DefaultLoadControl.Builder().setAllocator(preloader.allocator)
            .setBufferDurationsMs(5_000, 20_000, 250, 750)
            .setBackBuffer(2_000, false).setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false).build()
        val player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(preloader)
            .setPlaybackLooper(preloader.looper).setBandwidthMeter(preloader.bandwidth)
            .setLoadControl(loadControl).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(listener)
            addAnalyticsListener(PlaybackAnalytics())
        }
        exoPlayer = player
        val connectivity = getSystemService(ConnectivityManager::class.java)
        var validatedNetwork = connectivity.activeNetwork?.takeIf { network ->
            connectivity.getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }?.networkHandle
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                serviceScope.launch { networkRecovery.lost(network.networkHandle); warmNext() }
            }
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                if (!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                serviceScope.launch {
                    val changed = validatedNetwork != network.networkHandle
                    if (changed) { validatedNetwork = network.networkHandle; app.youtube.onConnectionChanged() }
                    val error = player.playerError
                    val restrictedConnection = error?.let { playbackFailure(it).kind in setOf(PlaybackFailureKind.VERIFICATION, PlaybackFailureKind.RATE_LIMIT) } == true
                    val failedConnection = error?.hasNetworkCause() == true || error?.errorCode in setOf(
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
                    if (networkRecovery.validated(network.networkHandle, player.playWhenReady, failedConnection || (changed && restrictedConnection))) {
                        recoveryJob?.cancel(); recoveryPolicy.reset()
                        PlaybackTrace.mark(player.currentMediaItem?.mediaId.orEmpty(), "connection_restored_retry")
                        player.prepare()
                    }
                    warmNext()
                }
            }
        }.also { connectivity.registerDefaultNetworkCallback(it) }
        recommendationQueue = RecommendationQueue(player, serviceScope, related = { item ->
            val seed = track(item) ?: throw IllegalStateException("Song metadata is unavailable.")
            app.recommendations.next(seed, app.library.state.first(), app.features.state.first(),
                excluded = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet())
        }, remember = { batch -> save { app.library.update { state ->
            state.copy(savedTracks = rememberTrackMetadata(state.savedTracks, batch))
        } } }, status = { enabled, loading, error -> extras {
            putBoolean(RECOMMENDATION_CHAIN, enabled); putBoolean("recommendationLoading", loading); putString("recommendationError", error)
        } })
        equalizer = PlaybackEqualizer { available, status -> extras { putBoolean("eqAvailable", available); putString("eqStatus", status) } }
        equalizer?.attach(player.audioSessionId)
        crossfade = CrossfadeController(player, serviceScope, {
            // Both decoders use the exact queued play's preparation and format cache key.
            // Two active leases keep the outgoing seek and incoming handoff independent.
            val nextResolver = resolver
            val sources = DefaultMediaSourceFactory(ResolvingDataSource.Factory(app.media.playbackFactory(nextResolver), nextResolver))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            incomingEqualizer?.release()
            val effect = PlaybackEqualizer().also { incomingEqualizer = it; it.apply(preferences.equalizer) }
            ExoPlayer.Builder(this, VisualizationRenderers(this)).setMediaSourceFactory(sources).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(id: Int) { effect.attach(id) }
                })
            }
        }, releaseIncoming = {
            incomingEqualizer?.release(); incomingEqualizer = null
        }) { status -> extras { putString("transitionStatus", status) } }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val base = super.onConnect(session, controller)
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(SessionCommand(NEXT_RECOMMENDATION, android.os.Bundle.EMPTY)).add(SessionCommand(RETRY_RECOMMENDATIONS, android.os.Bundle.EMPTY)).add(SessionCommand(APPEND_COLLECTION, android.os.Bundle.EMPTY)).add(SessionCommand(EXIT_APP, android.os.Bundle.EMPTY)).add(SessionCommand(RETRY_PLAYBACK, android.os.Bundle.EMPTY)).build())
                        .setAvailablePlayerCommands(base.availablePlayerCommands).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: android.os.Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction == RETRY_PLAYBACK) {
                        if (args.getString("mediaId") != player.currentMediaItem?.mediaId)
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                        recoveryJob?.cancel(); suspendPreparation(); recoveryPolicy.reset()
                        player.currentMediaItem?.takeIf { it.localConfiguration?.uri?.scheme == YoutubeStreamResolver.SCHEME }
                            ?.let { app.youtube.onPlaybackRetry(it.mediaId) }
                        streamResolver?.invalidate(player.currentMediaItem?.localConfiguration?.uri?.toString())
                        restartCurrentSource(player, player.currentPosition)
                        player.playWhenReady = true
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction == APPEND_COLLECTION) return result {
                        val token = args.getString(COLLECTION_SESSION) ?: return@result SessionResult(SessionError.ERROR_BAD_VALUE)
                        val raw = args.getString("tracks")?.takeIf { it.length <= 256_000 }
                            ?: return@result SessionResult(SessionError.ERROR_BAD_VALUE)
                        val tracks = withContext(Dispatchers.Default) { com.raagmusic.app.data.LibraryCodec.decode(raw).savedTracks }
                        // Check intent and actual queue together on the service thread. Another
                        // controller can still be showing an old timeline during setMediaItems.
                        if (requestedCollectionToken != token || player.mediaItemCount == 0 ||
                            player.getMediaItemAt(0).mediaMetadata.extras?.getString(COLLECTION_SESSION) != token)
                            return@result SessionResult(SessionError.ERROR_INVALID_STATE)
                        if (tracks.size > 200 || tracks.any { it.source != Source.YOUTUBE ||
                                !Regex("[A-Za-z0-9_-]{11}").matches(it.id) || it.uri != it.id })
                            return@result SessionResult(SessionError.ERROR_BAD_VALUE)
                        val used = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val batch = tracks.distinctBy { it.id }.filterNot { it.id in used }
                        val index = player.mediaItemCount
                        val advance = player.playbackState == Player.STATE_ENDED && player.playWhenReady
                        player.addMediaItems(batch.map { it.asMediaItem(collectionToken = token) })
                        save { app.library.update { it.copy(savedTracks = rememberTrackMetadata(it.savedTracks, batch)) } }
                        if (advance && batch.isNotEmpty()) { player.seekTo(index, 0); player.prepare() }
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                    if (command.customAction == EXIT_APP) return result {
                        if (app.features.state.first().preferences.stopOnExit) stopPlaybackOnExit()
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                    if (command.customAction == RETRY_RECOMMENDATIONS) {
                        recommendationQueue?.retry()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction != NEXT_RECOMMENDATION) return super.onCustomCommand(session, controller, command, args)
                    recommendationQueue?.next()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    // An explicit selection gets a fresh budget even when it selects the same song.
                    // Internal recovery replaces the source directly and never enters this callback.
                    recoveryJob?.cancel(); recoveryPolicy.onTrackTransition(null)
                    mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.let { PlaybackTrace.mark(it.mediaId, "service_accept") }
                    requestedCollectionToken = mediaItems.firstOrNull()?.mediaMetadata?.extras?.getString(COLLECTION_SESSION)
                    return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                }
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                ): ListenableFuture<List<MediaItem>> {
                    // Normalize controller requests to stable, unresolved references. Only the
                    // LOADER resolves them: resolving the entire queue here would expire later songs.
                    return result {
                    preferences = app.features.state.first().preferences
                    val library = app.library.state.first()
                    val normalized = mediaItems.map { request ->
                        val item = if (request.localConfiguration == null) {
                            val query = request.requestMetadata.searchQuery
                            if (!query.isNullOrBlank()) {
                                library.savedTracks.firstOrNull { it.title.contains(query, true) || it.artist.contains(query, true) }?.asMediaItem(recommendationChain = true)
                                    ?: app.youtube.searchTracks(query).first().firstOrNull()?.toLibraryTrack()?.asMediaItem(recommendationChain = true)
                                    ?: throw IllegalArgumentException("No matching song")
                            } else library.savedTracks.find { it.id == request.mediaId }?.asMediaItem()
                                ?: throw IllegalArgumentException("Unknown library item")
                        } else request
                        val uri = item.localConfiguration?.uri
                        if (uri?.scheme == YoutubeStreamResolver.SCHEME) {
                            require(uri.host == "track" && uri.pathSegments.size == 1)
                            YoutubeTrackIds.requireValid(uri.lastPathSegment.orEmpty())
                            item
                        } else if (uri == null) {
                            item.buildUpon().setUri(YoutubeStreamResolver.mediaUri(item.mediaId)).build()
                        } else {
                            require(uri.scheme in setOf("https", "content", "file")) { "Unsupported media URI." }
                            item
                        }
                    }
                    val tracks = normalized.mapNotNull(::track)
                    save { app.library.update { it.copy(savedTracks = rememberTrackMetadata(it.savedTracks, tracks)) } }
                    normalized.firstOrNull()?.let { PlaybackTrace.mark(it.mediaId, "queue_normalized") }
                    normalized
                    }
                }
                override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = result {
                    val saved = app.features.state.first()
                    val q = if (saved.preferences.rememberQueue) saved.queue.sanitized() else QueueSnapshot()
                    mediaSession.player.shuffleModeEnabled = q.shuffle
                    mediaSession.player.repeatMode = q.repeat
                    MediaSession.MediaItemsWithStartPosition(q.tracks.map { it.asMediaItem(q.recommendationChain) }, q.index, q.positionMs)
                }
                override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
                    Futures.immediateFuture(LibraryResult.ofItem(folder("root", getString(com.raagmusic.app.R.string.app_name)), params))
                override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = result {
                    val library = app.library.state.first()
                    val children = when (parentId) {
                        "root" -> listOf(folder("favorites", "Liked songs"), folder("playlists", "Playlists"), folder("recent", "Recently played"))
                        "playlists" -> library.playlists.map { folder("playlist:${it.id}", it.name) }
                        "favorites" -> library.savedTracks.filter { it.id in library.favorites }.map { it.asMediaItem() }
                        "recent" -> library.recentIds.mapNotNull { id -> library.savedTracks.find { it.id == id } }.map { it.asMediaItem() }
                        else -> {
                            val playlist = library.playlists.find { "playlist:${it.id}" == parentId }
                                ?: return@result LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                            playlist.trackIds.mapNotNull { id -> library.savedTracks.find { it.id == id } }.map { it.asMediaItem() }
                        }
                    }
                    if (page < 0 || pageSize !in 1..1000) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    else LibraryResult.ofItemList(children.drop((page.toLong() * pageSize).coerceAtMost(children.size.toLong()).toInt()).take(pageSize), params)
                }
                override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = result {
                    val count = app.library.state.first().savedTracks.count { it.title.contains(query, true) || it.artist.contains(query, true) }
                    session.notifySearchResultChanged(browser, query, count, params)
                    LibraryResult.ofVoid(params)
                }
                override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = result {
                    val tracks = app.library.state.first().savedTracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
                    if (page < 0 || pageSize !in 1..1000) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    else LibraryResult.ofItemList(tracks.drop((page.toLong() * pageSize).coerceAtMost(tracks.size.toLong()).toInt()).take(pageSize).map { it.asMediaItem() }, params)
                }
                override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = result {
                    val library = app.library.state.first()
                    val item = when (mediaId) {
                        "root" -> folder("root", getString(com.raagmusic.app.R.string.app_name))
                        "favorites" -> folder("favorites", "Liked songs")
                        "playlists" -> folder("playlists", "Playlists")
                        "recent" -> folder("recent", "Recently played")
                        else -> library.playlists.find { "playlist:${it.id}" == mediaId }?.let { folder(mediaId, it.name) }
                            ?: library.savedTracks.find { it.id == mediaId }?.asMediaItem()
                    }
                    if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(item, null)
                }
            }).setSessionActivity(activity).build()
        serviceScope.launch {
            val saved = app.features.state.first()
            preferences = saved.preferences
            crossfade?.configure(preferences); equalizer?.apply(preferences.equalizer)
            historyEpoch = saved.historyEpoch
            val queue = saved.queue.sanitized()
            if (preferences.rememberQueue && queue.tracks.isNotEmpty() && player.mediaItemCount == 0 && !player.playWhenReady) {
                player.setMediaItems(queue.tracks.map { it.asMediaItem(queue.recommendationChain) }, queue.index, queue.positionMs)
                player.shuffleModeEnabled = queue.shuffle
                player.repeatMode = queue.repeat
                // Restore paused. The user explicitly resumes through the app, widget or headset.
                player.playWhenReady = false
            }
            ready = true
            app.features.observed.collectLatest { state ->
                val previous = preferences
                preferences = state.preferences
                if (previous.videoMode != preferences.videoMode || previous.videoQuality != preferences.videoQuality) {
                    suspendPreparation(); resolver.invalidate()
                    val position = player.currentPosition.coerceAtLeast(0)
                    val index = player.currentMediaItemIndex
                    val resume = player.playWhenReady
                    val items = (0 until player.mediaItemCount).map { i -> player.getMediaItemAt(i).let { item ->
                        if (item.localConfiguration?.uri?.scheme == YoutubeStreamResolver.SCHEME)
                            item.buildUpon().setUri(YoutubeStreamResolver.mediaUri(item.mediaId)).build() else item
                    } }
                    if (items.isNotEmpty()) { player.setMediaItems(items, index.coerceAtLeast(0), position); player.prepare(); player.playWhenReady = resume }
                }
                if (previous.analyticsEnabled != preferences.analyticsEnabled || historyEpoch != state.historyEpoch) {
                    accumulatedMs = 0; lastTick = SystemClock.elapsedRealtime()
                    listeningSession.transition(player.currentMediaItem?.mediaId, repeated = true)
                    listeningSession.duration(player.currentMediaItem?.mediaId, player.duration)
                    if (player.isPlaying) player.currentMediaItem?.mediaId?.let(listeningSession::started)
                }
                if (previous.wifiQuality != preferences.wifiQuality || previous.mobileQuality != preferences.mobileQuality) warmNext()
                configureSleepTimer(state.sleepTimer)
                // Ending with this song must not start the incoming crossfade decoder.
                crossfade?.configure(if (state.sleepTimer.finishTrackId == null && preferences.videoMode != com.raagmusic.app.domain.VideoPlaybackMode.ALWAYS && player.videoFormat == null) preferences else preferences.copy(transitionMode = TransitionMode.GAPLESS))
                if (!state.sleepTimer.active) extras { putLong("sleepRemainingMs", 0); putBoolean("sleepEndOfTrack", false); putLong("sleepDeadlineEpochMs", 0) }
                if (previous.equalizer != preferences.equalizer) { equalizer?.apply(preferences.equalizer); incomingEqualizer?.apply(preferences.equalizer) }
                if (historyEpoch != state.historyEpoch) {
                    historyEpoch = state.historyEpoch
                    accumulatedMs = 0
                    lastTick = SystemClock.elapsedRealtime()
                }
                if (!preferences.rememberQueue) persist()
            }
        }
        serviceScope.launch {
            app.library.observed.collectLatest { state ->
                session?.notifyChildrenChanged("root", 3, null)
                session?.notifyChildrenChanged("favorites", state.favorites.size, null)
                session?.notifyChildrenChanged("playlists", state.playlists.size, null)
                session?.notifyChildrenChanged("recent", state.recentIds.size, null)
                state.playlists.forEach { session?.notifyChildrenChanged("playlist:${it.id}", it.trackIds.size, null) }
            }
        }
        serviceScope.launch { while (true) { updateSleepTimer(); delay(1_000) } }
        serviceScope.launch { while (true) { delay(5_000); sample(); flushListening(); persist(); warmNext() } }
    }

    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session?.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }

    private fun stopPlaybackOnExit() {
        recoveryJob?.cancel()
        suspendPreparation()
        recommendationQueue?.cancelPendingAdvance()
        sample(); flushListening()
        exoPlayer?.pause()
        exoPlayer?.stop()
        recommendationQueue?.suspendWork()
        persist()
        com.raagmusic.app.widget.RaagWidget.update(this, playing = false)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        sample(); flushListening(); persist()
        serviceScope.launch {
            if (app.features.state.first().preferences.stopOnExit) stopPlaybackOnExit()
            else if (session?.player?.playWhenReady != true) { delay(200); stopSelf() }
        }
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        sample(); flushListening(); persist()
        warmJob?.cancel()
        recommendationQueue?.close(); recommendationQueue = null
        crossfade?.release(); crossfade = null
        equalizer?.release(); equalizer = null
        incomingEqualizer?.release(); incomingEqualizer = null
        serviceScope.cancel()
        exoPlayer?.run { removeListener(listener); release() }
        exoPlayer = null
        queuePreloader?.release(); queuePreloader = null
        streamResolver?.invalidate()
        streamResolver = null
        session?.release()
        session = null
        super.onDestroy()
    }
}
