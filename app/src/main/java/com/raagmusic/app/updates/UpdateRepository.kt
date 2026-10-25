package com.raagmusic.app.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

internal data class UpdateState(
    val release: GitHubRelease? = null,
    val checking: Boolean = false,
    val lastChecked: Long = 0,
    val error: String? = null,
    val dismissedVersion: String? = null,
    val notifiedVersion: String? = null,
)
internal data class SavedUpdate(
    val json: String? = null, val etag: String? = null, val checked: Long = 0,
    val nextAttempt: Long = 0, val dismissed: String? = null, val manualAfter: Long = 0, val notified: String? = null,
)
internal interface UpdateStorage {
    fun read(): SavedUpdate
    fun write(value: SavedUpdate)
}
internal sealed interface ReleaseResponse {
    data class Found(val json: String, val etag: String?) : ReleaseResponse
    data object Unchanged : ReleaseResponse
    data object NonePublished : ReleaseResponse
}
internal class ReleaseRequestException(val retryAt: Long? = null) : IOException("Release check failed")
internal fun interface ReleaseFetcher { suspend fun fetch(etag: String?): ReleaseResponse }

internal class UpdateRepository(
    private val storage: UpdateStorage,
    private val fetcher: ReleaseFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var saved = SavedUpdate()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()

    private suspend fun loadLocked() {
        if (loaded) return
        saved = withContext(Dispatchers.IO) { storage.read() }
        val release = runCatching { saved.json?.let(::parseRelease) }.getOrNull()
        if (saved.json != null && release == null) saved = saved.copy(json = null, etag = null, checked = 0)
        mutableState.value = UpdateState(release = release, lastChecked = saved.checked, dismissedVersion = saved.dismissed, notifiedVersion = saved.notified)
        loaded = true
    }
    suspend fun load() = mutex.withLock { loadLocked() }

    /** Serialized across background/manual calls. Manual checks bypass the morning/evening cache, not server backoff. */
    suspend fun check(manual: Boolean = false) = mutex.withLock {
        try { loadLocked() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "Couldn't read update settings. Please try again later.")
            return@withLock
        }
        val now = clock()
        // Bound persisted clock values so changing the device clock cannot block checks indefinitely.
        if (saved.nextAttempt > now + DAY_MS || saved.manualAfter > now + DAY_MS) {
            saved = saved.copy(nextAttempt = 0, manualAfter = 0, checked = 0)
        }
        val blockedUntil = if (manual) saved.manualAfter else saved.nextAttempt
        val recentlyChecked = UpdateWindows.sameWindow(saved.checked, now)
        if (now < blockedUntil || (!manual && recentlyChecked)) {
            if (manual && now < blockedUntil) mutableState.value = mutableState.value.copy(error = "Please wait a little before checking again.")
            return@withLock
        }
        mutableState.value = mutableState.value.copy(checking = true, error = null)
        try {
            val response = fetcher.fetch(saved.etag.takeIf { saved.json != null })
            currentCoroutineContext().ensureActive()
            val next = when (response) {
                is ReleaseResponse.Found -> {
                    // Reject malformed responses without discarding the last known release.
                    val release = parseRelease(response.json)
                    if (release == null) throw IOException("Expected a stable release")
                    saved.copy(json = response.json, etag = response.etag, checked = now, nextAttempt = now + 30_000, manualAfter = now + 30_000)
                }
                ReleaseResponse.Unchanged -> {
                    if (saved.json == null) throw IOException("No cached release")
                    saved.copy(checked = now, nextAttempt = now + 30_000, manualAfter = now + 30_000)
                }
                ReleaseResponse.NonePublished -> saved.copy(json = null, etag = null, checked = now, nextAttempt = now + 30_000, manualAfter = now + 30_000)
            }
            withContext(Dispatchers.IO) { storage.write(next) }
            saved = next
            mutableState.value = mutableState.value.copy(release = next.json?.let(::parseRelease), lastChecked = now, error = null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val serverRetry = (error as? ReleaseRequestException)?.retryAt
            val backoff = serverRetry ?: (now + 60 * 60 * 1000)
            val next = saved.copy(nextAttempt = backoff.coerceIn(now + 30_000, now + DAY_MS),
                manualAfter = (serverRetry ?: (now + 30_000)).coerceIn(now + 30_000, now + DAY_MS))
            try { withContext(Dispatchers.IO) { storage.write(next) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Full/unavailable storage must not crash playback. */ }
            saved = next
            mutableState.value = mutableState.value.copy(error = "Couldn't check GitHub for updates. Check your connection and try again later.")
        } finally {
            mutableState.value = mutableState.value.copy(checking = false)
        }
    }

    suspend fun dismiss() = mutex.withLock {
        val next = saved.copy(dismissed = mutableState.value.release?.version)
        try { withContext(Dispatchers.IO) { storage.write(next) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Still allow dismissal for this session. */ }
        saved = next
        mutableState.value = mutableState.value.copy(dismissedVersion = next.dismissed)
    }
    suspend fun markNotified(version: String) = mutex.withLock {
        val next = saved.copy(notified = version)
        try { withContext(Dispatchers.IO) { storage.write(next) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { return@withLock }
        saved = next
        mutableState.value = mutableState.value.copy(notifiedVersion = version)
    }

}
