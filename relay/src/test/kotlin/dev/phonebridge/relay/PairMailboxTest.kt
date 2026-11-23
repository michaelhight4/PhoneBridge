package dev.phonebridge.relay

import com.google.gson.JsonObject
import dev.phonebridge.relay.client.RelayClientError
import dev.phonebridge.relay.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairMailboxTest {
    private val code = "7K4-Q9M"

    private fun offer(name: String = "relay-test"): JsonObject = Json.obj(
        "v" to 1,
        "relay" to "ws://127.0.0.1:1",
        "device_id" to name,
        "device_pub" to "AAAA",
        "secret" to "BBBB",
        "code" to code,
        "exp" to 1_790_000_000
    )

    @Test
    fun `a stored offer is released once and then gone`() {
        val relay = TestSupport.testRelay()
        val device = TestSupport.client(relay, role = "device")
        val bot = TestSupport.client(relay, role = "bot")
        val offers = TestSupport.Collector<JsonObject>()
        val stored = TestSupport.Collector<JsonObject>()
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
                stored.awaitWhere { Json.string(it, "type") == "pair_stored" },
                "the relay never confirmed the stored offer"
            )
            bot.pairLookup(code)
            val released = assertNotNull(offers.await(), "the bot never received the pair offer")
            assertEquals("pair_offer", Json.string(released, "type"))
            assertEquals(code, Json.string(released, "code"))
            val releasedOffer = assertNotNull(Json.objOrNull(released, "offer"))
            assertEquals("relay-test", Json.string(releasedOffer, "device_id"))

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
        val device = TestSupport.client(relay, role = "device")
        val bot = TestSupport.client(relay, role = "bot")
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
        val device = TestSupport.client(relay, role = "device")
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
        val device = TestSupport.client(relay, role = "device")
        val bot = TestSupport.client(relay, role = "bot")
        val offers = TestSupport.Collector<JsonObject>()
        val stored = TestSupport.Collector<JsonObject>()
        bot.onPairOffer = { offers.add(it) }
        device.onMessage = { stored.add(it) }
        try {
            device.start()
            bot.start()
            assertTrue(device.awaitConnected(5000))
            assertTrue(bot.awaitConnected(5000))

            device.pairStore("7k4 q9m", offer())
            assertNotNull(stored.awaitWhere { Json.string(it, "type") == "pair_stored" })
            bot.pairLookup("7K4Q9M")
            val released = assertNotNull(offers.await())
            assertEquals(code, Json.string(released, "code"))
        } finally {
            device.stop()
            bot.stop()
            relay.stop()
        }
    }
}
