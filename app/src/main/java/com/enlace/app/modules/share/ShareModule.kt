package com.enlace.app.modules.share

import android.content.ClipboardManager
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.enlace.app.MainActivity
import com.enlace.app.Strings
import com.enlace.app.Ui
import com.enlace.app.core.Module
import kotlin.concurrent.thread

/**
 * share: something on this device lands in the PC's clipboard.
 * Mostly used from outside the app ([ShareActivity]); the card explains that
 * and offers the one thing only an open app can do: send what was just copied
 * (Android does not let a background app read the clipboard).
 */
object ShareModule : Module {
    override val name = "share"

    override fun card(activity: MainActivity): View {
        val card = Ui.card(activity, "share", Strings.SHARE, Strings.SHARE_ABOUT)
        card.addView(
            Ui.button(activity, Strings.SHARE_CLIPBOARD, false) { sendClipboard(activity) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 14f) },
        )
        return card
    }

    private fun sendClipboard(activity: MainActivity) {
        val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
        val entry = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val item = when {
            entry == null -> null
            entry.uri != null -> Item(uri = entry.uri, mime = clip.description.getMimeType(0))
            else -> entry.coerceToText(activity)?.toString()?.takeIf { it.isNotEmpty() }?.let { Item(text = it) }
        }
        if (item == null) {
            Toast.makeText(activity, Strings.SHARE_EMPTY, Toast.LENGTH_SHORT).show()
            return
        }
        val app = activity.applicationContext
        thread(name = "enlace-share") {
            val result = Sender.send(app, listOf(item))
            activity.runOnUiThread { Toast.makeText(app, result, Toast.LENGTH_SHORT).show() }
        }
    }
}
