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

/** One thing to send: text, or a file some app handed over by Uri. */
class Item(val text: String? = null, val uri: Uri? = null, val mime: String? = null)

object Sender {
    /**
     * Sends [items] and returns the sentence to show the user. Blocks; call it
     * off the main thread. A single text or image goes to the PC's clipboard;
     * several things at once, or any other kind of file, go to its Downloads.
     */
    fun send(context: Context, items: List<Item>): String {
        if (items.isEmpty()) return Strings.SHARE_EMPTY
        val link = try {
            Link.open(context, Modules.names)
        } catch (e: LinkException) {
            return if (e.code == LinkException.NOT_LINKED) Strings.SHARE_NOT_LINKED else Strings.SHARE_FAILED
        }
        link.use {
            if (ShareModule.name !in link.modules) return Strings.SHARE_FAILED
            var clipboard = false
            for (item in items) {
                val mime: String
                val name: String?
                val body: ByteArray
                if (item.uri != null) {
                    mime = item.mime?.takeIf { '*' !in it }
                        ?: context.contentResolver.getType(item.uri) ?: "application/octet-stream"
                    name = displayName(context, item.uri)
                    body = try {
                        read(context, item.uri) ?: return Strings.SHARE_TOO_BIG
                    } catch (e: Exception) {
                        return Strings.SHARE_EMPTY
                    }
                } else {
                    mime = "text/plain"
                    name = null
                    body = (item.text ?: continue).toByteArray(Charsets.UTF_8)
                }
                val wanted = items.size == 1 && (mime.startsWith("text/") || mime.startsWith("image/"))
                val reply = try {
                    link.readTimeout(120_000)
                    link.request(Messages.shareItem(mime, name, wanted), body)
                } catch (e: Exception) {
                    return Strings.SHARE_FAILED
                }
                if (reply.type != "done") return Strings.SHARE_FAILED
                clipboard = reply.header.optString("result") == "clipboard"
            }
            return if (clipboard) Strings.SHARE_CLIPBOARD_DONE else Strings.SHARE_SAVED
        }
    }

    private fun read(context: Context, uri: Uri): ByteArray? {
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw java.io.IOException("cannot open")
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                if (out.size() > Protocol.MAX_BODY) return null
            }
            return out.toByteArray()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
}
