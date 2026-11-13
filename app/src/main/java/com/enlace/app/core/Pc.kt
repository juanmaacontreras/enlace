package com.enlace.app.core

import java.net.URI
import java.net.URLDecoder

/** The linked PC. [token] is empty until pairing finishes. */
data class Pc(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String = "",
)

/** What the pairing QR carries: where the PC is, how to recognise it, and a one-time code. */
class PairTarget(val pc: Pc, val code: String)

object PairUri {
    /** `enlace://pair?h=…&p=…&id=…&n=…&fp=…&c=…`, or null if it is anything else. */
    fun parse(text: String): PairTarget? {
        val uri = try { URI(text.trim()) } catch (e: Exception) { return null }
        if (uri.scheme != "enlace" || uri.host != "pair") return null
        val query = (uri.rawQuery ?: return null).split("&").mapNotNull {
            val cut = it.indexOf('=')
            if (cut < 1) null
            else it.substring(0, cut) to URLDecoder.decode(it.substring(cut + 1), "UTF-8")
        }.toMap()
        val host = query["h"]; val id = query["id"]; val fp = query["fp"]; val code = query["c"]
        val port = query["p"]?.toIntOrNull()
        if (host.isNullOrEmpty() || id.isNullOrEmpty() || fp.isNullOrEmpty() ||
            code.isNullOrEmpty() || port == null || port !in 1..65535) return null
        return PairTarget(Pc(id, query["n"] ?: "PC", host, port, fp.lowercase()), code)
    }
}
