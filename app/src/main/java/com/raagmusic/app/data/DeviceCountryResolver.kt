package com.raagmusic.app.data

import android.content.Context
import android.telephony.TelephonyManager
import com.raagmusic.app.domain.MusicRegion
import com.raagmusic.app.domain.chooseMusicRegion

/** Country-level device signals only. No GPS, coordinates, or runtime location permission. */
object DeviceCountryResolver {
    fun detect(context: Context): MusicRegion {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val network = runCatching { telephony?.networkCountryIso }.getOrNull()
        val sim = runCatching { telephony?.simCountryIso }.getOrNull()
        val locales = context.resources.configuration.locales
        return chooseMusicRegion(network, sim, List(locales.size()) { locales[it].country })
    }
}
