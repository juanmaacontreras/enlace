package com.enlace.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.enlace.app.core.Device
import com.enlace.app.core.Link
import com.enlace.app.core.PairUri
import com.enlace.app.core.Prefs
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.enlace.app.modules.share.Outbox
import com.enlace.app.modules.actions.ActionsWidget
import org.json.JSONArray
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the real screens to PNG without a device, for design proposals and to
 * see a change before installing it. Run by tools/screens.sh, which starts
 * the real PC service so the linked screen shows real data; skipped otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
class ScreensTest {
    private val out = System.getProperty("enlace.screens") ?: ""
    private val uri = System.getProperty("enlace.interop") ?: ""

    @Test fun mainScreens() {
        assumeTrue(out.isNotEmpty() && uri.isNotEmpty())
        val context = RuntimeEnvironment.getApplication() as Context

        save(open(), "1-sin-vincular.png")

        val target = PairUri.parse(uri)!!
        val link = Link.connect(target.pc, Device(Prefs.deviceId(context), "Redmi Note 11"),
            listOf("actions", "inbox", "mic", "share"), pair = target.code)
        Prefs.save(context, link.pc.copy(name = "arch"))
        link.close()
        save(open(), "2-vinculado.png")

        // The home-screen widget, drawn from the list the linked screen just got from the PC.
        val widget = ActionsWidget.views(context, JSONArray(
            context.getSharedPreferences("enlace", Context.MODE_PRIVATE).getString("widget_actions", "[]")))
        val holder = android.widget.FrameLayout(context)
        holder.addView(widget.apply(context, holder))
        saveView(holder, 1080, 560, "4-widget.png")

        saveView(board(1080, 2340).window.decorView, 1080, 2340, "5-pizarra.png")

        // Away from home: the PC does not answer and two shared things wait.
        Prefs.save(context, link.pc.copy(name = "arch", host = "192.0.2.77", id = "not-here"))
        for (text in listOf("https://campus.example/tp3", "apunte")) {
            val file = File.createTempFile("item", null, context.cacheDir).apply { writeText(text) }
            Outbox.add(context, file, "text/plain", null)
        }
        save(open(70), "3-sin-compu-con-cola.png")
    }

    /** The board the way the tablet shows it, lying down. */
    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun boardOnTheTablet() {
        assumeTrue(out.isNotEmpty())
        saveView(board(2304, 1440).window.decorView, 2304, 1440, "6-pizarra-tablet.png")
    }

    /** The board with something written on it, the way a stylus would: pressure rising and falling. */
    private fun board(width: Int, height: Int): Activity {
        val activity = Robolectric.buildActivity(com.enlace.app.modules.board.BoardActivity::class.java).setup().get()
        layout(activity.window.decorView, width, height)  // so the board has a size
        val view = (activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            as android.view.ViewGroup).getChildAt(1) as com.enlace.app.modules.board.BoardView
        val k = minOf(view.width / 1080f, view.height / 2100f) * if (width > height) 2.2f else 1f
        val ox = (view.width - 1080f * k) / 2
        fun curve(color: Int, x0: Float, y0: Float, f: (Float) -> Pair<Float, Float>) {
            view.color = color
            for (i in 0..60) {
                val t = i / 60f
                val (dx, dy) = f(t)
                val pressure = 0.15f + 0.8f * kotlin.math.sin(Math.PI * t).toFloat()
                val x = ox + (x0 + dx) * k; val y = (y0 + dy - if (width > height) 350f else 0f) * k * if (width > height) 0.42f else 1f
                if (i == 0) view.down(x, y, pressure, rub = false) else view.move(x, y, pressure)
            }
            view.up()
        }
        // A sine, its axis, and a resistor zigzag.
        curve(Ui.DIM, 120f, 700f) { t -> Pair(840f * t, 0f) }
        curve(Ui.ACCENT, 120f, 700f) { t -> Pair(840f * t, -260f * kotlin.math.sin(2 * Math.PI * 2 * t).toFloat()) }
        curve(Ui.BRIGHT, 200f, 1300f) { t ->
            val n = (t * 8).toInt().coerceAtMost(7); val r = t * 8 - n
            Pair(680f * t, if (n == 0 || n == 7) 0f else (if (n % 2 == 1) 1 else -1) * 90f * (1 - 2 * kotlin.math.abs(r - 0.5f)))
        }
        curve(Ui.YELLOW, 380f, 1600f) { t -> Pair(300f * kotlin.math.cos(2 * Math.PI * t).toFloat() * 0.5f + 150f, 120f * kotlin.math.sin(2 * Math.PI * t).toFloat()) }
        return activity
    }

    /** Opens the main screen and waits for its network work to reach the views. */
    private fun open(ticks: Int = 30): Activity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(ticks) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return activity
    }

    private fun save(activity: Activity, name: String) = saveView(activity.window.decorView, 1080, 2340, name)

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun saveView(view: View, width: Int, height: Int, name: String) {
        layout(view, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
