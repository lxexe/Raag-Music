package com.raagmusic.app.updates

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateRepositoryTest {
    private class Memory(var saved: SavedUpdate = SavedUpdate()) : UpdateStorage {
        override fun read() = saved
        override fun write(value: SavedUpdate) { saved = value }
    }
    private fun release(tag: String = "v1.25.0", body: String = "New player\n- Better lyrics") = JSONObject()
        .put("tag_name", tag).put("html_url", "https://github.com/lxexe/Raag-Music/releases/tag/$tag")
        .put("draft", false).put("prerelease", false).put("body", body)
        .put("assets", JSONArray().put(JSONObject().put("name", "Raag-Music-${tag.removePrefix("v")}.apk")
            .put("state", "uploaded").put("browser_download_url", "https://github.com/lxexe/Raag-Music/releases/download/$tag/Raag-Music-${tag.removePrefix("v")}.apk")))
        .toString()

    @Test fun numericComparisonAvoidsLexicographicAndDowngradeErrors() {
        assertTrue(AppVersion.parse("v1.10.0")!! > AppVersion.parse("1.9.99")!!)
        assertFalse(parseRelease(release("v1.24.6"))!!.isNewerThan("1.24.6"))
        assertFalse(parseRelease(release("v1.24.5"))!!.isNewerThan("1.24.6"))
        for (invalid in listOf("v1.2", "1.2.3-beta", "1.2.03", "999999999999.1.1", "1.2.3\n")) assertNull(AppVersion.parse(invalid))
    }
    @Test fun preservesNotesAndAcceptsOnlyOfficialApkUrls() {
        val parsed = parseRelease(release())!!
        assertEquals("New player\n- Better lyrics", parsed.notes)
        assertEquals("Visible notes", parseRelease(release(body = "Visible notes<!-- Maintainer: private checklist -->"))!!.notes)
        assertTrue(parsed.apkUrl!!.endsWith("Raag-Music-1.25.0.apk"))
        for (url in listOf("https://evil.example/file.apk", "https://github.com.evil.example/lxexe/Raag-Music/releases/download/v1/a.apk",
            "https://github.com@evil.example/lxexe/Raag-Music/releases/download/v1/a.apk", "http://github.com/lxexe/Raag-Music/releases/download/v1/a.apk")) {
            assertFalse(trustedReleaseUrl(url, true))
        }
        val bad = JSONObject(release())
        bad.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/file.apk")
        assertNull(parseRelease(bad.toString())!!.apkUrl)
    }
    @Test fun ignoresDraftsAndPrereleasesAndHandlesAbsentAssetsNotes() {
        for (key in listOf("draft", "prerelease")) assertNull(parseRelease(JSONObject(release()).put(key, true).toString()))
        val json = JSONObject(release()).put("body", JSONObject.NULL).put("assets", JSONArray())
        assertEquals("", parseRelease(json.toString())!!.notes)
        assertNull(parseRelease(json.toString())!!.apkUrl)
    }
    @Test fun dailyCacheAndManualOverride() = runBlocking {
        val memory = Memory()
        var now = 1_000_000L
        var requests = 0
        val repo = UpdateRepository(memory, { requests++; ReleaseResponse.Found(release(), "etag") }, { now })
        repo.check()
        now += 60_000
        repo.check()
        assertEquals(1, requests)
        repo.check(manual = true)
        assertEquals(2, requests)
        now += DAY_MS
        repo.check()
        assertEquals(3, requests)
    }
    @Test fun etag304AndDismissalSurviveRestart() = runBlocking {
        var now = 1_000_000L
        val memory = Memory()
        val repo = UpdateRepository(memory, { ReleaseResponse.Found(release(), "etag") }, { now })
        repo.check(); repo.dismiss()
        now += DAY_MS
        val restarted = UpdateRepository(memory, { etag -> assertEquals("etag", etag); ReleaseResponse.Unchanged }, { now })
        restarted.check()
        assertEquals("1.25.0", restarted.state.value.release!!.version)
        assertEquals("1.25.0", restarted.state.value.dismissedVersion)
        assertEquals(now, restarted.state.value.lastChecked)
    }
    @Test fun networkAndMalformedFailuresRetainCachedNotesAndRespectBackoff() = runBlocking {
        var now = 1_000_000L
        val memory = Memory()
        var requests = 0
        var response: ReleaseResponse = ReleaseResponse.Found(release(), "etag")
        val repo = UpdateRepository(memory, { requests++; response }, { now })
        repo.check()
        now += DAY_MS
        response = ReleaseResponse.Found("not json", null)
        repo.check(manual = true)
        assertEquals("1.25.0", repo.state.value.release!!.version)
        assertNotNull(repo.state.value.error)
        repo.check(manual = true)
        assertEquals(2, requests)
        assertFalse(repo.state.value.checking)
    }
    @Test fun manualRetryCanRecoverSoonAfterConnectivityFailure() = runBlocking {
        var now = 1_000_000L
        var requests = 0
        val repo = UpdateRepository(Memory(), {
            requests++
            if (requests == 1) throw java.io.IOException("offline")
            ReleaseResponse.Found(release(), null)
        }, { now })
        repo.check()
        now += 60_000
        repo.check()
        assertEquals(1, requests)
        repo.check(manual = true)
        assertEquals(2, requests)
        assertEquals("1.25.0", repo.state.value.release!!.version)
    }
    @Test fun rateLimitBlocksEvenManualChecksUntilAllowed() = runBlocking {
        var now = 1_000_000L
        var requests = 0
        val repo = UpdateRepository(Memory(), { requests++; throw ReleaseRequestException(now + 7_200_000) }, { now })
        repo.check(); now += 60_000; repo.check(manual = true)
        assertEquals(1, requests)
        now += 7_200_000; repo.check(manual = true)
        assertEquals(2, requests)
    }
    @Test fun cancellationDoesNotRecordSuccessOrBackoff() = runBlocking {
        val memory = Memory()
        val repo = UpdateRepository(memory, { throw CancellationException() })
        try { repo.check(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(SavedUpdate(), memory.saved)
        assertFalse(repo.state.value.checking)
    }
    @Test fun concurrentRequestsShareDailyResult() = runBlocking {
        var requests = 0
        val repo = UpdateRepository(Memory(), { requests++; delay(20); ReleaseResponse.Found(release(), null) })
        coroutineScope { repeat(8) { launch { repo.check() } } }
        assertEquals(1, requests)
    }
    @Test fun corruptCacheAndClockRollbackDoNotBlockRecovery() = runBlocking {
        val memory = Memory(SavedUpdate("bad json", "stale etag", checked = 20 * DAY_MS, nextAttempt = 20 * DAY_MS))
        val repo = UpdateRepository(memory, { etag -> assertNull(etag); ReleaseResponse.Found(release(), null) }, { DAY_MS })
        repo.check()
        assertEquals("1.25.0", repo.state.value.release!!.version)
    }
    @Test fun unavailableStorageDoesNotCrashCheckingOrDismissal() = runBlocking {
        for (readFails in listOf(false, true)) {
            val storage = object : UpdateStorage {
                override fun read(): SavedUpdate {
                    if (readFails) throw java.io.IOException("storage unavailable")
                    return SavedUpdate()
                }
                override fun write(value: SavedUpdate) { throw java.io.IOException("storage full") }
            }
            val repo = UpdateRepository(storage, { ReleaseResponse.Found(release(), null) })
            repo.check()
            assertNotNull(repo.state.value.error)
            assertFalse(repo.state.value.checking)
            repo.dismiss()
        }
    }
    @Test fun missingPublishedReleaseClearsPreviouslyCachedRelease() = runBlocking {
        val memory = Memory(SavedUpdate(release(), "etag"))
        val repo = UpdateRepository(memory, { ReleaseResponse.NonePublished })
        repo.check()
        assertNull(repo.state.value.release)
        assertNull(memory.saved.etag)
    }
}
