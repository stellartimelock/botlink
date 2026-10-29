package org.stellartimelock.botlink.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.stellartimelock.botlink.crypto.NoteCrypto
import org.stellartimelock.botlink.data.NoteRepository
import org.stellartimelock.botlink.pairing.PairingStore
import org.stellartimelock.botlink.protocol.BotNote
import org.stellartimelock.botlink.protocol.NoteKind
import org.stellartimelock.botlink.protocol.NoteStatus
import org.stellartimelock.botlink.protocol.Relay
import org.stellartimelock.botlink.protocol.SeedGuard
import org.stellartimelock.botlink.update.ApkProtocol
import org.stellartimelock.botlink.update.ApkStore
import java.util.concurrent.atomic.AtomicReference

data class LinkStatus(
    val lanDetail: String = "LAN idle",
    val lanPeer: Boolean = false,
    val relayDetail: String = "Relay idle",
    val relayUp: Boolean = false,
    val lastError: String? = null,
    val ipv4: String? = null,
    val relayTier: String = "",
    val relayUsed: Long = 0,
    val relayLimit: Long = 0,
    val relayEnforced: Boolean = false,
    val planTier: String = "free",
    val planLimit: Long = org.stellartimelock.botlink.protocol.Relay.FREE_BYTES,
    val planEnforced: Boolean = true,
)

