package org.stellartimelock.botlink.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.stellartimelock.botlink.pairing.Pairing
import org.stellartimelock.botlink.protocol.Relay
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phone side of FlipSend's relay. Role is `listen`.
 * First frame is the join JSON. Notes are SSND service frames after the meter.
 */
class RelayClient(
    private val pairing: () -> Pairing,
    private val credential: () -> Relay.Credential,
    private val onEnvelope: (ciphertext: ByteArray) -> Unit,
    private val onStatus: (connected: Boolean, detail: String, meter: Relay.Meter?) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var joined = false
    private var meter: Relay.Meter? = null
    private var attempt = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        attempt = 0
        connect()
    }

    fun stop() {
        running.set(false)
        joined = false
        generation.incrementAndGet()
        socket?.close(1000, "stop")
        socket = null
    }

    fun nudge() {
        if (!running.get()) return
        generation.incrementAndGet()
        joined = false
        socket?.close(1000, "rejoin")
        socket = null
        connect()
    }

    fun sendEnvelope(ciphertext: ByteArray): Boolean {
        val ws = socket ?: return false
        if (!joined || blocked()) return false
        val frame = Relay.encodeFrame(Relay.SERVICE, ciphertext)
        return ws.send(frame.toByteString())
    }

    fun isUp(): Boolean = joined && socket != null && !blocked()

    private fun blocked(): Boolean {
        val cred = credential()
        val policy = Relay.allowance(cred.email, cred.elite, cred.productId)
        return Relay.blocked(meter, policy)
    }

    private fun connect() {
        if (!running.get()) return
        onStatus(false, "Connecting to FlipSend relay…", meter)
        val req = Request.Builder().url(Relay.URL).build()
        val gen = generation.incrementAndGet()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val pair = pairing()
                val cred = credential().copy(
                    packageName = Relay.PACKAGE,
                    entitlementKey = Relay.quotaKey(pair.secret),
                )
                webSocket.send(Relay.joinMessage(pair.roomId, Relay.ROLE_LISTEN, cred))
                armHandshake(webSocket, gen)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = Relay.parseMeter(text) ?: return
                meter = parsed
                if (!parsed.ok || parsed.error == "quota") {
                    joined = false
                    val policy = policy()
                    onStatus(false, Relay.quotaNotice(parsed, policy), parsed)
                    webSocket.close(1000, "quota")
                    return
                }
                joined = true
                attempt = 0
                generation.incrementAndGet()
                val policy = policy()
                val detail = if (Relay.blocked(parsed, policy)) {
                    Relay.quotaNotice(parsed, policy)
                } else {
                    "Relay up · ${Relay.usageLine(parsed, policy)}"
                }
                onStatus(!Relay.blocked(parsed, policy), detail, parsed)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val frame = Relay.decodeFrame(bytes.toByteArray()) ?: return
                if (frame.type == Relay.SERVICE && frame.payload.isNotEmpty()) {
                    onEnvelope(frame.payload)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                socket = null
                joined = false
                onStatus(false, "Relay closed ($code)", meter)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket && socket != null) return
                socket = null
                joined = false
                onStatus(false, "Relay down: ${t.message ?: t.javaClass.simpleName}", meter)
                scheduleReconnect()
            }
        })
    }

    private fun policy(): Relay.Allowance {
        val cred = credential()
        return Relay.allowance(cred.email, cred.elite, cred.productId)
    }

    private fun armHandshake(webSocket: WebSocket, gen: Int) {
        Thread({
            try {
                Thread.sleep(HANDSHAKE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (running.get() && generation.get() == gen && !joined) {
                webSocket.close(1000, "handshake")
            }
        }, "botlink-relay-hs").also { it.isDaemon = true }.start()
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = DELAYS[attempt.coerceAtMost(DELAYS.lastIndex)]
        if (attempt < DELAYS.lastIndex) attempt += 1
        Thread({
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (running.get() && socket == null) connect()
        }, "botlink-relay-re").also { it.isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "BotLinkRelay"
        private const val HANDSHAKE_MS = 20_000L
        private val DELAYS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 15_000L, 20_000L)

        init {
            Log.d(TAG, "relay target ${Relay.URL}")
        }
    }
}
