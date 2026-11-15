package com.enlace.app.modules.share

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What was shared while the PC could not be reached (away from home, PC off).
 * Each item waits in its own folder inside the app's storage and is delivered
 * to the PC's Downloads the next time the PC answers: when the app is opened,
 * or by [OutboxJob] once the device is back on Wi-Fi.
 *
 * Queued items never go to the PC's clipboard: they arrive hours later, and
 * replacing what is copied there at that moment would be a surprise.
 */
object Outbox {
    const val KEEP_MS = 14L * 24 * 3600 * 1000

    class Entry(val folder: File, val mime: String, val name: String) {
        val data: File get() = File(folder, "data")
    }

    private fun dir(context: Context) = File(context.filesDir, "outbox")

    /** Takes [spooled] (moved, not copied). Text gets a dated name so it is findable in Downloads. */
    @Synchronized
    fun add(context: Context, spooled: File, mime: String, name: String?) {
        val now = System.currentTimeMillis()
        var folder = File(dir(context), now.toString())
        var n = 1
        while (folder.exists()) folder = File(dir(context), "$now-${n++}")
        folder.mkdirs()
        val shown = name ?: ("nota " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(now)) + ".txt")
        if (!spooled.renameTo(File(folder, "data"))) {
            spooled.copyTo(File(folder, "data"), overwrite = true)
            spooled.delete()
        }
        // Written last: a folder without it is half an item and is not listed.
        File(folder, "meta.json").writeText(JSONObject().put("mime", mime).put("name", shown).toString())
    }

    /** Waiting items, oldest first. Half-written and expired ones are removed. */
    @Synchronized
    fun list(context: Context, now: Long = System.currentTimeMillis()): List<Entry> {
        val found = ArrayList<Entry>()
        for (folder in dir(context).listFiles()?.sortedBy { it.name } ?: emptyList()) {
            val meta = runCatching { JSONObject(File(folder, "meta.json").readText()) }.getOrNull()
            val fresh = now - folder.lastModified() < KEEP_MS
            if (meta != null && fresh && File(folder, "data").isFile) {
                found.add(Entry(folder, meta.optString("mime", "application/octet-stream"), meta.optString("name", "enlace")))
            } else if (meta != null || now - folder.lastModified() > 60_000) {
                folder.deleteRecursively()
            }
        }
        return found
    }

    fun count(context: Context): Int = list(context).size

    @Synchronized
    fun remove(entry: Entry) { entry.folder.deleteRecursively() }

    /** Ask the system to run [OutboxJob] when the device is on Wi-Fi, not before [delayMs]. */
    fun schedule(context: Context, delayMs: Long = 0) {
        val job = JobInfo.Builder(OutboxJob.ID, ComponentName(context, OutboxJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(delayMs)
            .build()
        context.getSystemService(JobScheduler::class.java)?.schedule(job)
    }
}
