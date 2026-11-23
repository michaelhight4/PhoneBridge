package dev.phonebridge.relay

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.client.RelayClientError
import dev.phonebridge.core.crypto.Primitives
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelayAuthTest {
    @Test
    fun `two peers authenticate and exchange envelopes in both directions`() {
        val relay = TestSupport.testRelay()
        val device = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val bot = TestSupport.client(relay, Identity.generate(PeerRole.BOT))
        val atDevice = TestSupport.Collector<RelayEnvelope>()
        val atBot = TestSupport.Collector<RelayEnvelope>()
        device.onRoute = { atDevice.add(it) }
        bot.onRoute = { atBot.add(it) }
        try {
            device.start()
            bot.start()
            assertTrue(device.awaitConnected(5000), "device did not connect")
            assertTrue(bot.awaitConnected(5000), "bot did not connect")
            assertEquals(setOf(device.peerId, bot.peerId), relay.peers().toSet())

            val payload = B64Url.encode(Primitives.randomBytes(1500))
            val id = bot.sendRoute(device.peerId, payload)
            val delivered = assertNotNull(atDevice.await(), "the device received nothing")
            assertEquals(RelayType.ROUTE, delivered.type)
            assertEquals(bot.peerId, delivered.from)
            assertEquals(device.peerId, delivered.to)
            assertEquals(payload, delivered.payload)
            assertEquals(id, delivered.id)

            val replyId = device.sendRoute(bot.peerId, payload)
            val replied = assertNotNull(atBot.await(), "the bot received nothing")
            assertEquals(device.peerId, replied.from)
            assertEquals(replyId, replied.id)
            assertEquals(payload, replied.payload)

            assertEquals(2L, relay.stats().envelopesRouted)
            assertEquals(2, relay.stats().peers)
        } finally {
            device.stop()
            bot.stop()
            relay.stop()
        }
    }

    @Test
    fun `peer offline is reported to the sender`() {
        val relay = TestSupport.testRelay()
        val bot = TestSupport.client(relay)
        val errors = TestSupport.Collector<RelayClientError>()
        bot.onError = { errors.add(it) }
        try {
            bot.start()
            assertTrue(bot.awaitConnected(5000))
            val missing = Identity.generate(PeerRole.DEVICE).peerId
            val id = bot.sendRoute(missing, "AAAA")
            val error = assertNotNull(errors.awaitWhere { it.id == id }, "no peer_offline error")
            assertEquals("peer_offline", error.code)
            assertEquals(1L, relay.stats().peerOffline)
        } finally {
            bot.stop()
            relay.stop()
        }
    }

    @Test
    fun `ping is answered with pong`() {
        val relay = TestSupport.testRelay()
        val bot = TestSupport.client(relay)
        val messages = TestSupport.Collector<RelayEnvelope>()
        bot.onMessage = { messages.add(it) }
        try {
            bot.start()
            assertTrue(bot.awaitConnected(5000))
            bot.ping("ping-42")
            val pong = assertNotNull(
                messages.awaitWhere { it.type == RelayType.PONG },
                "no pong"
            )
            assertEquals("ping-42", pong.id)
        } finally {
            bot.stop()
            relay.stop()
        }
    }

    @Test
    fun `a route before hello is rejected as unauthorised`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(Identity.generate(PeerRole.BOT), relay.url).use { peer ->
                peer.open()
                peer.send(
                    RelayEnvelope(
                        type = RelayType.ROUTE,
                        from = peer.identity.peerId,
                        to = Identity.generate(PeerRole.DEVICE).peerId,
                        id = "1",
                        payload = "AAAA"
                    )
                )
                val reply = peer.readEnvelope()
                assertEquals(RelayType.ERROR, reply?.type)
                assertEquals("unauthorized", TestSupport.errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a forged hello proof is rejected`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(Identity.generate(PeerRole.BOT), relay.url).use { peer ->
                peer.open()
                peer.helloSigned(proofOverride = RawPeer.randomProof())
                val reply = peer.readEnvelope()
                assertEquals(RelayType.ERROR, reply?.type)
                assertEquals("unauthorized", TestSupport.errorCode(reply))
                assertTrue(relay.peers().isEmpty())
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a hello whose peer id does not match the public key is rejected`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(Identity.generate(PeerRole.BOT), relay.url).use { peer ->
                peer.open()
                peer.helloSigned(fromOverride = Identity.generate(PeerRole.DEVICE).peerId)
                val reply = peer.readEnvelope()
                assertEquals("unauthorized", TestSupport.errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a hello that reuses another connection nonce is rejected`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(Identity.generate(PeerRole.BOT), relay.url).use { peer ->
                peer.open()
                peer.helloSigned(nonceOverride = B64Url.encode(Primitives.randomBytes(32)))
                val reply = peer.readEnvelope()
                assertEquals("unauthorized", TestSupport.errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a hello with an all zero public key is rejected`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(Identity.generate(PeerRole.BOT), relay.url).use { peer ->
                peer.open()
                peer.helloSigned(publicKeyOverride = B64Url.encode(ByteArray(32)))
                val reply = peer.readEnvelope()
                assertEquals("unauthorized", TestSupport.errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a hello sent before the challenge is answered with the challenge`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(Identity.generate(PeerRole.BOT), relay.url).use { peer ->
                peer.open(readChallenge = false)
                // Exactly what a peer following docs/PROTOCOL.md literally would send first.
                peer.send(
                    RelayEnvelope(
                        type = RelayType.HELLO,
                        from = peer.identity.peerId,
                        pub = peer.identity.publicKeyBase64,
                        role = PeerRole.BOT
                    )
                )
                val challenge = assertNotNull(peer.readEnvelope())
                assertEquals(RelayType.CHALLENGE, challenge.type)
                peer.adoptChallenge(challenge)
                peer.helloSigned()

                // The relay also answered the first, nonce-less hello with this challenge, so skip
                // any extra challenge frames until the hello_ok arrives.
                var reply: RelayEnvelope? = null
                for (attempt in 0 until 3) {
                    val next = peer.readEnvelope() ?: break
                    if (next.type == RelayType.HELLO_OK) {
                        reply = next
                        break
                    }
                }
                assertEquals(RelayType.HELLO_OK, reply?.type)
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `an authenticated peer cannot spoof another sender`() {
        val relay = TestSupport.testRelay()
        val bot = TestSupport.client(relay)
        val victim = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val errors = TestSupport.Collector<RelayClientError>()
        bot.onError = { errors.add(it) }
        try {
            bot.start()
            victim.start()
            assertTrue(bot.awaitConnected(5000))
            assertTrue(victim.awaitConnected(5000))
            bot.sendRaw(
                RelayEnvelope(
                    type = RelayType.ROUTE,
                    from = victim.peerId,
                    to = victim.peerId,
                    id = "spoof",
                    payload = "AAAA"
                )
            )
            val error = assertNotNull(errors.awaitWhere { it.code == "bad_envelope" })
            assertEquals("bad_envelope", error.code)
            assertEquals(0L, relay.stats().envelopesRouted)
        } finally {
            bot.stop()
            victim.stop()
            relay.stop()
        }
    }

    @Test
    fun `health endpoint reports the relay id`() {
        val relay = TestSupport.testRelay()
        try {
            val body = URL("http://127.0.0.1:${relay.port}/healthz").readText()
            assertTrue(body.contains(relay.relayId), "health body does not name the relay: $body")
            assertTrue(body.contains("\"ok\":true"))
        } finally {
            relay.stop()
        }
    }
}
