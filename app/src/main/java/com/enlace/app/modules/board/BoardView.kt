package com.enlace.app.modules.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.enlace.app.Ui
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The drawing surface. Ink follows the stylus at its full sample rate and
 * gets thicker with pressure; a finger draws at a fixed width. Once a stylus
 * has been seen (touching or hovering), fingers stop drawing: that is the
 * palm resting on the tablet. The stylus button, its eraser end, or
 * [erasing] rub out whole strokes.
 *
 * Everything drawn is kept as strokes, not pixels, so undo and the cropped
 * export are exact. The bitmap is only a cache of them.
 */
class BoardView(context: Context) : View(context) {
    class Point(val x: Float, val y: Float, val pressure: Float)

    class Stroke(val color: Int, val width: Float) {
        val points = ArrayList<Point>()
    }

    var color = Ui.BRIGHT
    var widthDp = 2.5f
    var erasing = false

    /** Called after anything changes what is on the board. */
    var onChange: (() -> Unit)? = null

    var strokes: List<Stroke> = emptyList()
        private set
    private val history = ArrayList<List<Stroke>>()
    private var current: Stroke? = null
    private var rubbing = false
    private var rubbed = false
    private var activeId = -1
    private var activeIsPen = false
    private var stylusSeen = false

    private var bitmap: Bitmap? = null
    private var ink: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    val isEmpty: Boolean get() = strokes.isEmpty()
    val canUndo: Boolean get() = history.isNotEmpty()

