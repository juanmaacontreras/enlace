package com.enlace.app.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/** What the device remembers: who it is and which PC it is linked to. */
object Prefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("enlace", Context.MODE_PRIVATE)

    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString("device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString("device_id", id).apply()
        return id
    }

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"

    fun pc(context: Context): Pc? {
        val p = prefs(context)
        val id = p.getString("pc_id", null) ?: return null
        return Pc(
            id, p.getString("pc_name", "") ?: "", p.getString("pc_host", "") ?: "",
            p.getInt("pc_port", Protocol.PORT), p.getString("pc_fp", "") ?: "",
            p.getString("pc_token", "") ?: "",
        )
    }

    fun save(context: Context, pc: Pc) {
        prefs(context).edit()
            .putString("pc_id", pc.id).putString("pc_name", pc.name)
            .putString("pc_host", pc.host).putInt("pc_port", pc.port)
            .putString("pc_fp", pc.fingerprint).putString("pc_token", pc.token)
            .apply()
    }

    fun unlink(context: Context) {
        prefs(context).edit()
            .remove("pc_id").remove("pc_name").remove("pc_host").remove("pc_port")
            .remove("pc_fp").remove("pc_token").apply()
    }
}
