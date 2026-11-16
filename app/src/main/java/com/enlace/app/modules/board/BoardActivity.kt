package com.enlace.app.modules.board

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.enlace.app.Strings
import com.enlace.app.Ui
import com.enlace.app.modules.share.Item
import com.enlace.app.modules.share.Sender
import java.io.File
import kotlin.concurrent.thread

/**
 * The board: tools on top, the page below. "A la compu" sends the drawing as
 * a PNG through share (the PC's clipboard, or the queue if it is not there).
 */
class BoardActivity : Activity() {
    private lateinit var board: BoardView
    private lateinit var pens: LinearLayout
    private lateinit var edits: LinearLayout
    private var sending = false

    private val colors = listOf(Ui.BRIGHT, Ui.ACCENT, Ui.RED, Ui.GREEN, Ui.YELLOW)
    private val widths = listOf(1.5f, 2.5f, 4.5f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        board = BoardView(this)
        board.setBackgroundColor(Ui.BG)
        board.onChange = { renderTools() }
        build()
    }

    /** Turning the device keeps the drawing (see the manifest) and only lays the tools out again. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
    }

    /** One row of tools on a tablet; two on a phone, where they do not fit side by side. */
    private fun build() {
        (board.parent as? ViewGroup)?.removeView(board)
        pens = Ui.row(this)
        edits = Ui.row(this)
        val send = Ui.button(this, Strings.BOARD_SEND, true) { send() }
        val pad = Ui.dp(this, 8f)
        val wide = resources.configuration.screenWidthDp >= 600
        val bar = if (wide) Ui.row(this) else Ui.column(this)
        bar.setBackgroundColor(Ui.SURFACE)
        bar.setPadding(pad, pad, pad, pad)
        if (wide) {
            bar.addView(pens)
            bar.addView(edits, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = pad })
            bar.addView(send)
        } else {
            bar.addView(pens)
            val second = Ui.row(this)
            second.addView(edits, LinearLayout.LayoutParams(0, -2, 1f))
            send.setPadding(Ui.dp(this, 14f), send.paddingTop, Ui.dp(this, 14f), send.paddingBottom)
            send.maxLines = 1
            second.addView(send)
            bar.addView(second, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
        }

        val root = Ui.column(this)
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderTools()
    }

    private fun renderTools() {
        pens.removeAllViews()
        edits.removeAllViews()
        for (color in colors) {
            pens.addView(Ui.swatch(this, color, 22f, !board.erasing && board.color == color) {
                board.color = color
                board.erasing = false
                renderTools()
            })
        }
        pens.addView(Ui.gap(this, 1f), LinearLayout.LayoutParams(Ui.dp(this, 10f), 1))
        for (width in widths) {
            pens.addView(Ui.swatch(this, Ui.DIM, 4f + width * 3f, board.widthDp == width) {
                board.widthDp = width
                renderTools()
            })
        }
        edits.addView(small(Strings.BOARD_ERASER, board.erasing, true) {
            board.erasing = !board.erasing
            renderTools()
        })
        edits.addView(small(Strings.BOARD_UNDO, false, board.canUndo) { board.undo() })
        edits.addView(small(Strings.BOARD_CLEAR, false, !board.isEmpty) { board.clear() })
    }

    private fun small(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit): TextView =
        Ui.button(this, label, on) { if (enabled) onClick() }.apply {
            alpha = if (enabled) 1f else 0.4f
            val h = Ui.dp(this@BoardActivity, 10f)
            val v = Ui.dp(this@BoardActivity, 9f)
            setPadding(h, v, h, v)
            textSize = 14f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(this@BoardActivity, 8f) }
        }

    private fun send() {
        if (sending) return
        val image = board.export()
        if (image == null) {
            Toast.makeText(this, Strings.BOARD_EMPTY, Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        Toast.makeText(this, Strings.SHARE_SENDING, Toast.LENGTH_SHORT).show()
        val app = applicationContext
        thread(name = "enlace-board") {
            val folder = File(cacheDir, "board").apply { mkdirs() }
            val file = File(folder, "pizarra-" + System.currentTimeMillis() + ".png")
            val result = try {
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Sender.send(app, listOf(Item(uri = Uri.fromFile(file), mime = "image/png")))
            } catch (e: Exception) {
                Strings.SHARE_FAILED
            } finally {
                file.delete()
            }
            runOnUiThread {
                sending = false
                Toast.makeText(app, result, Toast.LENGTH_LONG).show()
            }
        }
    }
}
