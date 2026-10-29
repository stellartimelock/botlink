package org.stellartimelock.botlink.protocol

import java.security.MessageDigest
import kotlin.math.round

/**
 * FlipSend relay wire, copied from the phone client (join + SSND frames)
 * and the live host wss://stellar-send-relay.onrender.com/v1.
 *
 * Join is one text message. The server answers with a quota meter.
 * Notes ride after that as binary SSND frames (type Service). The payload
 * is ciphertext only. Bot Link's room hash stays domain-separated, so this
 * does not join FlipSend's file room.
 */
object Relay {
    const val VERSION: Int = 1
    const val HEADER: Int = 12
    const val MAX_FRAME: Int = 262144
    const val SERVICE: Int = 10
    const val URL: String = "wss://stellar-send-relay.onrender.com/v1"
    const val ROLE_LISTEN: String = "listen"
    const val ROLE_DIAL: String = "dial"
    const val PACKAGE: String = "org.stellartimelock.botlink"

    const val PLAN_PRO: String = "relay_pro"
    const val PLAN_PLUS: String = "relay_plus"
    const val PLAN_MAX: String = "relay_max"
    const val PLAN_ELITE: String = "elite"

    const val PRICE_PRO: String = "$5"
    const val PRICE_PLUS: String = "$10"
    const val PRICE_MAX: String = "$15"

    const val BYTES_PER_GB: Long = 1_000_000_000L
    const val FREE_BYTES: Long = 100_000_000L
    const val ELITE_BYTES: Long = BYTES_PER_GB
    const val PRO_BYTES: Long = 5L * BYTES_PER_GB
    const val PLUS_BYTES: Long = 10L * BYTES_PER_GB
    const val MAX_BYTES: Long = 15L * BYTES_PER_GB
    const val FAIR_USE_BYTES: Long = 50_000_000_000L

    const val QUOTA_MESSAGE: String =
        "This month's relay data is used up. Use the same Wi-Fi. Local transfers stay unlimited."

    /** x402 is on. The wallet signs; Bot Link never holds keys. */
    const val X402_ENABLED: Boolean = X402.ENABLED
    const val SPEND_ACTION_CENTS: Int = 100
    const val SPEND_MONTH_CENTS: Int = 500

    val UNLIMITED_EMAILS: Set<String> = setOf(
        "stellartimelock@gmail.com",
    )

    val PLANS: List<Plan> = listOf(
        Plan(PLAN_PRO, "Pro", PRICE_PRO, PRO_BYTES),
        Plan(PLAN_PLUS, "Plus", PRICE_PLUS, PLUS_BYTES),
        Plan(PLAN_MAX, "Max", PRICE_MAX, MAX_BYTES),
    )

    data class Plan(val id: String, val name: String, val price: String, val bytes: Long)

    data class Credential(
        val purchaseToken: String? = null,
        val productId: String? = null,
        val basePlanId: String? = null,
        val packageName: String? = null,
        val entitlementKey: String? = null,
        val email: String? = null,
        val elite: Boolean = false,
    )

    data class Allowance(
        val tier: String,
        val limit: Long,
        val enforced: Boolean,
    ) {
        val unlimited: Boolean get() = !enforced
    }

    data class Meter(
        val ok: Boolean,
        val error: String? = null,
        val message: String? = null,
        val tier: String = "free",
        val used: Long = 0,
        val limit: Long = 0,
        val enforced: Boolean = false,
        val resets: String? = null,
    )

    data class Frame(val type: Int, val payload: ByteArray)

    fun quotaKey(secret: ByteArray): String = sha256Hex("botlink.quota.v1|".toByteArray() + secret)

    fun allowance(email: String?, elite: Boolean, productId: String?): Allowance {
        val normalized = email?.trim()?.lowercase()
        if (normalized != null && normalized in UNLIMITED_EMAILS) {
            return Allowance(tier = "unlimited", limit = 0, enforced = false)
        }
        return when (productId) {
            PLAN_MAX -> Allowance("max", MAX_BYTES, true)
            PLAN_PLUS -> Allowance("plus", PLUS_BYTES, true)
            PLAN_PRO -> Allowance("pro", PRO_BYTES, true)
            else -> if (elite) Allowance("elite", ELITE_BYTES, true)
            else Allowance("free", FREE_BYTES, true)
        }
    }

    fun joinMessage(room: String, role: String, credential: Credential = Credential()): String {
        val body = LinkedHashMap<String, Any>()
        body["v"] = VERSION
        body["room"] = room
        body["role"] = role
        writeCredential(body, credential)
        return encodeObject(body)
    }

