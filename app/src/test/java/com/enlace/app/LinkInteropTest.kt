package com.enlace.app

import com.enlace.app.core.Device
import com.enlace.app.core.Discovery
import com.enlace.app.core.Link
import com.enlace.app.core.LinkException
import com.enlace.app.core.Messages
import com.enlace.app.core.PairUri
import com.enlace.app.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The app's real connection code against the real PC service, over real TLS.
 * Run by tools/interop.sh, which starts the service and passes its pairing
 * link; on a plain `gradle test` it skips itself.
 */
class LinkInteropTest {
    private val uri = System.getProperty("enlace.interop") ?: ""
    private val modules = listOf("actions", "inbox", "mic", "share")
    private val device = Device("interop-device", "Interop")

    @Test fun pairThenUseEveryModule() {
        assumeTrue(uri.isNotEmpty())
        val target = PairUri.parse(uri)!!

        // Pairing: the QR's code buys a token, once.
        val paired = Link.connect(target.pc, device, modules, pair = target.code)
        val pc = paired.pc
        assertTrue(pc.token.isNotEmpty())
        assertEquals(modules.toSet(), paired.modules)
        paired.close()
        try {
            Link.connect(target.pc, device, modules, pair = target.code)
            fail("the pairing code worked twice")
        } catch (e: LinkException) {
            assertEquals(LinkException.BAD_PAIR_CODE, e.code)
        }

        // A stranger, and a PC that is not the one in the QR.
        try {
            Link.connect(pc.copy(token = "made-up"), device, modules, pair = null)
            fail("a made-up token was accepted")
        } catch (e: LinkException) {
            assertEquals(LinkException.UNAUTHORIZED, e.code)
        }
        try {
            Link.connect(pc.copy(fingerprint = "00".repeat(32), id = "nobody"), device, modules, pair = null)
            fail("a PC with another certificate was trusted")
        } catch (e: LinkException) {
            assertEquals(LinkException.UNREACHABLE, e.code)
        }

        // Finding the PC again after its address changed.
        assertNotNull(Discovery.find(pc.id, pc.port))
        val moved = Link.connect(pc.copy(host = "192.0.2.77"), device, modules, pair = null)
        assertTrue(moved.pc.host != "192.0.2.77")
        moved.close()

        Link.connect(pc, device, modules, pair = null).use { link ->
            // actions: the list comes from the PC, and a button runs there.
            val list = link.request(Messages.actionsList())
            val actions = list.header.getJSONArray("actions")
            assertEquals("touch", actions.getJSONObject(0).getString("id"))
            assertTrue(link.request(Messages.actionsRun("touch")).header.getBoolean("ok"))
            assertTrue(!link.request(Messages.actionsRun("not-listed")).header.getBoolean("ok"))

            // share: a file lands in the PC's Downloads under a safe name.
            val body = ByteArray(300_000) { (it % 251).toByte() }
            val done = link.request(Messages.shareItem("application/pdf", "../apunte ñ.pdf", false), body)
            assertEquals("saved", done.header.getString("result"))
            assertEquals("apunte ñ.pdf", done.header.getString("name"))

            // inbox: what `enlace send` queued comes down once and is gone after the confirmation.
            val waiting = link.request(Messages.inboxList()).header.getJSONArray("items")
            assertEquals(2, waiting.length())
            val note = link.request(Messages.inboxGet(waiting.getJSONObject(0).getString("id")))
            assertTrue(note.header.getBoolean("text"))
            assertEquals("hola ñ", String(note.body, Charsets.UTF_8))
            val file = waiting.getJSONObject(1)
            assertEquals("para el celu.bin", file.getString("name"))
            val got = link.request(Messages.inboxGet(file.getString("id")))
            assertEquals("item", got.type)
            assertTrue(got.body.contentEquals(ByteArray(200_000) { (it % 249).toByte() }))
            assertEquals("not_found", link.request(Messages.inboxGet("../state")).header.getString("code"))
            for (i in 0 until 2) {
                assertEquals("removed", link.request(Messages.inboxDone(waiting.getJSONObject(i).getString("id"))).type)
            }
            assertEquals(0, link.request(Messages.inboxList()).header.getJSONArray("items").length())

            // mic: the PC accepts the stream the service sends (needs PipeWire).
            if (System.getProperty("enlace.interop.mic") == "1") {
                assertEquals("started", link.request(Messages.micStart(48000, 1)).type)
                val frame = ByteArray(1920)
                val head = Protocol.head(Messages.micAudio(), frame.size)
                repeat(50) {
                    for (i in 0 until 960) {
                        val v = (8000 * Math.sin(2 * Math.PI * 440 * (it * 960 + i) / 48000.0)).toInt()
                        frame[2 * i] = v.toByte(); frame[2 * i + 1] = (v shr 8).toByte()
                    }
                    link.sendRaw(head, frame, frame.size)
                    Thread.sleep(20)
                }
                assertEquals("stopped", link.request(Messages.micStop()).type)
            }
        }
    }
}
