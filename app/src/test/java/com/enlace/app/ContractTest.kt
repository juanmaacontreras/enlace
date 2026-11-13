package com.enlace.app

import com.enlace.app.core.Messages
import com.enlace.app.core.PairUri
import com.enlace.app.core.Protocol
import com.enlace.app.core.Updater
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The app's half of the contract: contract/fixtures.json, also run by the PC. */
class ContractTest {
    private val fixtures = JSONObject(File("../contract/fixtures.json").readText())

    private fun hex(text: String) =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun same(expected: JSONObject, actual: JSONObject) =
        assertTrue("expected $expected but was $actual", expected.similar(actual))

    @Test fun constants() {
        assertEquals(fixtures.getInt("version"), Protocol.VERSION)
        assertEquals(fixtures.getInt("port"), Protocol.PORT)
    }

    @Test fun framesDecodeAndRoundTrip() {
        val frames = fixtures.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val case = frames.getJSONObject(i)
            val frame = Protocol.decode(hex(case.getString("hex")))
            same(case.getJSONObject("header"), frame.header)
            assertArrayEquals(case.getString("name"), hex(case.getString("body_hex")), frame.body)
            val again = Protocol.decode(Protocol.encode(frame.header, frame.body))
            same(frame.header, again.header)
            assertArrayEquals(frame.body, again.body)
        }
    }

    @Test fun badFramesAreRefused() {
        val frames = fixtures.getJSONArray("bad_frames")
        for (i in 0 until frames.length()) {
            val case = frames.getJSONObject(i)
            try {
                Protocol.decode(hex(case.getString("hex")))
                fail("accepted: " + case.getString("name"))
            } catch (expected: Protocol.ProtocolException) {
            }
        }
    }

    @Test fun messagesTheAppBuilds() {
        val sends = fixtures.getJSONObject("app_sends")
        val modules = listOf("actions", "mic", "share")  // the fixture's example list
        same(sends.getJSONObject("hello_pair"), Messages.hello("d1", "Redmi", modules, pair = "CODE"))
        same(sends.getJSONObject("hello_auth"), Messages.hello("d1", "Redmi", modules, token = "TOKEN"))
        same(sends.getJSONObject("discovery_ask"), Messages.discoveryAsk("pc1"))
        same(sends.getJSONObject("mic_start"), Messages.micStart(48000, 1))
        same(sends.getJSONObject("mic_audio"), Messages.micAudio())
        same(sends.getJSONObject("mic_stop"), Messages.micStop())
        same(sends.getJSONObject("share_clipboard"), Messages.shareItem("image/jpeg", "foto.jpg", true))
        same(sends.getJSONObject("share_downloads"), Messages.shareItem("application/pdf", "apunte.pdf", false))
        same(sends.getJSONObject("actions_list"), Messages.actionsList())
        same(sends.getJSONObject("actions_run"), Messages.actionsRun("lock"))
        same(sends.getJSONObject("inbox_list"), Messages.inboxList())
        same(sends.getJSONObject("inbox_get"), Messages.inboxGet("1794600000-ab12cd34"))
        same(sends.getJSONObject("inbox_done"), Messages.inboxDone("1794600000-ab12cd34"))
    }

    @Test fun pairUri() {
        val case = fixtures.getJSONObject("pair_uri")
        val target = PairUri.parse(case.getString("uri"))!!
        assertEquals(case.getString("host"), target.pc.host)
        assertEquals(case.getInt("port"), target.pc.port)
        assertEquals(case.getString("id"), target.pc.id)
        assertEquals(case.getString("name"), target.pc.name)
        assertEquals(case.getString("fingerprint"), target.pc.fingerprint)
        assertEquals(case.getString("code"), target.code)
        assertNull(PairUri.parse("https://example.com/?h=1"))
        assertNull(PairUri.parse("enlace://pair?h=1.2.3.4&p=0&id=a&fp=b&c=c"))
        assertNull(PairUri.parse("not a uri at all"))
    }

    @Test fun updaterReadsAGitHubRelease() {
        val release = Updater.parse(
            """{"tag_name":"v12","assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
               {"name":"enlace.apk","browser_download_url":"https://github.com/o/r/releases/download/v12/enlace.apk"}]}""")!!
        assertEquals(12L, release.version)
        assertEquals("https://github.com/o/r/releases/download/v12/enlace.apk", release.apkUrl)
        assertNull(Updater.parse("""{"tag_name":"v12","assets":[]}"""))
        assertNull(Updater.parse("""{"tag_name":"nightly","assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]}"""))
        assertNull(Updater.parse("""{"tag_name":"v12","assets":[{"name":"a.apk","browser_download_url":"http://x/a.apk"}]}"""))
        assertNull(Updater.parse("not json"))
    }
}
