package com.raagmusic.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.data.LibraryStore
import com.raagmusic.app.data.youtube.YoutubeMusicRepository
import com.raagmusic.app.playback.MediaCache

@UnstableApi
class RaagApplication : Application() {
    val usageReporting by lazy { com.raagmusic.app.analytics.UsageReporting(this) }
    override fun onCreate() {
        super.onCreate()
        usageReporting
        persistenceScope.launch {
            try {
                com.raagmusic.app.updates.UpdateSchedule.ensure(this@RaagApplication)
                updates.load()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* Settings can retry when storage or scheduling is available. */ }
        }
    }
    internal val updates by lazy { com.raagmusic.app.updates.UpdateRepository(
        com.raagmusic.app.updates.PreferenceUpdateStorage(this), com.raagmusic.app.updates.GitHubReleaseFetcher()) }
    fun checkUpdatesIfDue() {
        persistenceScope.launch {
            try { updates.check() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* An update check must never interrupt playback/startup. */ }
        }
    }
    val playbackMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var collectionContinuationJob: kotlinx.coroutines.Job? = null
    fun cancelCollectionContinuation() { collectionContinuationJob?.cancel(); collectionContinuationJob = null }
    fun continueCollection(collection: com.raagmusic.app.data.youtube.BrowseCollection,
        cursor: com.raagmusic.app.data.youtube.CollectionCursor, ids: Set<String>, token: String,
        repository: YoutubeMusicRepository = youtube) {
        cancelCollectionContinuation()
        collectionContinuationJob = persistenceScope.launch {
            try { com.raagmusic.app.playback.loadRemainingCollection(this@RaagApplication, repository, collection, cursor, ids, token) }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { playbackMessages.emit("Couldn't load the rest of this collection. Reopen it to retry.") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { playbackMessages.emit("Couldn't load the rest of this collection. Reopen it to retry.") }
        }
    }
    /** An app-owned connection completes the exit command after the Activity's controller is gone. */
    fun stopPlaybackOnExit() {
        persistenceScope.launch {
            var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
            try {
                kotlinx.coroutines.withTimeout(8_000) {
                    if (!features.state.first().preferences.stopOnExit) return@withTimeout
                    future = androidx.media3.session.MediaController.Builder(this@RaagApplication,
                        androidx.media3.session.SessionToken(this@RaagApplication,
                            android.content.ComponentName(this@RaagApplication, com.raagmusic.app.playback.PlaybackService::class.java))).buildAsync()
                    val controller = await(requireNotNull(future))
                    await(controller.sendCustomCommand(androidx.media3.session.SessionCommand(
                        com.raagmusic.app.playback.PlaybackService.EXIT_APP, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("RaagExit", "Couldn't stop playback on exit", e) }
            finally { future?.let { androidx.media3.session.MediaController.releaseFuture(it) } }
        }
    }
    private suspend fun <T> await(future: com.google.common.util.concurrent.ListenableFuture<T>): T = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        c.invokeOnCancellation { future.cancel(true) }
        future.addListener({ try { if (c.isActive) c.resumeWith(Result.success(future.get())) } catch (e: Exception) { if (c.isActive) c.resumeWith(Result.failure(e)) } }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
    }
    val persistenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val features by lazy { com.raagmusic.app.data.FeatureStore(this) }
    val lyrics by lazy { com.raagmusic.app.data.LyricsRepository(directory = java.io.File(cacheDir, "lyrics")) }
    val library by lazy { LibraryStore(this) }
    val youtube by lazy { YoutubeMusicRepository(metadataDirectory = java.io.File(cacheDir, "artist-metadata"),
        audioSessionDirectory = java.io.File(cacheDir, "playback-session"), initialCountry = com.raagmusic.app.data.DeviceCountryResolver.detect(this).providerCode) }
    val recommendations by lazy { com.raagmusic.app.data.RecommendationEngine(youtube) }
    val media by lazy { MediaCache(this) { id -> youtube.getAudioStream(id) } }
}
