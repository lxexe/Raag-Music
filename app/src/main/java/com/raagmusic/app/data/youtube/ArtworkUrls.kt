package com.raagmusic.app.data.youtube

import java.net.URI

/** Resize only known image CDNs; leave other providers and their signed parameters intact. */
object ArtworkUrls {
    fun highResolution(url: String, pixels: Int = 1024): String {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (host == "i.ytimg.com" && pixels >= 768) {
            return url.replace(Regex("/(?:default|mqdefault|hqdefault|sddefault|hq720)\\.(jpg|webp)(?=$|\\?)"), "/maxresdefault.$1")
        }
        if (!(host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") ||
                    host == "ggpht.com" || host.endsWith(".ggpht.com"))) return url
        val size = pixels.coerceIn(64, 2048)
        return url.replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size")
            .replace(Regex("=s\\d+"), "=s$size")
    }
}
