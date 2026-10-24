package com.raagmusic.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.PlaybackState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

private enum class Destination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Discover", Icons.Rounded.Explore), SEARCH("Search", Icons.Rounded.Search), LIBRARY("Library", Icons.Rounded.LibraryMusic), DOWNLOADS("Downloads", Icons.Rounded.Download)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@UnstableApi
@Composable
fun RaagApp(vm: MusicViewModel) {
    val screens = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val homeScroll = rememberLazyListState()
    val exitPreferences by vm.preferences.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var searchFocusRequest by remember { mutableIntStateOf(0) }
    var mood by rememberSaveable { mutableStateOf("For you") }
    var expandedPlayer by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<Track?>(null) }
    var addToPlaylist by remember { mutableStateOf<Track?>(null) }
    var createPlaylist by remember { mutableStateOf(false) }
    var newPlaylistTrack by remember { mutableStateOf<Track?>(null) }
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamePlaylist by remember { mutableStateOf<Playlist?>(null) }
    var deletePlaylist by remember { mutableStateOf<Playlist?>(null) }
    var settings by remember { mutableStateOf(false) }
    var countrySettings by rememberSaveable { mutableStateOf(false) }
    var appearanceSettings by rememberSaveable { mutableStateOf(false) }
    val appearance = LocalAppearance.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val region by vm.homeRegion.collectAsStateWithLifecycle()
    val discovery by vm.discoveryPreferences.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val navigate: (Destination) -> Unit = { target ->
        if (target != Destination.HOME) vm.pauseHomeLoading()
        if (destination == Destination.SEARCH && target != Destination.SEARCH) vm.clearSearch()
        vm.closeArtist(); vm.closeBrowse()
        if (target == Destination.SEARCH) searchFocusRequest++
        else { focus.clearFocus(); keyboard?.hide() }
        destination = target; playlistId = null
    }
    val library by vm.library.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val remoteCollection by vm.selectedCollection.collectAsStateWithLifecycle()
    val artistPage by vm.artist.collectAsStateWithLifecycle()
    val browsePage by vm.browse.collectAsStateWithLifecycle()
    val linkLoading by vm.linkLoading.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<Track?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingDownload?.let(vm::download)
        pendingDownload = null
    }
    val download: (Track) -> Unit = { track ->
        if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = track; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.download(track)
    }
    LaunchedEffect(vm) { vm.messages.collect { message -> snackbars.showSnackbar(message) } }
    LaunchedEffect(vm) { vm.undoVersion.collectLatest { version ->
        vm.undoLabel.value?.let { label ->
            val result = snackbars.showSnackbar(label, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed && vm.undoVersion.value == version) vm.undoRemoval()
        }
    } }
    LaunchedEffect(vm) { vm.playback.map { it.error }.distinctUntilChanged().collect { error -> error?.let { snackbars.showSnackbar(it) } } }
    BackHandler(exitPreferences.stopOnExit && destination == Destination.HOME && !imeOpen && artistPage == null && browsePage == null &&
        !expandedPlayer && !settings && !showQueue && options == null && remoteCollection == null && !appearanceSettings && !countrySettings) {
        (context as? android.app.Activity)?.finish()
    }
    BackHandler(destination == Destination.SEARCH && !imeOpen) { navigate(Destination.HOME) }
    BackHandler(playlistId != null) { playlistId = null }
    BackHandler(browsePage != null && artistPage == null) { vm.closeBrowse() }
    BackHandler(artistPage != null) { vm.closeArtist() }
    val page: @Composable () -> Unit = {
        if (artistPage != null) ArtistDetail(vm, artistPage!!) { options = it }
        else if (browsePage != null) NativeBrowseScreen(vm, browsePage!!, { options = it }, vm::closeBrowse)
        else Crossfade(targetState = destination, animationSpec = tween(if (appearance.reduceMotion) 0 else 180), label = "Navigation") { screen ->
            screens.SaveableStateProvider(screen.name) {
                when(screen) {
                    Destination.HOME -> DiscoveryScreen(vm, mood, { mood = it }, { options = it }, { query -> vm.searchFromHome(query); navigate(Destination.SEARCH) }, { settings = true }, list = homeScroll)
                    Destination.SEARCH -> MusicSearchScreen(vm, { options = it }, searchFocusRequest) { navigate(Destination.HOME) }
                    Destination.LIBRARY -> LibraryScreen(vm, playlistId, { playlistId = it }, { createPlaylist = true }, { renamePlaylist = it }, { deletePlaylist = it }, { options = it }, onSettings = { settings = true })
                    Destination.DOWNLOADS -> DownloadsScreen(vm, { options = it })
                }
            }
        }
    }
    SharedTransitionLayout {
    CompositionLocalProvider(LocalPlayerTransition provides this, LocalFeedback provides snackbars, LocalAtmosphereArtwork provides current?.artwork.orEmpty()) {
    BoxWithConstraints(Modifier.fillMaxSize().then(if (appearance.mode == ThemeMode.DYNAMIC) Modifier.dynamicArtworkBackground(current?.artwork.orEmpty()) else Modifier.background(Ink))) {
        val wide = maxWidth >= 760.dp

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .then(if (expandedPlayer) Modifier.clearAndSetSemantics { } else Modifier)) {
            Row(Modifier.weight(1f)) {
                if(wide) Column(Modifier.width(176.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Brand(Modifier.padding(vertical = 20.dp))
                    Destination.entries.forEach { item ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(item == destination) Lavender.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { navigate(item) }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            MusicIcon(item.icon, null, tint = if(item == destination) Lavender else Muted, modifier = Modifier.size(21.dp))
                            Text(item.label, color = if(item == destination) Lavender else Muted, modifier = Modifier.padding(start = 12.dp), fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f)); Text("YOUR WORLD. IN SOUND.", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Box(Modifier.weight(1f)) { page(); if (linkLoading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter)) }
                if (wide) current?.let { track ->
                    AnimatedVisibility(!expandedPlayer, enter = fadeIn(tween(motionDuration(140))), exit = fadeOut(tween(motionDuration(140)))) {
                        CompositionLocalProvider(LocalPlayerVisibility provides this) {
                            WidePlayerPane(vm, track, Modifier.width(280.dp).fillMaxHeight(), { expandedPlayer = true }, { options = track })
                        }
                    }
                }
            }
            current?.takeIf { !wide }?.let { track ->
                Box(Modifier.heightIn(min = 82.dp)) {
                    androidx.compose.animation.AnimatedVisibility(!expandedPlayer, enter = fadeIn(tween(motionDuration(140))), exit = fadeOut(tween(motionDuration(140)))) {
                        CompositionLocalProvider(LocalPlayerVisibility provides this) {
                            MiniPlayerHost(vm, track,
                                { expandedPlayer = true }, vm.player::toggle, vm.player::next)
                        }
                    }
                }
                if (imeOpen) Spacer(Modifier.height(8.dp))
            }
            AnimatedVisibility(!wide && !imeOpen, enter = expandVertically(tween(motionDuration(180))) + fadeIn(tween(motionDuration(180))), exit = shrinkVertically(tween(motionDuration(180))) + fadeOut(tween(motionDuration(180)))) {
                Row(Modifier.fillMaxWidth().background(if (LocalDarkTheme.current) Color(0xFF101010) else MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    Destination.entries.forEach { item ->
                        val selected = item == destination
                        val tint by animateColorAsState(if (LocalDarkTheme.current) { if (selected) Color.White else Color.White.copy(alpha = .6f) } else { if (selected) ContentColor else Muted }, tween(motionDuration(180)), label = "Navigation color")
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).selectable(selected, role = Role.Tab, onClick = hapticAction { navigate(item) })
                            .semantics { contentDescription = item.label }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.width(56.dp).height(32.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.animation.AnimatedVisibility(selected, enter = fadeIn(tween(motionDuration(160))) + scaleIn(tween(motionDuration(160))), exit = fadeOut(tween(motionDuration(160)))) {
                                    Box(Modifier.fillMaxSize().background(if (LocalDarkTheme.current) Color.White.copy(alpha = .14f) else MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)))
                                }
                                MusicIcon(item.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                            }
                            val label = if (LocalDensity.current.fontScale > 1.4f) when (item) {
                                Destination.HOME -> "Home"; Destination.DOWNLOADS -> "Offline"; else -> item.label
                            } else item.label
                            Text(label, maxLines = 1, fontSize = 11.sp, color = tint, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
        PlayerPresentation(vm, expandedPlayer, current, { expandedPlayer = false }, { showQueue = true },
            { current?.let(download) }, { options = it }, { name -> current?.let { vm.openArtist(name, it) }; expandedPlayer = false })
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = if (imeOpen || wide) 96.dp else 158.dp))
    }
    if(showQueue) RaagSheet(onDismissRequest = { showQueue = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink, fullHeight = true) {
        ThemeSheetSystemBars()
        QueueEditor(vm, onClose = { showQueue = false }) { options = it }
    }
    options?.let { track -> RaagSheet(onDismissRequest = { options = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Glass) {
        ThemeSheetSystemBars()
        val artistNames = rememberSongArtists(vm, track)
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Artwork(track, Modifier.size(60.dp)); Column(Modifier.padding(start = 14.dp)) { Text(track.title, fontWeight = FontWeight.Bold); Text(artistNames.joinToString(" · "), color = Muted, fontSize = 12.sp) } }
            Spacer(Modifier.height(18.dp))
            if (current?.id == track.id) {
                PlayerAudioDetails(vm, track)
                PlayerSleepTimer(vm)
                Option(Icons.AutoMirrored.Rounded.QueueMusic, "Open queue") { options = null; showQueue = true }
            }
            Option(Icons.Outlined.FavoriteBorder, if(track.id in library.favorites) "Remove from favorites" else "Add to favorites") { vm.favorite(track); options = null }
            Option(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { addToPlaylist = track; options = null }
            Option(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { vm.enqueue(track, next = true); options = null }
            Option(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { vm.enqueue(track); options = null }
            artistNames.forEach { name ->
                Option(Icons.Outlined.Person, if (artistNames.size == 1) "View artist & albums" else "View $name") {
                    vm.openArtist(name, track); options = null; expandedPlayer = false
                }
            }
            Option(Icons.Rounded.ThumbDownOffAlt, "Less like this") { vm.lessLike(track); options = null }
            if (track.source == Source.YOUTUBE) Option(Icons.Rounded.Share, "Share song") {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${track.id}")
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share ${track.title}")); options = null
            }
            if(track.canDownload) {
                val existing = downloads[track.id]
                if (existing == null || existing.state == Download.STATE_FAILED) {
                    Option(Icons.Rounded.Download, if (existing == null) "Download for offline" else "Retry download") { download(track); options = null }
                }
                if (existing != null) Option(Icons.Rounded.DeleteOutline,
                    if (existing.state == Download.STATE_COMPLETED || existing.state == Download.STATE_FAILED) "Remove download" else "Cancel download") {
                    vm.removeDownload(track.id); options = null
                }
            }
            if(playlistId != null) Option(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist") { vm.removeFromPlaylist(playlistId!!, track.id); options = null }
            Spacer(Modifier.height(24.dp))
        }
    } }
    addToPlaylist?.let { track -> AlertDialog(onDismissRequest = { addToPlaylist = null }, title = { Text("Add to playlist") }, text = {
        Column { if(library.playlists.isEmpty()) Text("Create a playlist in your library first.", color = Muted)
            library.playlists.forEach { p -> TextButton(onClick = { vm.addToPlaylist(p.id, track); addToPlaylist = null }) { Text(p.name) } }
        }
    }, confirmButton = { TextButton(onClick = { newPlaylistTrack = track; addToPlaylist = null; navigate(Destination.LIBRARY); createPlaylist = true }) { Text("New playlist") } }, dismissButton = { TextButton(onClick = { addToPlaylist = null }) { Text("Cancel") } }) }
    if(createPlaylist) NameDialog("New playlist", "", { createPlaylist = false; newPlaylistTrack = null }) { vm.createPlaylist(it, newPlaylistTrack); createPlaylist = false; newPlaylistTrack = null }
    renamePlaylist?.let { p -> NameDialog("Rename playlist", p.name, { renamePlaylist = null }) { vm.renamePlaylist(p.id, it); renamePlaylist = null } }
    deletePlaylist?.let { p -> AlertDialog(onDismissRequest = { deletePlaylist = null }, title = { Text("Delete ${p.name}?") }, text = { Text("This removes the playlist from your library.") }, confirmButton = { TextButton(onClick = { vm.deletePlaylist(p.id); playlistId = null; deletePlaylist = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletePlaylist = null }) { Text("Cancel") } }) }
    if(settings) ExpandableSettingsSheet(onDismiss = { settings = false }) {
        ListeningSettings(vm, onAppearance = { settings = false; appearanceSettings = true },
            onCountry = { settings = false; countrySettings = true })
    }
    if (countrySettings) HomeCountryPicker(region, discovery.countryOverride, vm::selectHomeCountry) { countrySettings = false }
    profile?.let { saved ->
        if (!saved.namePromptSeen) ListenerNameDialog(saved.name, firstTime = true,
            onDismiss = { vm.saveListenerName("") }, onSave = vm::saveListenerName)
    }
    if (appearanceSettings) ExpandableSettingsSheet(onDismiss = { appearanceSettings = false }, title = "Appearance") {
        AppearanceSettings(vm)
    }
    remoteCollection?.let { collection ->
        RaagSheet(onDismissRequest = vm::closeCollection,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink) {
            CollectionDetail(vm, collection) { options = it }
        }
    }
    } }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Image(androidx.compose.ui.res.painterResource(com.raagmusic.app.R.drawable.brand_mark), null, modifier = Modifier.size(30.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (LocalDarkTheme.current) Color(0xFFFF7963) else Color(0xFFD64335)))
        Text(androidx.compose.ui.res.stringResource(com.raagmusic.app.R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = (-1).sp)
        Box(Modifier.padding(start = 2.dp).size(5.dp).background(Mint, CircleShape))
    }
}

@UnstableApi
@Composable
private fun DownloadsScreen(vm: MusicViewModel, onMore: (Track) -> Unit) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val metadata by vm.trackIndex.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val entries = remember(downloads, metadata, query) {
        downloads.values.filter { download ->
            metadata[download.request.id]?.let { track ->
                query.isBlank() || track.title.contains(query.trim(), true) || track.artist.contains(query.trim(), true)
            } ?: query.isBlank()
        }.sortedWith(compareBy<Download> { it.state == Download.STATE_COMPLETED }.thenByDescending { it.startTimeMs })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Downloads", style = MaterialTheme.typography.headlineMedium)
            MusicSearchField(query, { query = it }, "Search downloads", Modifier.padding(vertical = 16.dp))
            Text("${downloads.values.count { it.state == Download.STATE_COMPLETED }} songs offline", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if(downloads.values.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_STOPPED }) {
            item { TextButton(onClick = vm::retryDownloads) { MusicIcon(Icons.Rounded.PlayArrow, null); Text("Resume downloads") } }
        }
        if(downloads.isEmpty()) item { EmptyState(Icons.Rounded.Download, "Your music, offline", "Tap the download button in the player or choose Download for offline in a song’s options.") }
        else if(entries.isEmpty()) item { EmptyState(Icons.Rounded.SearchOff, "No downloads found", "Try another song or artist.") }
        items(entries, key = { it.request.id }) { d -> metadata[d.request.id]?.let { track ->
            val subtitle = when(d.state) {
                Download.STATE_COMPLETED -> "Offline · ${d.bytesDownloaded / 1_000_000} MB"
                Download.STATE_FAILED -> "Download failed"
                Download.STATE_DOWNLOADING -> if (d.percentDownloaded < 0) "Downloading…" else "Downloading · ${d.percentDownloaded.toInt()}%"
                Download.STATE_STOPPED -> "Paused"
                Download.STATE_REMOVING, Download.STATE_RESTARTING -> "Updating download…"
                else -> "Waiting for connection"
            }
            TrackRow(track, favorite = track.id in library.favorites, subtitle = subtitle, onPlay = { vm.play(track) }, onFavorite = { vm.favorite(track) }, onMore = { onMore(track) })
            if(d.state == Download.STATE_FAILED) TextButton(onClick = { vm.download(track) }) { Text("Retry download") }
            if(d.state == Download.STATE_DOWNLOADING) {
                val progressModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                if (d.percentDownloaded < 0) LinearProgressIndicator(progressModifier, color = Lavender)
                else LinearProgressIndicator(progress = { (d.percentDownloaded / 100).coerceIn(0f, 1f) }, modifier = progressModifier, color = Lavender)
            }
        } }
    }
}

@Composable
fun PageHeading(title: String, label: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 23.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Lavender, style = MaterialTheme.typography.labelSmall)
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@UnstableApi
@Composable
private fun MiniPlayerHost(vm: MusicViewModel, track: Track, onExpand: () -> Unit,
                           onToggle: () -> Unit, onNext: () -> Unit) {
    val controls = remember(vm) { vm.playback.map { it.copy(position = 0, bufferedPosition = 0) }.distinctUntilChanged() }
    val playback by controls.collectAsStateWithLifecycle(vm.playback.value)
    MiniPlayer(vm, track, rememberSongArtists(vm, track).joinToString(" · "), playback, onExpand, onToggle, onNext)
}

@UnstableApi
@Composable
private fun MiniPlayer(vm: MusicViewModel, track: Track, artists: String, state: PlaybackState, onExpand: () -> Unit,
                       onToggle: () -> Unit, onNext: () -> Unit) {
    var swipeX by remember { mutableFloatStateOf(0f) }
    var swipeY by remember { mutableFloatStateOf(0f) }
    val previous = hapticAction { vm.player.previousTrack() }
    val next = hapticAction(onNext)
    Column(Modifier.padding(horizontal = 14.dp).glass(21).clickable(onClickLabel = "Open full player", onClick = onExpand)
        .semantics { contentDescription = "Open full player" }
        .pointerInput(track.id) { detectHorizontalDragGestures(onDragStart = { swipeX = 0f }, onDragEnd = {
            if (swipeX < -64.dp.toPx()) next() else if (swipeX > 64.dp.toPx()) previous()
        }) { change, amount -> change.consume(); swipeX += amount } }
        .pointerInput(track.id) { detectVerticalDragGestures(onDragStart = { swipeY = 0f }, onDragEnd = { if (swipeY < -32.dp.toPx()) onExpand() }) { change, amount -> change.consume(); swipeY += amount } }) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 9.dp, bottom = 9.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(43.dp).playerArtwork(track.id))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                PlayerMarquee(track.title, Modifier.fillMaxWidth().playerTitle(track.id), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                PlayerMarquee(artists, Modifier.fillMaxWidth(), Muted, 11.sp)
            }
            Glyph(PlayerIcons.Previous, "Previous track", ContentColor, vm.player::previous)
            val wantsPlay = state.playWhenReady && state.error == null
            val playInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            IconButton(onClick = hapticAction(onToggle), interactionSource = playInteraction,
                modifier = Modifier.size(48.dp).pressFeedback(playInteraction).background(Color(0xFF242424), CircleShape)) {
                if (state.buffering && wantsPlay) CircularProgressIndicator(Modifier.size(32.dp), color = OnAccent.copy(alpha = .55f), strokeWidth = 2.dp)
                Crossfade(wantsPlay, animationSpec = tween(motionDuration(140)), label = "Mini transport") { play ->
                    MusicIcon(if(play) PlayerIcons.Pause else PlayerIcons.Play, if(play) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            Glyph(PlayerIcons.Next, "Next track", tint = ContentColor, onClick = onNext)
        }
        MiniPlayerProgress(vm)
    }
}

@UnstableApi
@Composable
private fun MiniPlayerProgress(vm: MusicViewModel) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    PlaybackTimeline(playback.position, playback.duration, playback.bufferedPosition, playback.buffering)
}

@Composable
private fun Option(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        MusicIcon(icon, null, tint = Lavender, modifier = Modifier.size(21.dp)); Text(label, Modifier.padding(start = 15.dp), fontSize = 14.sp)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Playlist name") }, singleLine = true, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
