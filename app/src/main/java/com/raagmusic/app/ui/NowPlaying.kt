package com.raagmusic.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

@UnstableApi
@Composable
fun NowPlayingPanel(vm: MusicViewModel, track: Track, onQueue: () -> Unit, onDownload: () -> Unit,
    onClose: () -> Unit, onMore: (Track) -> Unit, onArtist: (String) -> Unit) {
    val scheme = raagColorScheme(LocalAppearance.current.copy(mode = ThemeMode.DARK), true)
    val appDark = LocalDarkTheme.current
    val activity = LocalContext.current as? android.app.Activity
    SideEffect {
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        } }
    }
    DisposableEffect(activity, appDark) {
        onDispose { activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
            isAppearanceLightStatusBars = !appDark; isAppearanceLightNavigationBars = !appDark
        } } }
    }
    CompositionLocalProvider(LocalDarkTheme provides true, LocalContentColor provides scheme.onSurface) {
        MaterialTheme(colorScheme = scheme) { NowPlayingContent(vm, track, onQueue, onDownload, onClose, onMore, onArtist) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun NowPlayingContent(vm: MusicViewModel, track: Track, onQueue: () -> Unit, onDownload: () -> Unit,
                    onClose: () -> Unit, onMore: (Track) -> Unit, onArtist: (String) -> Unit) {
    ThemeSheetSystemBars()
    val steadyPlayback = remember(vm) { vm.playback.map { it.copy(position = 0, bufferedPosition = 0) }.distinctUntilChanged() }
    val state by steadyPlayback.collectAsStateWithLifecycle(vm.playback.value)
    val library by vm.library.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val metadata by vm.trackIndex.collectAsStateWithLifecycle()
    val suggestions by vm.playerSuggestions.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsAvailable = lyrics.trackId == track.id && lyrics.lyrics?.available == true
    val artists = rememberSongArtists(vm, track)
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    val lyricList = rememberSaveable(track.id, saver = LazyListState.Saver) { LazyListState() }
    val lyricFollowing = rememberSaveable(track.id) { mutableStateOf(true) }
    val lyricScroll = rememberSaveable(track.id, saver = ScrollState.Saver) { ScrollState(0) }
    var fullScreenLyrics by rememberSaveable { mutableStateOf(false) }
    var fullScreenVideo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(track.id, lyricsAvailable) {
        if (!lyricsAvailable) { lyricsOpen = false; fullScreenLyrics = false }
    }
    LaunchedEffect(state.trackId, state.hasVideo, state.buffering, state.error) {
        if (!state.hasVideo && !state.buffering && state.error == null) fullScreenVideo = false
    }
    val expandVideo = { lyricsOpen = false; fullScreenLyrics = false; queueOpen = false; fullScreenVideo = true }
    androidx.activity.compose.BackHandler(fullScreenVideo) { fullScreenVideo = false }
    androidx.activity.compose.BackHandler(lyricsOpen || queueOpen) {
        if (fullScreenLyrics) fullScreenLyrics = false else { lyricsOpen = false; queueOpen = false }
    }
    val upcoming = remember(state.queueIds, state.upcomingIndices, metadata) {
        state.upcomingIndices.mapNotNull { index -> state.queueIds.getOrNull(index)?.let(metadata::get)?.let { index to it } }
    }
    val appearance = LocalAppearance.current
    val apple = appearance.playerStyle == PlayerStyle.APPLE
    val list = rememberLazyListState()
    LaunchedEffect(track.id) { vm.resetPlayerSuggestions(track) }
    LaunchedEffect(list, track.id, suggestions.items.size, suggestions.loading, suggestions.error) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .distinctUntilChanged().collect { (last, total) ->
                if (last != null && last > 0 && last >= total - 5 && !suggestions.loading && suggestions.error == null)
                    vm.loadMorePlayerSuggestions(track)
            }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        AmbientPlayerBackdrop(track.artwork, Modifier.matchParentSize())
        val viewportHeight = maxHeight
        val compactViewport = maxHeight < 520.dp
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().scrollPanelGestures(onClose)
            .then(if ((fullScreenLyrics && lyricsOpen) || fullScreenVideo) Modifier.clearAndSetSemantics { } else Modifier),
            contentPadding = PaddingValues(bottom = 32.dp)) {
            state.failure?.let { failure ->
                item(key = "failure") { Box(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                    PlaybackFailureCard(failure, track, vm.player::toggle)
                } }
            }
            item(key = "player") {
                val immersive = appearance.playerStyle == PlayerStyle.IMMERSIVE
                Box(Modifier.fillMaxWidth().height(viewportHeight)) {
                    if (immersive) {
                        CoverImage(track.artwork, "Full-screen artwork for ${track.title}", Modifier.matchParentSize().artworkBottomScrim(), highQuality = true, rounded = false)
                    }
                    // Artwork controls always use a readable dark palette, including a light app theme.
                    val playerScheme = raagColorScheme(appearance.copy(mode = ThemeMode.DARK), true)
                    MaterialTheme(colorScheme = playerScheme) {
                    PlayerViewport(Modifier.fillMaxSize(), header = {
                        Row(Modifier.fillMaxWidth().panelHeaderGestures(onClose).padding(horizontal = 12.dp, vertical = if (compactViewport) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(PlayerIcons.Collapse, "Close player", ContentColor, onClick = onClose)
                            Text("NOW PLAYING", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            if (!apple) AudioOutputButton()
                            Glyph(if (apple) Icons.Rounded.MoreHoriz else PlayerIcons.More, "Song options", ContentColor) { onMore(track) }
                        }
                    }, artwork = { area ->
                        Box(area.padding(horizontal = if (apple) 4.dp else 16.dp, vertical = 8.dp)
                            .then(if (!lyricsOpen && !queueOpen) Modifier.songSwipeGestures(track.id, vm.player::previousTrack, vm.player::next) else Modifier), contentAlignment = Alignment.Center) {
                            if (!fullScreenLyrics) Crossfade(if (lyricsOpen) "lyrics" else if (queueOpen) "queue" else "artwork",
                                animationSpec = tween(motionDuration(180)), label = "Player content") { content ->
                                when (content) {
                                    "lyrics" -> LyricsPanel(vm, track, embedded = true, list = lyricList, following = lyricFollowing,
                                        plainScroll = lyricScroll, onToggleFullScreen = { fullScreenLyrics = true }, onClose = { lyricsOpen = false })
                                    "queue" -> InlinePlayerQueue(vm, upcoming, onQueue, onMore)
                                    else -> if (state.hasVideo) {
                                        if (!fullScreenVideo) Box(Modifier.fillMaxSize()) {
                                            PlayerVideo(vm, state, Modifier.fillMaxSize())
                                            IconButton(onClick = expandVideo, modifier = Modifier.align(Alignment.BottomEnd)
                                                .padding(8.dp).background(Color.Black.copy(alpha = .6f), CircleShape)) {
                                                Icon(Icons.Rounded.Fullscreen, "Full screen video", tint = Color.White)
                                            }
                                        }
                                    }
                                    else CoverImage(track.artwork, track.title, Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(if (apple) 8.dp else 20.dp)).playerArtwork(track.id).artworkBottomScrim(), highQuality = true, rounded = false)
                                }
                            }
                        }
                    }, controls = {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.error != null) SectionFailure(state.error!!) { vm.player.toggle() }
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    PlayerMarquee(track.title, Modifier.fillMaxWidth().playerTitle(track.id), fontSize = if (compactViewport) 20.sp else if (apple) 30.sp else 26.sp, fontWeight = if (apple) FontWeight.ExtraBold else FontWeight.Bold)
                                    PlayerArtistLine(artists, onArtist)
                                }
                                Glyph(if (track.id in library.favorites) PlayerIcons.Favorite else PlayerIcons.FavoriteBorder,
                                    if (track.id in library.favorites) "Remove favorite" else "Add favorite", Lavender) { vm.favorite(track) }
                            }
                            PlayerProgress(vm, track, compactViewport)
                            ActiveSleepBadge(vm)
                            Row(Modifier.fillMaxWidth().padding(vertical = if (compactViewport) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                                if (!apple) Glyph(PlayerIcons.Shuffle, if (state.shuffle) "Shuffle on. Turn off" else "Shuffle off. Turn on", if(state.shuffle) Lavender else Muted, vm.player::shuffle)
                                Glyph(if (apple) Icons.Rounded.FastRewind else PlayerIcons.Previous, "Previous", ContentColor, vm.player::previous)
                                val wantsPlay = state.playWhenReady && state.error == null
                                val playInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                IconButton(onClick = hapticAction(vm.player::toggle), interactionSource = playInteraction,
                                    modifier = Modifier.size(if (compactViewport) 56.dp else 70.dp).pressFeedback(playInteraction).background(Color.White.copy(alpha = if (apple) 0f else .15f), CircleShape)) {
                                    if (state.buffering && wantsPlay) CircularProgressIndicator(Modifier.size(if (compactViewport) 50.dp else 62.dp), color = OnAccent.copy(alpha = .55f), strokeWidth = 2.dp)
                                    Crossfade(wantsPlay, animationSpec = tween(motionDuration(140)), label = "Player transport") { play ->
                                        MusicIcon(if(play) PlayerIcons.Pause else PlayerIcons.Play, if(play) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(if (apple) if (compactViewport) 42.dp else 56.dp else if (compactViewport) 28.dp else 36.dp))
                                    }
                                }
                                Glyph(if (apple) Icons.Rounded.FastForward else PlayerIcons.Next, "Next", ContentColor, vm.player::next)
                                if (!apple) Glyph(if(state.repeat == Player.REPEAT_MODE_ONE) PlayerIcons.RepeatOne else PlayerIcons.Repeat,
                                    "Repeat: ${when (state.repeat) { Player.REPEAT_MODE_ONE -> "one song"; Player.REPEAT_MODE_ALL -> "all songs"; else -> "off" }}. Change repeat mode", if(state.repeat != Player.REPEAT_MODE_OFF) Lavender else Muted, vm.player::repeat)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                                IconButton(enabled = lyricsAvailable, modifier = Modifier.semantics {
                                    contentDescription = if (!lyricsAvailable) "Lyrics unavailable" else if (lyricsOpen) "Hide lyrics" else "Show lyrics"
                                }, onClick = hapticAction {
                                    lyricsOpen = !lyricsOpen; queueOpen = false
                                }) {
                                    Icon(PlayerIcons.Lyrics, null,
                                        tint = (if (lyricsOpen) ContentColor else Muted).copy(alpha = if (lyricsAvailable) 1f else .3f))
                                }
                                if (state.hasVideo) Glyph(Icons.Rounded.Fullscreen, "Full screen video", ContentColor, expandVideo)
                                Spacer(Modifier.weight(1f))
                                if (apple) {
                                    AudioOutputButton()
                                    Spacer(Modifier.weight(1f))
                                    Glyph(Icons.AutoMirrored.Rounded.QueueMusic, if (queueOpen) "Hide playing next" else "Show playing next", if (queueOpen) ContentColor else Muted) {
                                        queueOpen = !queueOpen; lyricsOpen = false
                                    }
                                } else if (track.canDownload) {
                                    val download = downloads[track.id]
                                    val saving = download != null && download.state in listOf(
                                        androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING,
                                        androidx.media3.exoplayer.offline.Download.STATE_QUEUED,
                                        androidx.media3.exoplayer.offline.Download.STATE_RESTARTING,
                                    )
                                    if (saving) IconButton(onClick = { onMore(track) },
                                        modifier = Modifier.semantics { contentDescription = "Downloading. Open song options" }) {
                                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    } else {
                                        val complete = download?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
                                        Glyph(if (complete) Icons.Rounded.DownloadDone else PlayerIcons.Download,
                                            if (complete) "Downloaded. Open song options" else "Download track") {
                                            if (complete) onMore(track) else onDownload()
                                        }
                                    }
                                }

                            }
                        }
                    })
                    }
                }
            }
            item(key = "next-header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Up next", style = MaterialTheme.typography.headlineMedium)
                        Text(if (state.recommendationChain) "Endless radio · ${upcoming.size} ready to play" else "${upcoming.size} songs in your queue", color = Muted, fontSize = 12.sp)
                    }
                    Glyph(PlayerIcons.Queue, "Edit queue", onClick = onQueue)
                    Glyph(PlayerIcons.More, "Up next settings") { onMore(track) }
                }
            }
            items(upcoming, key = { "queued-${it.first}" }, contentType = { "queue-row" }) { (index, queued) ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TrackRow(queued, showFavorite = false, subtitle = if (index == upcoming.firstOrNull()?.first) "Plays next · ${queued.artist}" else queued.artist,
                        onPlay = { vm.player.selectQueue(index) }, onFavorite = { vm.favorite(queued) }, onMore = { onMore(queued) })
                }
            }
            item(key = "radio-status") {
                Column(Modifier.padding(horizontal = 22.dp)) {
                    if (state.recommendationLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lavender)
                    if (state.recommendationError != null) SectionFailure(state.recommendationError!!) { vm.player.retryRadio() }
                    if (upcoming.isEmpty() && !state.recommendationLoading) TextButton(onClick = { vm.startRadio(track) }) { Text("Start endless radio") }
                }
            }
            item(key = "suggestions-header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Discover more", style = MaterialTheme.typography.titleLarge)
                        Text("Keep exploring music that fits this song", color = Muted, fontSize = 12.sp)
                    }
                    Glyph(PlayerIcons.More, "Suggestion settings") { onMore(track) }
                }
            }
            items(suggestions.items, key = { "suggestion-${it.id}" }, contentType = { "suggestion-row" }) { song ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TrackRow(song, showFavorite = false, onPlay = { vm.play(song) }, onFavorite = { vm.favorite(song) }, onMore = { onMore(song) })
                }
            }
            item(key = "suggestions-status") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                    if (suggestions.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lavender)
                    if (suggestions.error != null) SectionFailure(suggestions.error!!) { vm.loadMorePlayerSuggestions(track) }
                    else TextButton(onClick = { vm.loadMorePlayerSuggestions(track) }, enabled = !suggestions.loading) { Text("More suggestions") }
                }
            }
        }
        if (lyricsOpen && fullScreenLyrics) {
            val opacity = remember { Animatable(0f) }
            val duration = motionDuration(180)
            LaunchedEffect(Unit) { opacity.animateTo(1f, tween(duration)) }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) {
                AmbientPlayerBackdrop(track.artwork, Modifier.matchParentSize())
                LyricsPanel(vm, track, fullScreen = true, list = lyricList, following = lyricFollowing, plainScroll = lyricScroll,
                    onToggleFullScreen = { fullScreenLyrics = false }, onClose = { fullScreenLyrics = false; lyricsOpen = false })
            }
        }
        if (fullScreenVideo) {
            Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                    Glyph(Icons.Rounded.FullscreenExit, "Exit full screen video", Color.White) { fullScreenVideo = false }
                }
                if (state.hasVideo) PlayerVideo(vm, state, Modifier.weight(1f).fillMaxWidth().songSwipeGestures(track.id, vm.player::previousTrack, vm.player::next))
                else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.buffering) CircularProgressIndicator(color = Color.White)
                }
                if (state.error != null) SectionFailure(state.error!!) { vm.player.toggle() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Glyph(PlayerIcons.Previous, "Previous", Color.White, vm.player::previous)
                    Glyph(if (state.playWhenReady && state.error == null) PlayerIcons.Pause else PlayerIcons.Play,
                        if (state.playWhenReady && state.error == null) "Pause" else "Play", Color.White, vm.player::toggle)
                    Glyph(PlayerIcons.Next, "Next", Color.White, vm.player::next)
                }
                Column(Modifier.padding(horizontal = 24.dp)) { PlayerProgress(vm, track, compact = true) }
            }
        }
    }
}

