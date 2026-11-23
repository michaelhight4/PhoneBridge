package dev.phonebridge.relay.crypto

/**
 * A long-term X25519 key pair for a device, a bot or the relay itself.
 *
 * `peer_id` follows docs/PROTOCOL.md: Crockford base32 of the first 16 bytes of SHA-256(public key),
 * lowercase, 26 characters. The short fingerprint shown to users is the first 8 characters grouped
 * `ABCD-EFGH`.
 */
class PeerKeyPair private constructor(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    val peerId: String get() = PeerId.of(publicKey)

    /** `ABCD-EFGH` form shown in the UI next to a bot/device name. */
    val fingerprint: String get() = PeerId.fingerprint(publicKey)

    /** Public key as base64url, the wire form used in `hello`. */
    fun publicKeyB64(): String = Codec.b64url(publicKey)

    companion object {
        fun generate(): PeerKeyPair {
            var privateKey: ByteArray
            var publicKey: ByteArray
            do {
                privateKey = Codec.randomBytes(32)
                publicKey = X25519.publicKey(privateKey)
            } while (Codec.isAllZero(publicKey))
            return PeerKeyPair(privateKey, publicKey)
        }

        fun fromPrivateKey(privateKey: ByteArray): PeerKeyPair {
            require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
            return PeerKeyPair(privateKey.copyOf(), X25519.publicKey(privateKey))
        }
    }
}

object PeerId {
    /** peer_id = base32(sha256(public key)[0..16]). 16 bytes are 26 base32 characters. */
    fun of(publicKey: ByteArray): String {
        require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
        val digest = Codec.sha256(publicKey)
        return Base32.encode(digest.copyOfRange(0, 16))
    }

    /** True when [peerId] is the id derived from [publicKey] (case insensitive). */
    fun matches(peerId: String, publicKey: ByteArray): Boolean =
        peerId.lowercase() == of(publicKey)

    fun fingerprint(publicKey: ByteArray): String {
        val id = of(publicKey).uppercase()
        return id.substring(0, 4) + "-" + id.substring(4, 8)
    }
}

/**
 * Proof that a peer holds the private key for the public key it claims, bound to a relay nonce.
 *
 * The proof is an HMAC-SHA256 keyed with the X25519 shared secret between the peer long-term key and
 * the relay per-connection ephemeral key, over the full transcript (relay id, ephemeral relay key,
 * peer id and nonce). Only the holder of the private key can compute it, and it cannot be replayed on
 * another connection because the relay ephemeral key and the nonce are both fresh.
 */
object HelloProof {
    private const val LABEL = "phonebridge/hello/v1"

    fun context(relayId: String, relayPublic: ByteArray, peerId: String, nonce: ByteArray): ByteArray =
        "$LABEL|$relayId|${Codec.b64url(relayPublic)}|$peerId|${Codec.b64url(nonce)}"
            .toByteArray(Charsets.UTF_8)

    fun compute(
        peerPrivateKey: ByteArray,
        relayPublicKey: ByteArray,
        relayId: String,
        peerId: String,
        nonce: ByteArray
    ): ByteArray {
        val shared = X25519.sharedSecret(peerPrivateKey, relayPublicKey)
        require(!X25519.isAllZero(shared)) { "relay ephemeral key is a low order point" }
        return Codec.hmacSha256(shared, context(relayId, relayPublicKey, peerId, nonce))
    }

    fun verify(
        relayPrivateKey: ByteArray,
        relayPublicKey: ByteArray,
        relayId: String,
        peerId: String,
        peerPublicKey: ByteArray,
        nonce: ByteArray,
        proof: ByteArray
    ): Boolean {
        val shared = X25519.sharedSecret(relayPrivateKey, peerPublicKey)
        if (X25519.isAllZero(shared)) return false
        val expected = Codec.hmacSha256(shared, context(relayId, relayPublicKey, peerId, nonce))
        return Codec.constantTimeEquals(expected, proof)
    }
}
