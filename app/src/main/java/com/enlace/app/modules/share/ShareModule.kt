package com.enlace.app.modules.share

import android.content.ClipboardManager
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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

    private var waiting: TextView? = null

    override fun card(activity: MainActivity): View {
        val card = Ui.card(activity, "share", Strings.SHARE, Strings.SHARE_ABOUT)
        val line = Ui.text(activity, "", 14f, Ui.YELLOW).apply { visibility = View.GONE }
        card.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 10f) })
        waiting = line
        showWaiting(activity)
        card.addView(
            Ui.button(activity, Strings.SHARE_CLIPBOARD, false) { sendClipboard(activity) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 14f) },
        )
        return card
    }

    /** The PC is reachable (the screen just connected): deliver what was shared while it was not. */
    override fun refresh(activity: MainActivity) {
        showWaiting(activity)
        val app = activity.applicationContext
        if (Outbox.count(app) == 0) return
        thread(name = "enlace-outbox") {
            val sent = Sender.flush(app)
            activity.runOnUiThread {
                if (sent > 0) Toast.makeText(app, plural(sent, Strings.SHARE_FLUSHED_ONE, Strings.SHARE_FLUSHED_MANY), Toast.LENGTH_LONG).show()
                showWaiting(activity)
            }
        }
    }

    private fun showWaiting(activity: MainActivity) {
        val line = waiting ?: return
        val count = Outbox.count(activity)
        line.visibility = if (count == 0) View.GONE else View.VISIBLE
        line.text = plural(count, Strings.SHARE_WAITING_ONE, Strings.SHARE_WAITING_MANY)
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many.format(n)

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
            activity.runOnUiThread {
                Toast.makeText(app, result, Toast.LENGTH_SHORT).show()
                showWaiting(activity)
            }
        }
    }
}
