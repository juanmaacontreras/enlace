package com.enlace.app.modules.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.enlace.app.MainActivity
import com.enlace.app.Strings
import com.enlace.app.Ui
import com.enlace.app.core.Messages
import com.enlace.app.core.Module
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * inbox: what the PC queued with `enlace send` arrives when this screen opens.
 * Text goes to the clipboard, images to Pictures/Enlace, other files to
 * Downloads. Each item is confirmed to the PC only after it is saved, so one
 * cut halfway comes again next time.
 *
 * Does not: receive anything while the app is closed (no background service,
 * no battery cost).
 */
object InboxModule : Module {
    override val name = "inbox"

    /** Something that arrived since the app started. [uri] is null for text. */
    class Received(val label: String, val where: String, val uri: Uri?, val mime: String)

    private val received = ArrayList<Received>()
    private val saved = HashSet<String>()
    private var busy = false
    private var rows: LinearLayout? = null

    override fun card(activity: MainActivity): View {
        val card = Ui.card(activity, "download", Strings.INBOX, Strings.INBOX_ABOUT)
        val list = Ui.column(activity)
        card.addView(list, LinearLayout.LayoutParams(-1, -2))
        rows = list
        fill(activity)
        return card
    }

    override fun refresh(activity: MainActivity) {
        if (busy) return
        busy = true
        activity.ask(Messages.inboxList()) { reply ->
            val items = reply?.header?.optJSONArray("items")
            val waiting = ArrayList<JSONObject>()
            if (items != null) for (i in 0 until items.length()) items.optJSONObject(i)?.let { waiting.add(it) }
            next(activity, waiting)
        }
        // A reply that never reaches the screen (it was closed) must not block the next open.
        rows?.postDelayed({ busy = false }, 60_000)
    }

    /** Fetch, save and confirm one item, then the following one. */
    private fun next(activity: MainActivity, waiting: MutableList<JSONObject>) {
        val item = waiting.removeFirstOrNull()
        if (item == null) {
            busy = false
            return
        }
        val id = item.optString("id")
        val confirm = { activity.ask(Messages.inboxDone(id)) { next(activity, waiting) } }
        if (id in saved) {  // saved last time, but the confirmation did not get through
            confirm()
            return
        }
        activity.ask(Messages.inboxGet(id)) { reply ->
            if (reply == null || reply.type != "item") {
                busy = false
                return@ask
            }
            val header = reply.header
            if (header.optBoolean("text")) {
                val text = String(reply.body, Charsets.UTF_8)
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(Strings.APP, text))
                arrived(activity, id, Received(text.trim().take(60), Strings.INBOX_COPIED, null, "text/plain"))
                confirm()
                return@ask
            }
            val app = activity.applicationContext
            thread(name = "enlace-inbox") {
                val mime = header.optString("mime", "application/octet-stream")
                val fileName = File(header.optString("name", "enlace")).name.ifEmpty { "enlace" }
                val uri = runCatching { save(app, fileName, mime, reply.body) }.getOrNull()
                activity.runOnUiThread {
                    if (uri == null) {
                        Toast.makeText(app, Strings.INBOX_FAILED, Toast.LENGTH_LONG).show()
                        busy = false
                    } else {
                        val where = if (mime.startsWith("image/")) Strings.INBOX_IN_GALLERY else Strings.INBOX_IN_DOWNLOADS
                        arrived(activity, id, Received(fileName, where, uri, mime))
                        confirm()
                    }
                }
            }
        }
    }

    private fun arrived(activity: MainActivity, id: String, item: Received) {
        saved.add(id)
        received.add(0, item)
        Toast.makeText(activity, item.where + ": " + item.label, Toast.LENGTH_SHORT).show()
        fill(activity)
    }

    private fun fill(activity: MainActivity) {
        val list = rows ?: return
        list.removeAllViews()
        for (item in received.take(5)) {
            val row = Ui.column(activity).apply {
                val pad = Ui.dp(activity, 12f)
                setPadding(pad, Ui.dp(activity, 9f), pad, Ui.dp(activity, 9f))
                background = Ui.rounded(Ui.RAISED, 14f, activity)
            }
            row.addView(Ui.text(activity, item.label, 14.5f, Ui.BRIGHT).apply { maxLines = 1 })
            row.addView(Ui.text(activity, item.where, 12.5f, Ui.DIM))
            if (item.uri != null) row.setOnClickListener { open(activity, item) }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(activity, 10f) })
        }
    }

    private fun open(activity: MainActivity, item: Received) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(view) }
            .onFailure { Toast.makeText(activity, Strings.INBOX_NO_APP, Toast.LENGTH_SHORT).show() }
    }

    /** Writes the file where the device's own apps look for it and returns its address. */
    private fun save(context: Context, fileName: String, mime: String, data: ByteArray): Uri {
        val image = mime.startsWith("image/")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Before Android 10 the shared folders need a storage permission; keep to the app's own.
            val folder = File(context.getExternalFilesDir(null), "received").apply { mkdirs() }
            var file = File(folder, fileName)
            var n = 1
            while (file.exists()) file = File(folder, "${n++}-$fileName")
            file.writeBytes(data)
            return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }
        val collection = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = if (image) Environment.DIRECTORY_PICTURES + "/Enlace" else Environment.DIRECTORY_DOWNLOADS
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("no place to save")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(data) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
