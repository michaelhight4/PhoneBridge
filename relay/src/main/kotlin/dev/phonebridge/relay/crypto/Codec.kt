package dev.phonebridge.relay.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Small, dependency-free encoding and hashing helpers.
 *
 * Everything here is available on Android API 26+ (`java.util.Base64`, `javax.crypto.Mac`,
 * `java.security.MessageDigest`), so the same code runs in the Android app, on the desktop and on a
 * plain JVM.
 */
object Codec {
    private val rng = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Constant-time comparison, safe against timing side channels. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun isAllZero(data: ByteArray): Boolean {
        var acc = 0
        for (b in data) acc = acc or b.toInt()
        return acc == 0
    }

    /** base64url without padding, as used in the protocol. */
    fun b64url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun b64urlDecode(text: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(text.trim())
    } catch (_: IllegalArgumentException) {
        null
    }

    fun hex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) sb.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        return sb.toString()
    }

    fun unhex(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex string has an odd length" }
        return ByteArray(clean.length / 2) { i ->
            ((hexValue(clean[i * 2]) shl 4) or hexValue(clean[i * 2 + 1])).toByte()
        }
    }

    /** Parses a shared secret given either as hex (64 chars) or base64url; returns raw bytes. */
    fun decodeSecret(text: String): ByteArray {
        val trimmed = text.trim()
        val isHex = trimmed.length % 2 == 0 && trimmed.isNotEmpty() &&
            trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (isHex) return unhex(trimmed)
        return b64urlDecode(trimmed) ?: throw IllegalArgumentException("secret must be hex or base64url")
    }

    private const val HEX = "0123456789abcdef"

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: $c")
    }
}
