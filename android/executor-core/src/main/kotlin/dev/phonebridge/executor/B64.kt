package dev.phonebridge.executor

import java.util.Base64

/**
 * Base64 for protocol fields.
 *
 * `docs/PROTOCOL.md` §1: binary values are **base64url without padding** on the wire, and decoders
 * accept standard `+`/`/`, padding and whitespace so values pasted from other tools still work.
 */
object B64 {

    /** Decodes base64url or standard base64, with or without padding and whitespace. */
    fun decodeLoose(value: String): ByteArray {
        val compact = value.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return ByteArray(0)
        val standard = compact.replace('-', '+').replace('_', '/')
        val padded = when (standard.length % 4) {
            0 -> standard
            2 -> "$standard=="
            3 -> "$standard="
            else -> throw IllegalArgumentException("invalid base64 length")
        }
        return try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64", e)
        }
    }

    /** Base64url without padding, as every binary field in the protocol is written. */
    fun encodeUrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
