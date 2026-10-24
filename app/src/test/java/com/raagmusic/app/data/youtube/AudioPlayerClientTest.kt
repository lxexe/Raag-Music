package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization

class AudioPlayerClientTest {
    @Test fun processRestartReusesValidSessionAndRefreshesARejectedRestoredSessionOnce() {
        val directory = java.nio.file.Files.createTempDirectory("native-session-test").toFile()
        val calls = mutableListOf<String>()
        var rejectNext = false
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val stage = request.url().substringBefore('?').substringAfterLast('/')
                calls += stage
                if (stage == "visitor_id") assertFalse("Fresh visitor requests must not carry a rejected visitor",
                    request.dataToSend()?.toString(Charsets.UTF_8).orEmpty().contains("fixture-visitor"))
                val body = if (stage == "visitor_id") """{"responseContext":{"visitorData":"fixture-visitor"}}"""
                    else if (rejectNext) { rejectNext = false; """{"playabilityStatus":{"status":"LOGIN_REQUIRED"},"videoDetails":{"videoId":"5NV6Rdv1a3I"}}""" }
                    else """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"5NV6Rdv1a3I"}}"""
                return Response(200, "OK", mapOf("Content-Type" to listOf("application/json")), body, request.url())
            }
        }, Localization("en", "US"))
        try {
            AudioPlayerClient(directory).player("5NV6Rdv1a3I", "US", "nonce")
            assertEquals(listOf("visitor_id", "player"), calls)
            calls.clear()
            AudioPlayerClient(directory).player("5NV6Rdv1a3I", "US", "nonce2")
            assertEquals(listOf("player"), calls)
            calls.clear(); rejectNext = true
            AudioPlayerClient(directory).player("5NV6Rdv1a3I", "US", "nonce3")
            assertEquals(listOf("player", "visitor_id", "player"), calls)
        } finally { directory.deleteRecursively(); NewPipe.init(NewPipeDownloader(), Localization("en", "US")) }
    }
    @Test fun supportedAudioUsesOnlyTheNativePlayerAndRejectedStreamsFallBack() {
        val engine = NewPipeExtractionEngine()
        val calls = mutableListOf<String>()
        var failPlayer = false
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val stage = request.url().substringBefore('?').substringAfterLast('/')
                calls += stage
                if (stage == "visitor_id") assertFalse("Fresh visitor requests must not carry a rejected visitor",
                    request.dataToSend()?.toString(Charsets.UTF_8).orEmpty().contains("fixture-visitor"))
                if (stage == "player" && failPlayer) throw java.net.SocketTimeoutException("Transport stalled")
                if (stage !in setOf("visitor_id", "player")) throw java.io.IOException("Full extractor reached")
                val body = if (stage == "visitor_id") """{"responseContext":{"visitorData":"fixture-visitor"}}""" else
                    """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"5NV6Rdv1a3I"},"streamingData":{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","bitrate":128000,"contentLength":"123456","lastModified":"1234","url":"https://r1.googlevideo.com/videoplayback?c=VISIONOS&clen=123456&lmt=1234"}]}}"""
                return Response(200, "OK", mapOf("Content-Type" to listOf("application/json")), body, request.url())
            }
        }, Localization("en", "US"))
        try {
            val stream = engine.audioStream("5NV6Rdv1a3I", 0)
            assertEquals("VISIONOS", stream.resolverClient)
            assertEquals(listOf("visitor_id", "player"), calls)
            calls.clear()
            failPlayer = true
            assertThrows(java.net.SocketTimeoutException::class.java) { engine.audioStream("dQw4w9WgXcQ", 0) }
            assertEquals("Network failure must not fan out into metadata requests", listOf("player"), calls)
            failPlayer = false; calls.clear()
            engine.onStreamRejected("5NV6Rdv1a3I", stream.url)
            assertThrows(java.io.IOException::class.java) { engine.audioStream("5NV6Rdv1a3I", 0) }
            assertTrue("Unusable native formats route to the full extractor", "reel_item_watch" in calls)
            assertEquals("Recovery refreshes the native session before resolving", listOf("visitor_id", "player"), calls.take(2))
        } finally { NewPipe.init(NewPipeDownloader(), Localization("en", "US")) }
    }
    @Test fun rejectedInMemorySessionRefreshesOnceAndDoesNotReuseTheOldContext() {
        val calls = mutableListOf<String>()
        var rejectNext = false
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val stage = request.url().substringBefore('?').substringAfterLast('/')
                calls += stage
                if (stage == "visitor_id") assertFalse("Fresh visitor requests must not carry a rejected visitor",
                    request.dataToSend()?.toString(Charsets.UTF_8).orEmpty().contains("fixture-visitor"))
                val body = if (stage == "visitor_id") """{"responseContext":{"visitorData":"fixture-visitor"}}"""
                    else if (rejectNext) { rejectNext = false; """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}""" }
                    else """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"5NV6Rdv1a3I"}}"""
                return Response(200, "OK", mapOf("Content-Type" to listOf("application/json")), body, request.url())
            }
        }, Localization("en", "US"))
        try {
            val client = AudioPlayerClient()
            client.player("5NV6Rdv1a3I", "US", "nonce")
            calls.clear(); rejectNext = true
            client.player("dQw4w9WgXcQ", "US", "nonce2")
            assertEquals(listOf("player", "visitor_id", "player"), calls)
        } finally { NewPipe.init(NewPipeDownloader(), Localization("en", "US")) }
    }
    @Test fun persistentVerificationStopsWithoutFullExtractorFanoutAndClearsTheRejectedSession() {
        val directory = java.nio.file.Files.createTempDirectory("blocked-session-test").toFile()
        AudioSessionStore(directory).write("US", "fixture-visitor")
        val calls = mutableListOf<String>()
        val engine = NewPipeExtractionEngine(sessionDirectory = directory)
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val stage = request.url().substringBefore('?').substringAfterLast('/')
                calls += stage
                if (stage == "visitor_id") assertFalse("Fresh visitor requests must not carry a rejected visitor",
                    request.dataToSend()?.toString(Charsets.UTF_8).orEmpty().contains("fixture-visitor"))
                val body = if (stage == "visitor_id") """{"responseContext":{"visitorData":"fixture-visitor"}}"""
                    else """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}"""
                return Response(200, "OK", mapOf("Content-Type" to listOf("application/json")), body, request.url())
            }
        }, Localization("en", "US"))
        try {
            assertThrows(YoutubeVerificationRequiredException::class.java) { engine.audioStream("5NV6Rdv1a3I", 0) }
            assertEquals(listOf("player", "visitor_id", "player"), calls)
            assertNull(AudioSessionStore(directory).read("US"))
            calls.clear()
            assertThrows(YoutubeVerificationRequiredException::class.java) { engine.videoStream("5NV6Rdv1a3I", 720) }
            assertEquals(listOf("visitor_id", "player"), calls)
        } finally { directory.deleteRecursively(); NewPipe.init(NewPipeDownloader(), Localization("en", "US")) }
    }
    @Test fun captchaChallengeDoesNotFanOutToOtherClients() {
        val engine = NewPipeExtractionEngine()
        val calls = mutableListOf<String>()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                calls += request.url().substringBefore('?').substringAfterLast('/')
                throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException("Verification required", request.url())
            }
        }, Localization("en", "US"))
        try {
            assertThrows(YoutubeVerificationRequiredException::class.java) { engine.audioStream("5NV6Rdv1a3I", 0) }
            assertEquals(listOf("visitor_id"), calls)
        } finally { NewPipe.init(NewPipeDownloader(), Localization("en", "US")) }
    }
    @Test fun accountOrAgeRestrictionsAreNotMislabelledAsConnectionVerification() {
        assertFalse(nativeVerificationRequired("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"}}"""))
        assertFalse(nativeVerificationRequired("""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Private video"}}"""))
        assertTrue(nativeVerificationRequired("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}"""))
    }
    @Test fun reusesOnlyTheVisitorContextAndRefreshesItForRegionChanges() {
        val calls = mutableListOf<String>()
        val downloader = object : Downloader() {
            override fun execute(request: Request): Response {
                val stage = request.url().substringBefore('?').substringAfterLast('/')
                calls += stage
                if (stage == "visitor_id") assertFalse("Fresh visitor requests must not carry a rejected visitor",
                    request.dataToSend()?.toString(Charsets.UTF_8).orEmpty().contains("fixture-visitor"))
                val body = if (stage == "visitor_id") """{"responseContext":{"visitorData":"fixture-visitor"}}"""
                    else """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"5NV6Rdv1a3I"}}"""
                return Response(200, "OK", mapOf("Content-Type" to listOf("application/json")), body, request.url())
            }
        }
        NewPipe.init(downloader, Localization("en", "US"))
        try {
            val client = AudioPlayerClient()
            client.player("5NV6Rdv1a3I", "US", "nonce")
            client.player("dQw4w9WgXcQ", "US", "nonce2")
            assertEquals(listOf("visitor_id", "player", "player"), calls)
            client.player("5NV6Rdv1a3I", "IN", "nonce3")
            assertEquals(listOf("visitor_id", "player"), calls.takeLast(2))
            client.reset()
            client.player("5NV6Rdv1a3I", "IN", "nonce4")
            assertEquals(listOf("visitor_id", "player"), calls.takeLast(2))
        } finally { NewPipe.init(NewPipeDownloader(), Localization("en", "US")) }
    }
}
