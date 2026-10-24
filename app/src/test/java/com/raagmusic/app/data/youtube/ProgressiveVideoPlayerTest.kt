package com.raagmusic.app.data.youtube

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProgressiveVideoPlayerTest {
    private val id = "5NV6Rdv1a3I"
    private fun format(height: Int) = JSONObject().put("itag", height).put("height", height)
        .put("mimeType", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
        .put("audioQuality", "AUDIO_QUALITY_MEDIUM").put("contentLength", "123456").put("lastModified", "1234")
        .put("url", "https://r1.googlevideo.com/videoplayback?c=VISIONOS&itag=$height&expire=2000000000")
    private fun response(vararg formats: JSONObject) = JSONObject()
        .put("playabilityStatus", JSONObject().put("status", "OK"))
        .put("videoDetails", JSONObject().put("videoId", id))
        .put("streamingData", JSONObject().put("formats", JSONArray(formats.toList())))
    private fun select(root: JSONObject, height: Int = 720) = ProgressiveVideoPlayer.select(root.toString(), id, height, "nonce")

    @Test fun oneCombinedStreamHonorsQualityAndKeepsSigningOutOfTheFormatIdentity() {
        val root = response(format(360), format(720), format(1080))
        val selected = select(root)!!
        assertTrue(selected.hasVideo)
        assertEquals(720, selected.videoHeight)
        assertEquals("video|720|123456|1234", selected.formatIdentity)
        assertTrue(selected.url.contains("cpn=nonce"))
        assertEquals(360, select(root, 144)!!.videoHeight)
    }
    @Test fun rejectsSilentCipherLiveWrongIdentityAndUntrustedStreams() {
        assertNull(select(response(format(360).put("audioQuality", ""))))
        assertNull(select(response(format(360).put("url", "").put("signatureCipher", "s=secret"))))
        assertNull(select(response(format(360).put("url", "https://googlevideo.com.attacker.example/video"))))
        assertNull(select(response(format(360)).put("videoDetails", JSONObject().put("videoId", "dQw4w9WgXcQ"))))
        assertNull(select(response(format(360)).put("videoDetails", JSONObject().put("videoId", id).put("isLive", true))))
    }
    @Test fun rejectedFormatsCannotWinAgainWithAnotherSignature() {
        val root = response(format(360), format(720))
        val video = ProgressiveVideoPlayer.select(root.toString(), id, 1080, "nonce") { !it.contains("itag=720") }!!
        assertEquals(360, video.videoHeight)
    }
}
