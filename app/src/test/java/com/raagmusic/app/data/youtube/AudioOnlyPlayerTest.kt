package com.raagmusic.app.data.youtube

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioOnlyPlayerTest {
    private val id = "5NV6Rdv1a3I"
    private fun format(rate: Int = 128_000, itag: Int = 140) = JSONObject()
        .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"").put("bitrate", rate).put("itag", itag)
        .put("contentLength", "123456").put("lastModified", "1234")
        .put("url", "https://r1.googlevideo.com/videoplayback?clen=123456&lmt=1234&expire=2000000000&c=VISIONOS")
    private fun response(vararg formats: JSONObject) = JSONObject()
        .put("playabilityStatus", JSONObject().put("status", "OK"))
        .put("videoDetails", JSONObject().put("videoId", id))
        .put("streamingData", JSONObject().put("adaptiveFormats", JSONArray(formats.toList())))
    private fun select(root: JSONObject, limit: Int = 0) = AudioOnlyPlayer.select(root.toString(), id, limit, "nonce")

    @Test fun selectsCappedProgressiveAudioAndKeepsStableEncodingIdentity() {
        val root = response(format(128_000), format(256_000, 141))
        val audio = select(root, 128_000)!!
        assertEquals(128_000, audio.bitrate)
        assertEquals("140||123456|1234", audio.formatIdentity)
        assertEquals(123456L, audio.contentLength)
        assertEquals(2_000_000_000_000L, audio.expiresAtEpochMillis)
        assertTrue(audio.url.contains("cpn=nonce"))
        assertEquals(256_000, select(root)!!.bitrate)
    }
    @Test fun rejectsWrongVideoPrivateLiveAndMissingLength() {
        assertNull(select(response(format()).put("videoDetails", JSONObject().put("videoId", "dQw4w9WgXcQ"))))
        assertNull(select(response(format()).put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED"))))
        assertNull(select(response(format()).put("videoDetails", JSONObject().put("videoId", id).put("isLive", true))))
        assertNull(select(response(format().put("contentLength", ""))))
    }
    @Test fun rejectsVideoManifestsUntrustedHostsAndCipherOnlyFormats() {
        assertNull(select(response(format().put("mimeType", "video/mp4"))))
        assertNull(select(response(format().put("type", "FORMAT_STREAM_TYPE_OTF"))))
        assertNull(select(response(format().put("url", "https://attacker.example/audio"))))
        assertNull(select(response(format().put("url", "").put("signatureCipher", "s=secret"))))
    }
    @Test fun ignoresNonDefaultAlternateTrackAndUsesSmallestAboveCap() {
        assertNull(select(response(format().put("audioTrack", JSONObject().put("id", "en.1").put("audioIsDefault", false)))))
        assertEquals(128_000, select(response(format(256_000), format(128_000)), 64_000)!!.bitrate)
    }
}
