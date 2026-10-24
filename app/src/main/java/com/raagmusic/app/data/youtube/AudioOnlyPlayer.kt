package com.raagmusic.app.data.youtube

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Parse only progressive original audio. Unsupported/cipher-only responses use the full extractor. */
internal object AudioOnlyPlayer {
    fun select(response: String, id: String, maxBitrate: Int, nonce: String, accepts: (String) -> Boolean = { true }): ResolvedAudioStream? {
        val root = JSONObject(response)
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val details = root.optJSONObject("videoDetails") ?: return null
        if (details.optString("videoId") != id || details.optBoolean("isLive") || details.optBoolean("isPostLiveDvr")) return null
        val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
        val candidates = (0 until formats.length()).mapNotNull { index ->
            val format = formats.optJSONObject(index) ?: return@mapNotNull null
            val mime = format.optString("mimeType")
            if (!mime.startsWith("audio/mp4") && !mime.startsWith("audio/webm")) return@mapNotNull null
            if (format.optString("type") == "FORMAT_STREAM_TYPE_OTF") return@mapNotNull null
            val track = format.optJSONObject("audioTrack")
            // NewPipe decodes the provider's protobuf xtags; default can be a translated dub.
            val type = runCatching { org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
                .extractAudioTrackType(format.optString("xtags").takeIf { it.isNotBlank() }) }.getOrNull()
            if (type != null && type != org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL) return@mapNotNull null
            if (track != null && type == null && !track.optBoolean("audioIsDefault")) return@mapNotNull null
            val url = format.optString("url").toHttpUrlOrNull() ?: return@mapNotNull null
            if (!url.isHttps || !url.host.endsWith(".googlevideo.com")) return@mapNotNull null
            if (!accepts(url.toString())) return@mapNotNull null
            val length = format.optString("contentLength").toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val modified = format.optString("lastModified").ifBlank { url.queryParameter("lmt").orEmpty() }
            val itag = format.optInt("itag")
            if (itag <= 0 || modified.isBlank()) return@mapNotNull null
            val rate = format.optInt("bitrate").takeIf { it > 0 } ?: return@mapNotNull null
            ResolvedAudioStream(url.newBuilder().setQueryParameter("cpn", nonce).build().toString(), rate,
                listOf(itag, track?.optString("id").orEmpty(), length, modified).joinToString("|"),
                url.queryParameter("expire")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000),
                contentLength = length, mimeType = mime.substringBefore(';'), resolverClient = "VISIONOS")
        }
        val capped = if (maxBitrate <= 0) candidates else candidates.filter { (it.bitrate ?: 0) <= maxBitrate }
        return if (capped.isEmpty()) candidates.minByOrNull { it.bitrate ?: Int.MAX_VALUE }
        else capped.maxWithOrNull(compareBy({ it.bitrate ?: 0 }, { it.mimeType == "audio/webm" }))
    }
}
