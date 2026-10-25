package com.raagmusic.app.domain

import com.raagmusic.app.data.youtube.ArtistProfile
import com.raagmusic.app.data.youtube.BrowseCollection
import com.raagmusic.app.data.youtube.CollectionKind
import org.junit.Assert.*
import org.junit.Test

class GroupedSearchTest {
    private val songs = (1..12).map { Track("song-$it", "Test song $it", "Artist", "", "", Source.YOUTUBE) }
    private val videos = (1..8).map { Track("video-$it", "Test video $it", "Artist", "", "", Source.YOUTUBE) }
    private val artists = (1..8).map { ArtistProfile("Test artist $it", channelUrl = "https://www.youtube.com/channel/artist-$it") }
    private val collections = CollectionKind.entries.flatMap { kind -> (1..8).map {
        BrowseCollection("$kind-$it", "Test collection $it", "Artist", "", "", 10, kind)
    } }
    @Test fun allShowsBoundedDistinctGroupsAndSeparateLibrary() {
        val groups = groupedSearchResults("Test", "All", songs, videos, artists, collections, songs)
        assertEquals(listOf("Songs", "Artists", "Albums", "Playlists", "Videos", "Library"), groups.map { it.kind })
        assertEquals(listOf(4, 3, 3, 3, 2, 3), groups.map { it.entries.size })
        assertTrue(groups.first { it.kind == "Artists" }.entries.all { it is SearchEntry.Artist })
    }
    @Test fun dedicatedCategoryHasFullResultsWithoutOtherTypes() {
        for ((kind, count) in listOf("Songs" to 12, "Videos" to 8, "Artists" to 8, "Albums" to 8, "Playlists" to 8, "Library" to 12)) {
            val groups = groupedSearchResults("Test", kind, songs, videos, artists, collections, songs)
            assertEquals(listOf(kind), groups.map { it.kind })
            assertEquals(count, groups.single().entries.size)
        }
    }
    @Test fun categoryResultsKeepAppendOrderAndDeduplicate() {
        val groups = groupedSearchResults("Test", "Songs", songs + songs.first(), videos, artists, collections)
        assertEquals(songs.map { it.id }, groups.single().entries.map { (it as SearchEntry.Song).track.id })
    }
    @Test fun emptyCategoriesAreOmittedAndLibraryDoesNotLeakIntoSongs() {
        assertTrue(groupedSearchResults("Test", "Songs", emptyList(), videos, artists, collections, songs).isEmpty())
        assertTrue(groupedSearchResults("Test", "Artists", songs, videos, emptyList(), collections).isEmpty())
    }
    @Test fun paginationCanCrossDuplicateOnlyPagesButStopsCyclesAndStalls() {
        val progress = SearchPageProgress()
        assertTrue(progress.next("first", "second", true))
        assertTrue(progress.next("second", "third", false))
        assertTrue(progress.next("third", "fourth", false))
        assertFalse(progress.next("fourth", "fifth", false))
        progress.reset()
        assertTrue(progress.next("first", "second", true))
        assertFalse(progress.next("second", "first", true))
        progress.reset()
        assertFalse(progress.next("one", null, true))
    }
}
