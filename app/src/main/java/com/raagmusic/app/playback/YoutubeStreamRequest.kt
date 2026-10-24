package com.raagmusic.app.playback

import com.raagmusic.app.data.youtube.NewPipeDownloader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/** Match the client which issued the signed URL; don't send a browser identity to visionOS audio. */
internal fun youtubeStreamHeaders(url: String): Map<String, String> = buildMap {
    val client = url.toHttpUrlOrNull()?.queryParameter("c").orEmpty()
    put("User-Agent", if (client == "VISIONOS")
        YoutubeParsingHelper.getVisionOsUserAgent(null) else NewPipeDownloader.USER_AGENT)
    put("Accept-Encoding", "identity")
    if (client.startsWith("WEB")) {
        put("Origin", "https://www.youtube.com")
        put("Referer", "https://www.youtube.com")
        put("Sec-Fetch-Dest", "empty")
        put("Sec-Fetch-Mode", "cors")
        put("Sec-Fetch-Site", "cross-site")
    }
}

/** YouTube's native streaming clients fetch progressive audio with this small POST body. */
internal val youtubeStreamBody get() = byteArrayOf(0x78, 0)
