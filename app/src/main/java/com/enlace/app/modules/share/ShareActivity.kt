package com.enlace.app.modules.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.enlace.app.Strings
import kotlin.concurrent.thread

/**
 * Invisible. What the system opens for "Compartir → A la compu" and for the
 * "Enviar a la compu" entry in the text-selection menu. Stays alive until the
 * send finishes, says how it went, and leaves.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = items(intent)
        Toast.makeText(this, Strings.SHARE_SENDING, Toast.LENGTH_SHORT).show()
        thread(name = "enlace-share") {
            val result = Sender.send(applicationContext, items)
            runOnUiThread {
                Toast.makeText(applicationContext, result, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun items(intent: Intent): List<Item> {
        val type = intent.type
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                return if (text.isNullOrEmpty()) emptyList() else listOf(Item(text = text))
            }
            Intent.ACTION_SEND_MULTIPLE -> return streams(intent).map { Item(uri = it, mime = type) }
            Intent.ACTION_SEND -> {
                val stream = streams(intent).firstOrNull()
                if (stream != null) return listOf(Item(uri = stream, mime = type))
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                return if (text.isNullOrEmpty()) emptyList() else listOf(Item(text = text))
            }
        }
        return emptyList()
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            return list ?: emptyList()
        }
        val one = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        return listOfNotNull(one)
    }
}
