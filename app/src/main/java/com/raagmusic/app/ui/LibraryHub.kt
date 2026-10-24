package com.raagmusic.app.ui

import androidx.compose.foundation.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.raagmusic.app.domain.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
@Composable
fun LibraryScreen(vm: MusicViewModel, playlistId: String?, onPlaylist: (String?) -> Unit, onCreate: () -> Unit,
                  onRename: (Playlist) -> Unit, onDelete: (Playlist) -> Unit, onMore: (Track) -> Unit, onSettings: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val features by vm.features.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf<Playlist?>(null) }
    var folder by rememberSaveable { mutableStateOf("") }
    var historyDay by rememberSaveable { mutableStateOf<String?>(null) }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importLocalAudio(uris) }
    var tab by rememberSaveable { mutableStateOf("Playlists") }
    var analyticsRange by rememberSaveable { mutableStateOf(AnalyticsRange.WEEK) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedSmart by rememberSaveable { mutableStateOf<String?>(null) }
    val analytics = if (tab == "Insights") rememberListeningAnalytics(features, library, analyticsRange) else ListeningAnalytics()
    val sort = preferences.librarySort
    val playlist = library.playlists.find { it.id == playlistId }
    if (playlist != null) { PlaylistEditor(vm, playlist, { onPlaylist(null) }, { onRename(playlist) }, { onDelete(playlist) }, onMore); return }
    val smart = selectedSmart?.let { name -> SmartPlaylist.entries.find { it.name == name } }
    androidx.activity.compose.BackHandler(smart != null) { selectedSmart = null }
    if (smart != null) {
        val tracks = insights.smart[smart].orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp)) {
            item {
                TextButton(onClick = { selectedSmart = null }) { Text("Back to library") }
                Text(smart.label, style = MaterialTheme.typography.headlineMedium)
                Text(smart.description, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
                CollectionActions(vm, tracks) { vm.saveTracksAsPlaylist(smart.label, tracks) }
                Text("Updates automatically as your library and listening history change.", color = Muted)
            }
            if (tracks.isEmpty()) item { Text("No matching songs yet. Like songs and listen to build this playlist.", modifier = Modifier.padding(vertical = 20.dp), color = Muted) }
            items(tracks, key = { it.id }, contentType = { "track" }) { t -> TrackRow(t, favorite = t.id in library.favorites, onPlay = { vm.play(t, tracks) }, onFavorite = { vm.favorite(t) }, onMore = { onMore(t) }) }
        }; return
    }
    val filter = preferences.libraryFilter
    val downloaded = remember(downloads) { downloads.filterValues { it.state == Download.STATE_COMPLETED }.keys }
    val metadata = remember(library.savedTracks) { library.savedTracks.associateBy { it.id } }
    val tracks by produceState(emptyList<Track>(), library, sort, filter, query, downloaded) {
        value = withContext(Dispatchers.Default) { libraryTracks(library, sort, filter, query, downloaded) }
    }
    val playlists = remember(library.playlists, query, sort, metadata, folder, features.experience.pinnedPlaylists) {
        library.playlists.filter { it.name.contains(query.trim(), true) && (folder.isBlank() || it.folder == folder) }.let { ps -> when (sort) {
            LibrarySort.RECENT -> ps.asReversed()
            LibrarySort.TITLE -> ps.sortedBy { it.name.lowercase() }
            LibrarySort.ARTIST -> ps.sortedBy { p -> metadata[p.trackIds.firstOrNull()]?.artist?.lowercase().orEmpty() }
        } }.sortedByDescending { it.id in features.experience.pinnedPlaylists }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(if (tab == "Insights") 24.dp else 10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your library", style = MaterialTheme.typography.headlineLarge, color = ContentColor)
                    Text("${library.savedTracks.size} songs · ${library.playlists.size} playlists", color = Muted, fontSize = 12.sp)
                }
                FilledTonalIconButton(onClick = onCreate) { MusicIcon(Icons.Rounded.Add, "New playlist", tint = ContentColor) }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LibraryShortcut("Liked songs", "${library.favorites.size} favorites", Icons.Rounded.Favorite,
                    Modifier.weight(1f)) { tab = "Songs"; query = ""; vm.updatePreferences { it.copy(libraryFilter = LibraryFilter.FAVORITES) } }
                LibraryShortcut("On your device", "Listen anywhere", Icons.Rounded.OfflinePin,
                    Modifier.weight(1f)) { tab = "Songs"; query = ""; vm.updatePreferences { it.copy(libraryFilter = LibraryFilter.LOCAL) } }
            }
            if (tab == "Playlists" || tab == "Songs") MusicSearchField(query, { query = it }, "Search library", Modifier.padding(vertical = 16.dp))
            else Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Playlists", "Songs", "Smart playlists", "History", "Insights").forEach { label -> Pill(label, tab == label) { tab = label } }
            }
            if (tab != "Playlists" && tab != "Songs") Spacer(Modifier.height(12.dp))
            if (tab == "Playlists" || tab == "Songs") {
                var sorting by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sorting = true }) {
                        MusicIcon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text(sort.label)
                    }
                    DropdownMenu(sorting, { sorting = false }) {
                        LibrarySort.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                vm.updatePreferences { it.copy(librarySort = choice) }; sorting = false
                            })
                        }
                    }
                }
            }
            if (tab == "Songs") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.entries.forEach { choice -> Pill(choice.label, filter == choice) { vm.updatePreferences { it.copy(libraryFilter = choice) } } }
            }
            UndoRemoval(vm)
        }
        when (tab) {
            "Playlists" -> {
                item {
                    if (library.playlists.any { it.folder.isNotBlank() }) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("All folders", folder.isBlank()) { folder = "" }
                        library.playlists.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted().forEach { name -> Pill(name, folder == name) { folder = name } }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Made by you", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = ContentColor)
                        IconButton(onClick = { vm.updateExperience { it.copy(libraryGrid = !it.libraryGrid) } }) {
                            MusicIcon(if (features.experience.libraryGrid) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                                if (features.experience.libraryGrid) "Show list" else "Show grid", tint = Muted)
                        }
                    }
                }
                if (features.experience.libraryGrid) items(playlists.chunked(2), key = { it.joinToString { p -> p.id } }) { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.forEach { p -> Column(Modifier.weight(1f).glass().clickable { onPlaylist(p.id) }.padding(12.dp)) {
                            PlaylistArtwork(p, p.trackIds.mapNotNull(metadata::get), Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)); Text("${p.trackIds.size} songs", color = Muted)
                            Row { Glyph(if (p.id in features.experience.pinnedPlaylists) Icons.Rounded.PushPin else Icons.Rounded.PushPin, if (p.id in features.experience.pinnedPlaylists) "Unpin ${p.name}" else "Pin ${p.name}") { vm.pinPlaylist(p.id) }; Glyph(Icons.Rounded.MoreHoriz, "Edit ${p.name}") { info = p } }
                        } }
                        if (group.size == 1) Spacer(Modifier.weight(1f))
                    }
                } else items(playlists, key = { it.id }, contentType = { "playlist" }) { p ->
                    Row(Modifier.fillMaxWidth().then(if (LocalAppearance.current.reduceMotion) Modifier else Modifier.animateItem()).glass(18).clickable { onPlaylist(p.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlaylistArtwork(p, p.trackIds.mapNotNull(metadata::get), Modifier.size(64.dp))
                        Column(Modifier.weight(1f).padding(start = 15.dp)) { Text(p.name, fontWeight = FontWeight.Bold); Text("${p.trackIds.size} songs${if (p.folder.isBlank()) "" else " · ${p.folder}"}", color = Muted) }
                        Glyph(Icons.Rounded.PushPin, if (p.id in features.experience.pinnedPlaylists) "Unpin ${p.name}" else "Pin ${p.name}", if (p.id in features.experience.pinnedPlaylists) Lavender else Muted) { vm.pinPlaylist(p.id) }
                        Glyph(Icons.Rounded.MoreHoriz, "Edit ${p.name}") { info = p }
                    }
                }
                if (playlists.isEmpty()) item { EmptyState(Icons.Rounded.LibraryMusic,
                    if (query.isBlank()) "Make room for your favorites" else "No matching playlists",
                    if (query.isBlank()) "Gather the songs you love into a playlist for any moment." else "Try another name or folder.") }
                item { FilledTonalButton(onClick = onCreate, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    MusicIcon(Icons.Rounded.Add, null, tint = ContentColor); Spacer(Modifier.width(8.dp)); Text("Create a playlist")
                } }
            }
            "Songs" -> {
                item { OutlinedButton(onClick = { importAudio.launch(arrayOf("audio/*")) }) { MusicIcon(Icons.Rounded.AudioFile, null); Text("Import local audio") } }
                item { CollectionActions(vm, tracks) { vm.saveTracksAsPlaylist("Library selection", tracks) } }
                if (tracks.isEmpty()) item { Text(if (filter == LibraryFilter.LOCAL) "No local tracks or completed downloads yet." else "No songs match these filters.", color = Muted) }
                items(tracks, key = { it.id }, contentType = { "track" }) { t -> TrackRow(t, favorite = t.id in library.favorites, onPlay = { vm.play(t, tracks) }, onFavorite = { vm.favorite(t) }, onMore = { onMore(t) }) }
            }
            "Smart playlists" -> {
                items(SmartPlaylist.entries) { kind ->
                    val count = insights.smart[kind].orEmpty().size
                    Column(Modifier.fillMaxWidth().glass().clickable { selectedSmart = kind.name }.padding(18.dp)) {
                        Text(kind.label, style = MaterialTheme.typography.titleLarge)
                        Text("$count songs · ${kind.description}", color = Muted)
                    }
                }
            }
            "Insights" -> libraryInsights(vm, features, library, analytics, analyticsRange, { analyticsRange = it }, onSettings, onMore)
            "History" -> {
                val days = insights.days.filter { historyDay == null || it.day == historyDay }
                item { HistoryCalendar(features, historyDay) { historyDay = it } }
                item { Text("Listening history", style = MaterialTheme.typography.titleLarge)
                    Text("Songs grouped by day. Repeated plays are combined; exact play times were not recorded.", color = Muted)
                    if (!preferences.collectHistory) Text("History collection is off. Enable it in Listening settings to record new plays.", color = Muted)
                }
                if (days.isEmpty()) item { Text("Listen to music to start your timeline.", color = Muted) }
                days.forEach { day ->
                    item(key = "day-${day.day}") {
                        Text(day.day, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                        Text("${day.plays} plays · ${day.milliseconds / 60_000} min", color = Muted)
                        CollectionActions(vm, day.tracks) { vm.saveTracksAsPlaylist("Listening · ${day.day}", day.tracks) }
                    }
                    items(day.tracks, key = { "${day.day}-${it.id}" }, contentType = { "track" }) { t -> TrackRow(t, favorite = t.id in library.favorites, onPlay = { vm.play(t, day.tracks) }, onFavorite = { vm.favorite(t) }, onMore = { onMore(t) }) }
                }
            }
        }
    }
    info?.let { selected -> PlaylistDetailsDialog(vm, selected, { info = null }, { onRename(selected) }) }
}

