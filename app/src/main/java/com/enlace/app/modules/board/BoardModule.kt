package com.enlace.app.modules.board

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import com.enlace.app.MainActivity
import com.enlace.app.Strings
import com.enlace.app.Ui
import com.enlace.app.core.Module
import com.enlace.app.modules.share.ShareModule

/**
 * board: a page to draw or write on with the stylus (or a finger) that goes
 * to the PC's clipboard as an image. It has no PC side: the image travels
 * through share, like anything else sent from this device. It does not keep
 * pages: the drawing lives while the board is open.
 */
object BoardModule : Module {
    override val name = "board"
    override val via get() = ShareModule.name

    override fun card(activity: MainActivity): View {
        val card = Ui.card(activity, "pen", Strings.BOARD, Strings.BOARD_ABOUT)
        card.addView(
            Ui.button(activity, Strings.BOARD_OPEN, false) {
                activity.startActivity(Intent(activity, BoardActivity::class.java))
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 14f) },
        )
        return card
    }
}
