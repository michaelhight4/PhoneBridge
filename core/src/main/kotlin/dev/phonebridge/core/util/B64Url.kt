package dev.phonebridge.core.util

import java.util.Base64

/**
 * Base64url without padding, the only binary encoding used on the PhoneBridge wire.
 *
 * Encoding always emits the URL-safe alphabet with `-`/`_` and no `=` padding. Decoding is tolerant:
 * standard `+`/`/`, padding and embedded whitespace are accepted so links pasted from other tools work.
 */
object B64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray {
        val cleaned = buildString(text.length) {
            for (ch in text) {
                when {
                    ch.isWhitespace() -> Unit
                    ch == '-' -> append('+')
                    ch == '_' -> append('/')
                    ch == '=' -> Unit
                    else -> append(ch)
                }
            }
        }
        val padded = when (cleaned.length % 4) {
            0 -> cleaned
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> throw IllegalArgumentException("invalid base64url length ${cleaned.length}")
        }
        return Base64.getDecoder().decode(padded)
    }

    fun encodeText(text: String): String = encode(text.toByteArray(Charsets.UTF_8))

    fun decodeText(text: String): String = String(decode(text), Charsets.UTF_8)
}
