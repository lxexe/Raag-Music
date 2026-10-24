package com.raagmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Album artwork is never used as an artist portrait. */
@UnstableApi
@Composable
fun ArtistPortrait(vm: MusicViewModel, name: String, modifier: Modifier, imageUrl: String = "", fullBleed: Boolean = false, resolveByName: Boolean = true) {
    val portraits by vm.portraits.collectAsStateWithLifecycle()
    val portrait = imageUrl.ifBlank { if (resolveByName) portraits[name] ?: vm.cachedPortrait(name) else "" }
    LaunchedEffect(name, imageUrl) { if (imageUrl.isBlank() && resolveByName) vm.requestPortrait(name) }
    if (portrait.isNotBlank()) CoverImage(portrait, "Artist image for $name", modifier, highQuality = fullBleed, rounded = !fullBleed, animate = fullBleed)
    else Box(modifier.background(Glass), contentAlignment = Alignment.Center) {
        MusicIcon(Icons.Rounded.Person, "Artist image unavailable for $name", tint = Muted, modifier = Modifier.size(48.dp))
    }
}

/** Render metadata immediately; request extra credits only when no byline is available. */
@UnstableApi
@Composable
fun rememberSongArtists(vm: MusicViewModel, track: com.raagmusic.app.domain.Track): List<String> {
    val credits by vm.songCredits.collectAsStateWithLifecycle()
    LaunchedEffect(track.id) { vm.requestSongArtists(track) }
    return credits[track.id] ?: vm.cachedArtists(track)
}
