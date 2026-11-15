package com.enlace.app.modules.actions

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.enlace.app.Icons
import com.enlace.app.R
import com.enlace.app.Strings
import com.enlace.app.Ui
import com.enlace.app.core.Link
import com.enlace.app.core.Messages
import com.enlace.app.core.Modules
import org.json.JSONArray
import kotlin.concurrent.thread

/**
 * The action buttons on the home screen, so the lamp or the volume are one
 * touch away without opening the app. It draws from the list the app last got
 * from the PC (the widget never asks by itself), in rows titled by each
 * button's `group` in actions.toml; buttons without one share a last row.
 *
 * A touch opens a link, runs that one action and closes it. Nothing stays
 * running: no service, no polling, no battery.
 */
class ActionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context, stored(context))
        for (id in ids) manager.updateAppWidget(id, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return super.onReceive(context, intent)
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "enlace-widget") {
            val ok = try {
                Link.open(app, Modules.names).use { it.request(Messages.actionsRun(id)).header.optBoolean("ok") }
            } catch (e: Exception) {
                null
            }
            if (ok != true) {
                val text = if (ok == null) Strings.WIDGET_UNREACHABLE else Strings.ACTION_FAILED
                Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
            }
            pending.finish()
        }
    }

    class Button(val id: String, val label: String, val icon: String)
    class Group(val title: String, val buttons: List<Button>)

    companion object {
        const val ACTION_RUN = "com.enlace.app.widget.RUN"
        const val EXTRA_ID = "id"
        const val GROUPS = 3
        const val SLOTS = 6

        /** The app got a fresh list from the PC: keep it and redraw every widget. */
        fun remember(context: Context, actions: JSONArray) {
            val prefs = context.getSharedPreferences("enlace", Context.MODE_PRIVATE)
            val text = actions.toString()
            if (prefs.getString("widget_actions", null) == text) return
            prefs.edit().putString("widget_actions", text).apply()
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ActionsWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, actions))
        }

        private fun stored(context: Context): JSONArray = runCatching {
            JSONArray(context.getSharedPreferences("enlace", Context.MODE_PRIVATE).getString("widget_actions", "[]"))
        }.getOrDefault(JSONArray())

        /**
         * Rows in the order groups first appear; ungrouped buttons last. What does
         * not fit ([GROUPS] rows of [SLOTS]) is left out: the app shows everything.
         */
        fun groups(actions: JSONArray): List<Group> {
            val rows = LinkedHashMap<String, ArrayList<Button>>()
            val loose = ArrayList<Button>()
            for (i in 0 until actions.length()) {
                val action = actions.optJSONObject(i) ?: continue
                val id = action.optString("id")
                if (id.isEmpty()) continue
                val button = Button(id, action.optString("label", id), action.optString("icon"))
                val group = action.optString("group")
                if (group.isEmpty()) loose.add(button) else rows.getOrPut(group) { ArrayList() }.add(button)
            }
            val all = rows.map { Group(it.key, it.value.take(SLOTS)) }.toMutableList()
            if (loose.isNotEmpty()) all.add(Group(if (rows.isEmpty()) "" else Strings.WIDGET_OTHER, loose.take(SLOTS)))
            return all.take(GROUPS)
        }

        fun views(context: Context, actions: JSONArray): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget)
            val groups = groups(actions)
            views.setViewVisibility(R.id.widget_empty, if (groups.isEmpty()) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_empty, Strings.WIDGET_EMPTY)
            val size = Ui.dp(context, 24f)
            for (g in 0 until GROUPS) {
                val group = groups.getOrNull(g)
                val title = id(context, "g${g}_title")
                views.setViewVisibility(title, if (group == null || group.title.isEmpty()) View.GONE else View.VISIBLE)
                views.setViewVisibility(id(context, "g${g}_row"), if (group == null) View.GONE else View.VISIBLE)
                if (group == null) continue
                views.setTextViewText(title, group.title)
                for (s in 0 until SLOTS) {
                    val slot = id(context, "g${g}_s$s")
                    val button = group.buttons.getOrNull(s)
                    views.setViewVisibility(slot, if (button == null) View.GONE else View.VISIBLE)
                    if (button == null) continue
                    views.setImageViewBitmap(id(context, "g${g}_s${s}_icon"), bitmap(button.icon, size))
                    views.setTextViewText(id(context, "g${g}_s${s}_label"), button.label)
                    val run = Intent(context, ActionsWidget::class.java).setAction(ACTION_RUN).putExtra(EXTRA_ID, button.id)
                    views.setOnClickPendingIntent(slot, PendingIntent.getBroadcast(
                        context, button.id.hashCode(), run,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            return views
        }

        @Suppress("DiscouragedApi")  // the layout's ids follow a pattern (g0_s0…): looked up by name
        private fun id(context: Context, name: String): Int =
            context.resources.getIdentifier(name, "id", context.packageName)

        private fun bitmap(icon: String, size: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Icons.drawable(icon, Ui.BRIGHT).apply { setBounds(0, 0, size, size) }.draw(Canvas(bitmap))
            return bitmap
        }
    }
}
