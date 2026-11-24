package dev.phonebridge.desktop

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.pairing.BotRecord
import dev.phonebridge.desktop.app.DesktopBridge
import dev.phonebridge.desktop.app.RelayResult
import dev.phonebridge.relay.Relay
import dev.phonebridge.relay.RelayConfig
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The desktop switch has to change both the saved registry and the relay's live bot ACL. */
class DesktopBridgeMessagingTest {

    private fun record(identity: Identity, canMessage: Boolean = true): BotRecord = BotRecord(
        botId = identity.peerId,
        name = identity.name ?: "bot",
        publicKey = identity.publicKeyBase64,
        canMessage = canMessage,
    )

    private fun await(what: String, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `setCanMessage persists the switch and resyncs the relay`() {
        val relayDir = Files.createTempDirectory("pb-botmsg-relay")
        val bridgeDir = Files.createTempDirectory("pb-botmsg-desktop")
        val relay = Relay.start(
            RelayConfig(port = 0, bindAddress = "127.0.0.1", insecureDev = true, dataDir = relayDir.toString()),
        )
        val bridge = DesktopBridge(
            dataDir = bridgeDir,
            log = {},
            device = FakeDesktopDevice(),
            screenSource = FakeDesktopDevice(),
        )
        val alice = Identity.generate(PeerRole.BOT, "Alice")
        val bob = Identity.generate(PeerRole.BOT, "Bob")
        val quiet = Identity.generate(PeerRole.BOT, "Quiet")
        val botClient = RelayClient(RelayClientConfig(url = relay.url, identity = bob))
        val results = LinkedBlockingQueue<RelayEnvelope>()
        try {
            bridge.registry.put(record(alice))
            bridge.registry.put(record(bob))
            bridge.registry.put(record(quiet, canMessage = false))

            botClient.onPeersResult = { results.add(it) }
            botClient.start()
            assertTrue(botClient.awaitConnected(10_000L), "the bot must reach the relay")

            assertTrue(bridge.setCustomRelay(relay.url, null) is RelayResult.Ok)
            await("the desktop to connect to the relay") { relay.peers().contains(bridge.identity.peerId) }

            fun query(): RelayEnvelope? {
                results.clear()
                botClient.peersList(deviceId = bridge.identity.peerId)
                return results.poll(3, TimeUnit.SECONDS)
            }

            fun canMessageOf(result: RelayEnvelope?, botId: String): Boolean? =
                result?.peers?.firstOrNull { it.peerId == botId }?.canMessage

            await("the first peers_result") { query()?.type == RelayType.PEERS_RESULT }
            val initial = query()
            assertNotNull(initial)
            assertEquals(true, canMessageOf(initial, alice.peerId))
            assertEquals(false, canMessageOf(initial, quiet.peerId))

            assertTrue(bridge.setCanMessage(alice.peerId, false))
            assertFalse(bridge.bots().first { it.botId == alice.peerId }.canMessage)
            assertTrue(bridge.activityEntries().any { it.cmd == "bot_msg_off" })
            await("the relay to see messaging switched off") {
                canMessageOf(query(), alice.peerId) == false
            }

            assertTrue(bridge.setCanMessage(alice.peerId, true))
            assertTrue(bridge.bots().first { it.botId == alice.peerId }.canMessage)
            assertTrue(bridge.activityEntries().any { it.cmd == "bot_msg_on" })
            await("the relay to see messaging switched back on") {
                canMessageOf(query(), alice.peerId) == true
            }
        } finally {
            botClient.stop()
            bridge.stop()
            relay.stop()
            bridgeDir.toFile().deleteRecursively()
            relayDir.toFile().deleteRecursively()
        }
    }
}
