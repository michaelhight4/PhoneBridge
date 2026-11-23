package dev.phonebridge.core.util

/**
 * RFC 4648 base32 without padding. PhoneBridge uses the lowercase form for `peer_id` values and the
 * uppercase form for the human-facing fingerprint.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
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
