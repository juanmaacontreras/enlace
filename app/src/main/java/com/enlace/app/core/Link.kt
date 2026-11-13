package com.enlace.app.core

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Why a link could not be opened. [code] is one of the constants below or a PC error code. */
class LinkException(val code: String) : IOException(code) {
    companion object {
        const val NOT_LINKED = "not_linked"
        const val UNREACHABLE = "unreachable"
        const val UNAUTHORIZED = "unauthorized"
        const val BAD_PAIR_CODE = "bad_pair_code"
    }
}

/** This device, as it introduces itself to the PC. */
class Device(val id: String, val name: String)

/**
 * One authenticated connection to the PC: TLS pinned to the certificate seen
 * at pairing, then hello/welcome. Modules open one, exchange frames, close it.
 * All calls block; use it off the main thread.
 */
class Link private constructor(
    private val socket: SSLSocket,
    val pc: Pc,
    /** Modules both this app and the PC have. */
    val modules: Set<String>,
) : Closeable {
    private val output = BufferedOutputStream(socket.outputStream, 16 * 1024)
    private val input = BufferedInputStream(socket.inputStream, 16 * 1024)

    @Synchronized
    fun send(header: JSONObject, body: ByteArray? = null, length: Int = body?.size ?: 0) {
        output.write(Protocol.head(header, length))
        if (body != null && length > 0) output.write(body, 0, length)
        output.flush()
    }

    /** Like [send] with the length prefix and header already encoded: the audio path. */
    @Synchronized
    fun sendRaw(head: ByteArray, body: ByteArray, length: Int) {
        output.write(head)
        output.write(body, 0, length)
        output.flush()
    }

    fun read(): Protocol.Frame = Protocol.read(input)

    fun request(header: JSONObject, body: ByteArray? = null): Protocol.Frame {
        send(header, body)
        return read()
    }

    /** Reads give up after [millis] (0 = never). Writes are not covered by this. */
    fun readTimeout(millis: Int) { socket.soTimeout = millis }

    override fun close() { runCatching { socket.close() } }

    companion object {
        private const val CONNECT_MS = 2000
        private const val REPLY_MS = 15000

        /** Open a link to the PC this device is linked to. */
        fun open(context: Context, appModules: List<String>): Link {
            val pc = Prefs.pc(context) ?: throw LinkException(LinkException.NOT_LINKED)
            return remember(context, pc, connect(pc, identity(context), appModules, pair = null))
        }

        /** Pair with the PC of a scanned QR and remember it. */
        fun pair(context: Context, target: PairTarget, appModules: List<String>): Link =
            remember(context, null, connect(target.pc, identity(context), appModules, pair = target.code))

        private fun identity(context: Context) = Device(Prefs.deviceId(context), Prefs.deviceName(context))

        /** Saves what the connection learned: the token from pairing, a new address or name. */
        private fun remember(context: Context, before: Pc?, link: Link): Link {
            if (link.pc != before) Prefs.save(context, link.pc)
            return link
        }

        /**
         * The whole handshake, with no Android in it so LinkInteropTest can run
         * it on a plain JVM against the real PC service.
         */
        fun connect(known: Pc, device: Device, appModules: List<String>, pair: String?): Link {
            var pc = known
            val socket: SSLSocket = runCatching { tls(pc) }.getOrNull() ?: run {
                // The PC's address may have changed since last time: ask for it.
                val found = Discovery.find(pc.id, pc.port) ?: throw LinkException(LinkException.UNREACHABLE)
                pc = pc.copy(host = found.address.hostAddress ?: pc.host, port = found.port)
                try { tls(pc) } catch (e: IOException) {
                    throw LinkException(LinkException.UNREACHABLE)
                }
            }
            try {
                socket.soTimeout = REPLY_MS
                val hello = Messages.hello(
                    device.id, device.name, appModules,
                    pair = pair, token = if (pair == null) pc.token else null,
                )
                socket.outputStream.write(Protocol.head(hello))
                socket.outputStream.flush()
                // Read unbuffered here: the Link built below owns the stream from the next byte on.
                val welcome = Protocol.read(socket.inputStream, maxBody = 0)
                if (welcome.type != "welcome") {
                    throw LinkException(welcome.header.optString("code", LinkException.UNREACHABLE))
                }
                val shared = HashSet<String>()
                welcome.header.optJSONArray("modules")?.let { for (i in 0 until it.length()) shared.add(it.getString(i)) }
                val name = welcome.header.optJSONObject("pc")?.optString("name")?.takeIf { it.isNotEmpty() } ?: pc.name
                val token = welcome.header.optString("token").takeIf { it.isNotEmpty() } ?: pc.token
                return Link(socket, pc.copy(name = name, token = token), shared)
            } catch (e: LinkException) {
                runCatching { socket.close() }
                throw e
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw LinkException(LinkException.UNREACHABLE)
            }
        }

        private fun tls(pc: Pc): SSLSocket {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(pc.host, pc.port), CONNECT_MS)
                plain.tcpNoDelay = true
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf(Pinned(pc.fingerprint)), null)
                val socket = context.socketFactory.createSocket(plain, pc.host, pc.port, true) as SSLSocket
                socket.soTimeout = REPLY_MS
                socket.startHandshake()
                return socket
            } catch (e: Exception) {
                runCatching { plain.close() }
                throw if (e is IOException) e else IOException(e)
            }
        }
    }

    /**
     * Trusts exactly one certificate: the PC's own, by the SHA-256 the QR
     * carried. No certificate authority is involved, and the host name is
     * irrelevant (the PC is reached by address), so this check is the whole
     * of the server's identity.
     */
    @SuppressLint("CustomX509TrustManager")
    private class Pinned(private val fingerprint: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("not a server")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            val hex = digest.joinToString("") { "%02x".format(it) }
            if (!hex.equals(fingerprint, ignoreCase = true)) {
                throw CertificateException("this is not the linked PC")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
