package com.raagmusic.app.updates

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.RaagApplication
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal class PreferenceUpdateStorage(context: Context) : UpdateStorage {
    private val preferences by lazy { context.getSharedPreferences("app_updates", Context.MODE_PRIVATE) }
    override fun read() = SavedUpdate(
        preferences.getString("release_json", null), preferences.getString("etag", null),
        preferences.getLong("checked", 0), preferences.getLong("next_attempt", 0), preferences.getString("dismissed", null),
        preferences.getLong("manual_after", 0),
    )
    override fun write(value: SavedUpdate) {
        check(preferences.edit().putString("release_json", value.json).putString("etag", value.etag)
            .putLong("checked", value.checked).putLong("next_attempt", value.nextAttempt)
            .putLong("manual_after", value.manualAfter)
            .putString("dismissed", value.dismissed).commit()) { "Couldn't save update check" }
    }
}

internal class GitHubReleaseFetcher : ReleaseFetcher {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    override suspend fun fetch(etag: String?): ReleaseResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Raag-Music-Update-Checker")
            .apply { etag?.let { header("If-None-Match", it) } }.build()
        client.newCall(request).execute().use { response ->
            ensureActive()
            when (response.code) {
                304 -> ReleaseResponse.Unchanged
                404 -> ReleaseResponse.NonePublished
                200 -> {
                    val source = response.body?.source() ?: throw ReleaseRequestException()
                    source.request(1_048_577)
                    if (source.buffer.size > 1_048_576) throw ReleaseRequestException()
                    ReleaseResponse.Found(source.readUtf8(), response.header("ETag"))
                }
                else -> {
                    val now = System.currentTimeMillis()
                    val retry = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 86_400)?.let { now + it * 1000 }
                        ?: response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.let { it * 1000 }
                    throw ReleaseRequestException(retry)
                }
            }
        }
    }
}

@UnstableApi
internal object UpdateSchedule {
    private const val ID = 72041
    fun ensure(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(ID) != null) return
        scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, UpdateCheckService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(DAY_MS, 6 * 60 * 60 * 1000L)
            .setPersisted(true)
            .build())
    }
}

@UnstableApi
class UpdateCheckService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                (application as RaagApplication).updates.check()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Next scheduled or foreground check can retry. */ }
            if (isActive) jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
