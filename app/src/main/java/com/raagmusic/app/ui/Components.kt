package com.raagmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.raagmusic.app.data.youtube.ArtworkUrls
import com.raagmusic.app.domain.*

@Composable
fun Artwork(track: Track, modifier: Modifier = Modifier, highQuality: Boolean = false) {
    CoverImage(track.artwork, "Artwork for ${track.title}", modifier, highQuality)
}

@Composable
fun CoverImage(url: String, description: String?, modifier: Modifier = Modifier, highQuality: Boolean = false, rounded: Boolean = true, animate: Boolean = true) {
    val context = LocalContext.current
    var useOriginal by remember(url, highQuality) { mutableStateOf(false) }
    val original = if (url.startsWith("asset://")) "file:///android_asset/${url.removePrefix("asset://")}" else url
    val source = if (useOriginal) original else if (url.startsWith("asset://")) "file:///android_asset/${url.removePrefix("asset://")}" else
        ArtworkUrls.highResolution(url, if (highQuality) 1024 else 512)
    val motion = animate && !LocalAppearance.current.reduceMotion
    val request = remember(source, highQuality, motion) {
        // Coil resolves the measured display bounds instead of decoding every row at 512px.
        ImageRequest.Builder(context).data(source).crossfade(if (motion) if (highQuality) 360 else 140 else 0)
            .precision(Precision.INEXACT).build()
    }
    Box((if (rounded) modifier.clip(if (highQuality) MaterialTheme.shapes.large else MaterialTheme.shapes.small) else modifier).background(Glass)) {
        MusicIcon(Icons.Rounded.MusicNote, null, tint = Muted.copy(alpha = .45f),
            modifier = Modifier.align(Alignment.Center).size(24.dp))
        AsyncImage(request, description, Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            filterQuality = if (highQuality) FilterQuality.Medium else FilterQuality.Low,
            onSuccess = { result ->
                if (source != original && source.contains("/maxresdefault.") && result.result.drawable.intrinsicWidth <= 160) useOriginal = true
            },
            onError = { if (source != original && !useOriginal) useOriginal = true })
    }
}

@Composable
fun Modifier.glass(radius: Int = 22): Modifier {
    val appearance = LocalAppearance.current
    val shape = remember(radius, appearance.corners) { RoundedCornerShape((radius * appearance.corners.radius / 22f).dp) }
    val surface = Glass
    val outline = MaterialTheme.colorScheme.outlineVariant
    val clipped = clip(shape)
    return if (appearance.surfaces == SurfaceStyle.GLASS) clipped
        .background(Brush.linearGradient(listOf(surface, surface.copy(alpha = .65f))))
        .border(1.dp, outline, shape)
    else clipped.background(surface)
}

@Composable
fun Glyph(icon: ImageVector, description: String, tint: Color = Muted, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val duration = motionDuration(140)
    IconButton(onClick = hapticAction(onClick), interactionSource = source,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pressFeedback(source).semantics { contentDescription = description }) {
        AnimatedContent(icon, transitionSpec = { (fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = .8f)) togetherWith fadeOut(tween(duration)) }, label = "Control icon") {
            MusicIcon(it, null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val fill by animateColorAsState(if(selected) Lavender else Glass, tween(motionDuration(180)), label = "Pill color")
    Surface(onClick = hapticAction(onClick), modifier = Modifier.semantics { this.selected = selected; role = Role.RadioButton }, shape = RoundedCornerShape(50), color = fill,
        border = BorderStroke(1.dp, if(selected) Lavender else MaterialTheme.colorScheme.outlineVariant)) {
        Text(text, modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 19.dp, vertical = 10.dp), fontSize = 12.sp,
            fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Normal, color = if(selected) OnAccent else Muted)
    }
}

@Composable
fun TrackRow(track: Track, index: Int? = null, active: Boolean = false, favorite: Boolean = false,
             subtitle: String = track.artist, showFavorite: Boolean = true, onPlay: () -> Unit, onFavorite: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if(active) Lavender.copy(alpha = 0.075f) else Color.Transparent)
        .clickable(role = Role.Button, onClickLabel = "Play ${track.title}", onClick = onPlay)
        .semantics { if (active) stateDescription = "Current song" }
        .padding(horizontal = 8.dp, vertical = if (LocalAppearance.current.density == ContentDensity.COMPACT) 4.dp else 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if(index != null) Text(if(active) "♫" else "%02d".format(index), color = if(active) Lavender else Muted, fontSize = 11.sp, modifier = Modifier.width(29.dp))
        Artwork(track, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(track.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, color = if(active) Lavender else ContentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (showFavorite) Glyph(if(favorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder, if(favorite) "Remove ${track.title} from favorites" else "Favorite ${track.title}", if(favorite) Lavender else Muted, onFavorite)
        Glyph(Icons.Outlined.MoreVert, "Options for ${track.title}", onClick = onMore)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 50.dp, horizontal = 25.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).glass(24), contentAlignment = Alignment.Center) { MusicIcon(icon, null, tint = Lavender, modifier = Modifier.size(29.dp)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if(action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

fun timeLabel(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/** Material sheets have their own window and need the same icon contrast as the app. */
@Composable
fun ThemeSheetSystemBars() {
    val view = LocalView.current
    val dark = LocalDarkTheme.current
    SideEffect {
        generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

/** One measured line; animation is a drawing transform and respects Reduce motion. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerMarquee(text: String, modifier: Modifier = Modifier, color: Color = ContentColor,
                  fontSize: TextUnit = 14.sp, fontWeight: FontWeight = FontWeight.Normal) {
    val motion = !LocalAppearance.current.reduceMotion
    Text(text, modifier = modifier.then(if (motion) Modifier.basicMarquee(iterations = Int.MAX_VALUE,
        initialDelayMillis = 1_600, repeatDelayMillis = 1_600, spacing = MarqueeSpacing(32.dp), velocity = 28.dp) else Modifier),
        color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1,
        softWrap = false, overflow = TextOverflow.Ellipsis)
}

/** Scroll layout coordinates with the text so each artist remains a direct tap target. */
@Composable
fun PlayerArtistLine(artists: List<String>, onArtist: (String) -> Unit) {
    val names = artists.ifEmpty { listOf("Unknown artist") }
    val motion = !LocalAppearance.current.reduceMotion
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    LaunchedEffect(names, motion, dragged) {
        if (!motion || dragged) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            kotlinx.coroutines.delay(1_600)
            val duration = (((scroll.maxValue - scroll.value) / density) / 26f * 1_000).toInt().coerceAtLeast(1)
            scroll.animateScrollTo(scroll.maxValue, tween(duration, easing = LinearEasing))
            kotlinx.coroutines.delay(1_200)
            scroll.scrollTo(0)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 3.dp).horizontalScroll(scroll)) {
        names.forEachIndexed { index, name ->
            if (index > 0) Text(" · ", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
            Text(name, color = Muted, fontSize = 13.sp, maxLines = 1, softWrap = false,
                modifier = Modifier.clickable(enabled = name != "Unknown artist", role = Role.Button,
                    onClickLabel = "View $name") { onArtist(name) }.padding(vertical = 10.dp))
        }
    }
}
