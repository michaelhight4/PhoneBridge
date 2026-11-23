package dev.phonebridge.relay

import dev.phonebridge.relay.crypto.Codec
import dev.phonebridge.relay.crypto.PeerKeyPair
import dev.phonebridge.relay.envelope.RelayEnvelope
import dev.phonebridge.relay.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayMoveTest {
    private val secret = Codec.hex(Codec.randomBytes(32))

    private fun tempDir(): String = Files.createTempDirectory("pb-move").toFile().absolutePath

    @Test
    fun `both peers follow relay A to relay B and keep exchanging messages`() {
        val relayA = TestSupport.testRelay(moveSecret = secret, dataDir = tempDir())
        val relayB = TestSupport.testRelay(moveSecret = secret, dataDir = tempDir())
        val device = TestSupport.client(relayA, role = "device")
        val bot = TestSupport.client(relayA, role = "bot")
        val atDevice = TestSupport.Collector<RelayEnvelope>()
        val atBot = TestSupport.Collector<RelayEnvelope>()
        device.onRoute = { atDevice.add(it) }
        bot.onRoute = { atBot.add(it) }
        val moves = TestSupport.Collector<dev.phonebridge.relay.client.RelayMovedEvent>()
        bot.onRelayMoved = { moves.add(it) }

        var raw: RawPeer? = null
        try {
            device.start()
            bot.start()
            assertTrue(device.awaitConnected(5000))
            assertTrue(bot.awaitConnected(5000))
            assertEquals(relayA.relayId, device.relayId)

            bot.sendRoute(device.peerId, "AAAA", id = "before-move")
            assertEquals("before-move", assertNotNull(atDevice.await()).id)

            raw = RawPeer(PeerKeyPair.generate(), relayA.url).apply { authenticate() }

            val notified = relayA.moveTo(relayB.url, ttlMs = 60_000)
            assertEquals(3, notified, "all three connected peers should be told about the move")

            val moved = assertNotNull(moves.await(), "the bot never saw relay_moved")
            assertEquals(relayB.url, moved.newUrl)
            assertTrue(moved.expiresEpochSeconds > System.currentTimeMillis() / 1000)

            TestSupport.waitUntil(10_000, "both peers to reconnect to relay B") {
                device.isConnected && bot.isConnected &&
                    device.relayId == relayB.relayId && bot.relayId == relayB.relayId
            }
            assertEquals(setOf(device.peerId, bot.peerId), relayB.peers().toSet())

            bot.sendRoute(device.peerId, "BBBB", id = "after-move-1")
            val afterMove = assertNotNull(atDevice.await(), "no envelope after the move")
            assertEquals("after-move-1", afterMove.id)
            assertEquals("BBBB", afterMove.payload)

            device.sendRoute(bot.peerId, "CCCC", id = "after-move-2")
            assertEquals("after-move-2", assertNotNull(atBot.await()).id)
            assertEquals(2L, relayB.stats().envelopesRouted)

            // The raw peer followed manually: its token is accepted once, then rejected as used.
            val rawMoved = assertNotNull(raw.readJson(), "the raw peer did not receive relay_moved")
            assertEquals("relay_moved", Json.string(rawMoved, "type"))
            val token = assertNotNull(Json.string(rawMoved, "token"))

            RawPeer(raw.identity, relayB.url).use { follower ->
                follower.open()
                follower.helloSigned(moveToken = token)
                val ok = assertNotNull(follower.readJson())
                assertEquals("hello_ok", Json.string(ok, "type"), "relay B rejected the first use of the token: $ok")
            }

            RawPeer(raw.identity, relayB.url).use { replayer ->
                replayer.open()
                replayer.helloSigned(moveToken = token)
                val rejected = assertNotNull(replayer.readJson())
                assertEquals("error", Json.string(rejected, "type"))
                val code = Json.objOrNull(rejected, "error")?.let { Json.string(it, "code") }
                assertEquals("token_used", code)
            }
        } finally {
            raw?.close()
            device.stop()
            bot.stop()
            relayA.stop()
            relayB.stop()
        }
    }

    @Test
    fun `an expired move token is rejected`() {
        val relayA = TestSupport.testRelay(moveSecret = secret, dataDir = tempDir())
        val relayB = TestSupport.testRelay(moveSecret = secret, dataDir = tempDir())
        try {
            RawPeer(PeerKeyPair.generate(), relayA.url).use { peer ->
                peer.authenticate()
                assertEquals(1, relayA.moveTo(relayB.url, ttlMs = 200))
                val moved = assertNotNull(peer.readJson())
                val token = assertNotNull(Json.string(moved, "token"))
                Thread.sleep(500)

                RawPeer(peer.identity, relayB.url).use { late ->
                    late.open()
                    late.helloSigned(moveToken = token)
                    val rejected = assertNotNull(late.readJson())
                    assertEquals("error", Json.string(rejected, "type"))
                    assertEquals(
                        "unauthorized",
                        Json.objOrNull(rejected, "error")?.let { Json.string(it, "code") }
                    )
                    // A plain reconnect (no token) still works: the token is only an admission ticket.
                    RawPeer(peer.identity, relayB.url).use { plain ->
                        assertEquals("hello_ok", Json.string(plain.authenticate(), "type"))
                    }
                }
            }
        } finally {
            relayA.stop()
            relayB.stop()
        }
    }

    @Test
    fun `a relay without a move secret refuses to hand over`() {
        val relayA = TestSupport.testRelay(dataDir = tempDir())
        try {
            val failure = runCatching { relayA.moveTo("ws://127.0.0.1:9") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "expected IllegalStateException, got $failure")
        } finally {
            relayA.stop()
        }
    }

    @Test
    fun `a token from a different secret is rejected`() {
        val otherSecret = Codec.hex(Codec.randomBytes(32))
        val relayA = TestSupport.testRelay(moveSecret = secret, dataDir = tempDir())
        val relayB = TestSupport.testRelay(moveSecret = otherSecret, dataDir = tempDir())
        try {
            RawPeer(PeerKeyPair.generate(), relayA.url).use { peer ->
                peer.authenticate()
                relayA.moveTo(relayB.url, ttlMs = 60_000)
                val moved = assertNotNull(peer.readJson())
                val token = assertNotNull(Json.string(moved, "token"))

                RawPeer(peer.identity, relayB.url).use { follower ->
                    follower.open()
                    follower.helloSigned(moveToken = token)
                    val rejected = assertNotNull(follower.readJson())
                    assertEquals("error", Json.string(rejected, "type"))
                    assertEquals(
                        "unauthorized",
                        Json.objOrNull(rejected, "error")?.let { Json.string(it, "code") }
                    )
                    assertNull(Json.string(rejected, "relay_id"))
                }
            }
        } finally {
            relayA.stop()
            relayB.stop()
        }
    }
}