@UnstableApi
@Composable
private fun InlinePlayerQueue(vm: MusicViewModel, upcoming: List<Pair<Int, Track>>, expand: () -> Unit, onMore: (Track) -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Playing next", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Glyph(PlayerIcons.Shuffle, if (state.shuffle) "Shuffle on. Turn off" else "Shuffle off. Turn on", if (state.shuffle) ContentColor else Muted, vm.player::shuffle)
            Glyph(if (state.repeat == Player.REPEAT_MODE_ONE) PlayerIcons.RepeatOne else PlayerIcons.Repeat, "Change repeat mode", if (state.repeat == Player.REPEAT_MODE_OFF) Muted else ContentColor, vm.player::repeat)
            Glyph(Icons.Rounded.Fullscreen, "Expand playing next", onClick = expand)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(upcoming, key = { "inline-${it.first}" }) { (index, song) ->
                TrackRow(song, showFavorite = false, onPlay = { vm.player.selectQueue(index) }, onFavorite = { vm.favorite(song) }, onMore = { onMore(song) })
            }
            if (upcoming.isEmpty()) item { Text("Your next songs appear here.", color = Muted, modifier = Modifier.padding(vertical = 22.dp)) }
        }
    }
}

/** Keep transport against the safe bottom edge; artwork consumes only the remaining space. */
@Composable
private fun PlayerViewport(modifier: Modifier, header: @Composable () -> Unit,
    artwork: @Composable (Modifier) -> Unit, controls: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 8.dp)) {
            header()
            if (landscape) Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Bottom) {
                artwork(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) { controls() }
            } else {
                artwork(Modifier.fillMaxWidth().weight(1f))
                controls()
            }
        }
    }
}

@UnstableApi
@Composable
private fun PlayerProgress(vm: MusicViewModel, track: Track, compact: Boolean) {
    val state by vm.playback.collectAsStateWithLifecycle()
    var preview by remember(track.id) { mutableStateOf<Long?>(null) }
    val duration = state.duration.takeIf { it > 0 } ?: track.durationSeconds * 1000
    Box(Modifier.fillMaxWidth()) {
    if (preview != null) Surface(modifier = Modifier.align(Alignment.TopCenter), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = Glass) { Text(timeLabel(preview!!), color = Lavender, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
    PlaybackTimeline(preview ?: state.position, duration, state.bufferedPosition, state.buffering,
        modifier = Modifier.padding(top = if (compact) 0.dp else 22.dp), interactive = true,
        onPreview = { preview = it }, onSeek = { vm.player.seek(it); preview = null })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(timeLabel(preview ?: state.position), color = Muted, fontSize = 11.sp)
        Text(timeLabel(duration), color = Muted, fontSize = 11.sp)
    }
}
