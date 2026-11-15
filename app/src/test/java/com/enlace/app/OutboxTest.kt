package com.enlace.app

import android.content.Context
import com.enlace.app.modules.share.Outbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The queue of things shared while the PC could not be reached. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun spooled(text: String) = File.createTempFile("item", null, context.cacheDir).apply { writeText(text) }

    @Test fun keepsOrderAndContentAndForgetsWhatWasDelivered() {
        Outbox.add(context, spooled("uno"), "text/plain", null)
        Outbox.add(context, spooled("dos"), "application/pdf", "apunte.pdf")
        val waiting = Outbox.list(context)
        assertEquals(listOf("uno", "dos"), waiting.map { it.data.readText() })
        assertTrue(waiting[0].name.startsWith("nota ") && waiting[0].name.endsWith(".txt"))
        assertEquals("apunte.pdf", waiting[1].name)
        Outbox.remove(waiting[0])
        assertEquals(listOf("apunte.pdf"), Outbox.list(context).map { it.name })
    }

    @Test fun dropsWhatWaitedTooLongAndHalfWrittenItems() {
        Outbox.add(context, spooled("viejo"), "text/plain", "viejo.txt")
        val later = System.currentTimeMillis() + Outbox.KEEP_MS + 1000
        assertEquals(0, Outbox.list(context, later).size)
        assertEquals(0, Outbox.count(context))
    }
}
