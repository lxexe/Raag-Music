package com.raagmusic.app.domain

import java.time.LocalDate
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

private fun String.key() = trim().removeSuffix(" - Topic").lowercase(Locale.ROOT)
private val artistSeparator = Regex(",\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)
private val recordingPunctuation = Regex("[^\\p{L}\\p{N}]")
internal fun Track.artistKeys() = artist.split(artistSeparator)
    .map { it.key() }.filter { it.isNotEmpty() && it !in setOf("unknown artist", "youtube music", "various artists") }.toSet()

/** Upload labels are aliases; live/remix/acoustic version markers remain part of identity. */
internal fun Track.recordingKey(): String? {
    if (title.isBlank() || artist.isBlank() || artist.equals("Unknown artist", true)) return null
    fun clean(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(recordingPunctuation, "")
    val names = artistKeys().sorted().joinToString("|") { clean(it) }
    return "${clean(lyricsTrackName(title, artist.removeSuffix(" - Topic")))}|$names"
}
internal fun sameRecording(first: Track, second: Track): Boolean = first.recordingKey()?.let { key ->
    key == second.recordingKey() && (first.durationSeconds <= 0 || second.durationSeconds <= 0 ||
        kotlin.math.abs(first.durationSeconds - second.durationSeconds) <= 5)
} == true

/** Provider order supplies similarity; local feedback personalizes it without a random next song. */
fun rankRecommendations(
    candidates: List<Track>, seed: Track?, library: LibraryState, features: FeatureState,
    day: LocalDate = LocalDate.now(), hour: Int = 12, relatedIds: Set<String> = emptySet(),
    similarArtists: Set<String> = emptySet(), excludedIds: Set<String> = emptySet(), limit: Int = 20,
    previousIds: Set<String> = emptySet(), variation: Int = 0, forQuickPicks: Boolean = false,
): List<Track> {
    val allTracks = library.savedTracks + candidates + listOfNotNull(seed)
    val artistCache = mutableMapOf<String, Set<String>>()
    val artistKeys = allTracks.associate { it.id to artistCache.getOrPut(it.artist) { it.artistKeys() } }
    val recordingKeys = allTracks.associate { it.id to it.recordingKey() }
    fun same(first: Track, second: Track): Boolean = recordingKeys[first.id]?.let { key -> key == recordingKeys[second.id] &&
        (first.durationSeconds <= 0 || second.durationSeconds <= 0 || kotlin.math.abs(first.durationSeconds - second.durationSeconds) <= 5) } == true
    val history = features.history.groupBy { it.trackId }
    val dates = features.history.map { it.day }.distinct().associateWith { runCatching { LocalDate.parse(it) }.getOrNull() }
    val weights = dates.mapValues { (_, date) -> date?.let { .5.pow(java.time.temporal.ChronoUnit.DAYS.between(it, day).coerceAtLeast(0) / 30.0) } ?: 0.0 }
    fun weight(record: ListenRecord) = weights[record.day] ?: 0.0
    val artistAffinity = mutableMapOf<String, Double>()
    val genreAffinity = mutableMapOf<String, Double>()
    val moodAffinity = mutableMapOf<String, Double>()
    val followed = features.experience.followedArtists.map { it.key() }.toSet()
    library.savedTracks.forEach { track ->
        val records = history[track.id].orEmpty()
        val affinity = if (track.id in features.lessLikeIds) -1.0 else (if (track.id in library.favorites) 3.0 else 0.0) +
            ln(1.0 + records.filter { it.hasPositiveListen() }.sumOf { it.milliseconds * weight(it) } / 120_000.0) +
            records.sumOf { it.completions * weight(it) }.coerceAtMost(8.0) * 1.5 -
            records.sumOf { it.skips * weight(it) } * .8
        artistKeys.getValue(track.id).forEach { artistAffinity[it] = (artistAffinity[it] ?: 0.0) + affinity }
        if (track.genre.isNotBlank()) genreAffinity.merge(track.genre.key(), affinity, Double::plus)
        if (track.mood.isNotBlank()) moodAffinity.merge(track.mood.key(), affinity, Double::plus)
    }
    val seedArtists = seed?.let { artistKeys[it.id] }.orEmpty()
    val similar = similarArtists.map { it.key() }.toSet()
    val recent = library.recentIds.take(12).toSet()
    val timeMood = when (hour) { in 5..10 -> "feel good"; in 11..16 -> "workout"; in 17..21 -> "romance"; else -> "sleep" }
    val excludedRecordings = allTracks.filter { it.id == seed?.id || it.id in excludedIds || it.id in features.lessLikeIds }.groupBy { recordingKeys[it.id] }
    val scored = candidates.distinctBy { it.id }.filter {
        it.id != seed?.id && it.id !in features.lessLikeIds && it.id !in excludedIds &&
            (it.durationSeconds == 0L || it.durationSeconds in 30..900) &&
            excludedRecordings[recordingKeys[it.id]].orEmpty().none { excluded -> same(it, excluded) }
    }.mapIndexed { index, track ->
        val artists = artistKeys.getValue(track.id)
        val records = history[track.id].orEmpty()
        val provider = if (track.id in relatedIds) (if (forQuickPicks) 4.0 else 12.0) / (1 + index * .07) else 1.5 / (1 + index * .05)
        val lastHeard = records.filter { it.plays > 0 || it.milliseconds >= 30_000 }.mapNotNull { dates[it.day] }.maxOrNull()
        val daysSince = lastHeard?.let { java.time.temporal.ChronoUnit.DAYS.between(it, day) }
        val repetitionPenalty = (if (forQuickPicks) .35 else 1.0) * when (daysSince) { 0L -> 18.0; 1L -> 14.0; 2L -> 10.0; 3L -> 6.0; else -> 0.0 }
        // Seed a PRNG rather than comparing String.hashCode: adjacent dates need different ordering.
        val dailyVariety = java.util.Random(("${day.toEpochDay()}:${hour / 6}:$variation:${track.id}").hashCode().toLong())
            .let { it.nextLong(); it.nextDouble() } * 2.5
        val score = provider + (if (artists.any { it in seedArtists }) 5.0 else 0.0) +
            (if (artists.any { it in similar }) 3.0 else 0.0) +
            (if (artists.any { it in followed }) 4.0 else 0.0) +
            (if (!seed?.album.isNullOrBlank() && track.album.key() == seed?.album?.key()) 4.0 else 0.0) +
            (if (!seed?.genre.isNullOrBlank() && track.genre.key() == seed?.genre?.key()) 3.0 else 0.0) +
            (if (!seed?.mood.isNullOrBlank() && track.mood.key() == seed?.mood?.key()) 2.0 else 0.0) +
            artists.sumOf { (artistAffinity[it] ?: 0.0).coerceIn(-6.0, if (forQuickPicks) 10.0 else 6.0) } +
            (genreAffinity[track.genre.key()] ?: 0.0).coerceIn(-3.0, 3.0) +
            (moodAffinity[track.mood.key()] ?: 0.0).coerceIn(-2.0, 2.0) +
            (if (track.id in library.favorites) 2.0 else 0.0) - records.sumOf { it.skips * weight(it) } * 2.0 -
            (if (track.id in recent) 3.0 else 0.0) + (if (track.mood.key() == timeMood) (if (forQuickPicks) .5 else 3.0) else 0.0) +
            dailyVariety - repetitionPenalty +
            (if (forQuickPicks) (ln(1 + records.filter { it.hasPositiveListen() }.sumOf { it.milliseconds * weight(it) } / 120_000.0).coerceAtMost(3.0) +
                records.sumOf { it.completions * weight(it) }.coerceAtMost(3.0) * 1.5) else 0.0) -
            (if (forQuickPicks && (artistAffinity.values.any { it > 0 } || followed.isNotEmpty()) &&
                track.id !in relatedIds && artists.none { (artistAffinity[it] ?: 0.0) > 0 || it in followed }) 8.0 else 0.0) - (if (track.id in previousIds) 20.0 else 0.0)
        track to score
    }.toMutableList()
    val result = mutableListOf<Track>()
    val artistCounts = mutableMapOf<String, Int>()
    while (scored.isNotEmpty() && result.size < limit) {
        // Prefer at most four songs per credited artist while alternatives are available.
        val familiarCount = result.count { it.id in library.favorites || history[it.id].orEmpty().any(ListenRecord::hasPositiveListen) }
        val freshSupported = if (forQuickPicks && familiarCount >= (limit * .4).toInt().coerceAtLeast(1)) scored.filter { (track, _) ->
            track.id !in library.favorites && history[track.id].orEmpty().none(ListenRecord::hasPositiveListen) &&
                (track.id in relatedIds || artistKeys.getValue(track.id).any { (artistAffinity[it] ?: 0.0) > 0 || it in followed })
        } else emptyList()
        val pool = freshSupported.ifEmpty { scored }
        val diverse = pool.filter { (track, _) -> artistKeys.getValue(track.id).all { (artistCounts[it] ?: 0) < 4 } }
        val best = diverse.ifEmpty { pool }.maxBy { (track, score) -> score - artistKeys.getValue(track.id).sumOf { (artistCounts[it] ?: 0) * 3.0 } }
        scored.removeAll { it == best || same(it.first, best.first) }
        result += best.first
        artistKeys.getValue(best.first.id).forEach { artistCounts[it] = (artistCounts[it] ?: 0) + 1 }
    }
    return result
}

private fun historyWeight(record: ListenRecord, day: LocalDate): Double {
    val date = runCatching { LocalDate.parse(record.day) }.getOrNull() ?: return 0.0
    val age = java.time.temporal.ChronoUnit.DAYS.between(date, day).coerceAtLeast(0)
    return .5.pow(age / 30.0)
}

/** Choose retrieval seeds from evidence, independently of daily song rotation/repeat penalties. */
fun preferredArtists(library: LibraryState, features: FeatureState, day: LocalDate = LocalDate.now()): List<String> {
    val history = features.history.groupBy { it.trackId }
    val scores = mutableMapOf<String, Pair<String, Double>>()
    features.experience.followedArtists.sorted().forEach { scores[it.key()] = it to 5.0 }
    library.savedTracks.filterNot { it.id in features.lessLikeIds }.forEach { track ->
        val records = history[track.id].orEmpty()
        val evidence = (if (track.id in library.favorites) 4.0 else 0.0) +
            ln(1 + records.filter { it.hasPositiveListen() }.sumOf { it.milliseconds * historyWeight(it, day) } / 120_000.0) +
            records.sumOf { it.completions * historyWeight(it, day) }.coerceAtMost(8.0) * 1.5 -
            records.sumOf { it.skips * historyWeight(it, day) }
        track.artist.split(Regex(",\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)).forEach { name ->
            val cleaned = name.trim().removeSuffix(" - Topic")
            if (cleaned.isNotBlank() && cleaned.key() !in setOf("unknown artist", "youtube music", "various artists")) {
                val key = cleaned.key()
                scores[key] = cleaned to ((scores[key]?.second ?: 0.0) + evidence)
            }
        }
    }
    return scores.values.filter { it.second > 0 }.sortedByDescending { it.second }.take(3).map { it.first }
}

/** Radios start from songs the listener actually kept, never an arbitrary saved search result. */
fun quickPickSeeds(library: LibraryState, features: FeatureState): List<Track> {
    val history = features.history.groupBy { it.trackId }
    val recent = library.recentIds.withIndex().associate { it.value to it.index }
    return library.savedTracks.filter { track ->
        track.source == Source.YOUTUBE && track.id !in features.lessLikeIds &&
            (track.id in library.favorites || history[track.id].orEmpty().any(ListenRecord::hasPositiveListen))
    }.sortedWith(compareBy<Track> { recent[it.id] ?: Int.MAX_VALUE }
        .thenByDescending { it.id in library.favorites }
        .thenByDescending { history[it.id].orEmpty().sumOf { record -> record.milliseconds } })
        .distinctBy { it.artistKeys() }.take(3)
}

/** A completed refresh shows another window of the most relevant candidates. */
fun rotateQuickPicks(candidates: List<Track>, revision: Int, limit: Int = 20): List<Track> {
    val pool = candidates.distinctBy { it.id }.take(40)
    if (pool.isEmpty()) return emptyList()
    val offset = ((revision.toLong() * 4) % pool.size).toInt()
    return (pool.drop(offset) + pool.take(offset)).take(limit)
}

/** Share the same diversity boundary between candidate acquisition and ranking. */
fun needsRecommendationVariety(candidates: List<Track>, limit: Int = 20): Boolean {
    val songs = candidates.distinctBy { it.id }
    if (songs.size < limit) return true
    val counts = songs.take(limit).flatMap { it.artistKeys() }.groupingBy { it }.eachCount()
    return counts.values.maxOrNull()?.let { it > limit / 2 } ?: true
}

/** Daily aggregates of repeated early skips are not evidence of a song the listener enjoyed. */
internal fun ListenRecord.hasPositiveListen(): Boolean = completions > 0 ||
    (milliseconds >= 30_000L * plays.coerceAtLeast(1) && (skips == 0 || plays > skips))

fun positiveRecommendationTracks(library: LibraryState, features: FeatureState): List<Track> {
    val history = features.history.groupBy { it.trackId }
    return library.savedTracks.filter { it.id !in features.lessLikeIds &&
        (it.id in library.favorites || history[it.id].orEmpty().any(ListenRecord::hasPositiveListen)) }
}

fun rankQuickPicks(candidates: List<Track>, library: LibraryState, features: FeatureState,
    day: LocalDate = LocalDate.now(), hour: Int = 12, relatedIds: Set<String> = emptySet(),
    limit: Int = 20, previousIds: Set<String> = emptySet(), variation: Int = 0): List<Track> =
    rankRecommendations(candidates, null, library, features, day, hour, relatedIds = relatedIds,
        limit = limit, previousIds = previousIds, variation = variation, forQuickPicks = true)

/** Key only meaningful taste changes; metadata/loading ticks cannot invalidate the home cache. */
fun recommendationTasteKey(library: LibraryState, features: FeatureState): String = buildString {
    append("taste-v3|")
    append(library.favorites.sorted().joinToString(",")); append('|')
    append(features.lessLikeIds.sorted().joinToString(",")); append('|')
    append(features.experience.followedArtists.sorted().joinToString(",")); append('|')
    features.history.sortedWith(compareBy({ it.trackId }, { it.day })).forEach {
        append(it.trackId); append(':'); append(it.day); append(':'); append(it.milliseconds / 120_000)
        append(':'); append(it.hasPositiveListen()); append(':'); append(it.skips); append(':'); append(it.completions); append(';')
    }
}.hashCode().toString()