class LinkHub(
    context: Context,
    private val pairingStore: PairingStore,
    private val notes: NoteRepository,
) {
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val lan = LanHost(
        pairing = { pairingStore.current() },
        onEnvelope = { ct -> onCiphertext(ct) },
        onPeer = { up, detail ->
            post {
                _status.value = _status.value.copy(
                    lanPeer = up,
                    lanDetail = detail,
                    ipv4 = LanHost.firstIpv4(),
                )
            }
        },
    )

    private val credential = AtomicReference(Relay.Credential())

    private val relay = RelayClient(
        pairing = { pairingStore.current() },
        credential = { credential.get() },
        onEnvelope = { ct -> onCiphertext(ct) },
        onStatus = { up, detail, meter ->
            post {
                _status.value = _status.value.copy(
                    relayUp = up,
                    relayDetail = detail,
                    relayTier = meter?.tier ?: _status.value.relayTier,
                    relayUsed = meter?.used ?: _status.value.relayUsed,
                    relayLimit = meter?.limit ?: _status.value.relayLimit,
                    relayEnforced = meter?.enforced ?: _status.value.relayEnforced,
                    lastError = if (!up && meter?.error == "quota") detail else _status.value.lastError,
                )
            }
        },
    )

    fun setCredential(next: Relay.Credential) {
        val previous = credential.getAndSet(next)
        val policy = Relay.allowance(next.email, next.elite, next.productId)
        post {
            _status.value = _status.value.copy(
                planTier = policy.tier,
                planLimit = policy.limit,
                planEnforced = policy.enforced,
            )
        }
        if (running && previous != next) relay.nudge()
    }

    @Volatile
    var apkStore: ApkStore? = null

    @Volatile
    var running: Boolean = false
        private set

    fun start() {
        if (running) {
            post { _status.value = _status.value.copy(ipv4 = LanHost.firstIpv4()) }
            return
        }
        running = true
        lan.start()
        relay.start()
        post { _status.value = _status.value.copy(ipv4 = LanHost.firstIpv4()) }
    }

    fun stop() {
        running = false
        lan.stop()
        relay.stop()
    }

    fun sendNote(text: String): BotNote? {
        val clean = text.trim().take(BotNote.MAX_CHARS)
        if (clean.isEmpty()) return null
        if (SeedGuard.looksLikeSeed(NoteKind.NOTE, "note", clean)) {
            val blocked = BotNote(
                id = BotNote.newId(),
                from = "phone",
                kind = NoteKind.NOTE,
                text = "Blocked: this channel does not carry seeds.",
                wantsAction = false,
                createdAt = System.currentTimeMillis(),
                status = NoteStatus.REJECTED_SEED,
                direction = BotNote.Direction.OUT,
            )
            notes.upsert(blocked)
            return blocked
        }
        val note = BotNote(
            id = BotNote.newId(),
            from = "phone",
            kind = NoteKind.NOTE,
            text = clean,
            wantsAction = false,
            createdAt = System.currentTimeMillis(),
            status = NoteStatus.SENT,
            direction = BotNote.Direction.OUT,
        )
        notes.upsert(note)
        push(note)
        return note
    }

    fun approve(id: String, approved: Boolean): BotNote? {
        if (approved && !pairingStore.actionsArmed()) {
            post { _status.value = _status.value.copy(lastError = "Turn on Allow bot actions first.") }
            return null
        }
        val updated = notes.decide(id, approved) ?: return null
        val decision = updated.copy(
            kind = NoteKind.DECISION,
            from = "phone",
            text = if (approved) "approved" else "denied",
            wantsAction = false,
            createdAt = System.currentTimeMillis(),
            status = updated.status,
            ref = updated.id,
            direction = BotNote.Direction.OUT,
            x402 = updated.x402,
            sep7 = updated.sep7,
            signedXdr = updated.signedXdr,
            paid = updated.paid,
        )
        notes.upsert(decision)
        push(decision)
        return updated
    }

    fun attachPayment(id: String, xdr: String): BotNote? {
        val current = notes.notes.value.find { it.id == id } ?: return null
        val paid = current.copy(signedXdr = xdr, paid = true)
        notes.upsert(paid)
        val decision = paid.copy(
            kind = NoteKind.DECISION,
            from = "phone",
            text = "approved",
            wantsAction = false,
            createdAt = System.currentTimeMillis(),
            status = NoteStatus.APPROVED,
            ref = paid.id,
            direction = BotNote.Direction.OUT,
            signedXdr = xdr,
            paid = true,
        )
        notes.upsert(decision)
        push(decision)
        return paid
    }

    fun pushJson(obj: JSONObject): Boolean {
        return try {
            val pairing = pairingStore.current()
            val ct = NoteCrypto.encryptRaw(pairing.aesKey, obj.toString().toByteArray())
            val lanOk = lan.sendEnvelope(ct)
            val relayOk = relay.sendEnvelope(ct)
            if (!lanOk && !relayOk) {
                post {
                    _status.value = _status.value.copy(
                        lastError = "Note stored, but no PC companion is on the pipe yet.",
                    )
                }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            post { _status.value = _status.value.copy(lastError = e.message) }
            false
        }
    }

    private fun push(note: BotNote) {
        pushJson(note.toPlainJson())
    }

    private fun onCiphertext(ct: ByteArray) {
        try {
            val plain = NoteCrypto.decryptRaw(pairingStore.current().aesKey, ct)
            val obj = JSONObject(String(plain, Charsets.UTF_8))
            if (ApkProtocol.isApkKind(obj.optString("kind"))) {
                apkStore?.onPlain(obj)
                return
            }
            val draft = BotNote.fromPlainJson(obj, BotNote.Direction.IN)
            if (draft.kind == NoteKind.SUMMARY || obj.optString("kind") == "summary") {
                acceptSummary(obj, draft)
                return
            }
            if (draft.kind == NoteKind.DECISION) {
                notes.upsert(draft.copy(direction = BotNote.Direction.IN))
                return
            }
            notes.acceptIncoming(obj.optString("kind"), draft, pairingStore.actionsArmed())
        } catch (e: Exception) {
            post { _status.value = _status.value.copy(lastError = "Dropped a bad envelope: ${e.message}") }
        }
    }

    private fun acceptSummary(obj: JSONObject, draft: BotNote) {
        val html = obj.optString("html").take(BotNote.MAX_HTML)
        val blurb = draft.text.trim().take(BotNote.MAX_BLURB)
        if (SeedGuard.looksLikeSeed(NoteKind.SUMMARY, "summary", blurb) ||
            SeedGuard.looksLikeSeed(NoteKind.SUMMARY, "summary", html.replace(Regex("<[^>]+>"), " "))
        ) {
            post { _status.value = _status.value.copy(lastError = "Blocked a summary that looked like a seed.") }
            return
        }
        if (blurb.isBlank() || html.isBlank()) return
        notes.upsert(
            draft.copy(
                kind = NoteKind.SUMMARY,
                text = blurb,
                html = html,
                wantsAction = false,
                status = NoteStatus.SENT,
                direction = BotNote.Direction.IN,
                cents = null,
            ),
        )
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    init {
        // Keep context referenced so a future ContentProvider handshake can use it.
        context.applicationContext
    }
}
