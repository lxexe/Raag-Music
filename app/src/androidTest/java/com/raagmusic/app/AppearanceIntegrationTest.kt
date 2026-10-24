package com.raagmusic.app

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.*
import com.raagmusic.app.ui.raagColorScheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AppearanceIntegrationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
    private fun argb(color: androidx.compose.ui.graphics.Color) = color.toArgb().toLong() and 0xFFFFFFFF
    @Test fun lightDarkAndAmoledPalettesHaveReadableRoles() {
        ThemeMode.entries.forEach { mode -> listOf(false, true).forEach { systemDark ->
            val scheme = raagColorScheme(ThemePreferences(mode), mode.isDark(systemDark))
            assertTrue(AccentPalette.contrast(argb(scheme.onSurface), argb(scheme.background)) >= 7.0)
            assertTrue(AccentPalette.contrast(argb(scheme.onSurfaceVariant), argb(scheme.surfaceContainer)) >= 4.5)
            assertTrue(AccentPalette.contrast(argb(scheme.onPrimary), argb(scheme.primary)) >= 4.5)
            if (mode == ThemeMode.AMOLED) assertEquals(0xFF000000, argb(scheme.background))
        } }
    }
    @Test fun customPaletteRemainsReadableAcrossHues() {
        (0..359 step 15).forEach { hue -> listOf(false, true).forEach { dark ->
            val scheme = raagColorScheme(ThemePreferences(colors = ThemeColors.CUSTOM,
                accent = AccentPalette.tone(hue.toDouble(), 1.0, .5)), dark)
            assertTrue(AccentPalette.contrast(argb(scheme.onSurface), argb(scheme.surfaceContainer)) >= 7.0)
            assertTrue(AccentPalette.contrast(argb(scheme.onPrimary), argb(scheme.primary)) >= 4.5)
        } }
    }
    @Test fun wallpaperPalettesResolveOnSupportedAndroid() {
        if (Build.VERSION.SDK_INT < 31) return
        listOf(dynamicDarkColorScheme(app), dynamicLightColorScheme(app)).forEach { scheme ->
            assertTrue(AccentPalette.contrast(argb(scheme.primary), argb(scheme.onPrimary)) >= 4.5)
            assertTrue(AccentPalette.contrast(argb(scheme.background), argb(scheme.onBackground)) >= 7.0)
        }
    }
    @Test fun concurrentAppearanceAndPreferenceUpdatesRemainAtomic() = runBlocking {
        val original = app.features.state.first()
        try {
            val theme = ThemePreferences(ThemeMode.AMOLED, ThemeColors.CUSTOM, 0xFF08916D, SurfaceStyle.GLASS, CornerStyle.ROUND, true)
            coroutineScope {
                launch { app.features.update { it.copy(theme = theme) } }
                launch { app.features.update { it.copy(preferences = it.preferences.copy(mobileQuality = AudioQuality.BALANCED)) } }
            }
            val saved = app.features.state.first()
            assertEquals(theme, saved.theme)
            assertEquals(AudioQuality.BALANCED, saved.preferences.mobileQuality)
            assertEquals(original.queue, saved.queue)
            assertEquals(original.history, saved.history)
        } finally { app.features.update { original } }
        Unit
    }
    @Test fun systemThemeTracksDeviceChangesAfterExplicitLightAndDark() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = app.getSystemService(android.app.UiModeManager::class.java)
        val originalMode = manager.nightMode
        val original = app.features.state.first()
        fun deviceMode(mode: String) {
            instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
        suspend fun awaitNight(dark: Boolean) {
            withTimeout(15_000) {
                while (true) {
                    var mode = 0
                    scenario!!.onActivity { mode = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK }
                    if (mode == if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO) break
                    delay(100)
                }
            }
        }
        try {
            deviceMode("no")
            app.features.update { it.copy(theme = ThemePreferences(mode = ThemeMode.SYSTEM)) }
            scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            awaitNight(false)
            deviceMode("yes"); awaitNight(true)
            app.features.update { it.copy(theme = it.theme.copy(mode = ThemeMode.LIGHT)) }; awaitNight(false)
            deviceMode("no"); deviceMode("yes"); awaitNight(false)
            app.features.update { it.copy(theme = it.theme.copy(mode = ThemeMode.DARK)) }; awaitNight(true)
            deviceMode("no"); awaitNight(true)
            app.features.update { it.copy(theme = it.theme.copy(mode = ThemeMode.SYSTEM)) }; awaitNight(false)
        } finally {
            scenario?.close()
            app.features.update { original }
            deviceMode(when (originalMode) {
                android.app.UiModeManager.MODE_NIGHT_YES -> "yes"
                android.app.UiModeManager.MODE_NIGHT_NO -> "no"
                android.app.UiModeManager.MODE_NIGHT_CUSTOM -> "custom"
                else -> "auto"
            })
        }
        Unit
    }
}
