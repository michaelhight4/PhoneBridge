package dev.phonebridge.relay.crypto

/**
 * Crockford base32.
 *
 * Used for `peer_id` (base32 of the first 16 bytes of the public key hash, lowercase) and for the
 * 6 symbol pairing short code (`7K4-Q9M`), whose last symbol is a check symbol.
 *
 * The relay treats a pairing code as an opaque mailbox key: it normalizes the code (case,
 * separators, Crockford confusables) but does not reject a code whose check symbol is wrong, so a
 * device that later generates codes with a different check algorithm still interoperates. Use
 * [canonicalShortCode] when the check symbol must be enforced.
 */
object Base32 {
    /** Crockford alphabet, uppercase, without the ambiguous I, L, O and U. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** RFC 4648 base32 alphabet, used for `peer_id` (docs/PROTOCOL.md). */
    private const val RFC4648_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Crockford check symbols beyond the 32 data symbols. */
    private const val CHECK_SYMBOLS = "*~\$=^"

    fun encode(data: ByteArray): String = encodeWith(ALPHABET, data)

    /**
     * RFC 4648 base32, lowercase, no padding. `peer_id` uses this alphabet (docs/PROTOCOL.md), which
     * is deliberately different from the Crockford alphabet of the short pairing codes.
     */
    fun encodeRfc4648(data: ByteArray): String = encodeWith(RFC4648_ALPHABET, data)

    private fun encodeWith(alphabet: String, data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(alphabet[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) {
            out.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        }
        return out.toString().lowercase()
    }

    fun decode(text: String): ByteArray {
        val normalized = normalize(text) ?: throw IllegalArgumentException("not Crockford base32: $text")
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in normalized) {
            buffer = (buffer shl 5) or dataValue(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /**
     * Uppercases, strips separators and maps the Crockford confusables (O to 0, I and L to 1).
     * Returns null when the text contains characters outside the alphabet.
     */
    fun normalize(text: String): String? {
        val sb = StringBuilder(text.length)
        for (raw in text) {
            if (raw == '-' || raw == ' ' || raw == '\t' || raw == '_') continue
            val c = raw.uppercaseChar()
            when (c) {
                in '0'..'9' -> sb.append(c)
                'O' -> sb.append('0')
                'I', 'L' -> sb.append('1')
                in 'A'..'Z' -> {
                    if (ALPHABET.indexOf(c) < 0) return null
                    sb.append(c)
                }
                in CHECK_SYMBOLS -> sb.append(c)
                else -> return null
            }
        }
        val result = sb.toString()
        return result.ifEmpty { null }
    }

    /**
     * Canonical mailbox key for a short code: six normalized symbols in uppercase, formatted
     * `ABC-DEF`. Returns null for malformed input. The check symbol is not enforced here.
     */
    fun canonicalCode(text: String): String? {
        val normalized = normalize(text) ?: return null
        if (normalized.length != 6) return null
        return normalized.substring(0, 3) + "-" + normalized.substring(3)
    }

    /**
     * Crockford check symbol for five data symbols: the numeric value of the data modulo 31, which
     * always maps onto a non-ambiguous data symbol.
     */
    fun checkSymbol(dataSymbols: String): Char {
        val normalized = normalize(dataSymbols) ?: throw IllegalArgumentException("invalid code")
        var remainder = 0
        for (c in normalized) remainder = (remainder * 32 + dataValue(c)) % 31
        return ALPHABET[remainder]
    }

    /** Returns the canonical form when the trailing check symbol matches, else null. */
    fun canonicalShortCode(text: String): String? {
        val normalized = normalize(text) ?: return null
        if (normalized.length != 6) return null
        if (checkSymbol(normalized.substring(0, 5)) != normalized[5]) return null
        return normalized.substring(0, 3) + "-" + normalized.substring(3)
    }

    private fun dataValue(c: Char): Int {
        val idx = ALPHABET.indexOf(c)
        if (idx >= 0) return idx
        throw IllegalArgumentException("not a Crockford data symbol: $c")
    }
}
