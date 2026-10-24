package com.raagmusic.app

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.widget.RaagWidget
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class WidgetLayoutTest {
    @Test fun widgetLayoutsInflateAndFitResizeBoundsWithWorkingControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            listOf(180 to 110, 240 to 140, 250 to 190, 300 to 260, 430 to 400).forEach { (width, height) ->
                val layout = RaagWidget.layoutFor(width, height)
                val host = FrameLayout(context)
                val view = RaagWidget.createViews(context, 998, layout).apply(context, host)
                val density = context.resources.displayMetrics.density
                view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                listOf(R.id.widget_previous, R.id.widget_toggle, R.id.widget_next).forEach { id ->
                    val button = view.findViewById<ImageButton>(id)
                    assertTrue("Missing action at $width x $height", button.hasOnClickListeners())
                    val rect = android.graphics.Rect(); button.getDrawingRect(rect)
                    (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(button, rect)
                    assertTrue("Control clipped at $width x $height: $rect", rect.left >= 0 && rect.right <= view.width && rect.top >= 0 && rect.bottom <= view.height)
                    assertTrue(button.width >= (48 * density).toInt())
                }
                assertTrue(view.findViewById<TextView>(R.id.widget_title).height > 0)
                assertEquals(if (width >= 250 && height >= 190) View.VISIBLE else View.GONE,
                    view.findViewById<View>(R.id.widget_playlist).parent.let { it as View }.visibility)
            }
        }
    }
}
