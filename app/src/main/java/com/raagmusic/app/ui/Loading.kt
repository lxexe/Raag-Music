package com.raagmusic.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

private val LocalShimmer = staticCompositionLocalOf<State<Float>?> { null }

/** One animation drives a whole section, rather than one animator per placeholder. */
@Composable
fun SkeletonGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val phase = if (LocalAppearance.current.reduceMotion) null else {
        val motion = rememberInfiniteTransition(label = "Skeleton shimmer")
        motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "Shimmer sweep")
    }
    Box(modifier.semantics { stateDescription = "Loading content" }.clearAndSetSemantics { stateDescription = "Loading content" }) {
        CompositionLocalProvider(LocalShimmer provides phase, content = content)
    }
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, radius: Int = 14) {
    val phase = LocalShimmer.current
    val color = ContentColor
    val shape = RoundedCornerShape(radius.dp)
    Box(modifier.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val shift = (phase?.value ?: .3f) * size.width * 3 - size.width
            val brush = Brush.linearGradient(
                listOf(color.copy(alpha = .06f), color.copy(alpha = .14f), color.copy(alpha = .06f)),
                start = Offset(shift, 0f), end = Offset(shift + size.width, size.height))
            drawOutline(outline, brush = brush)
        }
    })
}

@Composable
fun SongSkeletons(count: Int = 5) = SkeletonGroup {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        repeat(count) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SkeletonBox(Modifier.size(52.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.8f).height(13.dp), 5)
                    SkeletonBox(Modifier.fillMaxWidth(0.5f).height(10.dp), 5)
                }
                SkeletonBox(Modifier.size(22.dp), 50)
            }
        }
    }
}

@Composable
fun ShelfSkeletons() = SkeletonGroup {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBox(Modifier.size(150.dp), 22)
                SkeletonBox(Modifier.width(110.dp).height(12.dp), 5)
                SkeletonBox(Modifier.width(75.dp).height(10.dp), 5)
            }
        }
    }
}
