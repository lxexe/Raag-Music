package com.raagmusic.app.domain

import java.text.Normalizer
import java.util.Locale

private val searchMarks = Regex("(?<=\\p{IsLatin})\\p{M}+")
private val searchSeparators = Regex("[^\\p{L}\\p{M}\\p{N}]+")
fun normalizedSearch(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(searchMarks, "").lowercase(Locale.ROOT).replace(searchSeparators, " ").trim()

/** Exact intent wins. Provider order remains the stable tie breaker; likes never override a query. */
fun searchMatchScore(query: String, title: String, subtitle: String = ""): Int {
    val q = normalizedSearch(query)
    if (q.isEmpty()) return 0
    val name = normalizedSearch(title)
    val credit = normalizedSearch(subtitle)
    return when {
        name == q -> 120
        credit == q -> 100
        name.startsWith("$q ") -> 90
        credit.startsWith("$q ") -> 80
        name.contains(q) -> 70
        credit.contains(q) -> 60
        q.split(' ').all { token -> ("$name $credit").split(' ').any { it.startsWith(token) } } -> 40
        else -> 0
    }
}

fun <T> rankSearchMatches(items: List<T>, query: String, title: (T) -> String, subtitle: (T) -> String = { "" }): List<T> =
    items.sortedByDescending { searchMatchScore(query, title(it), subtitle(it)) }

/** Bounded local work supplies completions before a network response, including offline. */
fun rankedSearchSuggestions(query: String, recent: List<String>, tracks: List<Track>,
    followedArtists: Set<String> = emptySet(), remote: List<String> = emptyList(), limit: Int = 6): List<String> {
    val key = normalizedSearch(query)
    if (key.length < 2) return emptyList()
    return buildList {
        recent.forEachIndexed { index, text -> add(text to (12 - index.coerceAtMost(10))) }
        followedArtists.sorted().forEach { add(it to 8) }
        remote.forEachIndexed { index, text -> add(text to (6 - index.coerceAtMost(5))) }
        tracks.take(500).forEach { add(it.title to 2); add(it.artist.removeSuffix(" - Topic") to 1) }
    }.filter { (text, _) -> normalizedSearch(text) != key && searchMatchScore(query, text) > 0 }
        .groupBy { normalizedSearch(it.first) }.values.map { entries -> entries.maxBy { it.second } }
        .sortedByDescending { (text, weight) -> searchMatchScore(query, text) + weight }
        .take(limit).map { it.first.trim() }
}
