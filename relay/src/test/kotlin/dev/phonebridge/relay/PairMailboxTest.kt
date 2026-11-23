package dev.phonebridge.relay

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.pairing.ShortCode
import dev.phonebridge.relay.client.RelayClientError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PairMailboxTest {
    private val code: String = ShortCode.generate()

    private fun offer(name: String = "relay-test"): JsonObject = buildJsonObject {
        put("v", 1)
        put("relay", "ws://127.0.0.1:1")
        put("device_id", name)
        put("device_pub", "AAAA")
        put("secret", "BBBB")
        put("code", code)
        put("exp", 1_790_000_000)
    }

    @Test
    fun `a stored offer is released once and then gone`() {
        val relay = TestSupport.testRelay()
        val device = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val bot = TestSupport.client(relay)
        val offers = TestSupport.Collector<RelayEnvelope>()
        val stored = TestSupport.Collector<RelayEnvelope>()
        val errors = TestSupport.Collector<RelayClientError>()
        bot.onPairOffer = { offers.add(it) }
        bot.onError = { errors.add(it) }
        device.onMessage = { stored.add(it) }
        try {
            device.start()
            bot.start()
            assertTrue(device.awaitConnected(5000))
            assertTrue(bot.awaitConnected(5000))

            device.pairStore(code, offer())
            assertNotNull(
                stored.awaitWhere { it.type == RelayType.PAIR_STORED },
                "the relay never confirmed the stored offer"
            )
            bot.pairLookup(code)
            val released = assertNotNull(offers.await(), "the bot never received the pair offer")
            assertEquals(RelayType.PAIR_OFFER, released.type)
            assertEquals(ShortCode.format(code), released.code)
            val releasedOffer = assertNotNull(released.offer)
            assertEquals("relay-test", releasedOffer["device_id"]?.jsonPrimitive?.content)

            bot.pairLookup(code)
            val second = assertNotNull(errors.awaitWhere { it.code == "code_not_found" }, "the code was released twice")
            assertEquals("code_not_found", second.code)
            assertEquals(0, relay.stats().mailboxEntries)
        } finally {
            device.stop()
            bot.stop()
            relay.stop()
        }
    }

    @Test
    fun `a mailbox entry expires after its ttl`() {
        val relay = TestSupport.testRelay(pairMailboxTtlMs = 200)
        val device = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val bot = TestSupport.client(relay)
        val errors = TestSupport.Collector<RelayClientError>()
        bot.onError = { errors.add(it) }
        try {
            device.start()
            bot.start()
            assertTrue(device.awaitConnected(5000))
            assertTrue(bot.awaitConnected(5000))

            device.pairStore(code, offer())
            Thread.sleep(500)
            bot.pairLookup(code)
            val error = assertNotNull(errors.awaitWhere { it.code == "code_expired" }, "the expired code was not reported")
            assertEquals("code_expired", error.code)
        } finally {
            device.stop()
            bot.stop()
            relay.stop()
        }
    }

    @Test
    fun `a malformed code is rejected`() {
        val relay = TestSupport.testRelay()
        val device = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val errors = TestSupport.Collector<RelayClientError>()
        device.onError = { errors.add(it) }
        try {
            device.start()
            assertTrue(device.awaitConnected(5000))
            device.pairStore("nope!", offer())
            val error = assertNotNull(errors.awaitWhere { it.code == "bad_args" })
            assertEquals("bad_args", error.code)
            assertEquals(0, relay.stats().mailboxEntries)
        } finally {
            device.stop()
            relay.stop()
        }
    }

    @Test
    fun `codes are normalized before lookup`() {
        val relay = TestSupport.testRelay()
        val device = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val bot = TestSupport.client(relay)
        val offers = TestSupport.Collector<RelayEnvelope>()
        val stored = TestSupport.Collector<RelayEnvelope>()
        bot.onPairOffer = { offers.add(it) }
        device.onMessage = { stored.add(it) }
        try {
            device.start()
            bot.start()
            assertTrue(device.awaitConnected(5000))
            assertTrue(bot.awaitConnected(5000))

            device.pairStore(code.lowercase().replace("-", " "), offer())
            assertNotNull(stored.awaitWhere { it.type == RelayType.PAIR_STORED })
            bot.pairLookup(code.replace("-", ""))
            val released = assertNotNull(offers.await())
            assertEquals(ShortCode.format(code), released.code)
        } finally {
            device.stop()
            bot.stop()
            relay.stop()
        }
    }
}
