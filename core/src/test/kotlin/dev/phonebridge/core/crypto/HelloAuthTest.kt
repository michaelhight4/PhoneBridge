package dev.phonebridge.core.crypto

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelloAuthTest {
    @Test
    fun peerProofVerifiesAtTheRelay() {
        val relay = X25519.generateKeyPair()
        val peer = Identity.generate(PeerRole.DEVICE)
        val nonce = Primitives.randomBytes(16)
        val proof = HelloAuth.proof(peer.privateKey, peer.publicKey, relay.publicKey, nonce)
        assertTrue(HelloAuth.verify(relay.privateKey, peer.publicKey, nonce, proof))
    }

    @Test
    fun proofIsBoundToNonceKeyAndPeer() {
        val relay = X25519.generateKeyPair()
        val peer = Identity.generate(PeerRole.BOT)
        val otherPeer = Identity.generate(PeerRole.BOT)
        val nonce = Primitives.randomBytes(16)
        val proof = HelloAuth.proof(peer.privateKey, peer.publicKey, relay.publicKey, nonce)

        assertFalse(HelloAuth.verify(relay.privateKey, peer.publicKey, Primitives.randomBytes(16), proof))
        assertFalse(HelloAuth.verify(relay.privateKey, otherPeer.publicKey, nonce, proof))
        assertFalse(HelloAuth.verify(X25519.generateKeyPair().privateKey, peer.publicKey, nonce, proof))

        val tampered = proof.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(HelloAuth.verify(relay.privateKey, peer.publicKey, nonce, tampered))

        val forged = HelloAuth.proof(otherPeer.privateKey, peer.publicKey, relay.publicKey, nonce)
        assertFalse(HelloAuth.verify(relay.privateKey, peer.publicKey, nonce, forged))
    }
}
