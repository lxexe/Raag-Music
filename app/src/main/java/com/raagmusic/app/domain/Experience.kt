package com.raagmusic.app.domain

val defaultHomeSections = listOf("quick", "moods", "recent", "recommended", "trending", "releases", "playlists", "genres", "artists", "albums")
val defaultHomeMoods = listOf("For you", "Romance", "Dance", "Party", "Feel good", "Workout", "Relax", "Focus", "Sleep")
data class ExperiencePreferences(
    val pinnedPlaylists: Set<String> = emptySet(),
    val followedArtists: Set<String> = emptySet(),
    val libraryGrid: Boolean = false,
    val lyricTextSize: Int = 23,
    val lyricBlur: Boolean = true,
    val lyricCentered: Boolean = false,
    val lyricOffsetMs: Long = 0,
    val homeMoods: List<String> = defaultHomeMoods,
) {
    fun sanitized() = copy(
        homeMoods = (listOf("For you") + homeMoods.map { it.trim().replace(Regex("\\s+"), " ").take(24) })
            .filter { it.isNotBlank() }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(12),
        lyricTextSize = lyricTextSize.coerceIn(16, 34), lyricOffsetMs = lyricOffsetMs.coerceIn(-10_000, 10_000),
    )
}
