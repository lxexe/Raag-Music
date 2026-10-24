package com.raagmusic.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.analytics.AnalyticsBackend
import com.raagmusic.app.analytics.UsageReporting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated preference file and fake transport; never enables Firebase/network collection. */
@RunWith(AndroidJUnit4::class)
class UsageReportingTest {
    private fun preferences() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("reporting_test_${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
    private class FakeBackend : AnalyticsBackend {
        val decisions = mutableListOf<Boolean>()
        override fun setEnabled(enabled: Boolean) { decisions += enabled }
    }

    @Test fun newFirebaseInstallationEnablesBasicReportingByDefault() {
        val backend = FakeBackend()
        val reporting = UsageReporting(preferences(), backend)
        assertTrue(reporting.enabled.value)
        assertEquals(listOf(true), backend.decisions)
    }

    @Test fun declinePersistsAndRemainsDisabledAfterRestart() {
        val preferences = preferences()
        preferences.edit().putBoolean("enabled", false).commit()
        val backend = FakeBackend()
        UsageReporting(preferences, backend).setEnabled(false)
        val restarted = UsageReporting(preferences, backend)
        assertEquals(false, restarted.enabled.value)
        assertFalse(backend.decisions.any { it })
    }

    @Test fun explicitChoicesPersistAcrossRestart() {
        val preferences = preferences()
        val backend = FakeBackend()
        val reporting = UsageReporting(preferences, backend)
        reporting.setEnabled(true)
        assertEquals(true, reporting.enabled.value)
        assertEquals(true, UsageReporting(preferences, backend).enabled.value)
        reporting.setEnabled(false)
        assertEquals(false, UsageReporting(preferences, backend).enabled.value)
        assertEquals(listOf(true, true, true, false, false), backend.decisions)
    }

    @Test fun buildWithoutBackendCannotEnableReporting() {
        val reporting = UsageReporting(preferences(), null)
        reporting.setEnabled(true)
        assertFalse(reporting.available)
        assertFalse(reporting.enabled.value)
    }

    @Test fun turningOffDefaultReportingRemainsOffAfterRestart() {
        val preferences = preferences()
        val backend = FakeBackend()
        val reporting = UsageReporting(preferences, backend)
        reporting.setEnabled(false)
        assertFalse(UsageReporting(preferences, backend).enabled.value)
        assertEquals(listOf(true, false, false), backend.decisions)
    }
}
