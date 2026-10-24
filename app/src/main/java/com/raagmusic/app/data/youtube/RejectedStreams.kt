package com.raagmusic.app.data.youtube

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A rejected client/format must not win selection again just because its signature changed. */
internal class RejectedStreams(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val rejected = LinkedHashMap<Pair<String, String>, Long>()
    private fun key(url: String): String {
        val uri = url.toHttpUrlOrNull() ?: return url
        return "${uri.queryParameter("c")}|${uri.queryParameter("itag")}"
    }
    @Synchronized fun reject(id: String, url: String) {
        rejected[id to key(url)] = now()
        while (rejected.size > 64) rejected.remove(rejected.keys.first())
    }
    @Synchronized fun accepts(id: String, url: String): Boolean {
        rejected.entries.removeAll { now() - it.value !in 0 until 5 * 60_000 }
        return (id to key(url)) !in rejected
    }
    @Synchronized fun reset(id: String) { rejected.keys.removeAll { it.first == id } }
}
