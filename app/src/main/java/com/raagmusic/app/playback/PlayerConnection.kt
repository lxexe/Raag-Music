package com.raagmusic.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import com.raagmusic.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Snapshot includes position because player callbacks do not fire for every progress tick.
data class PlaybackState(
    val trackId: String? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val buffering: Boolean = false,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val queueIds: List<String> = emptyList(), val error: String? = null,
    val bitrate: Int? = null,
    val eqAvailable: Boolean? = null, val eqStatus: String? = null,
    val recommendationChain: Boolean = false, val recommendationLoading: Boolean = false, val recommendationError: String? = null,
    val nextIndex: Int = -1, val sleepRemainingMs: Long = 0, val sleepEndOfTrack: Boolean = false, val sleepDeadlineEpochMs: Long = 0,
    val bufferedPosition: Long = 0, val playWhenReady: Boolean = false, val queueIndex: Int = -1,
    val upcomingIndices: List<Int> = emptyList(),
    val audioMimeType: String? = null, val sampleRate: Int? = null,
    val metadataTrack: Track? = null,
    val hasVideo: Boolean = false, val videoDecoding: Boolean = false, val videoWidth: Int = 0, val videoHeight: Int = 0,
    val failure: PlaybackFailure? = null,
)

@UnstableApi
class PlayerConnection(context: Context, private val scope: CoroutineScope) : Player.Listener {
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    private var controller: MediaController? = null
    private var videoSurface: android.view.SurfaceView? = null
    private var closed = false
    private var uiVisible = true
    private var queueTimeline: Timeline? = null
    private var cachedQueueIds = emptyList<String>()
    private var cachedUpcoming = emptyList<Int>()
    private var projectionIndex = -1
    private var projectionShuffle = false
    private var projectionRepeat = Player.REPEAT_MODE_OFF
    private var metadataItem: MediaItem? = null
    private var cachedMetadata: Track? = null
    private val pending = ArrayDeque<() -> Unit>()
    private var pendingSeek: Pair<String, Long>? = null
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private var poll: Job? = null
    init {
        future.addListener({
            if (closed || !scope.isActive) return@addListener
            try { controller = future.get(); controller?.addListener(this); while (pending.isNotEmpty()) pending.removeFirst().invoke(); snapshot() }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = "Couldn't connect to the player. Restart Raag Music.") }
        }, ContextCompat.getMainExecutor(context))
        startPolling()
    }
    private fun startPolling() {
        if (closed || !uiVisible || poll?.isActive == true) return
        poll = scope.launch { while (isActive) {
            PlaybackTrace.mark(mutable.value.trackId.orEmpty(), "ui_position_poll")
            snapshot(); delay(if (controller?.isPlaying == true) 100 else 750)
        } }
    }
    /** Service/listener events keep working; only visible UI needs a progress timer. */
    fun setUiVisible(visible: Boolean) {
        if (closed || visible == uiVisible) return
        uiVisible = visible
        if (visible) startPolling() else { poll?.cancel(); poll = null }
    }
    fun play(track: Track, queue: List<Track>, startPosition: Long = 0, ordered: Boolean = false, recommendationChain: Boolean = false,
        collectionToken: String? = null) {
        PlaybackTrace.mark(track.id, "tap")
        pendingSeek = null
        val selectedQueue = queue.ifEmpty { listOf(track) }.let { if (it.none { t -> t.id == track.id }) listOf(track) + it else it }
        val index = selectedQueue.indexOfFirst { it.id == track.id }
        // Selection updates the UI before the service persists/normalizes the media request.
        mutable.value = mutable.value.copy(trackId = track.id, playing = false, position = startPosition.coerceAtLeast(0),
            duration = track.durationSeconds * 1000, buffering = true, playWhenReady = true, error = null, failure = null,
            queueIds = selectedQueue.map { it.id }, queueIndex = index, nextIndex = if (index < selectedQueue.lastIndex) index + 1 else -1,
            upcomingIndices = (index + 1 until selectedQueue.size).toList(), bufferedPosition = 0, bitrate = null,
            audioMimeType = null, sampleRate = null,
            metadataTrack = track,
            recommendationChain = recommendationChain, recommendationError = null, recommendationLoading = false,
            shuffle = if (ordered) false else mutable.value.shuffle, repeat = if (ordered) Player.REPEAT_MODE_OFF else mutable.value.repeat)
        val action: () -> Unit = {
            controller?.apply {
                if (ordered) { shuffleModeEnabled = false; repeatMode = Player.REPEAT_MODE_OFF }
                setMediaItems(selectedQueue.map { it.asMediaItem(recommendationChain, collectionToken) }, selectedQueue.indexOfFirst { it.id == track.id }, startPosition.coerceAtLeast(0))
                prepare(); play()
            }
        }
        if (controller == null) { pending.clear(); pending.addLast(action) } else action()
    }
    fun toggle() {
        if (controller == null) {
            if (mutable.value.playWhenReady) pause()
            else {
                mutable.value = mutable.value.copy(playWhenReady = true)
                whenConnected { controller?.run { prepare(); play() }; snapshot() }
            }
            return
        }
        controller?.run {
        // After an error ExoPlayer may keep playWhenReady=true even though the UI shows Play.
        if (playerError != null) {
            sendCustomCommand(SessionCommand(PlaybackService.RETRY_PLAYBACK, Bundle.EMPTY),
                Bundle().apply { putString("mediaId", currentMediaItem?.mediaId) })
        } else if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            prepare(); play()
        } else if (playWhenReady) pause() else play()
    } }
    fun pause() {
        mutable.value = mutable.value.copy(playing = false, playWhenReady = false)
        whenConnected { controller?.pause(); snapshot() }
    }
    fun seek(position: Long) {
        val target = position.coerceAtLeast(0)
        val p = controller
        pendingSeek = mutable.value.trackId?.takeIf {
            p == null || p.currentMediaItem?.mediaId != it || p.duration == C.TIME_UNSET
        }?.let { it to target }
        mutable.value = mutable.value.copy(position = target)
        whenConnected { controller?.seekTo(target); snapshot() }
    }
    fun next() = whenConnected {
        controller?.let { p -> if (p.nextMediaItemIndex in 0 until p.mediaItemCount) PlaybackTrace.mark(p.getMediaItemAt(p.nextMediaItemIndex).mediaId, "next_tap"); p.nextOrRecommend() }; snapshot()
    }
    fun retryRadio() = whenConnected { controller?.sendCustomCommand(SessionCommand(RETRY_RECOMMENDATIONS, Bundle.EMPTY), Bundle.EMPTY) }
    fun previous() = whenConnected { controller?.run {
        val index = if (currentPosition > 3000 || previousMediaItemIndex == C.INDEX_UNSET) currentMediaItemIndex else previousMediaItemIndex
        if (index in 0 until mediaItemCount) PlaybackTrace.mark(getMediaItemAt(index).mediaId, "previous_tap")
        if (currentPosition > 3000) seekTo(0) else seekToPreviousMediaItem()
    }; snapshot() }
    /** A swipe means the previous song even after the current song has played for a while. */
    fun previousTrack() = whenConnected { controller?.run {
        val index = previousMediaItemIndex
        if (index in 0 until mediaItemCount) {
            PlaybackTrace.mark(getMediaItemAt(index).mediaId, "previous_swipe")
            seekToPreviousMediaItem()
        } else seekTo(0)
    }; snapshot() }
    fun shuffle() {
        val target = !mutable.value.shuffle
        mutable.value = mutable.value.copy(shuffle = target)
        whenConnected { controller?.shuffleModeEnabled = target; snapshot() }
    }
    fun repeat() {
        val target = when (mutable.value.repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        mutable.value = mutable.value.copy(repeat = target)
        whenConnected { controller?.repeatMode = target; snapshot() }
    }
    private fun whenConnected(action: () -> Unit) {
        if (controller == null) pending.addLast(action) else action()
    }
    fun enqueue(track: Track, next: Boolean = false, onAdded: () -> Unit = {}) = whenConnected {
        controller?.run {
            if (!isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                mutable.value = mutable.value.copy(error = "Queue editing is unavailable. Reconnect the player.")
                return@run
            }
            val empty = mediaItemCount == 0
            if (next) addMediaItem((currentMediaItemIndex + 1).coerceIn(0, mediaItemCount), track.asMediaItem())
            else addMediaItem(track.asMediaItem())
            if (empty) { playWhenReady = false; prepare() }
            snapshot()
            onAdded()
        }
    }
    fun playNext(track: Track) = enqueue(track, next = true)
    fun moveQueue(from: Int, to: Int) { controller?.run { if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) moveMediaItem(from, to) } }
    fun selectQueue(index: Int) { controller?.run { if (index in 0 until mediaItemCount) { seekTo(index, 0); prepare(); play() } } }
    fun insertQueue(index: Int, track: Track) { controller?.run { addMediaItem(index.coerceIn(0, mediaItemCount), track.asMediaItem()) } }
    fun removeQueue(index: Int) { controller?.removeMediaItem(index) }
    override fun onEvents(player: Player, events: Player.Events) { snapshot() }
    private fun snapshot() {
        if (closed) return
        controller?.let { p ->
            // A setMediaItems request can finish after an early seek. Reapply it once
            // the selected source has a real timeline, before publishing its position.
            pendingSeek?.let { (id, position) ->
                if (p.currentMediaItem?.mediaId == id && p.duration != C.TIME_UNSET) {
                    pendingSeek = null
                    p.seekTo(position)
                }
            }
            val changed = queueTimeline != p.currentTimeline
            if (changed) {
                queueTimeline = p.currentTimeline
                cachedQueueIds = List(p.mediaItemCount) { p.getMediaItemAt(it).mediaId }
            }
            if (changed || projectionIndex != p.currentMediaItemIndex || projectionShuffle != p.shuffleModeEnabled || projectionRepeat != p.repeatMode) {
                projectionIndex = p.currentMediaItemIndex; projectionShuffle = p.shuffleModeEnabled; projectionRepeat = p.repeatMode
                cachedUpcoming = p.upcomingQueueIndices()
            }
            val audioFormat = p.currentTracks.groups.firstNotNullOfOrNull { group ->
                if (group.type != C.TRACK_TYPE_AUDIO) null else (0 until group.length).firstNotNullOfOrNull { index ->
                    if (group.isTrackSelected(index)) group.getTrackFormat(index) else null
                }
            }
            if (metadataItem != p.currentMediaItem) {
                metadataItem = p.currentMediaItem
                cachedMetadata = metadataItem?.asTrack()
            }
            val failure = p.playerError?.let { error ->
                val extra = p.sessionExtras
                val kind = extra.getString("failureKind")?.let { runCatching { PlaybackFailureKind.valueOf(it) }.getOrNull() }
                if (extra.getString("failureTrackId") == p.currentMediaItem?.mediaId && kind != null)
                    PlaybackFailure(kind, extra.getString("failureTitle").orEmpty(), extra.getString("failureMessage").orEmpty(),
                        extra.getLong("failureRetryAt").takeIf { it > 0 }) else playbackFailure(error)
            }
            mutable.value = PlaybackState(p.currentMediaItem?.mediaId, p.isPlaying, p.currentPosition,
                p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
                p.playbackState == Player.STATE_BUFFERING || (p.playbackState == Player.STATE_IDLE && p.playWhenReady && p.mediaItemCount > 0), p.shuffleModeEnabled, p.repeatMode,
                cachedQueueIds,
                failure?.message,
                p.sessionExtras.takeIf { it.getString("bitrateTrackId") == p.currentMediaItem?.mediaId }?.getInt("bitrate")?.takeIf { it > 0 }
                    ?: audioFormat?.bitrate?.takeIf { it > 0 }, p.sessionExtras.takeIf { it.containsKey("eqAvailable") }?.getBoolean("eqAvailable"), p.sessionExtras.getString("eqStatus"), p.sessionExtras.getBoolean(RECOMMENDATION_CHAIN), p.sessionExtras.getBoolean("recommendationLoading"), p.sessionExtras.getString("recommendationError"), p.nextMediaItemIndex, p.sessionExtras.getLong("sleepRemainingMs"),
                p.sessionExtras.getBoolean("sleepEndOfTrack"), p.sessionExtras.getLong("sleepDeadlineEpochMs"), p.bufferedPosition.coerceAtLeast(0), p.playWhenReady, p.currentMediaItemIndex, cachedUpcoming,
                audioMimeType = audioFormat?.sampleMimeType, sampleRate = audioFormat?.sampleRate?.takeIf { it > 0 },
                failure = failure, metadataTrack = cachedMetadata, hasVideo = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO },
                videoDecoding = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected },
                videoWidth = p.videoSize.width, videoHeight = p.videoSize.height)
        }
    }
    fun attachVideoSurface(view: android.view.SurfaceView) {
        if (closed || controller == null) return
        videoSurface = view
        controller?.setVideoSurfaceView(view)
        controller?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build() }
    }
    fun detachVideoSurface(view: android.view.SurfaceView) {
        if (videoSurface !== view) return
        controller?.clearVideoSurfaceView(view)
        controller?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build() }
        videoSurface = null
    }
    fun close() { videoSurface?.let(::detachVideoSurface); closed = true; pending.clear(); pendingSeek = null; poll?.cancel(); controller?.removeListener(this); MediaController.releaseFuture(future) }
}

