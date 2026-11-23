package dev.phonebridge.desktop

import dev.phonebridge.core.crypto.E2ESession
import dev.phonebridge.core.crypto.NoiseRole
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.AppMessage
import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.AppRequest
import dev.phonebridge.core.model.AppResponse
import dev.phonebridge.core.model.CommandType
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RequestControlResult
import dev.phonebridge.core.model.StatusArgs
import dev.phonebridge.core.model.StatusResult
import dev.phonebridge.core.pairing.BotRecord
import dev.phonebridge.core.pairing.InMemoryBotRegistry
import dev.phonebridge.core.pairing.PairingClient
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.pairing.PendingApproval
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.desktop.executor.BotSession
import dev.phonebridge.desktop.session.DesktopSessionHandler
import dev.phonebridge.desktop.state.DesktopState
import dev.phonebridge.desktop.state.InMemoryActivityLog
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopSessionHandlerTest {
    private var now = 1_700_000_000_000L
    private val device = Identity.generate(PeerRole.DEVICE, "Test PC")
    private val registry = InMemoryBotRegistry(clock = { now })
    private val pairing = PairingManager(device, registry, clock = { now })
    private val log = InMemoryActivityLog()
    private val state = DesktopState("Test PC")
    private val bot = Identity.generate(PeerRole.BOT, "Claude")

    private val sent = mutableListOf<Pair<String, ByteArray>>()
    private val executed = mutableListOf<AppRequest>()
    private var pending: PendingApproval? = null

    private val handler = DesktopSessionHandler(
        identity = device,
        registry = registry,
        pairingManager = pairing,
        state = state,
        activity = log,
        executor = { _: BotSession, request: AppRequest ->
            executed.add(request)
            if (request.cmd == "request_control") {
                AppMessages.response(request.id, CommandType.REQUEST_CONTROL, RequestControlResult(granted = false, position = 2))
            } else {
                AppMessages.response(request.id, CommandType.STATUS, StatusResult(device = "test pc", screenOn = true))
            }
        },
        send = { to, payload -> sent.add(to to payload) },
        onPairing = { approval -> pending = approval },
        onChange = {},
        clock = { now },
    )

    private fun route(payload: ByteArray) = RelayEnvelope.route(from = bot.peerId, to = device.peerId, payload = payload)

    private fun pairBot(level: PermissionLevel = PermissionLevel.CONTROL, enabled: Boolean = true) {
        registry.put(
            BotRecord(
                botId = bot.peerId,
                name = "Claude",
                publicKey = bot.publicKeyBase64,
                level = level,
                enabled = enabled,
                pairedAt = now,
                lastSeen = now,
            ),
        )
    }

    private fun connect(): E2ESession {
        val botSession = E2ESession(NoiseRole.INITIATOR, bot.keys)
        handler.onRoute(route(botSession.writeHandshakeFrame()))
        val message2 = sent.removeAt(sent.lastIndex).second
        botSession.readHandshakeFrame(message2)
        handler.onRoute(route(botSession.writeHandshakeFrame()))
        assertTrue(botSession.isEstablished)
        return botSession
    }

    @Test
    fun aPairRequestWaitsForTheUser() {
        val offer = pairing.createOffer("wss://relay.test:8443", "Test PC")
        val sealed = PairingClient.createRequest(offer, bot, requestedLevel = PermissionLevel.FULL)

        handler.onRoute(route(sealed.sealed))

        val approval = assertNotNull(pending)
        assertEquals("Claude", approval.botName)
        assertEquals(bot.peerId, approval.botId)
        assertEquals(PermissionLevel.FULL, approval.requestedLevel)
        assertTrue(sent.isEmpty(), "nothing is answered before Allow")
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun approvingSendsASealedResultAndStoresTheBot() {
        val offer = pairing.createOffer("wss://relay.test:8443", "Test PC")
        val sealed = PairingClient.createRequest(offer, bot, requestedLevel = PermissionLevel.CONTROL)
        handler.onRoute(route(sealed.sealed))
        val approval = assertNotNull(pending)

        assertTrue(handler.approve(approval, PermissionLevel.CONTROL) != null)

        val frame = sent.single()
        assertEquals(bot.peerId, frame.first)
        val result = PairingClient.openResult(offer, frame.second)
        assertTrue(result.ok)
        assertEquals(PermissionLevel.CONTROL, result.grantedLevel)
        assertEquals("Claude", registry.get(bot.peerId)?.name)
        assertTrue(log.recent().any { it.cmd == "pair_approve" })
    }

    @Test
    fun denyingDoesNotStoreTheBot() {
        val offer = pairing.createOffer("wss://relay.test:8443", "Test PC")
        val sealed = PairingClient.createRequest(offer, bot)
        handler.onRoute(route(sealed.sealed))
        val approval = assertNotNull(pending)

        assertTrue(handler.deny(approval))

        val result = PairingClient.openResult(offer, sent.single().second)
        assertFalse(result.ok)
        assertEquals("denied", result.reason)
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun aPairedBotCompletesNoiseAndGetsACommandResponse() {
        pairBot()
        val botSession = connect()
        assertTrue(state.isConnected(bot.peerId))

        val request = AppMessages.request("req-1", StatusArgs)
        handler.onRoute(route(botSession.sealText(AppMessages.encodeRequest(request))))

        val frame = sent.removeAt(sent.lastIndex).second
        val response = PbJson.decodeFromString(AppResponse.serializer(), botSession.openText(frame))
        assertTrue(response.ok)
        assertEquals("req-1", response.id)
        assertEquals("test pc", response.result?.get("device")?.jsonPrimitive?.content)
        assertEquals("status", executed.single().cmd)
        assertTrue(log.recent().any { it.cmd == "status" && it.ok })
        assertEquals(now, registry.get(bot.peerId)?.lastSeen)
    }

    @Test
    fun aDisabledBotIsIgnored() {
        pairBot(enabled = false)
        val botSession = E2ESession(NoiseRole.INITIATOR, bot.keys)
        handler.onRoute(route(botSession.writeHandshakeFrame()))
        assertTrue(sent.isEmpty())
        assertFalse(state.isConnected(bot.peerId))
    }

    @Test
    fun aQueuedRequestControlAlsoEmitsTheQueuePositionEvent() {
        pairBot()
        val botSession = connect()
        sent.clear()

        val request = AppMessages.request("q1", CommandType.REQUEST_CONTROL, dev.phonebridge.core.model.RequestControlArgs)
        handler.onRoute(route(botSession.sealText(AppMessages.encodeRequest(request))))

        val messages = sent.map { AppMessages.parse(botSession.openText(it.second)) }
        assertTrue(messages.any { it is AppMessage.Response && it.response.id == "q1" })
        val position = messages.filterIsInstance<AppMessage.Event>().mapNotNull { event ->
            if (event.event.event == "queue_position") event.event.data["position"]?.jsonPrimitive?.content?.toInt() else null
        }
        assertEquals(listOf(2), position)
    }

    @Test
    fun kickSendsASealedKickedEventAndClosesTheSession() {
        pairBot()
        connect()
        sent.clear()

        handler.closeSessions(listOf(bot.peerId), "kicked by the user")

        val frame = sent.single()
        assertEquals(bot.peerId, frame.first)
        assertFalse(state.isConnected(bot.peerId))
        assertEquals(0, handler.liveBotIds().size)
    }

    @Test
    fun kickedEventIsReadableByTheBot() {
        pairBot()
        val botSession = connect()
        sent.clear()

        handler.closeSessions(listOf(bot.peerId), "kicked by the user")

        val message = AppMessages.parse(botSession.openText(sent.single().second))
        assertTrue(message is AppMessage.Event)
        assertEquals("kicked", message.event.event)
    }

    @Test
    fun sendEventSealsAnEventForOneBot() {
        pairBot()
        val botSession = connect()
        sent.clear()

        assertTrue(handler.sendEvent(bot.peerId, AppMessages.queuePosition(4)))

        val message = AppMessages.parse(botSession.openText(sent.single().second)) as AppMessage.Event
        assertEquals("queue_position", message.event.event)
        assertFalse(handler.sendEvent("unknown-bot", AppMessages.queuePosition(1)))
    }

    @Test
    fun aTamperedTransportFrameDropsTheSession() {
        pairBot()
        val botSession = connect()
        val sealed = botSession.sealText(AppMessages.encodeRequest(AppMessages.request("r", StatusArgs)))
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 0x01).toByte()

        handler.onRoute(route(sealed))

        assertFalse(state.isConnected(bot.peerId))
        assertTrue(executed.isEmpty())
    }
}
