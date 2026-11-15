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

        // Away from home: the PC does not answer and two shared things wait.
        Prefs.save(context, link.pc.copy(name = "arch", host = "192.0.2.77", id = "not-here"))
        for (text in listOf("https://campus.example/tp3", "apunte")) {
            val file = File.createTempFile("item", null, context.cacheDir).apply { writeText(text) }
            Outbox.add(context, file, "text/plain", null)
        }
        save(open(70), "3-sin-compu-con-cola.png")
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

    private fun saveView(view: View, width: Int, height: Int, name: String) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
