package com.raagmusic.app

import android.net.ConnectivityManager
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Explicitly opt-in: toggles networking only on the disposable emulator used by the audit. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ConnectivityRecoveryTest {
    @Test fun validatedReconnectResumesAfterTheTimedRetryBudgetIsExhausted() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("toggleEmulatorNetwork") == "true")
        Assume.assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val app = instrumentation.targetContext.applicationContext as RaagApplication
        val previous = app.features.state.first(); val library = app.library.state.first()
        val wifi = Settings.Global.getInt(app.contentResolver, "wifi_on", 1) != 0
        val mobile = Settings.Global.getInt(app.contentResolver, "mobile_data", 1) != 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val events = CopyOnWriteArrayList<PlaybackTrace.Event>()
        var connection: PlayerConnection? = null
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        suspend fun eventually(timeout: Long = 35_000, check: suspend () -> Boolean) = withTimeout(timeout) { while (!check()) delay(50) }
        try {
            app.features.update { FeatureState(preferences = ListeningPreferences(rememberQueue = false, collectHistory = false)) }
            withContext(Dispatchers.IO) { app.media.streamingCache.keys.toList().forEach(app.media.streamingCache::removeResource) }
            withContext(Dispatchers.IO) { shell("svc wifi disable"); shell("svc data disable") }
            eventually(8_000) { app.getSystemService(ConnectivityManager::class.java).activeNetwork == null }
            PlaybackTrace.observer = { events.add(it) }
            withContext(Dispatchers.Main) {
                connection = PlayerConnection(app, scope)
                connection!!.play(Track("kJQP7kiw5Fk", "Reconnect probe", "Probe", "", "kJQP7kiw5Fk", Source.YOUTUBE), emptyList(), ordered = true)
            }
            eventually { events.any { it.stage == "recovery_budget_exhausted" } }
            // The service's error event precedes the asynchronous controller state update.
            eventually { connection!!.state.value.error != null }
            Assert.assertNotNull(connection!!.state.value.error)
            withContext(Dispatchers.IO) { shell("svc wifi enable"); shell("svc data enable") }
            eventually { events.any { it.stage == "connection_restored_retry" } && connection!!.state.value.playing }
            Assert.assertEquals("kJQP7kiw5Fk", connection!!.state.value.trackId)
            android.util.Log.i("RaagReconnectProbe", "RESULT resumedAfterBudget=true networkCallbackRetries=" + events.count { it.stage == "connection_restored_retry" })
        } finally {
            withContext(Dispatchers.IO) { shell("svc wifi ${if (wifi) "enable" else "disable"}"); shell("svc data ${if (mobile) "enable" else "disable"}") }
            withContext(Dispatchers.Main) { connection?.pause(); connection?.close() }
            scope.cancel(); PlaybackTrace.observer = null
            app.features.update { previous }; app.library.update { library }
        }
        Unit
    }
}
