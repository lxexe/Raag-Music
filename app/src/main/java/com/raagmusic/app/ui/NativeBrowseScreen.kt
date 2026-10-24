package com.raagmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.Track

@UnstableApi
@Composable
fun NativeBrowseScreen(vm: MusicViewModel, page: BrowsePageState, onMore: (Track) -> Unit, onClose: () -> Unit) {
    val retry = vm::retryBrowse
    val artists = remember(page.songs.items, page.trending.items) {
        (page.songs.items + page.trending.items).flatMap { com.raagmusic.app.data.youtube.ArtistNames.fallback(it.artist) }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(12)
    }
    val categories = if (page.mood) moodCategories else genreCategories
    key(page.title) {
        LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Home", onClick = onClose)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(if (page.mood) "MUSIC FOR YOUR MOMENT" else "EXPLORE A SOUND", color = Lavender, style = MaterialTheme.typography.labelSmall)
                        Text(page.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                    Glyph(Icons.Rounded.Refresh, "Refresh ${page.title}", onClick = retry)
                }
            }
            item(key = "popular") {
                HomeHeading("Popular tracks", "Start your ${page.title.lowercase()} soundtrack")
                Spacer(Modifier.height(14.dp))
                HomeTrackContent(page.songs, retry) { HomeSongShelf(page.songs.items) { vm.play(it) } }
            }
            item(key = "playlists") {
                HomeHeading("Curated playlists", "Stay in the moment")
                Spacer(Modifier.height(14.dp))
                HomeCollectionShelf(page.playlists, vm::openCollection, retry)
            }
            item(key = "albums") {
                HomeHeading("Recommended albums", "Explore the whole record")
                Spacer(Modifier.height(14.dp))
                HomeCollectionShelf(page.albums, vm::openCollection, retry)
            }
            item(key = "artists") {
                HomeHeading("Artists to explore", "Follow a familiar voice or find a new one")
                Spacer(Modifier.height(14.dp))
                if (artists.isEmpty() && page.songs.loading) ShelfSkeletons()
                else if (artists.isEmpty()) HomeEmptyInset("Artists will appear here", "Refresh this page to discover more music.", Icons.Rounded.Person)
                else LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(artists, key = { it }, contentType = { "artist" }) { name ->
                        Column(Modifier.width(112.dp).clickable { vm.openArtist(name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistPortrait(vm, name, Modifier.size(104.dp).clip(CircleShape))
                            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
            item(key = "trending") {
                HomeHeading("Trending now", "Popular in this sound")
                Spacer(Modifier.height(14.dp))
                HomeTrackContent(page.trending, retry) { HomeSongShelf(page.trending.items, ranked = true) { vm.play(it) } }
            }
            item(key = "releases") {
                HomeHeading("New releases", "Fresh music to discover")
                Spacer(Modifier.height(14.dp))
                HomeTrackContent(page.releases, retry) { HomeSongShelf(page.releases.items) { vm.play(it) } }
            }
            item(key = "related") {
                HomeHeading("Keep exploring", "Find your next moment")
                Spacer(Modifier.height(14.dp))
                CategoryShelf(categories.filter { it.title != page.title }) { vm.openBrowse(it.title, it.query, page.mood) }
            }
        }
    }
}
