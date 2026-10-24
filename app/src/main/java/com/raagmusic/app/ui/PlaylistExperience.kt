package com.raagmusic.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*

@Composable
fun PlaylistArtwork(playlist: Playlist, tracks: List<Track>, modifier: Modifier = Modifier) {
    if (playlist.coverUri.isNotBlank()) { CoverImage(playlist.coverUri, "Cover for ${playlist.name}", modifier); return }
    val covers = tracks.distinctBy { it.artwork }.filter { it.artwork.isNotBlank() }.take(4)
    Box(modifier.clip(MaterialTheme.shapes.large).background(Glass)) {
        if (covers.isEmpty()) MusicIcon(Icons.Rounded.LibraryMusic, null, tint = Lavender, modifier = Modifier.align(Alignment.Center).size(32.dp))
        else if (covers.size < 4) Artwork(covers.first(), Modifier.fillMaxSize())
        else Column { repeat(2) { row -> Row(Modifier.weight(1f)) { repeat(2) { col -> Artwork(covers[row * 2 + col], Modifier.weight(1f).fillMaxHeight()) } } } }
    }
}

@UnstableApi
@Composable
fun PlaylistDetailsDialog(vm: MusicViewModel, playlist: Playlist, onClose: () -> Unit, onRename: () -> Unit) {
    val context = LocalContext.current
    var description by remember(playlist.id) { mutableStateOf(playlist.description) }
    var folder by remember(playlist.id) { mutableStateOf(playlist.folder) }
    var cover by remember(playlist.id) { mutableStateOf(playlist.coverUri) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        try { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); cover = it.toString() }
        catch (_: SecurityException) { error = "This image provider cannot keep access. Choose another image." }
    } }
    AlertDialog(onDismissRequest = onClose, title = { Text("Edit ${playlist.name}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(description, { description = it.take(2000) }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            OutlinedTextField(folder, { folder = it.take(80) }, label = { Text("Folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column { TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Choose cover") }; TextButton(onClick = { cover = "" }) { Text("Use collage") } }
            TextButton(onClick = { onClose(); onRename() }) { Text("Rename playlist") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { vm.editPlaylistDetails(playlist.id, description, folder, cover); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeRemoveRow(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val latest by rememberUpdatedState(onRemove)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) latest()
        false
    })
    SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(20.dp), contentAlignment = Alignment.CenterEnd) {
            MusicIcon(Icons.Rounded.DeleteOutline, "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }) { Box(Modifier.fillMaxWidth().background(Ink)) { content() } }
}
