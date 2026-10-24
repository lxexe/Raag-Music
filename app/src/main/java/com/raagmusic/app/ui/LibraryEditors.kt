package com.raagmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*

private class DragRowState { var offset by mutableFloatStateOf(0f); var dragging by mutableStateOf(false) }
@Composable
private fun ReorderHandle(state: DragRowState, list: LazyListState, key: String, onMove: (Int) -> Unit) {
    val action by rememberUpdatedState(onMove)
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val haptics = LocalAppearance.current.haptics
    MusicIcon(Icons.Rounded.DragHandle, "Long press and drag to reorder", tint = Muted,
        modifier = Modifier.size(48.dp).semantics {
            customActions = listOf(CustomAccessibilityAction("Move up") { action(-1); true }, CustomAccessibilityAction("Move down") { action(1); true })
        }.pointerInput(list, key) {
            detectDragGesturesAfterLongPress(onDragStart = { state.offset = 0f; state.dragging = true; if (haptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                onDragEnd = { state.dragging = false; state.offset = 0f }, onDragCancel = { state.dragging = false; state.offset = 0f }) { change, amount ->
                change.consume(); state.offset += amount.y
                val row = list.layoutInfo.visibleItemsInfo.find { it.key == key }
                val threshold = (row?.size ?: 160).toFloat()
                if (state.offset >= threshold) { action(1); state.offset -= threshold }
                else if (state.offset <= -threshold) { action(-1); state.offset += threshold }
                val y = (row?.offset ?: 0) + change.position.y + state.offset
                if (y < 64.dp.toPx()) scope.launch { list.scrollBy(-18.dp.toPx()) }
                else if (y > list.layoutInfo.viewportEndOffset - 64.dp.toPx()) scope.launch { list.scrollBy(18.dp.toPx()) }
            }
        })
}
@Composable
private fun Modifier.dragFeedback(state: DragRowState): Modifier {
    val offset = animateFloatAsState(if (state.dragging) state.offset else 0f, if (state.dragging) snap() else spring(), label = "Reorder settlement")
    return zIndex(if (state.dragging) 1f else 0f).graphicsLayer { translationY = offset.value; shadowElevation = if (state.dragging) 8.dp.toPx() else 0f; scaleX = if (state.dragging) 1.02f else 1f; scaleY = scaleX }
}

@UnstableApi
@Composable
fun UndoRemoval(vm: MusicViewModel) {
    val label by vm.undoLabel.collectAsStateWithLifecycle()
    label?.let { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(it, Modifier.weight(1f), color = Muted); TextButton(onClick = vm::undoRemoval) { Text("Undo") }
    } }
}

@UnstableApi
@Composable
fun PlaylistEditor(vm: MusicViewModel, playlist: Playlist, onBack: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onMore: (Track) -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val activeId by vm.activeTrackId.collectAsStateWithLifecycle()
    var query by remember(playlist.id) { mutableStateOf("") }
    var selected by remember(playlist.id) { mutableStateOf(emptySet<String>()) }
    val metadata by vm.trackIndex.collectAsStateWithLifecycle()
    val tracks = remember(playlist.trackIds, metadata) { playlist.trackIds.mapNotNull(metadata::get) }
    val visible = remember(tracks, query) { tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) } }
    LaunchedEffect(playlist.trackIds) { selected = selected.intersect(playlist.trackIds.toSet()) }
    val list = rememberLazyListState()
    var editDetails by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val suggestions by vm.dailyMix.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(20.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back to library") }
            PlaylistArtwork(playlist, tracks, Modifier.size(128.dp).padding(bottom = 12.dp))
            Text(playlist.name, style = MaterialTheme.typography.headlineMedium)
            Text("${tracks.size} songs · ${tracks.sumOf { it.durationSeconds } / 60} min${if (playlist.folder.isBlank()) "" else " · ${playlist.folder}"}", color = Muted)
            if (playlist.description.isNotBlank()) Text(playlist.description, Modifier.padding(vertical = 8.dp), color = Muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = tracks.isNotEmpty(), onClick = { val queue = tracks.filter { selected.isEmpty() || it.id in selected }; queue.firstOrNull()?.let { vm.play(it, queue) } }) { Text(if (selected.isEmpty()) "Play" else "Play selected") }
                FilledTonalButton(enabled = tracks.isNotEmpty(), onClick = { val queue = tracks.shuffled(); vm.play(queue.first(), queue, ordered = true) }) { Text("Shuffle") }
                OutlinedButton(onClick = { editDetails = true }) { Text("Edit details") }
                OutlinedButton(onClick = onDelete) { Text("Delete playlist") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search this playlist") })
            TextButton(onClick = { selecting = !selecting; if (!selecting) selected = emptySet() }) { Text(if (selecting) "Done selecting" else "Select songs") }
            if (selecting) Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { selected = if (visible.all { it.id in selected }) selected - visible.map { it.id }.toSet() else selected + visible.map { it.id } }) { Text("Select all") }
                TextButton(enabled = selected.isNotEmpty(), onClick = { tracks.filter { it.id in selected }.forEach { vm.enqueue(it) }; selected = emptySet() }) { Text("Queue ${selected.size}") }
                TextButton(enabled = selected.isNotEmpty(), onClick = { vm.removePlaylistTracks(playlist.id, selected); selected = emptySet() }) { Text("Remove selected") }
            }
            UndoRemoval(vm)
            if (query.isNotBlank()) Text("Clear the search to reorder songs", color = Muted)
        }
        if (visible.isEmpty()) item { Text(if (tracks.isEmpty()) "Add songs using the song options menu." else "No songs match your search.", color = Muted) }
        items(visible, key = { it.id }, contentType = { "track" }) { track ->
            val drag = remember(track.id) { DragRowState() }
            SwipeRemoveRow({ vm.removeFromPlaylist(playlist.id, track.id) }) {
            Row(Modifier.then(if (LocalAppearance.current.reduceMotion) Modifier else Modifier.animateItem()).dragFeedback(drag), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(track.id in selected, { selected = if (it) selected + track.id else selected - track.id })
                Column(Modifier.weight(1f)) { TrackRow(track, active = activeId == track.id, favorite = track.id in library.favorites, showFavorite = !selecting,
                    onPlay = { vm.play(track, tracks) }, onFavorite = { vm.favorite(track) }, onMore = { onMore(track) }) }
                if (query.isBlank()) ReorderHandle(drag, list, track.id) { direction ->
                    val ids = vm.library.value.playlists.find { it.id == playlist.id }?.trackIds.orEmpty()
                    val index = ids.indexOf(track.id)
                    ids.getOrNull(index + direction)?.let { vm.movePlaylistTrack(playlist.id, track.id, it) }
                }
            } }
        }
        if (suggestions.any { it.id !in playlist.trackIds }) item {
            Text("Suggested additions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 22.dp))
            Text("From your daily mix and listening", color = Muted)
            suggestions.filterNot { it.id in playlist.trackIds }.take(5).forEach { song ->
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(song.title); Text(song.artist, color = Muted) }; Glyph(Icons.Rounded.Add, "Add ${song.title}") { vm.addToPlaylist(playlist.id, song) } }
            }
        }
    }
    if (editDetails) PlaylistDetailsDialog(vm, playlist, { editDetails = false }, onRename)
}

