package dev.phonebridge.core.crypto

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.util.B64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelloAuthTest {
    private val relay = X25519.generateKeyPair()
    private val relayId = "cc6wtab6rgspd48jah4bqcjv9w"
    private val peer = Identity.generate(PeerRole.DEVICE)
    private val nonce = Primitives.randomBytes(32)

    private fun proof(
        peerIdentity: Identity = peer,
        relayPublic: ByteArray = relay.publicKey,
        relayIdValue: String = relayId,
        nonceBytes: ByteArray = nonce,
    ): ByteArray = HelloAuth.proof(
        peerPrivateKey = peerIdentity.privateKey,
        peerPublicKey = peerIdentity.publicKey,
        relayPublicKey = relayPublic,
        relayId = relayIdValue,
        peerId = peerIdentity.peerId,
        nonce = nonceBytes,
    )

    private fun verify(
        proofBytes: ByteArray,
        peerIdentity: Identity = peer,
        relayPublic: ByteArray = relay.publicKey,
        relayPrivate: ByteArray = relay.privateKey,
        relayIdValue: String = relayId,
        nonceBytes: ByteArray = nonce,
    ): Boolean = HelloAuth.verify(
        relayPrivateKey = relayPrivate,
        relayPublicKey = relayPublic,
        relayId = relayIdValue,
        peerId = peerIdentity.peerId,
        peerPublicKey = peerIdentity.publicKey,
        nonce = nonceBytes,
        proof = proofBytes,
    )

    @Test
    fun contextMatchesTheRelayWireContract() {
        val context = HelloAuth.context(relayId, relay.publicKey, peer.peerId, nonce)
        assertEquals(
            "phonebridge/hello/v1|$relayId|${B64Url.encode(relay.publicKey)}|${peer.peerId}|${B64Url.encode(nonce)}",
            String(context, Charsets.UTF_8),
        )
        assertEquals("dh-hmac-sha256", HelloAuth.PROOF_METHOD)
    }

    @Test
    fun peerProofVerifiesAtTheRelay() {
        val proof = proof()
        assertTrue(verify(proof))
    }

    @Test
    fun proofIsBoundToNonceKeyAndPeer() {
        val proof = proof()
        val otherPeer = Identity.generate(PeerRole.BOT)

        assertFalse(verify(proof, nonceBytes = Primitives.randomBytes(32)), "another nonce must fail")
        assertFalse(verify(proof, peerIdentity = otherPeer), "another peer key must fail")
        assertFalse(
            verify(proof, relayPublic = X25519.generateKeyPair().publicKey, relayPrivate = X25519.generateKeyPair().privateKey),
            "another relay key must fail",
        )
        assertFalse(verify(proof, relayIdValue = "another-relay"), "the relay id is part of the transcript")

        val tampered = proof.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(verify(tampered))

        val forged = proof(peerIdentity = otherPeer)
        assertFalse(verify(forged), "a proof for another key must not pass")
    }
}
