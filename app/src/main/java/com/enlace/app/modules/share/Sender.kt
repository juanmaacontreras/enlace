package com.enlace.app.modules.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.enlace.app.Strings
import com.enlace.app.core.Link
import com.enlace.app.core.LinkException
import com.enlace.app.core.Messages
import com.enlace.app.core.Modules
import com.enlace.app.core.Protocol
import java.io.File
import java.io.IOException

/** One thing to send: text, or a file some app handed over by Uri. */
class Item(val text: String? = null, val uri: Uri? = null, val mime: String? = null)

object Sender {
    /** An item copied into the app's own storage: the Uri an app lends stops working when the share ends. */
    private class Spooled(val file: File, val mime: String, val name: String?)

    /**
     * Sends [items] and returns the sentence to show the user. Blocks; call it
     * off the main thread. A single text or image goes to the PC's clipboard;
     * several things at once, or any other kind of file, go to its Downloads.
     * If the PC cannot be reached, everything waits in [Outbox] instead.
     *
     * Nothing is held in memory: each item is copied to a file and streamed
     * from there, so a long video costs disk, not RAM.
     */
    fun send(context: Context, items: List<Item>): String {
        if (items.isEmpty()) return Strings.SHARE_EMPTY
        val spooled = ArrayList<Spooled>()
        try {
            for (item in items) spooled.add(spool(context, item) ?: continue)
        } catch (e: TooBig) {
            spooled.forEach { it.file.delete() }
            return Strings.SHARE_TOO_BIG
        } catch (e: Exception) {
            spooled.forEach { it.file.delete() }
            return Strings.SHARE_EMPTY
        }
        if (spooled.isEmpty()) return Strings.SHARE_EMPTY

        val link = try {
            Link.open(context, Modules.names)
        } catch (e: LinkException) {
            if (e.code == LinkException.UNREACHABLE) return queue(context, spooled)
            spooled.forEach { it.file.delete() }
            return if (e.code == LinkException.NOT_LINKED) Strings.SHARE_NOT_LINKED else Strings.SHARE_FAILED
        }
        var clipboard = false
        link.use {
            if (ShareModule.name !in link.modules) {
                spooled.forEach { it.file.delete() }
                return Strings.SHARE_FAILED
            }
            link.readTimeout(120_000)
            for ((i, item) in spooled.withIndex()) {
                val wanted = spooled.size == 1 && (item.mime.startsWith("text/") || item.mime.startsWith("image/"))
                val result = deliver(link, item.file, item.mime, item.name, wanted)
                    // Cut halfway: this one and the ones after it wait for the next chance.
                    ?: return queue(context, spooled.subList(i, spooled.size))
                item.file.delete()
                clipboard = result == "clipboard"
            }
        }
        return if (clipboard) Strings.SHARE_CLIPBOARD_DONE else Strings.SHARE_SAVED
    }

    /** Delivers what waits in [Outbox], oldest first, and returns how many arrived. Blocks. */
    fun flush(context: Context): Int {
        val waiting = Outbox.list(context)
        if (waiting.isEmpty()) return 0
        val link = try { Link.open(context, Modules.names) } catch (e: LinkException) { return 0 }
        var sent = 0
        link.use {
            if (ShareModule.name !in link.modules) return 0
            link.readTimeout(120_000)
            for (entry in waiting) {
                deliver(link, entry.data, entry.mime, entry.name, toClipboard = false) ?: return sent
                Outbox.remove(entry)
                sent++
            }
        }
        return sent
    }

    /** The PC's `result` ("clipboard" or "saved"), or null if it did not get through. */
    private fun deliver(link: Link, file: File, mime: String, name: String?, toClipboard: Boolean): String? = try {
        val reply = link.request(Messages.shareItem(mime, name, toClipboard), file)
        if (reply.type == "done") reply.header.optString("result") else null
    } catch (e: Exception) {
        null
    }

    private fun queue(context: Context, items: List<Spooled>): String {
        for (item in items) Outbox.add(context, item.file, item.mime, item.name)
        Outbox.schedule(context)
        return Strings.SHARE_QUEUED
    }

    private class TooBig : IOException("too big")

    private fun spool(context: Context, item: Item): Spooled? {
        val folder = File(context.cacheDir, "sending").apply { mkdirs() }
        val file = File.createTempFile("item", null, folder)
        try {
            if (item.uri == null) {
                val text = item.text?.takeIf { it.isNotEmpty() } ?: run { file.delete(); return null }
                file.writeText(text, Charsets.UTF_8)
                return Spooled(file, "text/plain", null)
            }
            val mime = item.mime?.takeIf { '*' !in it }
                ?: context.contentResolver.getType(item.uri) ?: "application/octet-stream"
            val input = context.contentResolver.openInputStream(item.uri) ?: throw IOException("cannot open")
            input.use { source ->
                file.outputStream().use { out ->
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = source.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > Protocol.MAX_BODY) throw TooBig()
                        out.write(chunk, 0, n)
                    }
                }
            }
            return Spooled(file, mime, displayName(context, item.uri))
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
}
