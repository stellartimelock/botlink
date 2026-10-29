package org.stellartimelock.botlink.net

import android.util.Log
import org.stellartimelock.botlink.pairing.Pairing
import org.stellartimelock.botlink.pairing.PairingStore
import org.stellartimelock.botlink.protocol.Relay
import org.stellartimelock.botlink.protocol.Wire
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Same-Wi-Fi pipe. A text hello, then the same SSND frames the relay carries.
 */
class LanHost(
    private val pairing: () -> Pairing,
    private val onEnvelope: (ciphertext: ByteArray) -> Unit,
    private val onPeer: (connected: Boolean, detail: String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var peer: Socket? = null
    private var output: OutputStream? = null
    @Volatile var localIpv4: String? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ loop() }, "botlink-lan").also { it.isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        try { peer?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        peer = null
        output = null
        server = null
    }

    @Synchronized
    fun sendEnvelope(ciphertext: ByteArray): Boolean {
        val out = output ?: return false
        return try {
            out.write(Relay.encodeFrame(Relay.SERVICE, ciphertext))
            out.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "LAN send failed", e)
            false
        }
    }

    fun isPeerUp(): Boolean = peer?.isConnected == true && output != null

    private fun loop() {
        while (running.get()) {
            try {
                localIpv4 = firstIpv4()
                val sock = ServerSocket(PairingStore.LAN_PORT)
                server = sock
                onPeer(false, "Listening on ${localIpv4 ?: "wifi"}:${PairingStore.LAN_PORT}")
                val client = sock.accept()
                if (!running.get()) {
                    client.close()
                    break
                }
                handleClient(client)
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "LAN listener", e)
                    onPeer(false, "LAN error: ${e.message ?: e.javaClass.simpleName}")
                    try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                }
            } finally {
                try { server?.close() } catch (_: Exception) {}
                server = null
            }
        }
    }

    private fun handleClient(client: Socket) {
        peer = client
        val input = client.getInputStream()
        val out = client.getOutputStream()
        output = out
        val expected = pairing()
        try {
            val first = readLine(input)
            if (first.isEmpty()) throw IllegalStateException("empty hello")
            val obj = Wire.parse(first) ?: throw IllegalStateException("bad hello")
            if (obj.optString("op") != "hello") throw IllegalStateException("expected hello")
            if (obj.optString("room") != expected.roomId) throw IllegalStateException("room mismatch")
            out.write(Wire.helloOk("phone", expected.roomId).toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            out.flush()
            onPeer(true, "PC companion on ${client.inetAddress.hostAddress}")
            while (running.get()) {
                val frame = readFrame(input) ?: break
                if (frame.type == Relay.SERVICE && frame.payload.isNotEmpty()) {
                    onEnvelope(frame.payload)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LAN peer dropped", e)
        } finally {
            output = null
            try { client.close() } catch (_: Exception) {}
            peer = null
            onPeer(false, "PC companion disconnected")
        }
    }

    companion object {
        private const val TAG = "BotLinkLan"

        fun firstIpv4(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            return null
        }

        private fun readLine(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return out.toString(StandardCharsets.UTF_8)
        }

        private fun readFrame(input: InputStream): Relay.Frame? {
            val header = ByteArray(Relay.HEADER)
            if (!readFully(input, header)) return null
            val length = ((header[8].toInt() and 0xff) shl 24) or
                ((header[9].toInt() and 0xff) shl 16) or
                ((header[10].toInt() and 0xff) shl 8) or
                (header[11].toInt() and 0xff)
            if (length < 0 || length > Relay.MAX_FRAME) return null
            val payload = ByteArray(length)
            if (length > 0 && !readFully(input, payload)) return null
            val all = ByteArray(header.size + payload.size)
            header.copyInto(all)
            payload.copyInto(all, header.size)
            return Relay.decodeFrame(all)
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }
    }
}
