package com.enlace.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.enlace.app.core.Link
import com.enlace.app.core.LinkException
import com.enlace.app.core.Modules
import com.enlace.app.core.PairUri
import com.enlace.app.core.Prefs
import com.enlace.app.core.Protocol
import com.enlace.app.modules.mic.MicModule
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The one screen. Unlinked: how to pair and a scan button. Linked: the PC's
 * name and state, then one card per module that both sides have. It knows
 * nothing about what a module does; it only asks each one for its card.
 */
class MainActivity : Activity() {
    private val network = Executors.newSingleThreadExecutor()
    private var link: Link? = null
    private var shared: Set<String> = emptySet()
    private var problem: String? = null
    private var searching = false
    private lateinit var root: LinearLayout
    private var status: TextView? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = Ui.column(this)
        val pad = Ui.dp(this, 20f)
        root.setPadding(pad, Ui.dp(this, 28f), pad, pad)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (Prefs.pc(this) == null) render() else connect()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        val open = link
        link = null
        network.execute { open?.close() }
    }

    // --- the link while the screen is open ----------------------------------

    private fun connect() {
        searching = true
        problem = null
        render()
        network.execute {
            link?.close()
            link = null
            var failure: String? = null
            val opened = try {
                Link.open(this, Modules.names)
            } catch (e: LinkException) {
                failure = e.code
                null
            }
            runOnUiThread {
                if (!resumed) {
                    network.execute { opened?.close() }
                    return@runOnUiThread
                }
                searching = false
                link = opened
                problem = failure
                if (opened != null) shared = opened.modules
                render()
                if (opened != null) Modules.all.filter { it.name in shared }.forEach { it.refresh(this) }
            }
        }
    }

    /**
     * One request over the screen's link; [onReply] runs on the main thread
     * with the answer, or null if the PC could not be reached.
     */
    fun ask(header: JSONObject, onReply: (Protocol.Frame?) -> Unit) {
        network.execute {
            val reply = try {
                val open = link ?: Link.open(this, Modules.names).also { link = it }
                open.request(header)
            } catch (e: Exception) {
                link?.close()
                link = null
                null
            }
            runOnUiThread { if (resumed) onReply(reply) }
        }
    }

    // --- the screen ---------------------------------------------------------

    private fun render() {
        root.removeAllViews()
        root.addView(Ui.text(this, Strings.APP, 30f, Ui.BRIGHT, bold = true))
        val pc = Prefs.pc(this)
        if (pc == null) {
            renderUnlinked()
            return
        }

        val line = when {
            searching -> Strings.SEARCHING
            link != null -> pc.name + "  ·  " + Strings.CONNECTED
            problem == LinkException.UNAUTHORIZED -> Strings.UNAUTHORIZED
            else -> pc.name + "  ·  " + Strings.UNREACHABLE
        }
        val color = when {
            searching -> Ui.DIM
            link != null -> Ui.GREEN
            else -> Ui.YELLOW
        }
        status = Ui.text(this, line, 15f, color)
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 2f) })
        root.addView(Ui.gap(this, 22f))

        if (link == null && !searching) {
            root.addView(Ui.button(this, Strings.RETRY, true) { connect() }, LinearLayout.LayoutParams(-1, -2))
            root.addView(Ui.gap(this, 14f))
        }
        // The microphone stays reachable even when the PC is not: it may be
        // running and reconnecting, and the user must be able to turn it off.
        val visible = Modules.all.filter { it.name in shared || (it === MicModule && shared.isEmpty()) }
        for (module in visible) {
            val card = module.card(this) ?: continue
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 14f) })
        }

        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Ui.text(this, Strings.UNLINK, 14f, Ui.DIM).apply {
            gravity = Gravity.CENTER
            val v = Ui.dp(this@MainActivity, 14f)
            setPadding(v, v, v, v)
            setOnClickListener { confirmUnlink() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderUnlinked() {
        root.addView(Ui.gap(this, 26f))
        root.addView(Ui.text(this, Strings.UNLINKED_TITLE, 19f, Ui.BRIGHT, bold = true))
        root.addView(Ui.text(this, Strings.UNLINKED_STEPS, 15f, Ui.TEXT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 12f) })
        root.addView(Ui.gap(this, 26f))
        root.addView(Ui.button(this, Strings.SCAN, true) { scan() }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun confirmUnlink() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(Strings.UNLINK_CONFIRM)
            .setPositiveButton(Strings.UNLINK) { _, _ ->
                Prefs.unlink(this)
                shared = emptySet()
                val open = link
                link = null
                network.execute { open?.close() }
                render()
            }
            .setNegativeButton(Strings.CANCEL, null)
            .show()
    }

    // --- pairing ------------------------------------------------------------

    private fun scan() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(Strings.SCAN_PROMPT)
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val scanned = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanned == null) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val text = scanned.contents ?: return
        val target = PairUri.parse(text)
        if (target == null) {
            toast(Strings.PAIR_BAD_QR)
            return
        }
        toast(Strings.PAIRING)
        network.execute {
            val message = try {
                Link.pair(this, target, Modules.names).close()
                Strings.PAIRED + " " + (Prefs.pc(this)?.name ?: "")
            } catch (e: LinkException) {
                if (e.code == LinkException.BAD_PAIR_CODE) Strings.PAIR_EXPIRED else Strings.PAIR_UNREACHABLE
            }
            runOnUiThread {
                toast(message)
                if (resumed && Prefs.pc(this) != null) connect()
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == MicModule.PERMISSION_REQUEST) MicModule.onPermissions(this)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
