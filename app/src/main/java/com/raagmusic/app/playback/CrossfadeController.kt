package com.raagmusic.app.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.raagmusic.app.domain.ListeningPreferences
import com.raagmusic.app.domain.TransitionMode
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The session player keeps the queue. A short-lived second player overlaps the next track,
 * then bridges its audio while the session player seeks past the already heard intro. */
@UnstableApi
class CrossfadeController(
    private val main: ExoPlayer, private val scope: CoroutineScope,
    private val createIncoming: () -> ExoPlayer,
    private val releaseIncoming: () -> Unit = {},
    private val report: (String) -> Unit = {},
) : Player.Listener {
    private var preferences = ListeningPreferences()
    private var incoming: ExoPlayer? = null
    private var outgoingId: String? = null
    private var outgoingIndex = -1
    private var nextItem: MediaItem? = null
    private var nextIndex = -1
    private var overlapping = false
    private var handingOff = false
    private var internalSeek = false
    private var handoffStarted = 0L
    private var blendStarted = 0L
    private var fadeDurationMs = 0L
    private var failedFor: Pair<String?, Int>? = null
    private var rememberedVolume = main.volume
    private val ownedVolumeChanges = mutableListOf<Float>()
    private var poll: Job? = null
    private var scheduled: androidx.media3.exoplayer.PlayerMessage? = null
    private var released = false
    init { main.addListener(this) }
    fun configure(value: ListeningPreferences) {
        if (preferences.transitionMode != value.transitionMode || preferences.crossfadeSeconds != value.crossfadeSeconds) cancel()
        preferences = value
        reschedule()
    }
    /** Wake at the preparation boundary, not twenty times a second for the whole song. */
    private fun reschedule() {
        scheduled?.cancel(); scheduled = null
        if (released || preferences.transitionMode != TransitionMode.CROSSFADE || !main.playWhenReady ||
            main.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || main.playerError != null) {
            poll?.cancel(); poll = null; cancel(); return
        }
        // The primary decoder deliberately buffers while seeking to the already heard intro.
        // Keep the incoming deck audible through that handoff; normal rebuffering cancels overlap.
        if (handingOff && incoming != null) { startPolling(); return }
        if (!main.isPlaying) { poll?.cancel(); poll = null; cancel(); return }
        if (incoming != null) { startPolling(); return }
        val index = main.nextMediaItemIndex
        if (index !in 0 until main.mediaItemCount || main.repeatMode == Player.REPEAT_MODE_ONE ||
            preserveAlbumTransition(main.currentMediaItem, main.getMediaItemAt(index), main.shuffleModeEnabled) ||
            failedFor == (main.currentMediaItem?.mediaId to main.currentMediaItemIndex)) {
            poll?.cancel(); poll = null; return
        }
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val configured = (preferences.crossfadeSeconds.coerceIn(1, 12) * 1000L).coerceAtMost(duration / 2)
        val at = (duration - configured - 12_000).coerceAtLeast(0)
        if (main.currentPosition >= at) startPolling()
        else {
            poll?.cancel(); poll = null
            scheduled = main.createMessage { _, _ -> startPolling() }.setLooper(main.applicationLooper)
                .setPosition(main.currentMediaItemIndex, at).send()
        }
    }
    private fun startPolling() {
        if (released || poll?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) { tick(); delay(if (overlapping || handingOff) 16 else 100) }
        }
        poll = job
        job.start()
    }
    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) reschedule()
    }
    private fun setMainVolume(value: Float) {
        if (abs(main.volume - value) < .000001f) return
        // ExoPlayer can defer callbacks during a transition listener. Track the
        // requested value until its callback arrives instead of a stack-local flag.
        ownedVolumeChanges += value
        main.volume = value
    }
    override fun onVolumeChanged(volume: Float) {
        val own = ownedVolumeChanges.indexOfFirst { abs(it - volume) < .000001f }
        if (own >= 0) ownedVolumeChanges.removeAt(own)
        else { rememberedVolume = volume; cancel() }
    }
    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && overlapping && main.currentMediaItemIndex == nextIndex && item == nextItem && incoming?.isPlaying == true) {
            handingOff = true; handoffStarted = android.os.SystemClock.elapsedRealtime(); blendStarted = 0
            setMainVolume(0f)
            internalSeek = true
            main.seekTo(incoming!!.currentPosition)
        } else cancel()
        if (!handingOff) failedFor = null
    }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            if (internalSeek) internalSeek = false else cancel()
        }
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) cancel()
    }
    override fun onPlaybackSuppressionReasonChanged(reason: Int) { if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) cancel() }
    override fun onPlayerError(error: PlaybackException) { cancel() }
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { cancel() }
    override fun onRepeatModeChanged(repeatMode: Int) { cancel() }
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (incoming != null && (nextIndex !in 0 until main.mediaItemCount || main.getMediaItemAt(nextIndex) != nextItem)) cancel()
    }
    private fun tick() {
        PlaybackTrace.mark(main.currentMediaItem?.mediaId.orEmpty(), "transition_tick")
        if (preferences.transitionMode != TransitionMode.CROSSFADE || !main.playWhenReady || main.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || main.playerError != null) { cancel(); return }
        val shadow = incoming
        if (handingOff && shadow != null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (shadow.playerError != null || now - handoffStarted > 5000 || main.currentMediaItem != nextItem) { cancel(); return }
            if (!main.isPlaying) return
            // Match clocks after the primary decoder has prepared. Keep the incoming audio
            // audible during seek, and never replay the beginning of the next song.
            val difference = abs(main.currentPosition - shadow.currentPosition)
            if (blendStarted == 0L && difference > 100) {
                internalSeek = true; main.seekTo(shadow.currentPosition + 40); return
            }
            if (blendStarted == 0L) blendStarted = now
            val fraction = ((now - blendStarted) / 180f).coerceIn(0f, 1f)
            setMainVolume(rememberedVolume * fraction); shadow.volume = rememberedVolume * (1f - fraction)
            if (fraction >= 1f) cancel()
            return
        }
        if (!main.isPlaying) { if (overlapping) cancel(); return }
        val duration = main.duration
        val index = main.nextMediaItemIndex
        if (duration == C.TIME_UNSET || duration <= 0 || index !in 0 until main.mediaItemCount || main.repeatMode == Player.REPEAT_MODE_ONE) { cancel(); return }
        val remaining = duration - main.currentPosition
        val configured = (preferences.crossfadeSeconds.coerceIn(1, 12) * 1000L).coerceAtMost(duration / 2)
        if (remaining > configured + 12_000) { cancel(); reschedule(); return }
        if (failedFor == (main.currentMediaItem?.mediaId to main.currentMediaItemIndex)) return
        if (shadow == null) {
            rememberedVolume = main.volume; outgoingId = main.currentMediaItem?.mediaId; outgoingIndex = main.currentMediaItemIndex
            nextIndex = index; nextItem = main.getMediaItemAt(index)
            val prepared = try { createIncoming() } catch (_: RuntimeException) {
                failedFor = outgoingId to outgoingIndex; cancel(); releaseIncoming()
                report("Crossfade unavailable for this transition; normal playback continues.")
                return
            }
            incoming = prepared.apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failedFor = outgoingId to outgoingIndex; cancel(); report("Crossfade unavailable for this transition; normal playback continues.")
                    }
                })
                setMediaItem(nextItem!!); prepare()
                playbackParameters = main.playbackParameters
            }
            return
        }
        if (outgoingId != main.currentMediaItem?.mediaId || outgoingIndex != main.currentMediaItemIndex || index != nextIndex) { cancel(); return }
        val shadowDuration = shadow.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: configured * 2
        val fade = configured.coerceAtMost(shadowDuration / 2)
        if (!overlapping && remaining in 1..fade && shadow.playbackState == Player.STATE_READY) {
            fadeDurationMs = remaining; overlapping = true; shadow.play(); return
        }
        if (overlapping) {
            if (!shadow.isPlaying) { cancel(); return }
            val fraction = (shadow.currentPosition.toDouble() / fadeDurationMs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            setMainVolume((rememberedVolume * cos(fraction * Math.PI / 2)).toFloat())
            shadow.volume = (rememberedVolume * sin(fraction * Math.PI / 2)).toFloat()
        }
    }
    private fun cancel() {
        val shadow = incoming
        incoming = null; overlapping = false; handingOff = false; internalSeek = false
        outgoingId = null; outgoingIndex = -1; nextItem = null; nextIndex = -1
        if (shadow != null) { shadow.release(); releaseIncoming() }
        setMainVolume(rememberedVolume)
    }
    fun release() { released = true; scheduled?.cancel(); poll?.cancel(); main.removeListener(this); cancel() }
}

/** Preserve an ordered album without treating blank/common album names as a continuous album. */
@UnstableApi
internal fun preserveAlbumTransition(current: MediaItem?, next: MediaItem?, shuffled: Boolean): Boolean {
    if (shuffled || current == null || next == null) return false
    val album = current.mediaMetadata.albumTitle?.toString()?.trim().orEmpty()
    val collection = current.mediaMetadata.extras?.getString(COLLECTION_SESSION)
    val orderedAlbum = collection?.startsWith("album:") == true && collection == next.mediaMetadata.extras?.getString(COLLECTION_SESSION)
    val artist = current.mediaMetadata.albumArtist?.toString()?.trim()
        ?: current.mediaMetadata.artist?.toString()?.trim().orEmpty()
    return album.isNotBlank() &&
        album.equals(next.mediaMetadata.albumTitle?.toString()?.trim(), true) &&
        (orderedAlbum || (artist.isNotBlank() && artist.equals((next.mediaMetadata.albumArtist ?: next.mediaMetadata.artist)?.toString()?.trim(), true)))
}
