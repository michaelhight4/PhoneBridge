package dev.phonebridge.e2e

import dev.phonebridge.core.crypto.BotMessageCrypto
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayBotMessage
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayPeerEntry
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.RelayHandle
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import dev.phonebridge.relay.client.RelayClientError
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bot-to-bot messaging over the real relay and the real `RelayClient`: a device publishes its paired
 * bots, the relay enforces its ACL, and the encrypted payload is only readable by the recipient.
 */
class BotMessagingE2eTest {

    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closers.asReversed().forEach { runCatching { it.close() } }
        closers.clear()
    }

    private fun entry(
        identity: Identity,
        deviceId: String,
        deviceName: String = "Phone",
        canMessage: Boolean = true,
    ): RelayPeerEntry = RelayPeerEntry(
        peerId = identity.peerId,
        name = identity.name ?: "bot",
        publicKey = identity.publicKeyBase64,
        fingerprint = identity.fingerprint,
        deviceId = deviceId,
        deviceName = deviceName,
        enabled = true,
        canMessage = canMessage,
    )

    private fun message(
        id: String,
        from: Identity,
        to: Identity,
        deviceId: String,
        frame: ByteArray,
    ): RelayBotMessage = RelayBotMessage(
        id = id,
        from = from.peerId,
        fromName = from.name,
        to = to.peerId,
        deviceId = deviceId,
        createdAt = System.currentTimeMillis(),
        payload = B64Url.encode(frame),
    )

    private fun awaitWhere(
        queue: LinkedBlockingQueue<RelayEnvelope>,
        what: String,
        predicate: (RelayEnvelope) -> Boolean,
    ): RelayEnvelope {
        val deadline = System.currentTimeMillis() + E2e.DEFAULT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val envelope = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(envelope)) return envelope
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun awaitError(
        queue: LinkedBlockingQueue<RelayClientError>,
        code: String,
    ): RelayClientError {
        val deadline = System.currentTimeMillis() + E2e.DEFAULT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val error = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (error.code == code) return error
        }
        throw AssertionError("timed out waiting for the $code error")
    }

    @Test
    fun `bots see each other and exchange encrypted store and forward messages`() {
        val relay: RelayHandle = E2e.startRelay(dataDir = E2e.tempDir("pb-e2e-botmsg")).also { closers += it }
        val device = Identity.generate(PeerRole.DEVICE, "Phone")
        val alice = Identity.generate(PeerRole.BOT, "Alice")
        val bob = Identity.generate(PeerRole.BOT, "Bob")
        val muted = Identity.generate(PeerRole.BOT, "Muted")

        fun client(identity: Identity): RelayClient = RelayClient(
            RelayClientConfig(url = relay.url, identity = identity),
        ).apply {
            start()
            assertTrue(awaitConnected(E2e.DEFAULT_TIMEOUT_MS), "$identity did not connect")
            closers += AutoCloseable { stop() }
        }

        val deviceEvents = LinkedBlockingQueue<RelayEnvelope>()
        val peerResults = LinkedBlockingQueue<RelayEnvelope>()
        val sent = LinkedBlockingQueue<RelayEnvelope>()
        val deliveries = LinkedBlockingQueue<RelayEnvelope>()
        val inboxLists = LinkedBlockingQueue<RelayEnvelope>()
        val acks = LinkedBlockingQueue<RelayEnvelope>()
        val errors = LinkedBlockingQueue<RelayClientError>()

        val deviceClient = client(device).apply { onMessage = { deviceEvents.add(it) } }
        val aliceClient = client(alice).apply { onMessage = { sent.add(it) } }
        var bobClient = client(bob).apply {
            onMsgDeliver = { deliveries.add(it) }
            onMsgList = { inboxLists.add(it) }
            onMessage = { acks.add(it) }
        }
        val mutedClient = client(muted).apply { onError = { errors.add(it) } }

        deviceClient.peersSync(
            deviceName = "Phone",
            peers = listOf(entry(alice, device.peerId), entry(bob, device.peerId), entry(muted, device.peerId, canMessage = false)),
        )
        assertNotNull(
            awaitWhere(deviceEvents, "peers_synced") { it.type == RelayType.PEERS_SYNCED },
        ).let { assertEquals(3, it.count) }

        aliceClient.onPeersResult = { peerResults.add(it) }
        aliceClient.peersList(deviceId = device.peerId)
        val visible = awaitWhere(peerResults, "peers_result") { it.type == RelayType.PEERS_RESULT }
        val byId = visible.peers.orEmpty().associateBy { it.peerId }
        assertEquals(setOf(bob.peerId, muted.peerId), byId.keys)
        assertEquals(true, byId[bob.peerId]!!.canMessage)
        assertEquals(false, byId[muted.peerId]!!.canMessage)
        assertEquals(device.peerId, byId[bob.peerId]!!.deviceId)

        val text = "hello Bob — from Alice 🚀"
        val frame = BotMessageCrypto.seal(
            sender = alice.keys,
            recipientPublicKey = bob.publicKey,
            fromPeerId = alice.peerId,
            toPeerId = bob.peerId,
            messageId = "m-1",
            text = text,
            replyTo = "m-0",
        )
        aliceClient.msgSend(message("m-1", alice, bob, device.peerId, frame))

        val confirmation = awaitWhere(sent, "msg_sent") { it.type == RelayType.MSG_SENT && it.message?.id == "m-1" }
        assertEquals("m-1", confirmation.message?.id)
        val pushed = awaitWhere(deliveries, "msg_deliver") { it.message?.id == "m-1" }
        val stored = assertNotNull(pushed.message)
        val plain = BotMessageCrypto.open(
            recipient = bob.keys,
            fromPeerId = alice.peerId,
            toPeerId = bob.peerId,
            messageId = stored.id,
            frame = B64Url.decode(stored.payload),
            expectedSenderPublicKey = alice.publicKey,
        )
        assertEquals(text, plain.text)
        assertEquals("m-0", plain.replyTo)

        bobClient.msgInbox(consume = false)
        val listed = awaitWhere(inboxLists, "the first msg_list") { it.messages?.any { m -> m.id == "m-1" } == true }
        assertEquals(listOf("m-1"), listed.messages!!.map { it.id })

        bobClient.msgAck(listOf("m-1"))
        awaitWhere(acks, "msg_acked") { it.type == RelayType.MSG_ACKED && it.ackIds == listOf("m-1") }
        inboxLists.clear()
        bobClient.msgInbox(consume = false)
        val empty = awaitWhere(inboxLists, "an empty msg_list") { it.type == RelayType.MSG_LIST }
        assertTrue(empty.messages.orEmpty().isEmpty())

        // A bot whose messaging switch is off cannot send, even though it is paired and online.
        val mutedFrame = BotMessageCrypto.seal(
            sender = muted.keys,
            recipientPublicKey = bob.publicKey,
            fromPeerId = muted.peerId,
            toPeerId = bob.peerId,
            messageId = "m-muted",
            text = "should be refused",
        )
        mutedClient.msgSend(message("m-muted", muted, bob, device.peerId, mutedFrame))
        assertEquals("permission_denied", awaitError(errors, "permission_denied").code)

        // Offline messages wait in the relay until the recipient comes back and reads its inbox.
        bobClient.stop()
        val offlineFrame = BotMessageCrypto.seal(
            sender = alice.keys,
            recipientPublicKey = bob.publicKey,
            fromPeerId = alice.peerId,
            toPeerId = bob.peerId,
            messageId = "m-2",
            text = "stored while you were away",
        )
        aliceClient.msgSend(message("m-2", alice, bob, device.peerId, offlineFrame))
        awaitWhere(sent, "msg_sent for the offline message") { it.type == RelayType.MSG_SENT && it.message?.id == "m-2" }

        bobClient = client(bob).apply { onMsgList = { inboxLists.add(it) } }
        inboxLists.clear()
        bobClient.msgInbox(consume = true)
        val recovered = awaitWhere(inboxLists, "the stored message") { it.messages?.any { m -> m.id == "m-2" } == true }
        val recoveredPlain = BotMessageCrypto.open(
            recipient = bob.keys,
            fromPeerId = alice.peerId,
            toPeerId = bob.peerId,
            messageId = "m-2",
            frame = B64Url.decode(recovered.messages!!.single().payload),
        )
        assertEquals("stored while you were away", recoveredPlain.text)
    }
}
