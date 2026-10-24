package com.raagmusic.app.domain

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CountrySource(val label: String) { NETWORK("Mobile network"), SIM("SIM country"), DEVICE("Device region"), MANUAL("Your selection"), GLOBAL("Worldwide") }

@Immutable
data class MusicRegion(val code: String? = null, val source: CountrySource = CountrySource.GLOBAL) {
    val providerCode: String get() = code ?: "US"
    val queryName: String get() = code?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH) } ?: "global"
    fun displayName(locale: Locale = Locale.getDefault()): String = code?.let { Locale("", it).getDisplayCountry(locale) } ?: "Worldwide"
    val flag: String get() = code?.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }?.joinToString("") ?: "🌍"
}

private val countryCodes = Locale.getISOCountries().toSet()
fun validCountryCode(value: String?): String? = value?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in countryCodes }

/** Prefer the current network while roaming; tablets and offline phones fall back safely. */
fun chooseMusicRegion(network: String?, sim: String?, deviceCountries: List<String>): MusicRegion {
    validCountryCode(network)?.let { return MusicRegion(it, CountrySource.NETWORK) }
    validCountryCode(sim)?.let { return MusicRegion(it, CountrySource.SIM) }
    deviceCountries.firstNotNullOfOrNull(::validCountryCode)?.let { return MusicRegion(it, CountrySource.DEVICE) }
    return MusicRegion()
}

data class DiscoveryPreferences(val countryOverride: String? = null, val recentSearches: List<String> = emptyList()) {
    fun withSearch(query: String): DiscoveryPreferences {
        val cleaned = query.trim().replace(Regex("\\s+"), " ").take(120)
        if (cleaned.isEmpty()) return this
        return copy(recentSearches = (listOf(cleaned) + recentSearches.filterNot { it.equals(cleaned, true) }).take(12))
    }
}

data class HomeQueries(val playlists: String, val trending: String, val newReleases: String, val albums: String)
fun regionalAlbumQuery(region: MusicRegion): String = when (region.code) {
    "IN" -> "Bollywood"; "BR" -> "MPB"; "JP" -> "J-pop"; "KR" -> "K-pop"
    "MX", "ES", "AR", "CO", "CL" -> "Latin"; "FR" -> "French pop"; "DE" -> "German pop"
    else -> "pop"
}
fun homeQueries(region: MusicRegion, mood: String, date: LocalDate): HomeQueries {
    val place = region.queryName
    val feeling = when (mood.lowercase(Locale.ROOT)) {
        "for you" -> null
        "chill", "relax" -> "chill relaxing"
        "focus" -> "instrumental focus"
        "energy", "dance" -> "upbeat dance"
        "romance" -> "romantic love"
        "party" -> "party hits"
        "feel good" -> "feel good happy"
        "workout" -> "workout motivation"
        "sleep" -> "sleep calm"
        else -> mood.trim().take(24).lowercase(Locale.ROOT)
    }
    val month = date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
    return HomeQueries(
        playlists = if (feeling == null) "Top songs $place" else "$feeling music $place",
        trending = if (feeling == null) "trending songs $place" else "$feeling songs $place",
        newReleases = "new songs $place $month",
        albums = "new albums $place ${date.year}",
    )
}

fun timeOfDayMusicQuery(region: MusicRegion, hour: Int): String {
    val moment = when (hour) {
        in 5..10 -> "morning feel good songs"
        in 11..16 -> "afternoon upbeat songs"
        in 17..21 -> "evening acoustic songs"
        else -> "late night relaxing songs"
    }
    return "$moment ${region.queryName}"
}
