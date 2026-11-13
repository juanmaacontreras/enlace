package com.enlace.app.modules.mic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.enlace.app.MainActivity
import com.enlace.app.Strings
import com.enlace.app.Ui
import com.enlace.app.core.Module

/**
 * mic: this device's microphone becomes a capture device on the PC.
 * The work is in [MicService]; this is its card on the main screen.
 */
object MicModule : Module {
    override val name = "mic"
    const val PERMISSION_REQUEST = 41

    private var button: TextView? = null

    override fun card(activity: MainActivity): View {
        val card = Ui.card(activity, "mic", Strings.MIC, Strings.MIC_ABOUT)
        val toggle = Ui.button(activity, "", false) { toggle(activity) }
        card.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 14f) })
        button = toggle
        show()
        return card
    }

    override fun refresh(activity: MainActivity) = show()

    /** Called on the main thread whenever [MicService.state] changes. */
    fun show() {
        val view = button ?: return
        when (MicService.state) {
            MicService.State.OFF -> { view.text = Strings.MIC_TURN_ON; Ui.style(view, false) }
            MicService.State.CONNECTING -> { view.text = Strings.MIC_CONNECTING; Ui.style(view, false) }
            MicService.State.ON -> { view.text = Strings.MIC_ON + "  ·  " + Strings.MIC_TURN_OFF; Ui.style(view, true) }
        }
    }

    private fun toggle(activity: MainActivity) {
        if (MicService.state != MicService.State.OFF) {
            MicService.stop(activity)
            return
        }
        val needed = missingPermissions(activity)
        if (needed.isEmpty()) MicService.start(activity)
        else activity.requestPermissions(needed, PERMISSION_REQUEST)
    }

    fun missingPermissions(context: android.content.Context): Array<String> {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        return wanted.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /** The answer to the permission dialog. Notifications are optional; the microphone is not. */
    fun onPermissions(activity: android.app.Activity) {
        val granted = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) MicService.start(activity)
        else Toast.makeText(activity, Strings.MIC_NO_PERMISSION, Toast.LENGTH_LONG).show()
    }
}
