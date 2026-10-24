package com.raagmusic.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.raagmusic.app.domain.isDark
import com.raagmusic.app.ui.resolveThemeColors
import android.widget.*
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.RaagApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@UnstableApi
class WidgetConfigurationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32); setBackgroundColor(Color.rgb(18, 17, 27)) }
        val heading = TextView(this).apply { text = getString(com.raagmusic.app.R.string.widget_shortcut_title); textSize = 24f; setTextColor(Color.WHITE) }
        box.addView(heading)
        setContentView(ScrollView(this).apply { addView(box) })
        scope.launch {
            val app = application as RaagApplication
            val appearance = app.features.state.first().theme
            val dark = appearance.mode.isDark(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            val colors = resolveThemeColors(this@WidgetConfigurationActivity, appearance, dark)
            box.setBackgroundColor(colors.background.toArgb())
            heading.setTextColor(colors.onBackground.toArgb())
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
            fun button(label: String, action: () -> Unit) = Button(this@WidgetConfigurationActivity).apply {
                text = label; setTextColor(colors.onPrimary.toArgb())
                backgroundTintList = android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
                setOnClickListener { action() }
            }
            val library = app.library.state.first()
            fun select(playlistId: String?, name: String) {
                getSharedPreferences("widgets", MODE_PRIVATE).edit().putString("playlist:$id", playlistId).putString("name:$id", name).apply()
                RaagWidget.update(this@WidgetConfigurationActivity)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
            }
            box.addView(button("Playback controls only") { select(null, "Choose playlist") })
            library.playlists.forEach { p -> box.addView(button(p.name) { select(p.id, p.name) }) }
            if (library.playlists.isEmpty()) box.addView(TextView(this@WidgetConfigurationActivity).apply { text = getString(com.raagmusic.app.R.string.widget_create_playlist); setTextColor(colors.onBackground.toArgb()) })
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
