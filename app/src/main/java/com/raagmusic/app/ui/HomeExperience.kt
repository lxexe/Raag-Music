@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.raagmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*
import java.time.LocalDateTime
import kotlinx.coroutines.launch

internal val homeSectionNames = mapOf("quick" to "Quick picks", "moods" to "Music for your moment",
    "recent" to "Recently played", "recommended" to "Recommended", "trending" to "Trending", "releases" to "New releases",
    "playlists" to "Popular playlists", "genres" to "Genres", "artists" to "Artists", "albums" to "Albums")

@UnstableApi
@Composable
fun DiscoveryScreen(vm: MusicViewModel, mood: String, onMood: (String) -> Unit, onMore: (Track) -> Unit,
    onSearch: (String) -> Unit, onSettings: () -> Unit, list: LazyListState = rememberLazyListState()) {
    val picks by vm.quickPicks.collectAsStateWithLifecycle()
    val trending by vm.homeTrending.collectAsStateWithLifecycle()
    val releases by vm.homeNewReleases.collectAsStateWithLifecycle()
    val playlists by vm.homePlaylists.collectAsStateWithLifecycle()
    val albums by vm.homeAlbums.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val experience by vm.experience.collectAsStateWithLifecycle()
    val mix by vm.dailyMix.collectAsStateWithLifecycle()
    val refresh by vm.homeRefreshing.collectAsStateWithLifecycle()
    val region by vm.homeRegion.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val activeId by vm.activeTrackId.collectAsStateWithLifecycle()
    val metadata by vm.trackIndex.collectAsStateWithLifecycle()
    val recent = remember(library.recentIds, metadata) { library.recentIds.mapNotNull(metadata::get).take(12) }
    val artists = remember(picks.items, recent, experience.followedArtists) {
        (experience.followedArtists.toList() + (picks.items + recent).flatMap { com.raagmusic.app.data.youtube.ArtistNames.fallback(it.artist) }).distinct().take(16)
    }
    var addMood by remember { mutableStateOf(false) }
    var allSongs by remember { mutableStateOf<Pair<String, List<Track>>?>(null) }
    val now by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now(); kotlinx.coroutines.delay(60_000) } }
    val greeting = homeGreeting(profile ?: ListenerProfile(), now)
    val retry = { vm.loadHome(mood, refresh = true) }
    val sections = remember(recent.isEmpty()) { defaultHomeSections.filterNot { it == "recent" && recent.isEmpty() } }
    LaunchedEffect(activeId) { kotlinx.coroutines.delay(3_000); vm.resumeHomeIfNeeded() }
    PullToRefreshBox(refresh, retry, Modifier.fillMaxSize().homePresetBackground(now)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "header", contentType = "header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(androidx.compose.ui.res.painterResource(com.raagmusic.app.R.drawable.brand_mark), null, modifier = Modifier.size(30.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (LocalDarkTheme.current) Color(0xFFFF7963) else Color(0xFFD64335)))
                    Text(androidx.compose.ui.res.stringResource(com.raagmusic.app.R.string.app_name), fontSize = 22.sp, letterSpacing = (-.6).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Glyph(Icons.Outlined.Search, "Search", onClick = { onSearch("") })
                    Glyph(Icons.Outlined.Tune, "Settings", onClick = onSettings)
                }
            }
            item(key = "mood-chips", contentType = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(experience.homeMoods, key = { it }) { choice ->
                        val selected = mood.equals(choice, true)
                        Surface(onClick = { if (!selected) { onMood(choice); vm.loadHome(choice) } },
                            shape = RoundedCornerShape(10.dp), color = if (selected) ContentColor else ContentColor.copy(alpha = .10f),
                            contentColor = if (selected) Ink else ContentColor) {
                            Text(choice, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                    }
                    item(key = "add-mood") { Surface(onClick = { addMood = true }, shape = RoundedCornerShape(10.dp), color = ContentColor.copy(alpha = .10f)) {
                        MusicIcon(Icons.Outlined.Add, "Add mood chip", modifier = Modifier.padding(14.dp).size(22.dp))
                    } }
                }
            }
            item(key = "greeting", contentType = "header") {
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(greeting.title, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    Text(greeting.message, fontSize = 12.sp, color = Muted)
                }
            }
            items(sections, key = { it }, contentType = { "home-section" }) { section ->
                LaunchedEffect(section) { vm.requestHomeSection(section) }
                EnterHomeSection(section) {
                    when (section) {
                        "quick", "trending", "releases", "recommended", "recent" -> {
                            val state = when (section) { "quick" -> picks; "trending" -> trending; "releases" -> releases
                                "recommended" -> ContentState(items = mix); else -> ContentState(items = recent) }
                            val subtitle = when (section) { "quick" -> "Shaped by your likes and listening"; "trending" -> "Popular in ${region?.displayName() ?: "your region"}"
                                "recommended" -> "Your daily mix"; "recent" -> "Back to your favorites"; else -> "Fresh finds for your rotation" }
                            if (section != "quick" || state.items.isEmpty()) HomeHeading(homeSectionNames.getValue(section), subtitle, if (state.items.isNotEmpty()) "See all" else null) { allSongs = homeSectionNames.getValue(section) to state.items }
                            HomeTrackContent(state, retry) {
                                if (section == "quick") {
                                    QuickPickShelf(state.items, activeId, { vm.play(it) }, onMore,
                                        onPlayAll = { state.items.firstOrNull()?.let { vm.play(it, state.items, ordered = true) } })
                                } else HomeSongShelf(state.items, ranked = section == "trending") { vm.play(it, state.items) }
                            }
                        }
                        "moods", "genres" -> {
                            HomeHeading(homeSectionNames.getValue(section), "Find your next soundtrack")
                            CategoryShelf(if (section == "moods") moodCategories else regionalGenres(region?.code) + genreCategories) { vm.openBrowse(it.title, it.query, section == "moods") }
                        }
                        "playlists", "albums" -> { HomeHeading(homeSectionNames.getValue(section), "Stay for the whole story")
                            HomeCollectionShelf(if (section == "albums") albums else playlists, vm::openCollection, retry) }
                        "artists" -> {
                            HomeHeading("Artists", "Follow the voices you love")
                            LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(artists, key = { it }) { name -> Column(Modifier.width(100.dp).clickable { vm.openArtist(name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    ArtistPortrait(vm, name, Modifier.size(96.dp).clip(CircleShape)); Text(name, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                } }
                            }
                        }
                    }
                }
            }
        }
    }
    if (addMood) AddHomeMoodDialog(vm) { addMood = false }
    allSongs?.let { (title, songs) -> RaagSheet(onDismissRequest = { allSongs = null }, containerColor = Ink) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(22.dp))
        LazyColumn(Modifier.heightIn(max = 580.dp).navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(songs, key = { it.id }, contentType = { "track" }) { song -> TrackRow(song, favorite = song.id in library.favorites,
                onPlay = { vm.play(song, songs) }, onFavorite = { vm.favorite(song) }, onMore = { onMore(song) }) }
        }
    } }
}

