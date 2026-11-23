package dev.phonebridge.relay

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.relay.client.RelayClientError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelayLimitsTest {
    @Test
    fun `a peer that floods envelopes is rate limited`() {
        val relay = TestSupport.testRelay(rateLimit = RateLimitConfig(maxMessages = 4, windowMs = 2000, burst = 4))
        val bot = TestSupport.client(relay)
        val device = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val atDevice = TestSupport.Collector<RelayEnvelope>()
        val errors = TestSupport.Collector<RelayClientError>()
        device.onRoute = { atDevice.add(it) }
        bot.onError = { errors.add(it) }
        try {
            bot.start()
            device.start()
            assertTrue(bot.awaitConnected(5000))
            assertTrue(device.awaitConnected(5000))

            var lastId = ""
            repeat(12) { lastId = bot.sendRoute(device.peerId, "AAAA", id = "flood-$it") }
            assertNotNull(errors.awaitWhere { it.code == "rate_limited" }, "the relay never rate limited the flood")
            Thread.sleep(200)
            val delivered = atDevice.size()
            assertTrue(delivered in 1..4, "expected at most the burst of 4 envelopes, got $delivered")
            assertTrue(relay.stats().rateLimited >= 8, "expected the excess to be dropped")
            assertTrue(relay.stats().envelopesRouted <= 4)
            assertEquals("flood-11", lastId)
        } finally {
            bot.stop()
            device.stop()
            relay.stop()
        }
    }

    @Test
    fun `an oversize frame is dropped and its connection closed`() {
        val relay = TestSupport.testRelay(maxEnvelopeBytes = 4096)
        val receiver = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val atReceiver = TestSupport.Collector<RelayEnvelope>()
        receiver.onRoute = { atReceiver.add(it) }
        try {
            receiver.start()
            assertTrue(receiver.awaitConnected(5000))

            RawPeer(Identity.generate(PeerRole.BOT), relay.url, maxMessageBytes = 1 shl 20).use { peer ->
                peer.authenticate()
                val big = "x".repeat(8192)
                try {
                    peer.send(
                        RelayEnvelope(
                            type = RelayType.ROUTE,
                            from = peer.identity.peerId,
                            to = receiver.peerId,
                            id = "oversize",
                            payload = big
                        )
                    )
                } catch (_: Exception) {
                    // The relay may reset the connection while the frame is still being written.
                }
                try {
                    peer.readEnvelope()
                } catch (_: Exception) {
                    // Expected: the relay closed the connection.
                }
                TestSupport.waitUntil(3000, "the oversize peer to be disconnected") {
                    relay.peers().size == 1
                }
            }
            Thread.sleep(100)
            assertEquals(0, atReceiver.size(), "the oversize envelope must not be forwarded")
            assertEquals(0L, relay.stats().envelopesRouted)
            assertEquals(1, relay.stats().peers)
        } finally {
            receiver.stop()
            relay.stop()
        }
    }

    @Test
    fun `the default limit drops a frame larger than 16 MiB`() {
        val relay = TestSupport.testRelay()
        val receiver = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val atReceiver = TestSupport.Collector<RelayEnvelope>()
        receiver.onRoute = { atReceiver.add(it) }
        try {
            receiver.start()
            assertTrue(receiver.awaitConnected(5000))

            RawPeer(Identity.generate(PeerRole.BOT), relay.url, maxMessageBytes = 32 * 1024 * 1024).use { peer ->
                peer.authenticate()
                // Built by hand: :core's EnvelopeCodec would refuse to encode over its own 16 MiB cap,
                // and this test is about the relay's transport cap.
                val text = buildString {
                    append("{\"v\":1,\"type\":\"route\",\"from\":\"").append(peer.identity.peerId)
                    append("\",\"to\":\"").append(receiver.peerId)
                    append("\",\"id\":\"huge\",\"payload\":\"")
                    append("y".repeat(16 * 1024 * 1024))
                    append("\"}")
                }
                try {
                    peer.sendRawText(text)
                } catch (_: Exception) {
                }
                try {
                    peer.readEnvelope()
                } catch (_: Exception) {
                }
                TestSupport.waitUntil(5000, "the oversize peer to be disconnected") {
                    relay.peers().size == 1
                }
            }
            Thread.sleep(100)
            assertEquals(0, atReceiver.size())
            assertEquals(0L, relay.stats().envelopesRouted)
        } finally {
            receiver.stop()
            relay.stop()
        }
    }
}
