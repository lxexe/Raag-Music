package com.raagmusic.app.playback

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.raagmusic.app.domain.Track
import kotlinx.coroutines.*

const val RECOMMENDATION_CHAIN = "recommendationChain"
const val RETRY_RECOMMENDATIONS = "com.raagmusic.app.RETRY_RECOMMENDATIONS"
const val NEXT_RECOMMENDATION = "com.raagmusic.app.NEXT_RECOMMENDATION"

/** Keep a bounded runway ready before the user reaches the tail, preserving explicit queue order. */
@UnstableApi
class RecommendationQueue(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val related: suspend (MediaItem) -> List<Track>,
    private val remember: suspend (List<Track>) -> Unit,
    private val status: (enabled: Boolean, loading: Boolean, error: String?) -> Unit,
    private val targetAhead: Int = 20,
    private val lowWater: Int = 8,
) {
    private var job: Job? = null
    private var anchor: MediaItem? = null
    private var failed: MediaItem? = null
    private var pendingNext = false
    private var committing = false
    private var sessionRoot: MediaItem? = null
    val enabled: Boolean get() = (0 until player.mediaItemCount).any {
        player.getMediaItemAt(it).mediaMetadata.extras?.getBoolean(RECOMMENDATION_CHAIN) == true
    }
    private fun ahead() = player.upcomingQueueIndices()
    private fun tail(): MediaItem? = ahead().lastOrNull()?.let(player::getMediaItemAt) ?: player.currentMediaItem

    fun sync() {
        if (committing) return
        val root = if (player.mediaItemCount > 0) player.getMediaItemAt(0) else null
        if (!enabled || root == null || player.repeatMode == Player.REPEAT_MODE_ALL) {
            suspendWork(); sessionRoot = root
            return
        }
        if (root != sessionRoot) {
            job?.cancel(); job = null; anchor = null; failed = null; pendingNext = false
            sessionRoot = root
        }
        val tail = tail() ?: return
        if (job?.isActive == true) {
            if (anchor == tail) return
            job?.cancel(); job = null; anchor = null
        }
        if (ahead().size > lowWater || failed == tail) return
        anchor = tail; failed = null
        status(true, true, null)
        job = scope.launch {
            try {
                val loaded = related(tail)
                ensureActive()
                if (tail() != tail || !enabled || player.repeatMode == Player.REPEAT_MODE_ALL) return@launch
                val queueSnapshot = List(player.mediaItemCount) { player.getMediaItemAt(it) }
                val used = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val queued = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).asTrack() }
                val candidates = withContext(Dispatchers.Default) { loaded.distinctBy { it.id }.filter { candidate ->
                    candidate.id != tail.mediaId && queued.none { it.id != candidate.id && com.raagmusic.app.domain.sameRecording(it, candidate) }
                }.fold(mutableListOf<Track>()) { batch, candidate ->
                    if (batch.none { com.raagmusic.app.domain.sameRecording(it, candidate) }) batch.add(candidate)
                    batch
                } }
                val fresh = candidates.filter { it.id !in used }
                // A small catalog may need to revisit an older song; don't immediately repeat.
                val recent = ((player.currentMediaItemIndex - 19).coerceAtLeast(0)..player.currentMediaItemIndex).map { player.getMediaItemAt(it).mediaId }.toSet()
                val upcomingIds = ahead().map { player.getMediaItemAt(it).mediaId }.toSet() + player.currentMediaItem?.mediaId
                val batch = fresh.ifEmpty { candidates.filter { it.id !in recent && it.id !in upcomingIds } }
                    .ifEmpty { candidates.filter { it.id !in upcomingIds } }
                    .take((targetAhead - ahead().size).coerceAtLeast(1))
                if (batch.isEmpty()) throw IllegalStateException("Couldn't find more music. Retry radio or add a song to keep listening.")
                remember(batch)
                ensureActive()
                if (player.mediaItemCount != queueSnapshot.size || queueSnapshot.indices.any { player.getMediaItemAt(it) != queueSnapshot[it] } ||
                    tail() != tail || !enabled || player.repeatMode == Player.REPEAT_MODE_ALL) return@launch
                val advance = pendingNext || (player.playbackState == Player.STATE_ENDED && player.playWhenReady && player.repeatMode == Player.REPEAT_MODE_OFF)
                val index = player.mediaItemCount
                val order = if (player.shuffleModeEnabled) buildList {
                    var window = player.currentTimeline.getFirstWindowIndex(true)
                    while (window != C.INDEX_UNSET) {
                        add(window)
                        window = player.currentTimeline.getNextWindowIndex(window, Player.REPEAT_MODE_OFF, true)
                    }
                } else emptyList()
                pendingNext = false
                committing = true
                player.addMediaItems(batch.map { it.asMediaItem(recommendationChain = true) })
                if (order.isNotEmpty()) player.setShuffleOrder(DefaultShuffleOrder((order + (index until index + batch.size)).toIntArray(), System.nanoTime()))
                if (advance) { player.seekTo(index, 0); player.prepare() }
                // Bound session history without touching the current item, position or future order.
                val previous = if (order.isNotEmpty()) order.takeWhile { it != player.currentMediaItemIndex }
                    else (0 until player.currentMediaItemIndex).toList()
                val expired = previous.dropLast(50)
                if (expired.isNotEmpty()) {
                    // Descending physical indices preserve both shuffle order and the active item.
                    if (!player.shuffleModeEnabled) player.removeMediaItems(0, expired.size)
                    else expired.sortedDescending().forEach(player::removeMediaItem)
                    sessionRoot = player.getMediaItemAt(0)
                }
                committing = false
                status(true, false, null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (enabled && tail() == tail) {
                    failed = tail; pendingNext = false
                    status(true, false, e.message ?: "Couldn't load radio. Try again.")
                }
            } finally {
                committing = false
                if (anchor == tail) {
                    anchor = null; job = null
                    if (failed != tail && enabled && ahead().size <= lowWater) scope.launch { yield(); sync() }
                }
            }
        }
    }

    fun next() {
        if (!enabled || player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ALL) {
            player.seekToNextMediaItem()
            sync()
        } else {
            failed = null; pendingNext = true
            sync()
        }
    }
    fun retry() { failed = null; sync() }
    fun cancelPendingAdvance() { pendingNext = false }
    fun suspendWork() {
        job?.cancel(); job = null; anchor = null; failed = null; pendingNext = false
        status(enabled, false, null)
    }
    fun close() { job?.cancel() }
}

/** One canonical playback-order projection, including shuffle and excluding repeat cycles. */
@UnstableApi
internal fun Player.upcomingQueueIndices(): List<Int> {
    if (mediaItemCount == 0 || currentMediaItemIndex == C.INDEX_UNSET) return emptyList()
    return buildList {
        val repeat = if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        var index = currentTimeline.getNextWindowIndex(currentMediaItemIndex, repeat, shuffleModeEnabled)
        while (index != C.INDEX_UNSET && index != currentMediaItemIndex && size < mediaItemCount) {
            add(index)
            index = currentTimeline.getNextWindowIndex(index, repeat, shuffleModeEnabled)
        }
    }
}
