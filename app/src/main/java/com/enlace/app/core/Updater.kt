package com.enlace.app.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates from GitHub Releases. The repo is public, so neither the release
 * list nor the APK needs a token. A release is tagged v<N> where N is the
 * versionCode (tools/release.sh); a tag above the installed one is offered on
 * the main screen. Android never installs silently: the user confirms, and it
 * only accepts an APK signed with the same key as the installed one.
 */
object Updater {
    const val REPO = "juanmaacontreras/enlace"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    class Release(val version: Long, val apkUrl: String)

    fun installedVersion(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) { 0L }

    /** Pure: what a "latest release" answer offers, or null. Tested without a device. */
    fun parse(json: String): Release? {
        val release = try { JSONObject(json) } catch (e: Exception) { return null }
        val version = release.optString("tag_name").trimStart('v', 'V').toLongOrNull() ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url")
            if (asset.optString("name").endsWith(".apk") && url.startsWith("https://")) {
                return Release(version, url)
            }
        }
        return null
    }

    /** Asks GitHub in the background; [onNewer] runs on the main thread, only for a newer release. */
    fun check(activity: Activity, onNewer: (Release) -> Unit) {
        val installed = installedVersion(activity)
        thread(name = "enlace-update-check") {
            val release = try {
                val conn = URL(LATEST).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "enlace-app")  // GitHub refuses requests without one
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                try {
                    if (conn.responseCode !in 200..299) null
                    else parse(conn.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null  // offline, or no release yet
            }
            if (release != null && release.version > installed) activity.runOnUiThread { onNewer(release) }
        }
    }

    /**
     * Downloads the APK and opens the system installer. [onState] gets one of
     * NEEDS_PERMISSION, DOWNLOADING, FAILED on the main thread.
     */
    fun install(activity: Activity, release: Release, onState: (State) -> Unit) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            onState(State.NEEDS_PERMISSION)
            runCatching {
                activity.startActivity(Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            return
        }
        onState(State.DOWNLOADING)
        thread(name = "enlace-update") {
            try {
                val folder = File(activity.cacheDir, "update").apply { mkdirs() }
                val file = File(folder, "enlace.apk")
                val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                try {
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
                activity.runOnUiThread {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                    activity.startActivity(Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
                activity.runOnUiThread { onState(State.FAILED) }
            }
        }
    }

    enum class State { NEEDS_PERMISSION, DOWNLOADING, FAILED }
}
