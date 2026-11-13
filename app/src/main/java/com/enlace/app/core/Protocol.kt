package com.enlace.app.core

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Wire format shared with the PC. The contract is docs/contract.md.
 *
 * A frame is: 4 bytes big-endian header length, the header as UTF-8 JSON, then
 * `header.size` bytes of body when that key is present. Pure JVM on purpose:
 * ContractTest runs it against contract/fixtures.json without a device.
 */
object Protocol {
    const val VERSION = 1
    const val PORT = 47800
    const val MAX_HEADER = 64 * 1024
    const val MAX_BODY = 256 * 1024 * 1024

    class ProtocolException(message: String) : IOException(message)

    class Frame(val header: JSONObject, val body: ByteArray) {
        val type: String get() = header.optString("type")
    }

    /** Length prefix plus header, announcing a body of [bodySize] bytes. */
    fun head(header: JSONObject, bodySize: Int = 0): ByteArray {
        val copy = JSONObject(header.toString())
        copy.remove("size")
        if (bodySize > 0) copy.put("size", bodySize)
        val raw = copy.toString().toByteArray(Charsets.UTF_8)
        if (raw.size > MAX_HEADER) throw ProtocolException("header too large")
        return ByteBuffer.allocate(4 + raw.size).putInt(raw.size).put(raw).array()
    }

    fun encode(header: JSONObject, body: ByteArray = ByteArray(0)): ByteArray =
        head(header, body.size) + body

    fun read(input: InputStream, maxBody: Int = MAX_BODY): Frame {
        val data = DataInputStream(input)
        val length = data.readInt()
        if (length < 0 || length > MAX_HEADER) throw ProtocolException("header too large")
        val raw = ByteArray(length)
        data.readFully(raw)
        val header = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw ProtocolException("header is not a JSON object")
        }
        var size = 0
        if (header.has("size")) {
            val value = header.get("size")
            if (value !is Int || value < 0) throw ProtocolException("bad size")
            size = value
        }
        if (size > maxBody) throw ProtocolException("body too large")
        val body = ByteArray(size)
        data.readFully(body)
        return Frame(header, body)
    }

    /** Decode exactly one frame held in memory (tests and fixtures). */
    fun decode(bytes: ByteArray): Frame {
        val input = ByteArrayInputStream(bytes)
        val frame = try {
            read(input)
        } catch (e: EOFException) {
            throw ProtocolException("truncated frame")
        }
        if (input.available() > 0) throw ProtocolException("body does not match size")
        return frame
    }
}
