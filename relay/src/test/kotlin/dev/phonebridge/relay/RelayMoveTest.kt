package dev.phonebridge.relay

import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.client.RelayMovedEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayMoveTest {
    private val secret = B64Url.encode(Primitives.randomBytes(32))

    private fun tempDir(): String = Files.createTempDirectory("pb-move").toFile().absolutePath

    @Test
    fun `both peers follow relay A to relay B and keep exchanging messages`() {
        val relayA = TestSupport.testRelay(moveSecret = secret, dataDir = tempDir())
        val relayB = TestSupport.testRelay(moveSecret = secret, dataDir = tempDir())
        val device = TestSupport.client(relayA, Identity.generate(PeerRole.DEVICE))
        val bot = TestSupport.client(relayA)
        val atDevice = TestSupport.Collector<RelayEnvelope>()
        val atBot = TestSupport.Collector<RelayEnvelope>()
        device.onRoute = { atDevice.add(it) }
        bot.onRoute = { atBot.add(it) }
        val moves = TestSupport.Collector<RelayMovedEvent>()
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

            raw = RawPeer(Identity.generate(PeerRole.BOT), relayA.url).apply { authenticate() }

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
            val rawMoved = assertNotNull(raw.readEnvelope(), "the raw peer did not receive relay_moved")
            assertEquals(RelayType.RELAY_MOVED, rawMoved.type)
            val token = assertNotNull(rawMoved.token)

            RawPeer(raw.identity, relayB.url).use { follower ->
                follower.open()
                follower.helloSigned(moveToken = token)
                val ok = assertNotNull(follower.readEnvelope())
                assertEquals(RelayType.HELLO_OK, ok.type, "relay B rejected the first use of the token: $ok")
            }

            RawPeer(raw.identity, relayB.url).use { replayer ->
                replayer.open()
                replayer.helloSigned(moveToken = token)
                val rejected = assertNotNull(replayer.readEnvelope())
                assertEquals(RelayType.ERROR, rejected.type)
                assertEquals("token_used", TestSupport.errorCode(rejected))
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
            RawPeer(Identity.generate(PeerRole.BOT), relayA.url).use { peer ->
                peer.authenticate()
                assertEquals(1, relayA.moveTo(relayB.url, ttlMs = 200))
                val moved = assertNotNull(peer.readEnvelope())
                val token = assertNotNull(moved.token)
                Thread.sleep(500)

                RawPeer(peer.identity, relayB.url).use { late ->
                    late.open()
                    late.helloSigned(moveToken = token)
                    val rejected = assertNotNull(late.readEnvelope())
                    assertEquals(RelayType.ERROR, rejected.type)
                    assertEquals("unauthorized", TestSupport.errorCode(rejected))
                    // A plain reconnect (no token) still works: the token is only an admission ticket.
                    RawPeer(peer.identity, relayB.url).use { plain ->
                        assertEquals(RelayType.HELLO_OK, plain.authenticate().type)
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
        val otherSecret = B64Url.encode(Primitives.randomBytes(32))
        val relayA = TestSupport.testRelay(moveSecret = secret, dataDir = tempDir())
        val relayB = TestSupport.testRelay(moveSecret = otherSecret, dataDir = tempDir())
        try {
            RawPeer(Identity.generate(PeerRole.BOT), relayA.url).use { peer ->
                peer.authenticate()
                relayA.moveTo(relayB.url, ttlMs = 60_000)
                val moved = assertNotNull(peer.readEnvelope())
                val token = assertNotNull(moved.token)

                RawPeer(peer.identity, relayB.url).use { follower ->
                    follower.open()
                    follower.helloSigned(moveToken = token)
                    val rejected = assertNotNull(follower.readEnvelope())
                    assertEquals(RelayType.ERROR, rejected.type)
                    assertEquals("unauthorized", TestSupport.errorCode(rejected))
                    assertNull(rejected.relayId)
                }
            }
        } finally {
            relayA.stop()
            relayB.stop()
        }
    }
}
