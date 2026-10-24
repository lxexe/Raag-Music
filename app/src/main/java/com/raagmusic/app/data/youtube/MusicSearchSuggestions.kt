package com.raagmusic.app.data.youtube

import org.json.JSONObject
import java.util.Locale

/** Read only query endpoints, never renderer labels or unrelated navigation text. */
internal object MusicSearchSuggestions {
    fun parse(response: String): List<String> {
        if (response.length > 1_000_000) return emptyList()
        val contents = JSONObject(response).optJSONArray("contents") ?: return emptyList()
        return buildList {
            for (section in 0 until minOf(contents.length(), 8)) {
                val rows = contents.optJSONObject(section)?.optJSONObject("searchSuggestionsSectionRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (index in 0 until minOf(rows.length(), 30)) {
                    val renderer = rows.optJSONObject(index)?.optJSONObject("searchSuggestionRenderer") ?: continue
                    val query = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("searchEndpoint")
                        ?.optString("query").orEmpty().trim().take(120)
                    if (query.isNotEmpty()) add(query)
                }
            }
        }.distinctBy { it.lowercase(Locale.ROOT) }.take(6)
    }
}
