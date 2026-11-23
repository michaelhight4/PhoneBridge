package dev.phonebridge.core.util

/**
 * Crockford base32 without padding, the one base32 alphabet PhoneBridge uses.
 *
 * `peer_id` is the lowercase form of `base32(SHA-256(public_key)[0..16])` (26 characters) and pairing
 * short codes use the uppercase form. Crockford skips the ambiguous `I`, `L`, `O` and `U`, which matches
 * the relay's implementation and keeps ids and codes typo-resistant.
 */
object Base32 {
    /** Crockford alphabet: no I, L, O or U. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    fun encodeLower(bytes: ByteArray): String = encode(bytes).lowercase()
}