    fun usageMessage(credential: Credential = Credential()): String {
        val body = LinkedHashMap<String, Any>()
        body["v"] = VERSION
        body["op"] = "usage"
        writeCredential(body, credential)
        return encodeObject(body)
    }

    fun encodeFrame(type: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        require(type in 1..12) { "Unknown message type." }
        require(payload.size <= MAX_FRAME) { "Frame payload exceeds the limit." }
        val out = ByteArray(HEADER + payload.size)
        out[0] = 'S'.code.toByte()
        out[1] = 'S'.code.toByte()
        out[2] = 'N'.code.toByte()
        out[3] = 'D'.code.toByte()
        out[4] = VERSION.toByte()
        out[5] = type.toByte()
        out[6] = ((flags ushr 8) and 0xff).toByte()
        out[7] = (flags and 0xff).toByte()
        val length = payload.size
        out[8] = ((length ushr 24) and 0xff).toByte()
        out[9] = ((length ushr 16) and 0xff).toByte()
        out[10] = ((length ushr 8) and 0xff).toByte()
        out[11] = (length and 0xff).toByte()
        payload.copyInto(out, HEADER)
        return out
    }

    fun decodeFrame(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER) return null
        if (bytes[0] != 'S'.code.toByte() || bytes[1] != 'S'.code.toByte() ||
            bytes[2] != 'N'.code.toByte() || bytes[3] != 'D'.code.toByte()
        ) {
            return null
        }
        if (bytes[4].toInt() != VERSION) return null
        val type = bytes[5].toInt() and 0xff
        if (type !in 1..12) return null
        val flags = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        if (flags != 0) return null
        val length = ((bytes[8].toInt() and 0xff) shl 24) or
            ((bytes[9].toInt() and 0xff) shl 16) or
            ((bytes[10].toInt() and 0xff) shl 8) or
            (bytes[11].toInt() and 0xff)
        if (length > MAX_FRAME || bytes.size != HEADER + length) return null
        return Frame(type, bytes.copyOfRange(HEADER, bytes.size))
    }

    fun parseMeter(json: String): Meter? {
        val text = json.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val ok = booleanField(text, "ok") ?: return null
        return Meter(
            ok = ok,
            error = stringField(text, "error"),
            message = stringField(text, "message"),
            tier = stringField(text, "tier") ?: "free",
            used = longField(text, "used") ?: 0L,
            limit = longField(text, "limit") ?: 0L,
            enforced = booleanField(text, "enforced") ?: false,
            resets = stringField(text, "resets"),
        )
    }

    fun overCap(meter: Meter?): Boolean =
        meter != null && meter.enforced && meter.limit > 0 && meter.used >= meter.limit

    fun blocked(meter: Meter?, policy: Allowance): Boolean {
        if (meter?.error == "quota") return true
        if (overCap(meter)) return true
        val used = meter?.used ?: 0L
        return policy.enforced && policy.limit > 0 && used >= policy.limit
    }

    fun quotaNotice(meter: Meter?, policy: Allowance): String {
        if (meter?.error == "quota") return meter.message ?: QUOTA_MESSAGE
        val limit = when {
            meter != null && meter.limit > 0 && policy.enforced -> minOf(meter.limit, policy.limit)
            meter != null && meter.limit > 0 -> meter.limit
            else -> policy.limit
        }
        if (!policy.enforced && (meter == null || !meter.enforced)) return "Unlimited"
        if (limit >= FAIR_USE_BYTES) {
            return "Relay transfers are paused. This account has used its ${formatGb(limit)} relay cap for this month. It resets on the 1st. Same-Wi-Fi and USB transfers keep working."
        }
        return QUOTA_MESSAGE
    }

    fun usageLabel(used: Long, limit: Long): String {
        if (limit <= 0) return "Usage appears after the relay answers."
        return "${formatGb(used)} of ${formatGb(limit)} relay this month"
    }

    fun formatGb(bytes: Long): String {
        val n = if (bytes < 0) 0.0 else bytes.toDouble()
        val gb = round((n / BYTES_PER_GB.toDouble()) * 100.0) / 100.0
        val text = if (gb == gb.toLong().toDouble()) gb.toLong().toString() else {
            val raw = ((gb * 100.0).toLong() / 100.0).toString()
            raw.trimEnd('0').trimEnd('.')
        }
        return "$text GB"
    }

    fun planLine(policy: Allowance): String = when (policy.tier) {
        "unlimited" -> "Unlimited"
        "elite" -> "Elite · 1 GB/mo"
        "pro" -> "Pro · $5/mo"
        "plus" -> "Plus · $10/mo"
        "max" -> "Max · $15/mo"
        else -> "Free"
    }

    fun usageLine(meter: Meter?, policy: Allowance): String {
        if (policy.unlimited && (meter == null || !meter.enforced)) return "Unlimited"
        val limit = when {
            meter != null && meter.limit > 0L && policy.enforced -> minOf(meter.limit, policy.limit)
            meter != null && meter.limit > 0L -> meter.limit
            policy.enforced -> policy.limit
            else -> return "Unlimited"
        }
        return usageLabel(meter?.used ?: 0L, limit)
    }

    /**
     * Within the cap, build an x402 + SEP-7 signing request.
     * The phone still has to Approve. The wallet signs. Bot Link holds no keys.
     */
    fun spendDecision(
        agent: String,
        cents: Int,
        spentThisMonth: Int,
        noteId: String = "",
        payTo: String = "",
        network: String = X402.NETWORK_PUBNET,
    ): SpendResult {
        if (agent.isBlank()) return SpendResult(false, "cap", "Name the agent.")
        if (cents <= 0) return SpendResult(false, "cap", "Enter an amount.")
        if (cents > SPEND_ACTION_CENTS) {
            return SpendResult(false, "cap", "Cap is \$1 a call.")
        }
        if (spentThisMonth + cents > SPEND_MONTH_CENTS) {
            return SpendResult(false, "cap", "Cap is \$5 an agent each month.")
        }
        if (!X402.isGAddress(payTo)) {
            return SpendResult(
                false,
                "pay_to",
                "Set the house Stellar G-address. x402 will not pay a blank destination.",
            )
        }
        val id = noteId.ifBlank { "pending" }
        return SpendResult(
            ok = true,
            code = "x402_sign",
            message = "Within the cap. Approve on the phone, then StellarTimeLock signs. Bot Link never holds keys.",
            allowedCents = cents,
            request = X402.signRequest(id, cents, payTo, network, agent),
        )
    }

    data class SpendResult(
        val ok: Boolean,
        val code: String,
        val message: String,
        val allowedCents: Int = 0,
        val request: X402.SignRequest? = null,
    )

    private fun writeCredential(body: LinkedHashMap<String, Any>, credential: Credential) {
        credential.purchaseToken?.takeIf { it.isNotBlank() }?.let { body["purchaseToken"] = it }
        credential.productId?.takeIf { it.isNotBlank() }?.let { body["productId"] = it }
        credential.basePlanId?.takeIf { it.isNotBlank() }?.let { body["basePlanId"] = it }
        credential.packageName?.takeIf { it.isNotBlank() }?.let { body["packageName"] = it }
        credential.entitlementKey?.takeIf { it.isNotBlank() }?.let { body["entitlementKey"] = it }
        val proof = accountProof(credential) ?: return
        body["accountProof"] = proof
    }

    private fun accountProof(credential: Credential): LinkedHashMap<String, Any>? {
        val proof = LinkedHashMap<String, Any>()
        proof["v"] = VERSION
        val email = credential.email?.trim()?.lowercase()
        var any = false
        if (!email.isNullOrBlank()) {
            proof["email"] = email
            any = true
        }
        if (credential.elite) {
            proof["elite"] = true
            any = true
        }
        credential.productId?.takeIf { it.isNotBlank() }?.let {
            proof["productId"] = it
            any = true
        }
        return if (any) proof else null
    }

    private fun encodeObject(body: Map<String, Any>): String =
        body.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            jsonString(key) + ":" + encodeValue(value)
        }

    private fun encodeValue(value: Any): String = when (value) {
        is String -> jsonString(value)
        is Int -> value.toString()
        is Long -> value.toString()
        is Boolean -> if (value) "true" else "false"
        is Map<*, *> -> encodeObject(value.entries.associate { it.key.toString() to it.value!! })
        else -> jsonString(value.toString())
    }

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun stringField(json: String, name: String): String? {
        val key = "\"$name\""
        val at = json.indexOf(key)
        if (at < 0) return null
        var i = at + key.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        if (json.startsWith("null", i)) return null
        if (json[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                out.append(json[i + 1])
                i += 2
                continue
            }
            if (ch == '"') return out.toString()
            out.append(ch)
            i++
        }
        return null
    }

    private fun booleanField(json: String, name: String): Boolean? {
        val raw = rawField(json, name) ?: return null
        return when (raw) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun longField(json: String, name: String): Long? =
        rawField(json, name)?.toLongOrNull()

    private fun rawField(json: String, name: String): String? {
        val key = "\"$name\""
        val at = json.indexOf(key)
        if (at < 0) return null
        var i = at + key.length
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && json[i] != ',' && json[i] != '}') i++
        return json.substring(start, i).trim()
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
