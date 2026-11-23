package dev.phonebridge.core.identity

import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.util.Base32

/**
 * `peer_id` and fingerprint exactly as docs/PROTOCOL.md defines them (and as the relay implements
 * them):
 *
 * - `peer_id` = lowercase Crockford base32 (no padding) of the first 16 bytes of `SHA-256(public_key)`;
 *   26 characters from the alphabet `0-9a-z` minus `i`, `l`, `o`, `u`.
 * - fingerprint = the first 8 `peer_id` characters, uppercased and grouped `ABCD-EFGH`.
 */
object PeerIds {
    const val PEER_ID_BYTES = 16
    const val PEER_ID_CHARS = 26
    const val FINGERPRINT_CHARS = 8

    fun peerId(publicKey: ByteArray): String {
        require(publicKey.isNotEmpty()) { "public key must not be empty" }
        val digest = Primitives.sha256(publicKey)
        return Base32.encodeLower(digest.copyOf(PEER_ID_BYTES))
    }

    fun fingerprint(publicKey: ByteArray): String = fingerprintOf(peerId(publicKey))

    fun fingerprintOf(peerId: String): String = group(peerId.take(FINGERPRINT_CHARS))

    /** Uppercases and inserts the display dash: `abcdefgh` becomes `ABCD-EFGH`. */
    fun group(text: String): String = text.uppercase().chunked(4).joinToString("-")

    /** True for the canonical lowercase 26-character Crockford peer id alphabet. */
    fun isValidPeerId(peerId: String): Boolean =
        peerId.length == PEER_ID_CHARS && peerId.all { it in Base32.ALPHABET.lowercase() }

    /** True for a fingerprint with or without the dash, any case. */
    fun isValidFingerprint(text: String): Boolean {
        val bare = text.replace("-", "").replace(" ", "")
        if (bare.length != FINGERPRINT_CHARS) return false
        return bare.all { it.uppercaseChar() in Base32.ALPHABET }
    }
}
