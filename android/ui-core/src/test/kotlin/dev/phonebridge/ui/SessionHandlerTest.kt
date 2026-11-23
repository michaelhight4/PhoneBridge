package dev.phonebridge.ui

import dev.phonebridge.core.crypto.E2ESession
import dev.phonebridge.core.crypto.NoiseRole
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.AppMessage
import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.AppResponse
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.StatusArgs
import dev.phonebridge.core.pairing.BotRecord
import dev.phonebridge.core.pairing.InMemoryBotRegistry
import dev.phonebridge.core.pairing.PairingClient
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.pairing.PendingApproval
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.executor.BotSession
import dev.phonebridge.executor.ControlArbiter
import dev.phonebridge.executor.Request
import dev.phonebridge.executor.Response
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end checks of the transport seam: a fake bot builds real Noise frames and the handler must
 * answer them exactly as the Android service does on a phone.
 */
class SessionHandlerTest {

    private var now = 1_700_000_000_000L
    private val device = Identity.generate(PeerRole.DEVICE, "phone")
    private val registry = InMemoryBotRegistry(clock = { now })
    private val pairing = PairingManager(device, registry, clock = { now })
    private val log = InMemoryActivityLog(clock = { now })
    private val state = BridgeState(ControlArbiter(clock = { now }), registry, log, clock = { now })

    private val bot = Identity.generate(PeerRole.BOT, "Claude")
    private val sent = mutableListOf<Pair<String, ByteArray>>()
    private val executed = mutableListOf<Request>()
    private var pending: PendingApproval? = null
    private var pendingExpiry = 0L

    private val handler = SessionHandler(
        identity = device,
        registry = registry,
        pairingManager = pairing,
        state = state,
        executor = { _: BotSession, request: Request ->
            executed.add(request)
            Response.success(request.id, linkedMapOf("device" to "test"))
        },
        send = { to, payload -> sent.add(to to payload) },
        onPairing = { approval, expiresAtMs ->
            pending = approval
            pendingExpiry = expiresAtMs
        },
        onSessionsChanged = {},
    )

    private fun route(payload: ByteArray): RelayEnvelope =
        RelayEnvelope.route(from = bot.peerId, to = device.peerId, payload = payload)

    private fun pairBot() {
        registry.put(
            BotRecord(
                botId = bot.peerId,
                name = "Claude",
                publicKey = bot.publicKeyBase64,
                level = PermissionLevel.CONTROL,
                enabled = true,
                pairedAt = now,
                lastSeen = now,
            ),
        )
    }

    /** Runs the three-way Noise XX handshake through the handler. */
    private fun connect(botSession: E2ESession): E2ESession {
        handler.onRoute(route(botSession.writeHandshakeFrame()))
        val msg2 = sent.removeAt(sent.lastIndex).second
        botSession.readHandshakeFrame(msg2)
        handler.onRoute(route(botSession.writeHandshakeFrame()))
        assertTrue(botSession.isEstablished, "the bot side must complete the handshake")
        return botSession
    }

    @Test
    fun decryptsAPairRequestAgainstTheLiveOffer() {
        val offer = pairing.createOffer("ws://127.0.0.1:8443", deviceName = "phone")
        val sealed = PairingClient.createRequest(offer, bot, requestedLevel = PermissionLevel.FULL)

        handler.onRoute(route(sealed.sealed))

        val approval = assertNotNull(pending, "the service must hand the request to Allow this bot?")
        assertEquals("Claude", approval.botName)
        assertEquals(bot.peerId, approval.botId)
        assertEquals(PermissionLevel.FULL, approval.requestedLevel)
        assertEquals(offer.expiresAtMillis, pendingExpiry)
        assertTrue(sent.isEmpty(), "nothing is answered before the user taps Allow")
    }

    @Test
    fun aPairedBotCompletesNoiseAndGetsACommandResponse() {
        pairBot()
        val botSession = connect(E2ESession(NoiseRole.INITIATOR, bot.keys))

        val request = AppMessages.request("req-1", StatusArgs)
        handler.onRoute(route(botSession.sealText(AppMessages.encodeRequest(request))))

        val responseFrame = sent.removeAt(sent.lastIndex).second
        val response = PbJson.decodeFromString(
            AppResponse.serializer(),
            botSession.openText(responseFrame),
        )
        assertTrue(response.ok)
        assertEquals("req-1", response.id)
        assertEquals("test", response.result?.get("device")?.jsonPrimitive?.content)

        assertEquals(1, executed.size)
        assertEquals("status", executed.single().cmd)
        assertTrue(state.isConnected(bot.peerId))
        assertTrue(log.entriesFor(bot.peerId).any { it.cmd == "status" })
    }

    @Test
    fun aDisabledBotIsNotAnswered() {
        registry.put(
            BotRecord(
                botId = bot.peerId,
                name = "Claude",
                publicKey = bot.publicKeyBase64,
                enabled = false,
            ),
        )
        val botSession = E2ESession(NoiseRole.INITIATOR, bot.keys)

        handler.onRoute(route(botSession.writeHandshakeFrame()))

        assertTrue(sent.isEmpty(), "a switched-off bot must get no response at all")
        assertFalse(state.isConnected(bot.peerId))
    }

    @Test
    fun kickSendsASealedKickedEvent() {
        pairBot()
        val botSession = connect(E2ESession(NoiseRole.INITIATOR, bot.keys))
        sent.clear()

        handler.closeSessions(listOf(bot.peerId), "kicked by the device owner")

        val frame = sent.single()
        assertEquals(bot.peerId, frame.first)
        val message = AppMessages.parse(botSession.openText(frame.second))
        assertTrue(message is AppMessage.Event)
        assertEquals("kicked", message.event.event)
        assertFalse(state.isConnected(bot.peerId))
    }

    @Test
    fun aTamperedTransportFrameDropsTheSession() {
        pairBot()
        val botSession = connect(E2ESession(NoiseRole.INITIATOR, bot.keys))
        val sealed = botSession.sealText(AppMessages.encodeRequest(AppMessages.request("r", StatusArgs)))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

        handler.onRoute(route(sealed))

        assertFalse(state.isConnected(bot.peerId))
        assertTrue(executed.isEmpty())
    }
}
