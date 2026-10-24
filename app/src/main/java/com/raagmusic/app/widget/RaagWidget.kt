package com.raagmusic.app.widget

import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.raagmusic.app.data.youtube.ArtworkUrls
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.raagmusic.app.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class RaagWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { update(context) }
    override fun onDeleted(context: Context, ids: IntArray) {
        context.getSharedPreferences("widgets", Context.MODE_PRIVATE).edit().apply { ids.forEach { remove("playlist:$it"); remove("name:$it") } }.apply()
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in setOf(TOGGLE, NEXT, PREVIOUS, FAVORITES, PLAYLIST)) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            var future: ListenableFuture<MediaController>? = null
            try {
                withTimeout(8_000) {
                    future = MediaController.Builder(appContext, SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))).buildAsync()
                    val controller = await(requireNotNull(future))
                    when (intent.action) {
                        TOGGLE -> if (controller.playWhenReady && controller.playerError == null && controller.playbackState != Player.STATE_IDLE) controller.pause()
                            else { controller.prepare(); controller.play() }
                        NEXT -> controller.nextOrRecommend()
                        PREVIOUS -> if (controller.currentPosition > 3000) controller.seekTo(0) else controller.seekToPreviousMediaItem()
                        else -> {
                            val library = (context.applicationContext as RaagApplication).library.state.first()
                            val id = context.getSharedPreferences("widgets", Context.MODE_PRIVATE).getString("playlist:${intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)}", null)
                            val tracks = if (intent.action == FAVORITES) library.savedTracks.filter { it.id in library.favorites }
                                else library.playlists.find { it.id == id }?.trackIds.orEmpty().mapNotNull { trackId -> library.savedTracks.find { it.id == trackId } }
                            if (tracks.isNotEmpty()) { controller.setMediaItems(tracks.map { it.asMediaItem() }); controller.prepare(); controller.play() }
                        }
                    }
                    update(context, controller.currentMediaItem?.mediaMetadata?.title?.toString(), controller.playWhenReady,
                        controller.currentMediaItem?.mediaMetadata?.artist?.toString(), controller.currentMediaItem?.mediaMetadata?.artworkUri?.toString())
                }
            } catch (e: Exception) {
                android.util.Log.w("RaagWidget", "Widget action failed: ${e.javaClass.simpleName}")
                update(context, playing = false)
            }
            finally { future?.let { MediaController.releaseFuture(it) }; pending.finish() }
        }
    }
    companion object {
        const val TOGGLE = "com.raagmusic.app.widget.TOGGLE"
        const val NEXT = "com.raagmusic.app.widget.NEXT"
        const val PREVIOUS = "com.raagmusic.app.widget.PREVIOUS"
        const val FAVORITES = "com.raagmusic.app.widget.FAVORITES"
        const val PLAYLIST = "com.raagmusic.app.widget.PLAYLIST"
        private var cover: Bitmap? = null
        private var coverUrl = ""
        private var imageJob: Job? = null
        private var loadingUrl = ""
        fun update(context: Context, title: String? = null, playing: Boolean? = null, artist: String? = null, artwork: String? = null) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)
            if (title != null || playing != null || artist != null || artwork != null) prefs.edit().apply {
                title?.let { putString("title", it) }; playing?.let { putBoolean("playing", it) }
                artist?.let { putString("artist", it) }; artwork?.let { putString("artwork", it) }
            }.apply()
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, RaagWidget::class.java))
            if (ids.isEmpty()) return
            val url = prefs.getString("artwork", "").orEmpty()
            if (cover == null && url.isNotBlank() && prefs.getString("coverCacheUrl", "") == url) {
                cover = BitmapFactory.decodeFile(java.io.File(appContext.cacheDir, "widget-cover.png").path)
                coverUrl = url
            }
            ids.forEach { id ->
                val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                    SizeF(180f, 110f) to createViews(appContext, id, R.layout.raag_widget_compact),
                    SizeF(250f, 190f) to createViews(appContext, id, R.layout.raag_widget),
                    SizeF(300f, 260f) to createViews(appContext, id, R.layout.raag_widget_large),
                )) else {
                    val options = manager.getAppWidgetOptions(id)
                    createViews(appContext, id, layoutFor(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)))
                }
                manager.updateAppWidget(id, views)
            }
            if (url.isBlank()) { imageJob?.cancel(); loadingUrl = ""; return }
            if (coverUrl == url && cover != null || loadingUrl == url && imageJob?.isActive == true) return
            imageJob?.cancel(); loadingUrl = url
            imageJob = (appContext as RaagApplication).persistenceScope.launch {
                try {
                    suspend fun fetch(source: String) = appContext.imageLoader.execute(ImageRequest.Builder(appContext)
                        .data(if (source.startsWith("asset://")) "file:///android_asset/${source.removePrefix("asset://")}" else source)
                        .size(256).allowHardware(false).build()) as? SuccessResult
                    val result = fetch(ArtworkUrls.highResolution(url, 512)) ?: fetch(url) ?: return@launch
                    val bitmap = result.drawable.toBitmap(width = 256, height = 256)
                    cover = bitmap; coverUrl = url
                    withContext(Dispatchers.IO) {
                        java.io.File(appContext.cacheDir, "widget-cover.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        prefs.edit().putString("coverCacheUrl", url).apply()
                    }
                    update(appContext)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep functional controls when the artwork is unavailable. */ }
            }
        }

        internal fun layoutFor(width: Int, height: Int): Int = when {
            width >= 300 && height >= 260 -> R.layout.raag_widget_large
            width >= 250 && height >= 190 -> R.layout.raag_widget
            else -> R.layout.raag_widget_compact
        }

        internal fun createViews(context: Context, id: Int, layout: Int): RemoteViews {
            val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
            val title = prefs.getString("title", context.getString(R.string.widget_ready_title))
            val artist = prefs.getString("artist", context.getString(R.string.widget_ready_artist))
            val playing = prefs.getBoolean("playing", false)
            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            if (context.resources.configuration.fontScale > 1.3f) {
                views.setViewVisibility(R.id.widget_brand, View.GONE)
                views.setInt(R.id.widget_title, "setMaxLines", 1)
                if (layout == R.layout.raag_widget_compact || context.resources.configuration.fontScale > 1.5f)
                    views.setViewVisibility(R.id.widget_artist, View.GONE)
            }
            views.setImageViewResource(R.id.widget_toggle, if (playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
            views.setContentDescription(R.id.widget_toggle, context.getString(if (playing) R.string.widget_pause else R.string.widget_play))
            val bitmap = cover.takeIf { coverUrl == prefs.getString("artwork", "") }
            if (bitmap != null) views.setImageViewBitmap(R.id.widget_artwork, bitmap)
            views.setTextViewText(R.id.widget_playlist, prefs.getString("name:$id", context.getString(R.string.widget_choose_playlist)))
            views.setOnClickPendingIntent(R.id.widget_open, PendingIntent.getActivity(context, id,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            listOf(R.id.widget_previous to PREVIOUS, R.id.widget_toggle to TOGGLE, R.id.widget_next to NEXT,
                R.id.widget_favorites to FAVORITES, R.id.widget_playlist to PLAYLIST).forEach { (view, action) ->
                val target = if (action == PLAYLIST && prefs.getString("playlist:$id", null) == null)
                    PendingIntent.getActivity(context, id + 10000, Intent(context, WidgetConfigurationActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                else PendingIntent.getBroadcast(context, id, Intent(context, RaagWidget::class.java).setAction(action)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(view, target)
            }
            return views
        }
        private suspend fun <T> await(future: ListenableFuture<T>): T = suspendCancellableCoroutine { c ->
            c.invokeOnCancellation { future.cancel(true) }
            future.addListener({ try { if (c.isActive) c.resume(future.get()) } catch (e: Exception) { if (c.isActive) c.resumeWithException(e) } }, MoreExecutors.directExecutor())
        }
    }
}
