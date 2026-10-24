package com.raagmusic.app.ui

import android.app.Activity
import android.app.UiModeManager
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import com.raagmusic.app.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.raagmusic.app.domain.*

val Ink: Color @Composable get() = MaterialTheme.colorScheme.background
val Lavender: Color @Composable get() = MaterialTheme.colorScheme.primary
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Mint: Color @Composable get() = MaterialTheme.colorScheme.secondary
val Glass: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
val ContentColor: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val OnAccent: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
val LocalAppearance = staticCompositionLocalOf { ThemePreferences() }
val LocalAtmosphereArtwork = staticCompositionLocalOf { "" }
val LocalDarkTheme = staticCompositionLocalOf { true }

private val RaagFont = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal), Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold), Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
)

private val RaagTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 27.sp, letterSpacing = (-.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-.2).sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelSmall = TextStyle(fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium),
).let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = RaagFont),
        displayMedium = base.displayMedium.copy(fontFamily = RaagFont),
        displaySmall = base.displaySmall.copy(fontFamily = RaagFont),
        headlineLarge = base.headlineLarge.copy(fontFamily = RaagFont),
        headlineMedium = base.headlineMedium.copy(fontFamily = RaagFont),
        headlineSmall = base.headlineSmall.copy(fontFamily = RaagFont),
        titleLarge = base.titleLarge.copy(fontFamily = RaagFont),
        titleMedium = base.titleMedium.copy(fontFamily = RaagFont, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = RaagFont, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = RaagFont),
        bodyMedium = base.bodyMedium.copy(fontFamily = RaagFont),
        bodySmall = base.bodySmall.copy(fontFamily = RaagFont),
        labelLarge = base.labelLarge.copy(fontFamily = RaagFont),
        labelMedium = base.labelMedium.copy(fontFamily = RaagFont),
        labelSmall = base.labelSmall.copy(fontFamily = RaagFont),
    )
}

fun raagColorScheme(preferences: ThemePreferences, dark: Boolean): ColorScheme {
    val amoled = preferences.mode == ThemeMode.AMOLED
    val background = if (amoled) 0xFF000000 else if (dark) 0xFF101010 else 0xFFF6F7FB
    val primary = if (dark) 0xFFFFFFFF else 0xFF202736
    val secondary = primary
    val surface = if (dark) 0xFF151515 else 0xFFFFFFFF
    val container = if (dark) 0xFF202020 else 0xFFEBEEF5
    val primaryContainer = if (dark) 0xFF303030 else 0xFFE0E5F0
    val onSurface = Color(if (dark) 0xFFFFFFFF else 0xFF181D29)
    val muted = Color(if (dark) 0xFFB8B8B8 else 0xFF535D70)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(primary = Color(primary), onPrimary = Color(AccentPalette.onAccent(primary)),
        primaryContainer = Color(primaryContainer), onPrimaryContainer = onSurface,
        secondary = Color(secondary), onSecondary = Color(AccentPalette.onAccent(secondary)),
        secondaryContainer = Color(container), onSecondaryContainer = onSurface,
        tertiary = Color(secondary), onTertiary = Color(AccentPalette.onAccent(secondary)),
        background = Color(background), onBackground = onSurface, surface = Color(surface), onSurface = onSurface,
        surfaceVariant = Color(container), onSurfaceVariant = muted, surfaceTint = Color(primary),
        surfaceContainerLowest = Color(background), surfaceContainerLow = Color(surface), surfaceContainer = Color(container),
        surfaceContainerHigh = Color(if (dark) container else 0xFFE4E8F1), surfaceContainerHighest = Color(if (dark) container else 0xFFDEE3ED),
        outline = muted.copy(alpha = .55f), outlineVariant = muted.copy(alpha = .18f),
        inverseSurface = if (dark) Color(0xFFE9E6EF) else Color(0xFF262330), inverseOnSurface = if (dark) Color(0xFF262330) else Color(0xFFF4F1FA))
}

/** Shared by Compose and the native widget configuration activity. */
fun resolveThemeColors(context: android.content.Context, preferences: ThemePreferences, dark: Boolean): ColorScheme {
    val base = raagColorScheme(preferences, dark)
    return if (preferences.mode == ThemeMode.AMOLED) base.copy(background = Color.Black, surface = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF080808), surfaceContainer = Color(0xFF101010),
        surfaceContainerHigh = Color(0xFF151515), surfaceContainerHighest = Color(0xFF1B1B1B), surfaceTint = Color.Transparent) else base

}

@Composable
fun RaagTheme(preferences: ThemePreferences = ThemePreferences(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = preferences.mode.isDark(isSystemInDarkTheme())
    val scheme = remember(preferences, dark, configuration) { resolveThemeColors(context, preferences, dark) }
    val shapes = remember(preferences.corners) { Shapes(
        small = RoundedCornerShape((preferences.corners.radius / 2).dp), medium = RoundedCornerShape(preferences.corners.radius.dp),
        large = RoundedCornerShape(preferences.corners.radius.dp), extraLarge = RoundedCornerShape((preferences.corners.radius + 6).dp)) }
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    LaunchedEffect(preferences.mode) {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(UiModeManager::class.java)
            .setApplicationNightMode(when (preferences.mode) {
                // For the app API, AUTO clears the package override (NIGHT_UNDEFINED).
                // Unlike setNightMode(AUTO), this follows the device's current configuration.
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                else -> UiModeManager.MODE_NIGHT_YES
            })
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val systemMotionOff = remember(lifecycleState) {
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val effective = preferences.copy(reduceMotion = preferences.reduceMotion || preferences.motion == MotionLevel.OFF ||
        systemMotionOff || !lifecycleState.isAtLeast(Lifecycle.State.STARTED))
    CompositionLocalProvider(LocalAppearance provides effective, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, typography = RaagTypography, shapes = shapes, content = content)
    }
}
