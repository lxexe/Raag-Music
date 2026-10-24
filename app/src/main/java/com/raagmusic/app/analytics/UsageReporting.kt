package com.raagmusic.app.analytics

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Separate from on-device listening Insights; no library or playback data enters this API. */
class UsageReporting internal constructor(
    private val preferences: SharedPreferences,
    private val backend: AnalyticsBackend?,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("usage_reporting", Context.MODE_PRIVATE),
        UsageAnalyticsBackend.create(context.applicationContext),
    )
    val available: Boolean get() = backend != null
    // Preserve an existing opt-out. New Firebase installations start with reporting enabled.
    private val choice = MutableStateFlow(available && preferences.getBoolean("enabled", true))
    val enabled = choice.asStateFlow()

    init { backend?.setEnabled(choice.value) }

    fun setEnabled(enabled: Boolean) {
        if (!available) return
        // Persist before enabling any network collection. A failed save keeps collection off.
        if (!preferences.edit().putBoolean("enabled", enabled).commit()) return
        backend?.setEnabled(enabled)
        choice.value = enabled
    }
}

internal interface AnalyticsBackend {
    fun setEnabled(enabled: Boolean)
}
