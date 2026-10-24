package com.raagmusic.app.benchmarks

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.raagmusic.app"
private fun MacrobenchmarkScope.journey() {
    startActivityAndWait()
    device.waitForIdle()
    device.findObject(By.text("Skip for now"))?.click()
    repeat(3) { device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20) }
    device.findObject(By.text("Library"))?.click(); device.waitForIdle()
    device.findObject(By.text("Songs"))?.let { songs ->
        songs.click(); device.waitForIdle()
        val y = songs.visibleBounds.centerY()
        device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 20)
    }
    device.findObject(By.text("Insights"))?.click(); device.waitForIdle()
    device.findObject(By.text("Search"))?.click(); device.waitForIdle(); device.pressBack()
    device.findObject(By.text("Discover"))?.click(); device.waitForIdle()
}

@RunWith(AndroidJUnit4::class)
class BaselineProfiles {
    @get:Rule val rule = BaselineProfileRule()
    @Test fun startupAndNavigation() = rule.collect(PACKAGE, includeInStartupProfile = false,
        filterPredicate = { it.substringAfter('L').startsWith("com/raagmusic/app/") }) { journey() }
}
@RunWith(AndroidJUnit4::class)
class UiBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()
    @Test fun startup() = rule.measureRepeated(PACKAGE, metrics = listOf(StartupTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Partial(), startupMode = StartupMode.COLD, setupBlock = { pressHome() }) { startActivityAndWait() }
    @Test fun navigationAndScroll() = rule.measureRepeated(PACKAGE, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Partial(), setupBlock = { pressHome() }) { journey() }
}
