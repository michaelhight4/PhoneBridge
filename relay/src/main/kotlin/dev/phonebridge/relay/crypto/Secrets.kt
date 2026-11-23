package dev.phonebridge.relay.crypto

import dev.phonebridge.core.util.B64Url

/**
 * Shared secrets given on the command line or in [dev.phonebridge.relay.RelayConfig]: hex, base64url or
 * a plain passphrase. Both relays of a hand-over must read the same string, so the derivation is
 * deliberately simple and deterministic.
 */
internal object Secrets {
    fun decode(text: String): ByteArray {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "secret must not be empty" }
        if (isHex(trimmed)) return unhex(trimmed)
        return try {
            B64Url.decode(trimmed)
        } catch (_: IllegalArgumentException) {
            trimmed.toByteArray(Charsets.UTF_8)
        }
    }

    fun isHex(text: String): Boolean =
        text.isNotEmpty() && text.length % 2 == 0 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex string has an odd length" }
        return ByteArray(text.length / 2) { index ->
            ((hexValue(text[index * 2]) shl 4) or hexValue(text[index * 2 + 1])).toByte()
        }
    }

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: $character")
    }
}
