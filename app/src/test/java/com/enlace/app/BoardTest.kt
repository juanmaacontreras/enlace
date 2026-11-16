package com.enlace.app

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import com.enlace.app.core.Modules
import com.enlace.app.modules.board.BoardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The board's strokes: drawing, rubbing out, undo, palm rejection and the cropped export. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-mdpi")
class BoardTest {
    private fun board(): BoardView {
        val view = BoardView(RuntimeEnvironment.getApplication() as Context)
        view.layout(0, 0, 800, 1200)
        return view
    }

    private fun line(view: BoardView, x0: Float, y0: Float, x1: Float, y1: Float, pressure: Float = 0.5f) {
        view.down(x0, y0, pressure, rub = false)
        for (i in 1..10) view.move(x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10, pressure)
        view.up()
    }

    private fun touch(view: BoardView, action: Int, x: Float, y: Float, tool: Int, buttons: Int = 0) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.7f })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, 0, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun drag(view: BoardView, tool: Int, x0: Float, x1: Float, y: Float, buttons: Int = 0) {
        touch(view, MotionEvent.ACTION_DOWN, x0, y, tool, buttons)
        touch(view, MotionEvent.ACTION_MOVE, (x0 + x1) / 2, y, tool, buttons)
        touch(view, MotionEvent.ACTION_MOVE, x1, y, tool, buttons)
        touch(view, MotionEvent.ACTION_UP, x1, y, tool, buttons)
    }

    @Test fun aStrokeLeavesInkWhereItPassedAndNowhereElse() {
        val view = board()
        line(view, 100f, 100f, 300f, 100f)
        val image = view.export()!!
        // Cropped to the ink plus a 24dp margin: 200 wide, a hair tall.
        assertEquals(248, image.width)
        assertEquals(48, image.height)
        assertEquals(Ui.BRIGHT, image.getPixel(124, 24))
        assertEquals(Ui.BG, image.getPixel(124, 4))
    }

    @Test fun pressureMakesTheLineThicker() {
        fun inkRows(pressure: Float): Int {
            val view = board()
            line(view, 100f, 100f, 300f, 100f, pressure)
            val image = view.export()!!
            return (0 until image.height).count { image.getPixel(124, it) != Ui.BG }
        }
        assertTrue(inkRows(1f) > inkRows(0.1f))
    }

    @Test fun undoTakesBackOneStepAtATimeIncludingClearAndRubbing() {
        val view = board()
        assertNull(view.export())
        line(view, 100f, 100f, 300f, 100f)
        line(view, 100f, 400f, 300f, 400f)
        assertEquals(2, view.strokes.size)

        // Rubbing across the first line removes it whole; the other stays.
        view.down(200f, 60f, 0.5f, rub = true)
        view.move(200f, 100f, 0.5f)
        view.move(200f, 140f, 0.5f)
        view.up()
        assertEquals(1, view.strokes.size)
        view.undo()
        assertEquals(2, view.strokes.size)

        view.clear()
        assertTrue(view.isEmpty)
        view.undo()
        assertEquals(2, view.strokes.size)
        view.undo()
        view.undo()
        assertTrue(view.isEmpty)
        assertFalse(view.canUndo)
    }

    @Test fun aFingerDrawsUntilAStylusShowsUpThenItIsThePalm() {
        val view = board()
        drag(view, MotionEvent.TOOL_TYPE_FINGER, 100f, 300f, 100f)
        assertEquals(1, view.strokes.size)
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 100f, 300f, 300f)
        assertEquals(2, view.strokes.size)
        drag(view, MotionEvent.TOOL_TYPE_FINGER, 100f, 300f, 500f)
        assertEquals(2, view.strokes.size)
    }

    @Test fun theStylusButtonRubsOut() {
        val view = board()
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 100f, 300f, 100f)
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 150f, 250f, 100f, MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertTrue(view.isEmpty)
        assertNotNull(view.also { it.undo() }.export())
    }

    @Test fun theBoardRidesOnShareAndAddsNothingToTheHandshake() {
        assertEquals(listOf("mic", "share", "inbox", "actions"), Modules.names)
    }
}
