package com.raagmusic.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.raagmusic.app.data.youtube.BrowseCollection
import com.raagmusic.app.data.youtube.CollectionCursor
import com.raagmusic.app.data.youtube.YoutubeMusicRepository
import com.raagmusic.app.data.youtube.toLibraryTrack
import com.raagmusic.app.data.LibraryCodec
import com.raagmusic.app.domain.LibraryState
import kotlinx.coroutines.*

internal const val COLLECTION_SESSION = "collectionSession"
internal const val APPEND_COLLECTION = "com.raagmusic.app.APPEND_COLLECTION"

private suspend fun <T> awaitCollectionFuture(future: ListenableFuture<T>): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { future.cancel(true) }
    future.addListener({
        try { if (continuation.isActive) continuation.resumeWith(Result.success(future.get())) }
        catch (e: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
    }, MoreExecutors.directExecutor())
}

/** Process-owned controller: pagination survives the screen/ViewModel closing. */
@UnstableApi
internal suspend fun loadRemainingCollection(context: Context, repository: YoutubeMusicRepository,
    collection: BrowseCollection, firstCursor: CollectionCursor, initialIds: Set<String>, token: String) =
    withContext(Dispatchers.Main.immediate) {
        val future = MediaController.Builder(context, SessionToken(context,
            ComponentName(context, PlaybackService::class.java))).buildAsync()
        try {
            val controller = withTimeout(10_000) { awaitCollectionFuture(future) }
            fun ownsQueue() = controller.mediaItemCount > 0 && controller.getMediaItemAt(0)
                .mediaMetadata.extras?.getString(COLLECTION_SESSION) == token
            // setMediaItems normalization is asynchronous. Wait for this exact selection.
            val selected = withTimeoutOrNull(10_000) {
                while (!ownsQueue()) delay(50)
                true
            } ?: false
            if (!selected) return@withContext
            var cursor: CollectionCursor? = firstCursor
            val seen = mutableSetOf<String>()
            val used = initialIds.toMutableSet()
            while (cursor != null && ownsQueue()) {
                // Explicit Stop/exit ends this controller's work. Pause retains the continuation.
                if (controller.playbackState == Player.STATE_IDLE && !controller.playWhenReady) return@withContext
                if (!controller.playWhenReady || controller.playbackState == Player.STATE_BUFFERING ||
                    controller.upcomingQueueIndices().size > 12) { delay(1_000); continue }
                val current = cursor
                if (!seen.add(current.fingerprint)) return@withContext
                val page = withTimeout(8_000) { repository.getCollectionPage(collection, current) }
                ensureActive()
                if (!ownsQueue()) return@withContext
                val batch = page.tracks.map { it.toLibraryTrack() }.filter { used.add(it.id) }
                if (batch.isEmpty()) return@withContext
                val encoded = withContext(Dispatchers.Default) { LibraryCodec.encode(LibraryState(savedTracks = batch)) }
                val result = withTimeout(10_000) { awaitCollectionFuture(controller.sendCustomCommand(
                    SessionCommand(APPEND_COLLECTION, Bundle.EMPTY), Bundle().apply {
                        putString(COLLECTION_SESSION, token); putString("tracks", encoded)
                    })) }
                if (result.resultCode != SessionResult.RESULT_SUCCESS) return@withContext
                cursor = page.next
            }
        } finally { MediaController.releaseFuture(future) }
    }
