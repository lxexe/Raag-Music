package com.raagmusic.app.domain

import java.net.URI
import java.net.URLDecoder

sealed interface SharedMusicLink {
    data class Song(val id: String, val positionMs: Long = 0) : SharedMusicLink
    data class Collection(val id: String, val album: Boolean = false) : SharedMusicLink
    companion object {
        private val urls = Regex("https?://[^\\s<>\\\"]+")
        private val videoId = Regex("[A-Za-z0-9_-]{11}")
        private val collectionId = Regex("[A-Za-z0-9_-]{10,200}")
        fun parse(text: String): SharedMusicLink? = urls.findAll(text.take(8192)).mapNotNull { match ->
            runCatching {
                val uri = URI(match.value.trimEnd('.', ',', ')', ']', ';'))
                require(uri.scheme in setOf("https", "http") && uri.userInfo == null && uri.port == -1)
                val host = uri.host?.lowercase() ?: return@runCatching null
                require(host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be"))
                val params = uri.rawQuery.orEmpty().split('&').mapNotNull { p ->
                    val pair = p.split('=', limit = 2)
                    if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
                }.toMap()
                val path = uri.path.orEmpty().trimEnd('/')
                val id = when {
                    host.endsWith("youtu.be") -> path.removePrefix("/")
                    path == "/watch" -> params["v"]
                    path.startsWith("/shorts/") || path.startsWith("/embed/") -> path.substringAfterLast('/')
                    else -> null
                }
                if (id != null && videoId.matches(id)) {
                    val time = params["t"] ?: params["start"] ?: uri.fragment?.removePrefix("t=")
                    Song(id, timestamp(time))
                } else {
                    val list = if (path == "/playlist" || path == "/watch") params["list"] else if (path.startsWith("/browse/")) path.substringAfterLast('/') else null
                    if (list != null && collectionId.matches(list)) Collection(list, list.startsWith("OLAK5uy_") || list.startsWith("MPRE")) else null
                }
            }.getOrNull()
        }.firstOrNull()
        private fun timestamp(raw: String?): Long {
            val value = raw.orEmpty()
            val seconds = value.toLongOrNull() ?: if (Regex("(?:\\d+h)?(?:\\d+m)?(?:\\d+s)?").matches(value))
                Regex("(\\d+)([hms])").findAll(value).sumOf { it.groupValues[1].toLongOrNull()?.coerceAtMost(86_400)?.times(when(it.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 }) ?: 0 } else 0
            return seconds.coerceIn(0, 86_400) * 1000
        }
    }
}
