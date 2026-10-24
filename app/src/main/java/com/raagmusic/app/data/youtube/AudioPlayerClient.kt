package com.raagmusic.app.data.youtube

import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/** Reuse a native client session, not a watch-page extraction, for each new song. */
internal class AudioPlayerClient(directory: java.io.File? = null) {
    private val sessions = AudioSessionStore(directory)
    private var visitor: String? = null
    private var visitorCountry: String? = null
    private var createdAt = 0L

    // Repository audio work is serialized. Only this lane owns the short-lived visitor context.
    fun player(id: String, country: String, nonce: String): String {
        val client = InnertubeClientRequestInfo.ofVisionOsClient()
        val localization = Localization("en", country)
        val contentCountry = ContentCountry(country)
        val headers = mapOf("User-Agent" to listOf(YoutubeParsingHelper.getVisionOsUserAgent(localization)),
            "X-Goog-Api-Format-Version" to listOf("2"))
        val now = System.nanoTime() / 1_000_000
        var restored = false
        fun freshVisitor(): String = YoutubeParsingHelper.getVisitorDataFromInnertube(client, localization, contentCountry,
            headers, YoutubeParsingHelper.YOUTUBEI_V1_URL, null, false).also { sessions.write(country, it) }
        if (visitor == null || visitorCountry != country || now - createdAt !in 0 until AudioSessionStore.LIFETIME_MS) {
            val stored = sessions.readSession(country)
            visitor = stored?.first?.also { restored = true } ?: freshVisitor()
            visitorCountry = country; createdAt = now - (AudioSessionStore.LIFETIME_MS - (stored?.second ?: AudioSessionStore.LIFETIME_MS))
        }
        fun request(): String {
            client.clientInfo.visitorData = visitor
            val body = JsonWriter.string(YoutubeParsingHelper.prepareJsonBuilder(localization, contentCountry, client, null)
                .value("videoId", id).value("cpn", nonce).value("contentCheckOk", true).value("racyCheckOk", true).done())
            return YoutubeParsingHelper.getValidJsonResponseBody(NewPipe.getDownloader().postWithContentTypeJson(
                YoutubeParsingHelper.YOUTUBEI_V1_GAPIS_URL + "player?prettyPrint=false&t=" + YoutubeParsingHelper.generateTParameter() + "&id=" + id,
                headers, body.toByteArray(Charsets.UTF_8), localization))
        }
        val response = request()
        if (restored && org.json.JSONObject(response).optJSONObject("playabilityStatus")?.optString("status") == "LOGIN_REQUIRED") {
            // A rejected restored session gets one fresh context under the same outer deadline.
            sessions.clear(); visitor = freshVisitor()
            return request()
        }
        if (restored) com.raagmusic.app.playback.PlaybackTrace.mark(id, "native_session_reused")
        return response
    }

    fun reset() { visitor = null; sessions.clear() }
}
