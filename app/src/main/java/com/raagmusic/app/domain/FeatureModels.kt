package com.raagmusic.app.domain

import java.time.LocalDate

/** Limits apply to the provider's available audio, not a promised encoding bitrate. */
enum class AudioQuality(val label: String, val maxBitrate: Int) {
    HIGH("Highest available", 0), BALANCED("Balanced · up to 128 kbps", 128_000),
    DATA_SAVER("Data saver · up to 64 kbps", 64_000)
}
enum class VideoPlaybackMode(val label: String) {
    ALWAYS("Always play video"), FALLBACK("Video when audio is unavailable")
}
enum class VideoQuality(val label: String, val maxHeight: Int) {
    HIGHEST("Highest available", Int.MAX_VALUE), HD("720p", 720), SD("480p", 480), LOW("360p", 360)
}
data class ListeningPreferences(
    val rememberQueue: Boolean = true,
    val wifiQuality: AudioQuality = AudioQuality.HIGH,
    val mobileQuality: AudioQuality = AudioQuality.HIGH,
    val collectHistory: Boolean = true,
    val transitionMode: TransitionMode = TransitionMode.GAPLESS,
    val crossfadeSeconds: Int = 5,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryFilter: LibraryFilter = LibraryFilter.ALL,
    val stopOnExit: Boolean = false,
    val analyticsEnabled: Boolean = true,
    val videoMode: VideoPlaybackMode = VideoPlaybackMode.FALLBACK,
    val videoQuality: VideoQuality = VideoQuality.HIGHEST,
)
data class QueueSnapshot(
    val tracks: List<Track> = emptyList(), val index: Int = 0, val positionMs: Long = 0,
    val shuffle: Boolean = false, val repeat: Int = 0, val recommendationChain: Boolean = false,
) {
    fun sanitized(): QueueSnapshot = copy(index = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)),
        positionMs = positionMs.coerceAtLeast(0), repeat = repeat.takeIf { it in 0..2 } ?: 0)
}
data class ListenRecord(val trackId: String, val day: String, val milliseconds: Long = 0,
    val plays: Int = 0, val skips: Int = 0, val completions: Int = 0)
data class FeatureState(
    val preferences: ListeningPreferences = ListeningPreferences(),
    val queue: QueueSnapshot = QueueSnapshot(),
    val history: List<ListenRecord> = emptyList(), val lessLikeIds: Set<String> = emptySet(),
    val historyEpoch: Long = 0,
    val theme: ThemePreferences = ThemePreferences(),
    val discovery: DiscoveryPreferences = DiscoveryPreferences(),
    val profile: ListenerProfile = ListenerProfile(),
    val sleepTimer: SleepTimerSpec = SleepTimerSpec(),
    val experience: ExperiencePreferences = ExperiencePreferences(),
) {
    fun record(id: String, day: LocalDate, ms: Long = 0, plays: Int = 0, skips: Int = 0, completions: Int = 0): FeatureState {
        if (!preferences.analyticsEnabled) return this
        val key = day.toString()
        val existing = history.find { it.trackId == id && it.day == key } ?: ListenRecord(id, key)
        val updated = existing.copy(milliseconds = existing.milliseconds + ms.coerceAtLeast(0),
            plays = existing.plays + plays.coerceAtLeast(0), skips = existing.skips + skips.coerceAtLeast(0), completions = existing.completions + completions.coerceAtLeast(0))
        return copy(history = (history.filterNot { it.trackId == id && it.day == key } + updated)
            .takeLast(20_000))
    }
}
/** Deterministic per-day variety, weighted by likes, listening and early skips. */
fun dailyMix(candidates: List<Track>, library: LibraryState, features: FeatureState, day: LocalDate): List<Track> {
    val stats = features.history.groupBy { it.trackId }
    val artistAffinity = library.savedTracks.groupBy { it.artist }.mapValues { (_, tracks) ->
        tracks.sumOf { t -> (if (t.id in library.favorites) 4.0 else 0.0) +
            (stats[t.id]?.sumOf { it.milliseconds } ?: 0L) / 600_000.0 }
    }
    return candidates.distinctBy { it.id }.filterNot { it.id in features.lessLikeIds }
        .sortedByDescending { t ->
            val records = stats[t.id].orEmpty()
            (if (t.id in library.favorites) 5.0 else 0.0) + (artistAffinity[t.artist] ?: 0.0).coerceAtMost(12.0) +
                records.sumOf { it.milliseconds } / 600_000.0 - records.sumOf { it.skips } * 2.0 +
                java.util.Random((day.toEpochDay().toString() + t.id).hashCode().toLong()).nextDouble() * 4
        }.take(30)
}
