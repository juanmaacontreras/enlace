package com.enlace.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser

/**
 * The look, in one place: Nord, the palette the desktop and the other apps
 * use. Screens are built in code from these pieces; use them instead of
 * styling a view by hand.
 */
object Ui {
    const val BG = 0xFF2E3440.toInt()
    const val SURFACE = 0xFF3B4252.toInt()
    const val RAISED = 0xFF434C5E.toInt()
    const val MUTED = 0xFF4C566A.toInt()
    const val DIM = 0xFF8792A8.toInt()
    const val TEXT = 0xFFD8DEE9.toInt()
    const val BRIGHT = 0xFFECEFF4.toInt()
    const val ACCENT = 0xFF88C0D0.toInt()
    const val RED = 0xFFBF616A.toInt()
    const val GREEN = 0xFFA3BE8C.toInt()
    const val YELLOW = 0xFFEBCB8B.toInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Float, context: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    fun text(context: Context, value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** A module's card: icon and title on top, whatever the module adds below. */
    fun card(context: Context, icon: String, title: String, about: String? = null): LinearLayout {
        val card = column(context)
        card.background = rounded(SURFACE, 20f, context)
        val pad = dp(context, 18f)
        card.setPadding(pad, pad, pad, pad)
        val head = row(context)
        head.addView(View(context).apply { background = Icons.drawable(icon, ACCENT) },
            LinearLayout.LayoutParams(dp(context, 22f), dp(context, 22f)))
        head.addView(text(context, title, 17f, BRIGHT, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(context, 10f) })
        card.addView(head)
        if (about != null) {
            card.addView(text(context, about, 14f, DIM),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 8f) })
        }
        return card
    }

    /** A wide pill. [filled] is the "this is on / do this" look; otherwise quiet. */
    fun button(context: Context, label: String, filled: Boolean, onClick: () -> Unit): TextView =
        text(context, label, 15f, if (filled) BG else BRIGHT, bold = true).apply {
            gravity = Gravity.CENTER
            val v = dp(context, 13f)
            setPadding(dp(context, 18f), v, dp(context, 18f), v)
            style(this, filled)
            setOnClickListener { onClick() }
        }

    fun style(button: TextView, filled: Boolean) {
        val context = button.context
        button.setTextColor(if (filled) BG else BRIGHT)
        button.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            rounded(if (filled) ACCENT else RAISED, 14f, context), null,
        )
    }

    fun gap(context: Context, heightDp: Float) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
    }
}

/**
 * Icons drawn from 24x24 path data (Material Icons, Apache 2.0), so an icon is
 * one line here and the PC can name one in actions.toml without an app asset.
 */
object Icons {
    private val paths = mapOf(
        "mic" to "M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z",
        "share" to "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z",
        "bolt" to "M7 2v11h3v9l7-12h-4l4-8z",
        "lock" to "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z",
        "play" to "M8 5v14l11-7z",
        "next" to "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z",
        "previous" to "M6 6h2v12H6zm3.5 6l8.5 6V6z",
        "volume-up" to "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
        "volume-down" to "M18.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 9v6h4l5 5V4L9 9H5z",
        "mute" to "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z",
        "power" to "M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z",
        "screen" to "M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H3V4h18v12z",
        "moon" to "M9 2c-1.05 0-2.05.16-3 .46 4.06 1.27 7 5.06 7 9.54 0 4.48-2.94 8.27-7 9.54.95.3 1.95.46 3 .46 5.52 0 10-4.48 10-10S14.52 2 9 2z",
        "dot" to "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z",
    )

    fun drawable(name: String, color: Int): Drawable =
        PathDrawable(PathParser.createPathFromPathData(paths[name] ?: paths.getValue("dot")), color)

    private class PathDrawable(private val source: Path, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val scaled = Path()
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val box = bounds
            matrix.setScale(box.width() / 24f, box.height() / 24f)
            matrix.postTranslate(box.left.toFloat(), box.top.toFloat())
            source.transform(matrix, scaled)
            canvas.drawPath(scaled, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
