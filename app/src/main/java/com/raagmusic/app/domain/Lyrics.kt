package com.raagmusic.app.domain

data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val lines: List<LyricLine> = emptyList(), val plain: String = "", val instrumental: Boolean = false, val provider: String = "LRCLIB") {
    val available: Boolean get() = !instrumental && (plain.isNotBlank() || lines.any { it.text.isNotBlank() })
    fun activeIndex(positionMs: Long): Int {
        var low = 0; var high = lines.lastIndex; var result = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) { result = middle; low = middle + 1 } else high = middle - 1
        }
        return result
    }
}
fun parseLrc(raw: String): List<LyricLine> {
    val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    return raw.lineSequence().flatMap { line ->
        val matches = stamp.findAll(line).toList()
        val text = line.substring(matches.lastOrNull()?.range?.last?.plus(1) ?: line.length).trim()
        matches.mapNotNull { m ->
            val seconds = m.groupValues[2].toLong()
            if (seconds >= 60) null else {
                val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                LyricLine((m.groupValues[1].toLong() * 60_000 + seconds * 1000 + fraction + offset).coerceAtLeast(0), text)
            }
        }.asSequence()
    }.sortedBy { it.timeMs }.toList()
}

/** Remove upload labels while keeping version markers such as live, remix and acoustic. */
fun lyricsTrackName(title: String, artist: String = ""): String {
    val prefix = listOf("$artist - ", "$artist – ").firstOrNull { artist.isNotBlank() && title.startsWith(it, ignoreCase = true) }
    val name = if (prefix == null) title else title.substring(prefix.length)
    return name.replace(Regex(
    "\\s*[\\[(](?:official(?:\\s+(?:music|audio|lyric|lyrics|video|visualizer))*|lyrics?|audio|music video|visualizer)[\\])]", RegexOption.IGNORE_CASE), "").trim()
}

fun lyricIdentityMatches(title: String, artist: String, duration: Long, candidateTitle: String, candidateArtist: String, candidateDuration: Double): Boolean {
    fun key(value: String) = value.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
    fun names(value: String) = value.split(Regex(",|&|;|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE))
        .map { key(it.removeSuffix(" - Topic")) }.filter { it.isNotBlank() }.toSet()
    val a = names(artist); val b = names(candidateArtist)
    return key(lyricsTrackName(title)) == key(lyricsTrackName(candidateTitle)) && a.isNotEmpty() && a.intersect(b).isNotEmpty() &&
        (duration <= 0 || candidateDuration <= 0 || kotlin.math.abs(duration - candidateDuration) <= 12)
}
