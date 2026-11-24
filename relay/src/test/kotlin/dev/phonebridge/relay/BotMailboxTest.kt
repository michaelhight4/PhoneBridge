package dev.phonebridge.relay

import dev.phonebridge.core.crypto.BotMessageCrypto
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayBotMessage
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayPeerEntry
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotMailboxTest {

    private fun bot(name: String): Identity = Identity.generate(PeerRole.BOT, name)

    private fun entry(
        identity: Identity,
        deviceId: String = "device-1",
        deviceName: String = "Test PC",
        enabled: Boolean = true,
        canMessage: Boolean = true,
    ): RelayPeerEntry = RelayPeerEntry(
        peerId = identity.peerId,
        name = identity.name ?: "bot",
        publicKey = identity.publicKeyBase64,
        fingerprint = identity.fingerprint,
        deviceId = deviceId,
        deviceName = deviceName,
        enabled = enabled,
        canMessage = canMessage,
    )

    private fun message(
        id: String,
        from: Identity,
        to: Identity,
        createdAt: Long = 1_000L,
        deviceId: String? = "device-1",
        payload: String = B64Url.encode("$id payload".toByteArray(Charsets.UTF_8)),
    ): RelayBotMessage = RelayBotMessage(
        id = id,
        from = from.peerId,
        fromName = from.name,
        to = to.peerId,
        deviceId = deviceId,
        createdAt = createdAt,
        payload = payload,
    )

    private fun mailbox(
        dir: Path? = null,
        clock: () -> Long = { 1_000L },
        maxBotMessageBytes: Int = 64 * 1024,
        maxMailboxMessagesPerBot: Int = 100,
        maxMailboxBytesPerBot: Int = 1024 * 1024,
        maxTotalMailboxMessages: Int = 5000,
        botMessageTtlMs: Long = 7L * 24 * 60 * 60 * 1000,
    ): BotMailbox = BotMailbox(
        dataDir = dir?.toString(),
        maxBotMessageBytes = maxBotMessageBytes,
        maxMailboxMessagesPerBot = maxMailboxMessagesPerBot,
        maxMailboxBytesPerBot = maxMailboxBytesPerBot,
        maxTotalMailboxMessages = maxTotalMailboxMessages,
        botMessageTtlMs = botMessageTtlMs,
        clock = clock,
    )

    @Test
    fun `peers and mailboxes survive a relay restart`() {
        val dir = Files.createTempDirectory("pb-bot-mailbox")
        val alice = bot("Alice")
        val bob = bot("Bob")
        try {
            val first = mailbox(dir = dir, clock = { 1_000L })
            first.syncDevicePeers("device-1", "Test PC", listOf(entry(alice), entry(bob)))
            val stored = first.enqueue(message("m-1", alice, bob, createdAt = 1_000L))

            assertTrue(Files.exists(dir.resolve(BotMailbox.PEERS_FILE)))
            assertTrue(Files.exists(dir.resolve(BotMailbox.MAILBOXES_FILE)))

            val restarted = mailbox(dir = dir, clock = { 1_000L })
            assertTrue(restarted.authorizeSend(alice.peerId, bob.peerId, "device-1"))
            assertEquals(1, restarted.totalMessages())
            assertEquals(listOf("m-1"), restarted.readInbox(bob.peerId, 10, false).map { it.id })
            assertEquals(stored, restarted.readInbox(bob.peerId, 10, false).single())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `messages expire after their ttl`() {
        var now = 1_000L
        val alice = bot("Alice")
        val bob = bot("Bob")
        val box = mailbox(botMessageTtlMs = 1_000L, clock = { now })
        box.enqueue(message("old", alice, bob))
        assertEquals(1, box.totalMessages())

        now = 2_000L
        assertEquals(0, box.totalMessages())
        assertTrue(box.readInbox(bob.peerId, 10, false).isEmpty())
    }

    @Test
    fun `size count bytes and total caps are enforced`() {
        val alice = bot("Alice")
        val bob = bot("Bob")
        val carol = bot("Carol")
        val dave = bot("Dave")

        val sizeLimited = mailbox(maxBotMessageBytes = 8)
        assertFailsWith<BotMailbox.MailboxFullException> {
            sizeLimited.enqueue(message("big", alice, bob, payload = B64Url.encode("123456789".toByteArray())))
        }

        val countLimited = mailbox(maxMailboxMessagesPerBot = 2)
        countLimited.enqueue(message("one", alice, bob))
        countLimited.enqueue(message("two", alice, bob))
        assertFailsWith<BotMailbox.MailboxFullException> {
            countLimited.enqueue(message("three", alice, bob))
        }

        val byteLimited = mailbox(maxMailboxBytesPerBot = 5)
        byteLimited.enqueue(message("one", alice, bob, payload = B64Url.encode("abc".toByteArray())))
        assertFailsWith<BotMailbox.MailboxFullException> {
            byteLimited.enqueue(message("two", alice, bob, payload = B64Url.encode("xyz".toByteArray())))
        }

        val totalLimited = mailbox(maxTotalMailboxMessages = 2)
        totalLimited.enqueue(message("one", alice, bob))
        totalLimited.enqueue(message("two", alice, carol))
        assertFailsWith<BotMailbox.MailboxFullException> {
            totalLimited.enqueue(message("three", alice, dave))
        }
    }

    @Test
    fun `peer lists and sends require enabled messaging bots`() {
        val alice = bot("Alice")
        val bob = bot("Bob")
        val noMessaging = bot("NoMessaging")
        val disabled = bot("Disabled")
        val box = mailbox()
        box.syncDevicePeers(
            "device-1",
            "Test PC",
            listOf(entry(alice), entry(bob), entry(noMessaging, canMessage = false), entry(disabled, enabled = false)),
        )

        assertTrue(box.authorizeSend(alice.peerId, bob.peerId, "device-1"))
        assertFalse(box.authorizeSend(alice.peerId, noMessaging.peerId, "device-1"))
        assertFalse(box.authorizeSend(alice.peerId, disabled.peerId, "device-1"))
        assertFalse(box.authorizeSend(alice.peerId, bob.peerId, "another-device"))
        assertFalse(box.authorizeSend(alice.peerId, alice.peerId, "device-1"))

        val result = box.listPeersForBot(alice.peerId, "device-1") { it == bob.peerId }
        assertTrue(result is BotMailbox.PeersResult.Allowed)
        val peers = result.peers.associateBy { it.peerId }
        assertEquals(setOf(bob.peerId, noMessaging.peerId), peers.keys)
        assertEquals(true, peers[bob.peerId]!!.online)
        assertEquals(false, peers[noMessaging.peerId]!!.online)
        assertEquals(false, peers[noMessaging.peerId]!!.canMessage)
        assertEquals(bob.fingerprint, peers[bob.peerId]!!.fingerprint)
        assertEquals("Test PC", peers[bob.peerId]!!.deviceName)

        val denied = box.listPeersForBot(noMessaging.peerId, "device-1") { false }
        assertTrue(denied is BotMailbox.PeersResult.Denied)
    }

    @Test
    fun `the relay pushes messages and keeps them for mailbox reads`() {
        val relay = TestSupport.testRelay(dataDir = Files.createTempDirectory("pb-bot-relay").toString())
        val device = Identity.generate(PeerRole.DEVICE, "Phone")
        val alice = bot("Alice")
        val bob = bot("Bob")
        val clients = mutableListOf<RelayClient>()
        val deviceEvents = TestSupport.Collector<RelayEnvelope>()
        val aliceEvents = TestSupport.Collector<RelayEnvelope>()
        val bobDeliveries = TestSupport.Collector<RelayEnvelope>()
        val bobLists = TestSupport.Collector<RelayEnvelope>()
        val bobEvents = TestSupport.Collector<RelayEnvelope>()
        try {
            val deviceClient = start(relay, device, clients).apply { onMessage = { deviceEvents.add(it) } }
            val aliceClient = start(relay, alice, clients).apply { onMessage = { aliceEvents.add(it) } }
            var bobClient = start(relay, bob, clients).apply {
                onMsgDeliver = { bobDeliveries.add(it) }
                onMsgList = { bobLists.add(it) }
                onMessage = { bobEvents.add(it) }
            }

            deviceClient.peersSync("Test PC", listOf(entry(alice), entry(bob)))
            assertNotNull(deviceEvents.awaitWhere { it.type == RelayType.PEERS_SYNCED })

            val frame = BotMessageCrypto.seal(
                sender = alice.keys,
                recipientPublicKey = bob.publicKey,
                fromPeerId = alice.peerId,
                toPeerId = bob.peerId,
                messageId = "m-1",
                text = "hello Bob",
            )
            aliceClient.msgSend(message("m-1", alice, bob, payload = B64Url.encode(frame)))

            assertNotNull(aliceEvents.awaitWhere { it.type == RelayType.MSG_SENT && it.message?.id == "m-1" })
            val delivered = assertNotNull(bobDeliveries.awaitWhere { it.message?.id == "m-1" })
            val deliveredMessage = assertNotNull(delivered.message)
            assertEquals("m-1", deliveredMessage.id)
            val plain = BotMessageCrypto.open(
                recipient = bob.keys,
                fromPeerId = alice.peerId,
                toPeerId = bob.peerId,
                messageId = "m-1",
                frame = B64Url.decode(deliveredMessage.payload),
                expectedSenderPublicKey = alice.publicKey,
            )
            assertEquals("hello Bob", plain.text)

            // The push is a notification, not a consume: the copy stays in the mailbox until ack/read.
            bobLists.clear()
            bobClient.msgInbox(consume = false)
            val listed = assertNotNull(bobLists.awaitWhere { it.messages?.any { m -> m.id == "m-1" } == true })
            assertEquals(listOf("m-1"), listed.messages!!.map { it.id })

            bobClient.msgAck(listOf("m-1"))
            assertNotNull(bobEvents.awaitWhere { it.type == RelayType.MSG_ACKED && it.ackIds == listOf("m-1") })

            bobLists.clear()
            bobClient.msgInbox(consume = false)
            val empty = assertNotNull(bobLists.awaitWhere { it.type == RelayType.MSG_LIST })
            assertTrue(empty.messages.orEmpty().isEmpty())

            // Offline delivery: the second message waits in the relay until Bob reconnects.
            bobClient.stop()
            aliceClient.msgSend(message("m-2", alice, bob))
            assertNotNull(aliceEvents.awaitWhere { it.type == RelayType.MSG_SENT && it.message?.id == "m-2" })

            bobClient = start(relay, bob, clients).apply { onMsgList = { bobLists.add(it) } }
            bobLists.clear()
            bobClient.msgInbox(consume = true)
            val stored = assertNotNull(bobLists.awaitWhere { it.messages?.any { m -> m.id == "m-2" } == true })
            assertEquals(listOf("m-2"), stored.messages!!.map { it.id })
            bobLists.clear()
            bobClient.msgInbox()
            assertNotNull(bobLists.awaitWhere { it.type == RelayType.MSG_LIST && it.messages.orEmpty().isEmpty() })
        } finally {
            clients.forEach { runCatching { it.stop() } }
            relay.stop()
        }
    }

    @Test
    fun `the relay rate limits and size limits bot messages`() {
        val rateRelay = TestSupport.testRelay(
            botMessageRateLimit = RateLimitConfig(maxMessages = 1, windowMs = 60_000, burst = 2),
        )
        val alice = bot("Alice")
        val bob = bot("Bob")
        val device = Identity.generate(PeerRole.DEVICE, "Phone")
        val rateClients = mutableListOf<RelayClient>()
        val errors = TestSupport.Collector<RelayClientError>()
        val acks = TestSupport.Collector<RelayEnvelope>()
        try {
            val deviceClient = start(rateRelay, device, rateClients)
            val sender = start(rateRelay, alice, rateClients).apply {
                onError = { errors.add(it) }
                onMessage = { acks.add(it) }
            }
            deviceClient.peersSync("Test PC", listOf(entry(alice), entry(bob)))

            // The bucket holds two tokens; the third send must be refused.
            sender.msgSend(message("m-1", alice, bob))
            sender.msgSend(message("m-2", alice, bob))
            assertNotNull(acks.awaitWhere { it.type == RelayType.MSG_SENT && it.message?.id == "m-1" })
            assertNotNull(acks.awaitWhere { it.type == RelayType.MSG_SENT && it.message?.id == "m-2" })
            sender.msgSend(message("m-3", alice, bob))
            assertEquals("rate_limited", assertNotNull(errors.awaitWhere { it.code == "rate_limited" }).code)
        } finally {
            rateClients.forEach { runCatching { it.stop() } }
            rateRelay.stop()
        }

        val sizeRelay = TestSupport.testRelay(maxBotMessageBytes = 8)
        val sizeClients = mutableListOf<RelayClient>()
        val sizeErrors = TestSupport.Collector<RelayClientError>()
        try {
            start(sizeRelay, alice, sizeClients).apply { onError = { sizeErrors.add(it) } }
                .msgSend(message("huge", alice, bob, payload = B64Url.encode("this is too big".toByteArray())))
            assertEquals("too_large", assertNotNull(sizeErrors.awaitWhere { it.code == "too_large" }).code)
        } finally {
            sizeClients.forEach { runCatching { it.stop() } }
            sizeRelay.stop()
        }
    }

    @Test
    fun `ack removes only pending ids and preserves request order`() {
        val alice = bot("Alice")
        val bob = bot("Bob")
        val mailbox = BotMailbox()
        mailbox.enqueue(message("m-1", alice, bob))
        mailbox.enqueue(message("m-2", alice, bob))
        mailbox.enqueue(message("m-3", alice, bob))

        val removed = mailbox.ack(bob.peerId, listOf("m-3", "missing-id", "m-1", "m-3"))
        assertEquals(listOf("m-3", "m-1"), removed)
        assertEquals(listOf("m-2"), mailbox.readInbox(bob.peerId, limit = 10, consume = false).map { it.id })
    }

    private fun start(relay: RelayHandle, identity: Identity, clients: MutableList<RelayClient>): RelayClient =
        TestSupport.client(relay, identity).apply {
            start()
            assertTrue(awaitConnected(5_000), "$identity did not connect")
            clients.add(this)
        }
}