    // --- input ----------------------------------------------------------------

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (isPen(event, 0)) stylusSeen = true
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pen = isPen(event, index)
                if (pen) stylusSeen = true
                // The pen arrives while a finger (the palm) is drawing: drop that stroke.
                if (pen && activeId != -1 && !activeIsPen) cancel()
                if (activeId != -1 || (!pen && stylusSeen)) return true
                activeId = event.getPointerId(index)
                activeIsPen = pen
                parent?.requestDisallowInterceptTouchEvent(true)
                // Deliver every sample as it happens instead of once per frame.
                requestUnbufferedDispatch(event)
                val rub = erasing || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
                    (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                down(event.getX(index), event.getY(index), pressure(event.getPressure(index), pen), rub)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activeId)
                if (i < 0) return true
                for (h in 0 until event.historySize) {
                    move(event.getHistoricalX(i, h), event.getHistoricalY(i, h),
                        pressure(event.getHistoricalPressure(i, h), activeIsPen))
                }
                move(event.getX(i), event.getY(i), pressure(event.getPressure(i), activeIsPen))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(index) == activeId) up()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun isPen(event: MotionEvent, index: Int): Boolean {
        val tool = event.getToolType(index)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    /** A finger reports its contact area as pressure, which is noise: fixed width for it. */
    private fun pressure(raw: Float, pen: Boolean) = if (pen) raw.coerceIn(0f, 1f) else 0.5f

    // --- strokes (also the entry points the tests use) ----------------------------

    fun down(x: Float, y: Float, pressure: Float, rub: Boolean = erasing) {
        rubbing = rub
        rubbed = false
        if (rub) {
            rubAt(x, y)
            return
        }
        val stroke = Stroke(color, widthDp * resources.displayMetrics.density)
        stroke.points.add(Point(x, y, pressure))
        current = stroke
        ink?.let { draw(it, stroke, 0, finish = false) }
        invalidate()
    }

    fun move(x: Float, y: Float, pressure: Float) {
        if (rubbing) {
            rubAt(x, y)
            return
        }
        val stroke = current ?: return
        val last = stroke.points.last()
        if (last.x == x && last.y == y) return
        stroke.points.add(Point(x, y, pressure))
        ink?.let { draw(it, stroke, stroke.points.size - 1, finish = false) }
        invalidate()
    }

    fun up() {
        activeId = -1
        val stroke = current
        current = null
        if (rubbing || stroke == null) {
            rubbing = false
            return
        }
        ink?.let { tail(it, stroke) }
        history.add(strokes)
        strokes = strokes + stroke
        invalidate()
        onChange?.invoke()
    }

    private fun cancel() {
        activeId = -1
        rubbing = false
        if (current != null) {
            current = null
            redraw()
        }
    }

    private fun rubAt(x: Float, y: Float) {
        val reach = 12f * resources.displayMetrics.density
        val kept = strokes.filterNot { touches(it, x, y, reach + it.width) }
        if (kept.size == strokes.size) return
        // One undo step per rubbing gesture, however many strokes it took.
        if (!rubbed) history.add(strokes)
        rubbed = true
        strokes = kept
        redraw()
        onChange?.invoke()
    }

    private fun touches(stroke: Stroke, x: Float, y: Float, reach: Float): Boolean {
        val points = stroke.points
        if (points.size == 1) return hypot(points[0].x - x, points[0].y - y) <= reach
        for (i in 1 until points.size) {
            if (distance(x, y, points[i - 1], points[i]) <= reach) return true
        }
        return false
    }

    private fun distance(x: Float, y: Float, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / length).coerceIn(0f, 1f)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    fun undo() {
        if (history.isEmpty()) return
        strokes = history.removeAt(history.size - 1)
        redraw()
        onChange?.invoke()
    }

    fun clear() {
        if (strokes.isEmpty()) return
        history.add(strokes)
        strokes = emptyList()
        redraw()
        onChange?.invoke()
    }

    // --- drawing --------------------------------------------------------------

    /**
     * Draws the piece of [stroke] that point [index] adds: a curve between the
     * midpoints around the previous point, so corners come out round, as wide
     * as the pressure there. [finish] also draws the last half segment.
     */
    private fun draw(canvas: Canvas, stroke: Stroke, index: Int, finish: Boolean) {
        val points = stroke.points
        paint.color = stroke.color
        if (index == 0) {
            if (points.size == 1) {
                paint.strokeWidth = width(stroke, points[0].pressure)
                canvas.drawPoint(points[0].x, points[0].y, paint)
            }
            return
        }
        val a = points[index - 1]
        val b = points[index]
        path.rewind()
        if (index == 1) {
            path.moveTo(a.x, a.y)
        } else {
            val before = points[index - 2]
            path.moveTo((before.x + a.x) / 2, (before.y + a.y) / 2)
        }
        path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
        paint.strokeWidth = width(stroke, (a.pressure + b.pressure) / 2)
        canvas.drawPath(path, paint)
        if (finish) tail(canvas, stroke)
    }

    private fun tail(canvas: Canvas, stroke: Stroke) {
        val points = stroke.points
        if (points.size < 2) return
        val a = points[points.size - 2]
        val b = points[points.size - 1]
        paint.color = stroke.color
        paint.strokeWidth = width(stroke, b.pressure)
        canvas.drawLine((a.x + b.x) / 2, (a.y + b.y) / 2, b.x, b.y, paint)
    }

    private fun width(stroke: Stroke, pressure: Float) = stroke.width * (0.4f + 1.2f * pressure)

    private fun drawAll(canvas: Canvas, stroke: Stroke) {
        for (i in stroke.points.indices) draw(canvas, stroke, i, finish = false)
        tail(canvas, stroke)
    }

    private fun redraw() {
        val canvas = ink ?: return
        bitmap?.eraseColor(0)
        for (stroke in strokes) drawAll(canvas, stroke)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap = fresh
        ink = Canvas(fresh)
        redraw()
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /**
     * What is on the board as an image, cropped to the ink with a margin, on
     * the board's own background. Null when nothing is drawn.
     */
    fun export(): Bitmap? {
        if (strokes.isEmpty()) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (stroke in strokes) for (p in stroke.points) {
            left = min(left, p.x); top = min(top, p.y)
            right = max(right, p.x); bottom = max(bottom, p.y)
        }
        val margin = 24f * resources.displayMetrics.density
        val w = (right - left + 2 * margin).toInt().coerceAtLeast(1)
        val h = (bottom - top + 2 * margin).toInt().coerceAtLeast(1)
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Ui.BG)
        canvas.translate(margin - left, margin - top)
        for (stroke in strokes) drawAll(canvas, stroke)
        return image
    }
}