@UnstableApi
@Composable
fun QueueEditor(vm: MusicViewModel, onClose: () -> Unit = {}, onMore: (Track) -> Unit) {
    val controls = remember(vm) { vm.playback.map { it.copy(position = 0, bufferedPosition = 0) }.distinctUntilChanged() }
    val playback by controls.collectAsStateWithLifecycle(vm.playback.value)
    val metadata by vm.trackIndex.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val suggestions by vm.playerSuggestions.collectAsStateWithLifecycle()
    val displayIndices = remember(playback.queueIds, playback.queueIndex, playback.upcomingIndices) {
        (listOf(playback.queueIndex).filter { it in playback.queueIds.indices } + playback.upcomingIndices).distinct()
    }
    val keys = remember(playback.queueIds) { queueOccurrenceKeys(playback.queueIds) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var selecting by remember { mutableStateOf(false) }
    var discover by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(playback.queueIds) { selected = emptySet() }
    val list = rememberLazyListState()
    LaunchedEffect(discover, current?.id) { if (discover) current?.let { vm.loadMorePlayerSuggestions(it) } }
    LaunchedEffect(list, discover, suggestions.items.size, suggestions.loading, suggestions.error) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) ->
                if (discover && last != null && total > 0 && last >= total - 4 && !suggestions.loading && suggestions.error == null)
                    current?.let { vm.loadMorePlayerSuggestions(it) }
            }
    }
    val playerState = playback
    Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.headlineMedium)
                Text(if (playback.recommendationChain) "Endless radio · ${playback.upcomingIndices.size} ready" else "${playback.upcomingIndices.size} upcoming songs", color = Muted, fontSize = 12.sp)
            }
            Box {
                Glyph(Icons.Rounded.MoreVert, "Queue settings") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Select songs") }, onClick = { selecting = true; menu = false; discover = false })
                    DropdownMenuItem(text = { Text("Save queue as playlist") }, onClick = {
                        vm.saveTracksAsPlaylist("Listening queue", displayIndices.mapNotNull { playback.queueIds.getOrNull(it)?.let(metadata::get) }); menu = false
                    })
                    DropdownMenuItem(text = { Text("Continue radio") }, onClick = { current?.let(vm::startRadio); menu = false })
                }
            }
            Glyph(Icons.Rounded.KeyboardArrowDown, "Close queue", onClick = onClose)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!discover, { discover = false }, label = { Text("Up next") })
            FilterChip(discover, { discover = true }, label = { Text("Discover") })
            if (selecting) TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("Done") }
        }
        if (selecting) Row {
            TextButton(onClick = { selected = if (selected.size == displayIndices.size) emptySet() else displayIndices.toSet() }) { Text("Select all") }
            TextButton(enabled = selected.isNotEmpty(), onClick = { vm.removeQueue(selected); selected = emptySet() }) { Text("Remove selected") }
        }
        UndoRemoval(vm)
        if (playerState.shuffle && !discover) Text("Turn off shuffle to reorder", color = Muted)
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (discover) {
                items(suggestions.items, key = { "suggestion-${it.id}" }, contentType = { "suggestion" }) { song ->
                    TrackRow(song, showFavorite = false, onPlay = { vm.play(song) }, onFavorite = { vm.favorite(song) }, onMore = { onMore(song) })
                }
                item {
                    if (suggestions.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (suggestions.error != null) SectionFailure(suggestions.error!!) { current?.let { vm.loadMorePlayerSuggestions(it) } }
                    TextButton(onClick = { current?.let { vm.loadMorePlayerSuggestions(it) } }, enabled = !suggestions.loading) { Text("More suggestions") }
                }
            } else {
                items(displayIndices, key = { keys[it] }, contentType = { "queue" }) { index ->
                    val id = playback.queueIds[index]
                    metadata[id]?.let { track ->
                        val drag = remember(keys[index]) { DragRowState() }
                        SwipeRemoveRow({ vm.removeQueue(setOf(index)) }) {
                            Row(Modifier.then(if (LocalAppearance.current.reduceMotion) Modifier else Modifier.animateItem()).dragFeedback(drag), verticalAlignment = Alignment.CenterVertically) {
                                if (selecting) Checkbox(index in selected, { selected = if (it) selected + index else selected - index }, modifier = Modifier.semantics { contentDescription = "Select ${track.title} from queue" })
                                Column(Modifier.weight(1f)) {
                                    if (index == playback.queueIndex) Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = Lavender, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
                                    var rowMenu by remember(keys[index]) { mutableStateOf(false) }
                                    Box {
                                        TrackRow(track, active = index == playback.queueIndex, showFavorite = false,
                                            onPlay = { vm.player.selectQueue(index) }, onFavorite = { vm.favorite(track) }, onMore = { rowMenu = true })
                                        DropdownMenu(rowMenu, { rowMenu = false }) {
                                            DropdownMenuItem(text = { Text("Song details") }, onClick = { rowMenu = false; onMore(track) })
                                            DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { rowMenu = false; vm.removeQueue(setOf(index)) })
                                        }
                                    }
                                }
                                if (!playerState.shuffle && !selecting) ReorderHandle(drag, list, keys[index]) { direction ->
                                    displayIndices.getOrNull(displayIndices.indexOf(index) + direction)?.let { vm.player.moveQueue(index, it) }
                                }
                            }
                        }
                    }
                }
                item {
                    if (playback.recommendationLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (playback.recommendationError != null) SectionFailure(playback.recommendationError!!) { vm.player.retryRadio() }
                    if (displayIndices.isEmpty()) Text("Play a song to start your queue", color = Muted, modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
}
