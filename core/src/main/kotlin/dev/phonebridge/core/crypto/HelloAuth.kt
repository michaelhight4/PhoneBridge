package dev.phonebridge.core.crypto

/**
 * Proof that a peer owns the private key behind the `pub` it announces in `hello`.
 *
 * This is the exact `dh-hmac-sha256` proof the relay implements (`relay/PROTOCOL-RELAY.md`):
 *
 * ```
 * shared  = X25519(peer_private, relay_public)          // relay_public is per-connection ephemeral
 * context = "phonebridge/hello/v1|<relay_id>|<b64url(relay_public)>|<peer_id>|<b64url(nonce)>"
 * sig     = HMAC-SHA256(key = shared, message = context)
 * ```
 *
 * Only the holder of the peer private key can compute it, it is bound to the relay's fresh ephemeral
 * key and nonce, and the relay verifies it with `X25519(relay_private, peer_public)`.
 */
object HelloAuth {
    const val LABEL = "phonebridge/hello/v1"

    /** Value of `challenge.proof` that selects this construction. */
    const val PROOF_METHOD = "dh-hmac-sha256"

    fun context(
        relayId: String,
        relayPublicKey: ByteArray,
        peerId: String,
        nonce: ByteArray,
    ): ByteArray = buildString {
        append(LABEL)
        append('|').append(relayId)
        append('|').append(dev.phonebridge.core.util.B64Url.encode(relayPublicKey))
        append('|').append(peerId)
        append('|').append(dev.phonebridge.core.util.B64Url.encode(nonce))
    }.toByteArray(Charsets.UTF_8)

    /** The peer side: authenticates `peerId`/`peerPublicKey` for one relay challenge. */
    fun proof(
        peerPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        relayPublicKey: ByteArray,
        relayId: String,
        peerId: String,
        nonce: ByteArray,
    ): ByteArray {
        val shared = X25519.sharedSecret(peerPrivateKey, relayPublicKey)
        return Primitives.hmacSha256(shared, context(relayId, relayPublicKey, peerId, nonce))
    }

    /** The relay side: checks the `hello.sig` the peer sent. */
    fun verify(
        relayPrivateKey: ByteArray,
        relayPublicKey: ByteArray,
        relayId: String,
        peerId: String,
        peerPublicKey: ByteArray,
        nonce: ByteArray,
        proof: ByteArray,
    ): Boolean {
        val shared = X25519.sharedSecret(relayPrivateKey, peerPublicKey)
        val expected = Primitives.hmacSha256(shared, context(relayId, relayPublicKey, peerId, nonce))
        return Primitives.constantTimeEquals(expected, proof)
    }
}
