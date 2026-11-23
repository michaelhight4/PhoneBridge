package dev.phonebridge.relay

import com.google.gson.JsonObject
import dev.phonebridge.relay.crypto.Codec
import dev.phonebridge.relay.crypto.PeerKeyPair
import dev.phonebridge.relay.envelope.RelayEnvelope
import dev.phonebridge.relay.json.Json
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelayAuthTest {
    private fun errorCode(obj: JsonObject?): String? =
        obj?.let { Json.objOrNull(it, "error") }?.let { Json.string(it, "code") }

    @Test
    fun `two peers authenticate and exchange envelopes in both directions`() {
        val relay = TestSupport.testRelay()
        val device = TestSupport.client(relay, role = "device")
        val bot = TestSupport.client(relay, role = "bot")
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

            val payload = Codec.b64url(Codec.randomBytes(1500))
            val id = bot.sendRoute(device.peerId, payload)
            val delivered = assertNotNull(atDevice.await(), "the device received nothing")
            assertEquals("route", delivered.type)
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
        val errors = TestSupport.Collector<dev.phonebridge.relay.client.RelayClientError>()
        bot.onError = { errors.add(it) }
        try {
            bot.start()
            assertTrue(bot.awaitConnected(5000))
            val missing = PeerKeyPair.generate().peerId
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
        val messages = TestSupport.Collector<JsonObject>()
        bot.onMessage = { messages.add(it) }
        try {
            bot.start()
            assertTrue(bot.awaitConnected(5000))
            bot.ping("ping-42")
            val pong = assertNotNull(
                messages.awaitWhere { Json.string(it, "type") == "pong" },
                "no pong"
            )
            assertEquals("ping-42", Json.string(pong, "id"))
        } finally {
            bot.stop()
            relay.stop()
        }
    }

    @Test
    fun `a route before hello is rejected as unauthorised`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(PeerKeyPair.generate(), relay.url).use { peer ->
                peer.open()
                peer.send(
                    Json.obj(
                        "v" to 1,
                        "type" to "route",
                        "from" to peer.identity.peerId,
                        "to" to PeerKeyPair.generate().peerId,
                        "id" to "1",
                        "payload" to "AAAA"
                    )
                )
                val reply = peer.readJson()
                assertEquals("error", Json.string(reply ?: JsonObject(), "type"))
                assertEquals("unauthorized", errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a forged hello proof is rejected`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(PeerKeyPair.generate(), relay.url).use { peer ->
                peer.open()
                peer.helloSigned(proofOverride = Codec.b64url(Codec.randomBytes(32)))
                val reply = peer.readJson()
                assertEquals("error", Json.string(reply ?: JsonObject(), "type"))
                assertEquals("unauthorized", errorCode(reply))
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
            RawPeer(PeerKeyPair.generate(), relay.url).use { peer ->
                peer.open()
                peer.helloSigned(fromOverride = PeerKeyPair.generate().peerId)
                val reply = peer.readJson()
                assertEquals("unauthorized", errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a hello that reuses another connection nonce is rejected`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(PeerKeyPair.generate(), relay.url).use { peer ->
                peer.open()
                peer.helloSigned(nonceOverride = Codec.b64url(Codec.randomBytes(32)))
                val reply = peer.readJson()
                assertEquals("unauthorized", errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a hello with an all zero public key is rejected`() {
        val relay = TestSupport.testRelay()
        try {
            RawPeer(PeerKeyPair.generate(), relay.url).use { peer ->
                peer.open()
                peer.helloSigned(publicKeyOverride = Codec.b64url(ByteArray(32)))
                val reply = peer.readJson()
                assertEquals("unauthorized", errorCode(reply))
            }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `an authenticated peer cannot spoof another sender`() {
        val relay = TestSupport.testRelay()
        val bot = TestSupport.client(relay)
        val victim = TestSupport.client(relay, role = "device")
        val errors = TestSupport.Collector<dev.phonebridge.relay.client.RelayClientError>()
        bot.onError = { errors.add(it) }
        try {
            bot.start()
            victim.start()
            assertTrue(bot.awaitConnected(5000))
            assertTrue(victim.awaitConnected(5000))
            bot.sendRaw(
                Json.obj(
                    "v" to 1,
                    "type" to "route",
                    "from" to victim.peerId,
                    "to" to victim.peerId,
                    "id" to "spoof",
                    "payload" to "AAAA"
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
