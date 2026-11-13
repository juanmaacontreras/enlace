package com.enlace.app.core

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds the PC again when its address changed: one UDP broadcast naming the
 * PC's id, answered only by that PC. The answer is not trusted by itself; the
 * TLS certificate pinned at pairing is what proves it is the same machine.
 */
object Discovery {
    fun find(pcId: String, port: Int, timeoutMs: Int = 1500): InetSocketAddress? {
        val ask = Messages.discoveryAsk(pcId).toString().toByteArray()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs
                for (target in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(ask, ask.size, target, port)) }
                }
                val buffer = ByteArray(1024)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (e: SocketTimeoutException) { break }
                    val reply = runCatching {
                        JSONObject(String(packet.data, 0, packet.length))
                    }.getOrNull() ?: continue
                    if (reply.optString("pc") == pcId) {
                        return InetSocketAddress(packet.address, reply.optInt("port", port))
                    }
                }
            }
        } catch (e: Exception) {
            // No network: there is nothing to find.
        }
        return null
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val found = LinkedHashSet<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                nic.interfaceAddresses.mapNotNullTo(found) { it.broadcast }
            }
        }
        found.add(InetAddress.getByName("255.255.255.255"))
        return found.toList()
    }
}
