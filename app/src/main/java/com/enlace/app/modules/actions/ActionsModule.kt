package com.enlace.app.modules.actions

import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import com.enlace.app.MainActivity
import com.enlace.app.Icons
import com.enlace.app.Strings
import com.enlace.app.Ui
import com.enlace.app.core.Messages
import com.enlace.app.core.Module
import org.json.JSONArray

/**
 * actions: buttons that run a command on the PC. The list comes from the PC
 * (~/.config/enlace/actions.toml) every time the screen opens, so a new
 * button never needs a new version of this app.
 */
object ActionsModule : Module {
    override val name = "actions"
    private const val COLUMNS = 3

    private var grid: GridLayout? = null
    private var empty: View? = null

    override fun card(activity: MainActivity): View {
        val card = Ui.card(activity, "bolt", Strings.ACTIONS)
        val hint = Ui.text(activity, Strings.ACTIONS_EMPTY, 14f, Ui.DIM).apply { visibility = View.GONE }
        card.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 8f) })
        val buttons = GridLayout(activity).apply { columnCount = COLUMNS }
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 6f) })
        grid = buttons
        empty = hint
        return card
    }

    override fun refresh(activity: MainActivity) {
        activity.ask(Messages.actionsList()) { reply ->
            val list = reply?.header?.optJSONArray("actions") ?: JSONArray()
            fill(activity, list)
        }
    }

    private fun fill(activity: MainActivity, list: JSONArray) {
        val buttons = grid ?: return
        buttons.removeAllViews()
        empty?.visibility = if (list.length() == 0) View.VISIBLE else View.GONE
        for (i in 0 until list.length()) {
            val action = list.optJSONObject(i) ?: continue
            val id = action.optString("id")
            val tile = Ui.column(activity).apply {
                gravity = Gravity.CENTER
                val pad = Ui.dp(activity, 12f)
                setPadding(pad / 2, pad, pad / 2, pad)
                background = RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), Ui.rounded(Ui.RAISED, 14f, activity), null)
            }
            tile.addView(
                View(activity).apply { background = Icons.drawable(action.optString("icon"), Ui.BRIGHT) },
                LinearLayout.LayoutParams(Ui.dp(activity, 26f), Ui.dp(activity, 26f)))
            tile.addView(
                Ui.text(activity, action.optString("label", id), 12.5f, Ui.TEXT).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 6f) })
            tile.setOnClickListener {
                activity.ask(Messages.actionsRun(id)) { reply ->
                    if (reply?.header?.optBoolean("ok") != true) {
                        Toast.makeText(activity, Strings.ACTION_FAILED, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val margin = Ui.dp(activity, 5f)
            buttons.addView(tile, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                setMargins(margin, margin, margin, margin)
            })
        }
    }
}
