package dev.phonebridge.core.crypto

/**
 * Proof that a peer owns the private key behind the `pub` it announced in `hello`.
 *
 * Noise has no signatures, and X25519 cannot sign, so the relay sends an ephemeral X25519 public key
 * with its nonce. The peer MACs the nonce with a key derived from `DH(peer_private, relay_public)`.
 * The relay can verify that value with `DH(relay_private, peer_public)` without ever learning the
 * peer's private key, and an attacker cannot produce it from the public key alone.
 */
object HelloAuth {
    const val SALT_TEXT = "PhoneBridge/1/hello"
    const val INFO_TEXT = "PhoneBridge/1/hello-proof"

    /** The peer side: authenticates `peerPublicKey` to the relay for one fresh nonce. */
    fun proof(
        peerPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        relayPublicKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray = macKey(X25519.sharedSecret(peerPrivateKey, relayPublicKey), nonce, peerPublicKey)

    /** The relay side: checks the proof the peer sent for its own public key. */
    fun verify(
        relayPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        nonce: ByteArray,
        proof: ByteArray,
    ): Boolean {
        val expected = macKey(X25519.sharedSecret(relayPrivateKey, peerPublicKey), nonce, peerPublicKey)
        return Primitives.constantTimeEquals(expected, proof)
    }

    private fun macKey(sharedSecret: ByteArray, nonce: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val key = Primitives.hkdf(
            ikm = sharedSecret,
            salt = SALT_TEXT.toByteArray(Charsets.US_ASCII),
            info = INFO_TEXT.toByteArray(Charsets.US_ASCII),
            length = Primitives.KEY_BYTES,
        )
        return Primitives.hmacSha256(key, nonce + peerPublicKey)
    }
}
