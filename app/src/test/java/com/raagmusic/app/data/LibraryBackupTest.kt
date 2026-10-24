package com.raagmusic.app.data

import com.raagmusic.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    private val track = Track("abcdefghijk", "Song", "Artist", "https://example.com/cover", "abcdefghijk", Source.YOUTUBE, durationSeconds = 123)
    private val original = LibraryState(setOf(track.id), listOf(Playlist("p1", "My mix", listOf(track.id))), listOf(track), listOf(track.id))
    @Test fun roundTripPreservesMetadataPlaylistsAndFavorites() {
        assertEquals(original, LibraryBackup.parse(LibraryBackup.export(original)))
        assertFalse(LibraryBackup.export(original).contains("googlevideo"))
    }
    @Test fun favoriteDatesRoundTripAndMergeKeepsExistingLocalDates() {
        val dated = original.copy(likedAt = mapOf(track.id to 12345L))
        assertEquals(dated, LibraryBackup.parse(LibraryBackup.export(dated)))
        val current = dated.copy(likedAt = mapOf(track.id to 67890L))
        assertEquals(current, LibraryBackup.merge(current, dated))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDatesForNonFavoriteTracks() {
        LibraryBackup.parse(LibraryBackup.export(original.copy(likedAt = mapOf("missing" to 123L))))
    }
    @Test fun repeatedImportIsIdempotentAndKeepsExistingPlaylistName() {
        val current = original.copy(playlists = listOf(Playlist("p1", "Renamed locally", listOf(track.id))))
        val restored = LibraryBackup.merge(current, original)
        assertEquals(current, restored); assertEquals(restored, LibraryBackup.merge(restored, original))
    }
    @Test fun mergeAddsNewTracksAndPreservesExistingOrder() {
        val second = track.copy(id = "zyxwvutsrqp", uri = "zyxwvutsrqp")
        val imported = LibraryState(setOf(second.id), listOf(Playlist("p1", "Other", listOf(second.id))), listOf(second))
        val restored = LibraryBackup.merge(original, imported)
        assertEquals(listOf(track.id, second.id), restored.playlists.single().trackIds)
        assertEquals(setOf(track.id, second.id), restored.favorites)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsFutureSchemas() {
        val j = JSONObject(LibraryBackup.export(original)); j.put("version", 99); LibraryBackup.parse(j.toString())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedFilesBeforeParsing() { LibraryBackup.parse("x".repeat(LibraryBackup.MAX_BYTES + 1)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsDanglingPlaylistReferences() {
        LibraryBackup.parse(LibraryBackup.export(original.copy(playlists = listOf(Playlist("p", "Bad", listOf("missing"))))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsSignedYoutubePlaybackUrls() {
        LibraryBackup.parse(LibraryBackup.export(original.copy(savedTracks = listOf(track.copy(uri = "https://video.googlevideo.com/transient")))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDirectFilesAndUnsafeUriSchemes() {
        LibraryBackup.parse(LibraryBackup.export(original.copy(savedTracks = listOf(track.copy(source = Source.DIRECT, uri = "file:///private/data")))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCredentialBearingArtworkUrls() {
        LibraryBackup.parse(LibraryBackup.export(original.copy(savedTracks = listOf(track.copy(artwork = "https://user:secret@example.com/cover")))))
    }
    @Test fun legacyLibraryRecordsLoadWithoutDurationOrNewFields() {
        val legacy = """{"tracks":[{"id":"old","title":"Old","artist":"Artist","artwork":"","uri":"https://example.com/audio","source":"DIRECT"}]}"""
        val loaded = LibraryCodec.decode(legacy)
        assertEquals(0L, loaded.savedTracks.single().durationSeconds); assertTrue(loaded.favorites.isEmpty())
    }
}