@UnstableApi
@Composable
private fun AddHomeMoodDialog(vm: MusicViewModel, onDismiss: () -> Unit) {
    val experience by vm.experience.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("") }
    val cleaned = label.trim().replace(Regex("\\s+"), " ")
    val duplicate = experience.homeMoods.any { it.equals(cleaned, true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add a mood or genre") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Try Road trip, Indie, Ghazal, or anything you love.", color = Muted)
            OutlinedTextField(label, { label = it.take(24) }, label = { Text("Chip name") }, singleLine = true,
                isError = duplicate, supportingText = { if (duplicate) Text("This chip is already on Home") })
        } }, confirmButton = { TextButton(enabled = cleaned.isNotEmpty() && !duplicate && experience.homeMoods.size < 12,
            onClick = { vm.updateExperience { it.copy(homeMoods = it.homeMoods + cleaned) }; onDismiss() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun QuickPickShelf(songs: List<Track>, activeId: String?, onPlay: (Track) -> Unit, onMore: (Track) -> Unit, onPlayAll: () -> Unit) {
    val pages = remember(songs) { songs.take(20).chunked(4) }
    val shelf = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(pages.size) { if (pages.isNotEmpty() && shelf.firstVisibleItemIndex > pages.lastIndex) shelf.scrollToItem(pages.lastIndex) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Quick picks", fontSize = 22.sp, letterSpacing = (-.5).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onPlayAll, shape = CircleShape, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, Muted.copy(alpha = .3f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = ContentColor)) {
                Text("Play all", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedIconButton(onClick = { scope.launch { shelf.animateScrollToItem((shelf.firstVisibleItemIndex - 1).coerceAtLeast(0)) } },
                enabled = shelf.canScrollBackward, modifier = Modifier.padding(start = 6.dp).size(40.dp)) {
                MusicIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous quick picks page", modifier = Modifier.size(20.dp))
            }
            OutlinedIconButton(onClick = { scope.launch { shelf.animateScrollToItem((shelf.firstVisibleItemIndex + 1).coerceAtMost(pages.lastIndex)) } },
                enabled = shelf.canScrollForward, modifier = Modifier.padding(start = 6.dp).size(40.dp)) {
                MusicIcon(Icons.AutoMirrored.Rounded.ArrowForward, "Next quick picks page", modifier = Modifier.size(20.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pageWidth = (maxWidth - 62.dp).coerceIn(240.dp, 390.dp)
            LazyRow(state = shelf, contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(pages, key = { it.first().id }, contentType = { "quick-picks-page" }) { page ->
                    Column(Modifier.width(pageWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        page.forEach { song -> key(song.id) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).combinedClickable(onClickLabel = "Play ${song.title}",
                                onClick = { onPlay(song) }, onLongClickLabel = "Song options", onLongClick = { onMore(song) }),
                                verticalAlignment = Alignment.CenterVertically) {
                                CoverImage(song.artwork, "Artwork for ${song.title}", Modifier.size(56.dp).clip(RoundedCornerShape(5.dp)), rounded = false)
                                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(song.homeTitle(), color = if (song.id == activeId) Lavender else ContentColor,
                                        fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.distinct().joinToString(" • "),
                                        color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onMore(song) }, modifier = Modifier.size(40.dp)) {
                                    MusicIcon(Icons.Outlined.MoreVert, "Options for ${song.title}", tint = Muted, modifier = Modifier.size(20.dp))
                                }
                            }
                        } }
                    }
                }
            }
        }
    }
}
