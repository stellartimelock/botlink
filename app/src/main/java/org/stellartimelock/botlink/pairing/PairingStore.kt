package org.stellartimelock.botlink.pairing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.stellartimelock.botlink.crypto.NoteCrypto
import java.security.SecureRandom

data class Pairing(
    val secret: ByteArray,
    val displayCode: String,
    val roomId: String,
    val aesKey: ByteArray,
) {
    val secretHex: String get() = NoteCrypto.hex(secret)
}

class PairingStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "botlink.pairing",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun current(): Pairing {
        val existing = prefs.getString(KEY_SECRET, null)
        val secret = if (existing.isNullOrBlank()) newSecret().also { persist(it) }
        else NoteCrypto.parseHex(existing)
        return materialize(secret)
    }

    fun rotate(): Pairing {
        val secret = newSecret()
        persist(secret)
        prefs.edit().putBoolean(KEY_ACTIONS, false).apply()
        return materialize(secret)
    }

    fun actionsArmed(): Boolean = prefs.getBoolean(KEY_ACTIONS, false)

    fun setActionsArmed(armed: Boolean) {
        prefs.edit().putBoolean(KEY_ACTIONS, armed).apply()
    }

    private fun persist(secret: ByteArray) {
        prefs.edit().putString(KEY_SECRET, NoteCrypto.hex(secret)).apply()
    }

    private fun materialize(secret: ByteArray) = Pairing(
        secret = secret,
        displayCode = displayCode(secret),
        roomId = NoteCrypto.roomId(secret),
        aesKey = NoteCrypto.aesKey(secret),
    )

    companion object {
        private const val KEY_SECRET = "pairing_secret_hex"
        private const val KEY_ACTIONS = "actions_armed"
        const val LAN_PORT = 18766
        const val COMPANION_HTTP = 18765
        const val PUBLIC_RELAY = org.stellartimelock.botlink.protocol.Relay.URL

        fun newSecret(): ByteArray = ByteArray(10).also { SecureRandom().nextBytes(it) }

        fun displayCode(secret: ByteArray): String {
            val b32 = crockford(secret)
            return "BL-" + b32.chunked(4).joinToString("-")
        }

        fun parseDisplayOrHex(input: String): ByteArray {
            val trimmed = input.trim().uppercase().replace(" ", "")
            if (trimmed.startsWith("BL")) {
                val body = trimmed.removePrefix("BL-").removePrefix("BL").replace("-", "")
                return fromCrockford(body)
            }
            return NoteCrypto.parseHex(trimmed)
        }

        private val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        private fun crockford(data: ByteArray): String {
            var buffer = 0
            var bits = 0
            val out = StringBuilder()
            for (b in data) {
                buffer = (buffer shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    out.append(ALPHABET[(buffer shr bits) and 31])
                }
            }
            if (bits > 0) {
                out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
            }
            return out.toString()
        }

        private fun fromCrockford(text: String): ByteArray {
            var buffer = 0
            var bits = 0
            val bytes = ArrayList<Byte>()
            for (ch in text) {
                val idx = ALPHABET.indexOf(ch)
                require(idx >= 0) { "bad pairing character: $ch" }
                buffer = (buffer shl 5) or idx
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    bytes.add(((buffer shr bits) and 0xff).toByte())
                }
            }
            return bytes.toByteArray()
        }
    }
}
