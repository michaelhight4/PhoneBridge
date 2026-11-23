package dev.phonebridge.relay

import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.crypto.Secrets
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Relay behaviour that is not already covered by :core's own tests. */
class RelayBehaviourTest {
    @Test
    fun `a second connection for the same peer replaces the first and keeps routing`() {
        val relay = TestSupport.testRelay()
        val identity = Identity.generate(PeerRole.BOT)
        val first = RawPeer(identity, relay.url).apply { authenticate() }
        val second = RawPeer(identity, relay.url).apply { authenticate() }
        val device = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        try {
            device.start()
            assertTrue(device.awaitConnected(5000))

            TestSupport.waitUntil(3000, "the relay to close the replaced connection") {
                runCatching { first.readEnvelope() }.getOrNull() == null
            }
            assertEquals(
                1,
                relay.peers().count { it == identity.peerId },
                "only the newest connection may be registered: ${relay.peers()}"
            )

            device.sendRoute(identity.peerId, "AAAA", id = "replaced-1")
            val delivered = assertNotNull(second.readEnvelope(), "the newest connection received nothing")
            assertEquals(RelayType.ROUTE, delivered.type)
            assertEquals("replaced-1", delivered.id)
        } finally {
            first.close()
            second.close()
            device.stop()
            relay.stop()
        }
    }

    @Test
    fun `a second hello on an authenticated connection is rejected`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(Identity.generate(PeerRole.BOT), relay.url).use { peer ->
                peer.authenticate()
                peer.helloSigned()
                val reply = assertNotNull(peer.readEnvelope())
                assertEquals(RelayType.ERROR, reply.type)
                assertEquals("already_authenticated", TestSupport.errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `the mailbox rejects an oversize offer and a full mailbox`() {
        val mailbox = PairMailbox(ttlMs = 60_000, maxEntries = 2, maxOfferBytes = 64)
        val smallOffer = buildJsonObject { put("v", 1); put("relay", "ws://127.0.0.1:1") }
        mailbox.store("AAA-AAA", smallOffer)
        mailbox.store("BBB-BBB", smallOffer)

        assertFailsWith<PairMailbox.MailboxFullException> {
            mailbox.store("CCC-CCC", smallOffer)
        }

        val generous = PairMailbox(ttlMs = 60_000, maxEntries = 8, maxOfferBytes = 32)
        val largeOffer = buildJsonObject { put("blob", "x".repeat(200)) }
        assertFailsWith<PairMailbox.MailboxFullException> {
            generous.store("AAA-AAA", largeOffer)
        }
    }

    @Test
    fun `move tokens are bound to one peer and expire`() {
        val service = MoveTokenService.fromSecret(B64Url.encode(Primitives.randomBytes(32)))
        val peer = Identity.generate(PeerRole.BOT).peerId
        val other = Identity.generate(PeerRole.BOT).peerId

        val token = service.issue(peer, ttlMs = 60_000)
        val valid = service.verify(token, peer)
        assertTrue(valid is MoveTokenService.Verify.Valid, "expected the token to verify, got $valid")

        val wrongPeer = service.verify(token, other)
        assertTrue(wrongPeer is MoveTokenService.Verify.Invalid)

        val shortLived = service.issue(peer, ttlMs = 1)
        Thread.sleep(5)
        assertTrue(service.verify(shortLived, peer) is MoveTokenService.Verify.Invalid)

        val disabled = MoveTokenService.fromSecret(null)
        assertTrue(disabled.verify(token, peer) is MoveTokenService.Verify.Invalid)
        assertFailsWith<IllegalArgumentException> { disabled.issue(peer, 1000) }
    }

    @Test
    fun `shared secrets accept hex, base64url and plain text`() {
        assertContentEquals(byteArrayOf(0x00, 0x11, 0xFF.toByte()), Secrets.unhex("0011ff"))
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            Secrets.decode(B64Url.encode(byteArrayOf(1, 2, 3, 4)))
        )
        val passphrase = Secrets.decode("a long spoken passphrase")
        assertTrue(passphrase.isNotEmpty())
        assertContentEquals(passphrase, Secrets.decode("a long spoken passphrase"))
        assertFailsWith<IllegalArgumentException> { Secrets.decode("   ") }
    }

    @Test
    fun `the relay identity and certificate survive a restart`() {
        val dir = java.nio.file.Files.createTempDirectory("pb-restart").toFile().absolutePath
        val first = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        val relayId = first.relayId
        val pin = assertNotNull(first.fingerprint)
        first.stop()

        val second = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        try {
            assertEquals(relayId, second.relayId)
            assertEquals(pin, second.fingerprint)
        } finally {
            second.stop()
        }
    }
}
