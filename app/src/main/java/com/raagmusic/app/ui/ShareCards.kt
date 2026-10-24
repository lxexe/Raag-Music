package com.raagmusic.app.ui

import android.content.Intent
import android.graphics.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.raagmusic.app.R
import kotlinx.coroutines.*
import java.io.File

@Composable
fun ShareCardButton(title: String, subtitle: String, detail: String, highlights: List<String> = emptyList()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current
    var busy by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    paint.shader = LinearGradient(0f, 0f, 1080f, 1350f, intArrayOf(Color.rgb(30, 20, 57), Color.rgb(21, 26, 49)), null, Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, 1080f, 1350f, paint); paint.shader = null
                    paint.typeface = context.resources.getFont(R.font.jakarta_bold)
                    paint.color = Color.rgb(201, 178, 255); paint.textSize = 42f; canvas.drawText(context.getString(R.string.app_name), 80f, 120f, paint)
                    paint.color = Color.WHITE; paint.textSize = 64f
                    fun wrapped(text: String, y: Float, size: Float, lines: Int = 2): Float {
                        paint.textSize = size
                        val safe = text.take(300)
                        val layout = android.text.StaticLayout.Builder.obtain(safe, 0, safe.length, android.text.TextPaint(paint), 920)
                            .setIncludePad(false).setLineSpacing(size * .15f, 1f).setMaxLines(lines)
                            .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                        canvas.save(); canvas.translate(80f, y - size); layout.draw(canvas); canvas.restore()
                        return y - size + layout.height + size * .5f
                    }
                    var y = wrapped(title, 280f, 64f, 4)
                    paint.color = Color.rgb(195, 190, 209); y = wrapped(subtitle, y + 35, 38f)
                    paint.color = Color.rgb(201, 178, 255); y = wrapped(detail, y + 60, 38f)
                    paint.color = Color.WHITE
                    highlights.take(5).forEachIndexed { i, name -> if (y < 1130) y = wrapped("${i + 1}. $name", y + 28, 38f, 1) }
                    paint.color = Color.rgb(195, 190, 209); paint.textSize = 28f; canvas.drawText("YOUR WORLD. IN SOUND.", 80f, 1270f, paint)
                    val dir = File(context.cacheDir, "share-cards").apply { mkdirs() }
                    val file = File(dir, "raag-card.png")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                }
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = android.content.ClipData.newRawUri(context.getString(R.string.share_card_label), uri) }, context.getString(R.string.share_card_chooser)))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback?.showSnackbar("Couldn't create the share card. Try again.") }
            finally { busy = false }
        }
    }) { Text(if (busy) "Creating card…" else "Share card") }
}
