package com.raagmusic.app.data.youtube

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** One native player response can provide a progressive file containing both video and audio. */
internal object ProgressiveVideoPlayer {
    fun select(response: String, id: String, maxHeight: Int, nonce: String,
        accepts: (String) -> Boolean = { true }): ResolvedAudioStream? {
        val root = JSONObject(response)
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val details = root.optJSONObject("videoDetails") ?: return null
        if (details.optString("videoId") != id || details.optBoolean("isLive") || details.optBoolean("isPostLiveDvr")) return null
        val formats = root.optJSONObject("streamingData")?.optJSONArray("formats") ?: return null
        val streams = (0 until formats.length()).mapNotNull { index ->
            val format = formats.optJSONObject(index) ?: return@mapNotNull null
            val mime = format.optString("mimeType")
            if (!mime.startsWith("video/mp4") || format.optString("audioQuality").isBlank() ||
                format.optString("type") == "FORMAT_STREAM_TYPE_OTF") return@mapNotNull null
            val url = format.optString("url").toHttpUrlOrNull() ?: return@mapNotNull null
            if (!url.isHttps || !url.host.endsWith(".googlevideo.com") || !accepts(url.toString())) return@mapNotNull null
            val height = format.optInt("height").takeIf { it > 0 } ?: return@mapNotNull null
            val itag = format.optInt("itag").takeIf { it > 0 } ?: return@mapNotNull null
            val length = format.optString("contentLength").toLongOrNull()?.takeIf { it > 0 }
            val modified = format.optString("lastModified").ifBlank { url.queryParameter("lmt").orEmpty() }
            ResolvedAudioStream(url.newBuilder().setQueryParameter("cpn", nonce).build().toString(),
                formatIdentity = "video|$itag|${length.orEmptyString()}|$modified",
                expiresAtEpochMillis = url.queryParameter("expire")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000),
                contentLength = length, mimeType = mime.substringBefore(';'), resolverClient = "VISIONOS", hasVideo = true, videoHeight = height)
        }
        return streams.filter { it.videoHeight!! <= maxHeight }.maxByOrNull { it.videoHeight!! }
            ?: streams.minByOrNull { it.videoHeight!! }
    }
    private fun Long?.orEmptyString() = this?.toString().orEmpty()
}