/** Session metadata is available before asynchronous library persistence/catalog rebuilding. */
@UnstableApi
internal fun MediaItem.asTrack(): Track? {
    val config = localConfiguration ?: return null
    val metadata = mediaMetadata
    val source = runCatching { Source.valueOf(metadata.extras?.getString("source").orEmpty()) }
        .getOrDefault(if (config.uri.scheme == YoutubeStreamResolver.SCHEME) Source.YOUTUBE else Source.DIRECT)
    val uri = if (source == Source.YOUTUBE) config.uri.lastPathSegment.orEmpty() else config.uri.toString()
    return Track(mediaId, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
        metadata.artworkUri?.toString().orEmpty(), uri, source,
        metadata.extras?.getString("mood").orEmpty(), metadata.extras?.getLong("durationSeconds") ?: 0,
        metadata.albumTitle?.toString().orEmpty(), metadata.genre?.toString().orEmpty())
}

@UnstableApi
internal fun Track.asMediaItem(recommendationChain: Boolean = false, collectionToken: String? = null) = MediaItem.Builder().setMediaId(id)
    .setUri(if (source == Source.YOUTUBE) YoutubeStreamResolver.mediaUri(uri) else Uri.parse(uri))
    .setCustomCacheKey(id)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setGenre(genre).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setArtworkUri(artwork.takeIf { it.isNotBlank() }?.let(Uri::parse))
        .setExtras(Bundle().apply { putBoolean(RECOMMENDATION_CHAIN, recommendationChain); putString("source", source.name); putLong("durationSeconds", durationSeconds); putString("mood", mood); collectionToken?.let { putString(COLLECTION_SESSION, it) } }).build()).build()

@UnstableApi
internal fun MediaController.nextOrRecommend() {
    if (sessionExtras.getBoolean(RECOMMENDATION_CHAIN) && !hasNextMediaItem()) {
        sendCustomCommand(SessionCommand(NEXT_RECOMMENDATION, Bundle.EMPTY), Bundle.EMPTY)
    } else seekToNextMediaItem()
}
