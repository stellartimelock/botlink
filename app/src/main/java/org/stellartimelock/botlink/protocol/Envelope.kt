package org.stellartimelock.botlink.protocol

import org.json.JSONObject
import java.util.UUID

enum class NoteKind {
    ASK,
    INFO,
    ACT,
    DECISION,
    NOTE,
    SUMMARY,
    ;

    companion object {
        fun parse(raw: String?): NoteKind = when (raw?.lowercase()) {
            "ask" -> ASK
            "act" -> ACT
            "decision" -> DECISION
            "note" -> NOTE
            "summary" -> SUMMARY
            else -> INFO
        }
    }

    fun wire(): String = name.lowercase()
}

enum class NoteStatus {
    PENDING,
    HELD,
    APPROVED,
    DENIED,
    SENT,
    REJECTED_SEED,
}

data class BotNote(
    val id: String,
    val from: String,
    val kind: NoteKind,
    val text: String,
    val wantsAction: Boolean,
    val createdAt: Long,
    val status: NoteStatus,
    val decisionAt: Long? = null,
    val ref: String? = null,
    val direction: Direction,
    val html: String? = null,
    val cents: Int? = null,
    val x402: String? = null,
    val sep7: String? = null,
    val signedXdr: String? = null,
    val paid: Boolean = false,
) {
    enum class Direction { IN, OUT }

    fun toPlainJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", id)
        .put("from", from)
        .put("kind", kind.wire())
        .put("text", text)
        .put("wantsAction", wantsAction)
        .put("createdAt", createdAt)
        .put("status", status.name.lowercase())
        .put("decisionAt", decisionAt)
        .put("ref", ref)
        .put("direction", direction.name.lowercase())
        .apply {
            if (!html.isNullOrBlank()) put("html", html)
            if (cents != null && cents > 0) put("cents", cents)
            if (!x402.isNullOrBlank()) put("x402", JSONObject(x402))
            if (!sep7.isNullOrBlank()) put("sep7", sep7)
            if (!signedXdr.isNullOrBlank()) put("signedXdr", signedXdr)
            if (paid) put("paid", true)
        }

    companion object {
        const val MAX_CHARS = 2000
        const val MAX_BLURB = 280
        const val MAX_HTML = 100_000

        fun newId(): String = UUID.randomUUID().toString()

        fun fromPlainJson(obj: JSONObject, fallbackDirection: Direction): BotNote {
            val status = when (obj.optString("status").lowercase()) {
                "approved" -> NoteStatus.APPROVED
                "denied" -> NoteStatus.DENIED
                "held" -> NoteStatus.HELD
                "sent" -> NoteStatus.SENT
                "rejected_seed" -> NoteStatus.REJECTED_SEED
                else -> NoteStatus.PENDING
            }
            val direction = when (obj.optString("direction").lowercase()) {
                "out" -> Direction.OUT
                "in" -> Direction.IN
                else -> fallbackDirection
            }
            return BotNote(
                id = obj.optString("id").ifBlank { newId() },
                from = obj.optString("from").ifBlank { "unknown" },
                kind = NoteKind.parse(obj.optString("kind")),
                text = obj.optString("text"),
                wantsAction = obj.optBoolean("wantsAction", false),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                status = status,
                decisionAt = obj.optLong("decisionAt").takeIf { it > 0L },
                ref = obj.optString("ref").takeIf { it.isNotBlank() },
                direction = direction,
                html = obj.optString("html").takeIf { it.isNotBlank() }?.take(MAX_HTML),
                cents = obj.optInt("cents").takeIf { it > 0 },
                x402 = obj.optJSONObject("x402")?.toString(),
                sep7 = obj.optString("sep7").takeIf { it.isNotBlank() },
                signedXdr = obj.optString("signedXdr").takeIf { it.isNotBlank() }
                    ?: obj.optString("xdr").takeIf { it.isNotBlank() },
                paid = obj.optBoolean("paid", false),
            )
        }
    }
}

object SeedGuard {
    private val seedKinds = setOf("seed", "mnemonic", "secret", "secret-seed", "recovery")
    private val word = Regex("[a-z]+")

    fun looksLikeSeed(kind: NoteKind, fromKindWire: String?, text: String): Boolean {
        if (fromKindWire != null && fromKindWire.lowercase() in seedKinds) return true
        if (kind == NoteKind.ACT && text.lowercase().contains("secret seed")) return true
        val words = word.findAll(text.lowercase()).map { it.value }.toList()
        if (words.size in listOf(12, 15, 18, 21, 24) && words.all { it.length in 3..8 }) {
            return true
        }
        return false
    }
}
