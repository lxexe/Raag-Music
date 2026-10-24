package com.raagmusic.app.analytics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/** Initialize according to the saved reporting preference; FirebaseInitProvider stays removed. */
internal object UsageAnalyticsBackend {
    fun create(context: Context): AnalyticsBackend = object : AnalyticsBackend {
        private var analytics: FirebaseAnalytics? = null
        override fun setEnabled(enabled: Boolean) {
            if (enabled) {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    if (FirebaseApp.initializeApp(context) == null) return
                }
                val instance = analytics ?: FirebaseAnalytics.getInstance(context).also { analytics = it }
                instance.setConsent(mapOf(
                    FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to FirebaseAnalytics.ConsentStatus.GRANTED,
                    FirebaseAnalytics.ConsentType.AD_STORAGE to FirebaseAnalytics.ConsentStatus.DENIED,
                    FirebaseAnalytics.ConsentType.AD_USER_DATA to FirebaseAnalytics.ConsentStatus.DENIED,
                    FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to FirebaseAnalytics.ConsentStatus.DENIED,
                ))
                instance.setAnalyticsCollectionEnabled(true)
            } else {
                analytics?.let {
                    it.setAnalyticsCollectionEnabled(false)
                    it.resetAnalyticsData()
                    it.setConsent(mapOf(FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to FirebaseAnalytics.ConsentStatus.DENIED))
                }
            }
        }
    }
}
