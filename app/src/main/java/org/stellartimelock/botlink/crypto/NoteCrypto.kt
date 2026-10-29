package org.stellartimelock.botlink.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Domain-separated sidecar keys. FlipSend file rooms must never share this hash.
 *
 *   room = SHA-256("botlink.room.v1|" || pairing-secret)
 *   key  = SHA-256("botlink.key.v1|"  || pairing-secret)
 *   wire = base64(iv[12] || ciphertext || gcm-tag[16])
 */
object NoteCrypto {
    private const val ROOM_INFO = "botlink.room.v1|"
    private const val KEY_INFO = "botlink.key.v1|"

    fun roomId(secret: ByteArray): String = hex(sha256(ROOM_INFO.toByteArray() + secret))

    fun aesKey(secret: ByteArray): ByteArray = sha256(KEY_INFO.toByteArray() + secret)

    fun encrypt(key: ByteArray, plaintext: ByteArray): String =
        Base64.encodeToString(encryptRaw(key, plaintext), Base64.NO_WRAP)

    fun decrypt(key: ByteArray, wire: String): ByteArray =
        decryptRaw(key, Base64.decode(wire, Base64.NO_WRAP))

    /** iv[12] || AES-GCM ciphertext || tag[16]. This is the SSND payload. */
    fun encryptRaw(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val boxed = cipher.doFinal(plaintext)
        return iv + boxed
    }

    fun decryptRaw(key: ByteArray, raw: ByteArray): ByteArray {
        require(raw.size > 28) { "ciphertext too short" }
        val iv = raw.copyOfRange(0, 12)
        val boxed = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(boxed)
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

    fun parseHex(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        require(clean.length % 2 == 0) { "odd hex" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
