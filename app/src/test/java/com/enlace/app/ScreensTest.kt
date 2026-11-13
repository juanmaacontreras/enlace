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
    }

    /** Opens the main screen and waits for its network work to reach the views. */
    private fun open(): Activity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(30) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return activity
    }

    private fun save(activity: Activity, name: String) {
        val view: View = activity.window.decorView
        val width = 1080
        val height = 2340
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