@Composable
private fun LibraryShortcut(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = hapticAction(onClick), modifier = modifier, shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, contentColor = ContentColor) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                MusicIcon(icon, null, tint = ContentColor, modifier = Modifier.size(22.dp))
            }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}
@UnstableApi
@Composable
private fun CollectionActions(vm: MusicViewModel, tracks: List<Track>, save: () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = tracks.isNotEmpty(), onClick = { vm.play(tracks.first(), tracks) }) { Text("Play all") }
        TextButton(enabled = tracks.isNotEmpty(), onClick = { val shuffled = tracks.shuffled(); vm.play(shuffled.first(), shuffled) }) { Text("Shuffle") }
        TextButton(enabled = tracks.isNotEmpty(), onClick = save) { Text("Save as playlist") }
    }
}

@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistDetail(vm: MusicViewModel, page: ArtistPageState, onMore: (Track) -> Unit) {
    ThemeSheetSystemBars()
    val library by vm.library.collectAsStateWithLifecycle()
    val features by vm.features.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var expandedBio by remember(page.name) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AmbientPlayerBackdrop(page.imageUrl, Modifier.matchParentSize(), dark = LocalDarkTheme.current)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "artist-hero") {
                Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .46f).dp.coerceIn(260.dp, 520.dp))) {
                    ArtistPortrait(vm, page.name, Modifier.fillMaxSize().artworkBottomScrim(),
                        imageUrl = page.imageUrl, fullBleed = true, resolveByName = !page.identityBound)
                    if (!LocalDarkTheme.current) Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                        0f to Color.Transparent, .5f to Color.Transparent, 1f to Ink)))
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 22.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(page.name, style = MaterialTheme.typography.headlineLarge, color = ContentColor,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (page.profile?.verified == true) Text("Verified artist", color = Muted, fontSize = 12.sp)
                        if (page.profileLoading) LinearProgressIndicator(Modifier.width(120.dp), color = ContentColor, trackColor = Muted.copy(alpha = .2f))
                    }
                }
            }
            if (page.profile?.subscribers != null || page.profile?.monthlyAudience != null) item(key = "artist-audience") {
                Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth().glass(20).padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    page.profile?.subscribers?.let { ArtistStat(compactAudienceCount(it), "YouTube subscribers", Modifier.weight(1f)) }
                    page.profile?.monthlyAudience?.let { audience ->
                        ArtistStat(audience.substringBefore(" monthly", audience), "Monthly audience", Modifier.weight(1f))
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Popular songs", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = page.songs.items.isNotEmpty(), onClick = { page.songs.items.firstOrNull()?.let { vm.play(it, page.songs.items) } }) { Text("Play all") }
                }
            }
            item(key = "artist-popular-shelf") {
                if (page.songs.loading) Box(Modifier.padding(horizontal = 22.dp)) { SongSkeletons(4) }
                else if (page.songs.items.isEmpty()) Text(page.songs.error ?: "No songs available.", color = Muted, modifier = Modifier.padding(horizontal = 22.dp))
                else LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(page.songs.items.chunked(4), key = { it.first().id }) { group ->
                        Column(Modifier.width((LocalConfiguration.current.screenWidthDp * .84f).dp.coerceIn(280.dp, 420.dp))) {
                            group.forEach { song -> TrackRow(song, showFavorite = false,
                                onPlay = { vm.play(song, page.songs.items) }, onFavorite = { vm.favorite(song) }, onMore = { onMore(song) }) }
                        }
                    }
                }
            }
            if (page.albums.loading || page.albums.items.isNotEmpty() || page.albums.error != null) {
                item { Text("Albums & releases", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 22.dp)) }
                item(key = "artist-album-shelf") {
                    when {
                        page.albums.loading -> ShelfSkeletons()
                        page.albums.error != null -> Box(Modifier.padding(horizontal = 22.dp)) { SectionFailure(page.albums.error!!) { vm.openArtist(page.name, profile = page.profile) } }
                        else -> LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(page.albums.items, key = { it.id }) { album -> CollectionCard(album) { vm.openCollection(album) } }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 22.dp)) {
                    SectionHeading("Related artists", "YOU MIGHT ALSO LIKE")
                    if (page.related.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (page.related.items.isEmpty()) Text(page.related.error ?: "Related artists aren't available for this song.", color = Muted)
                }
            }
            item {

                LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(page.related.items.take(10), key = { it }) { name ->
                        Column(Modifier.width(116.dp).clickable { vm.openArtist(name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistPortrait(vm, name, Modifier.size(112.dp).clip(androidx.compose.foundation.shape.CircleShape))
                            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    page.profile?.description?.takeIf { it.isNotBlank() }?.let { bio ->
                        Text("About", style = MaterialTheme.typography.titleMedium)
                        Text(bio, color = Muted, maxLines = if (expandedBio) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { expandedBio = !expandedBio }) { Text(if (expandedBio) "Show less" else "Read more") }
                    }
                    page.profileError?.let { Text(it, color = Muted, fontSize = 12.sp) }
                }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtistImageButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back from artist", onClick = vm::closeArtist)
            Spacer(Modifier.weight(1f))
            val followed = page.name in features.experience.followedArtists
            ArtistImageButton(if (followed) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd,
                if (followed) "Unfollow ${page.name}" else "Follow ${page.name}") { vm.followArtist(page.name) }
            ArtistImageButton(Icons.Rounded.PlayArrow, "Play artist", enabled = page.songs.items.isNotEmpty()) {
                page.songs.items.firstOrNull()?.let { vm.play(it, page.songs.items) }
            }
        }
    }
}

@Composable
private fun ArtistImageButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = hapticAction(onClick), enabled = enabled,
        modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = .42f), androidx.compose.foundation.shape.CircleShape)) {
        Icon(icon, label, Modifier.size(24.dp), tint = Color.White.copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
private fun ArtistStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ContentColor)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}
