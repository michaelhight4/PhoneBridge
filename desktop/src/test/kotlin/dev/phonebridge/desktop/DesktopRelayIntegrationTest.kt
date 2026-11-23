package dev.phonebridge.desktop

import dev.phonebridge.core.control.ControlArbiter
import dev.phonebridge.core.crypto.E2ESession
import dev.phonebridge.core.crypto.Frame
import dev.phonebridge.core.crypto.NoiseRole
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.AppMessage
import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.AppRequest
import dev.phonebridge.core.model.AppResponse
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.StatusArgs
import dev.phonebridge.core.model.StatusResult
import dev.phonebridge.core.pairing.InMemoryBotRegistry
import dev.phonebridge.core.pairing.PairingClient
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.pairing.PendingApproval
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.desktop.device.ScreenStreamer
import dev.phonebridge.desktop.executor.BotSession
import dev.phonebridge.desktop.executor.DesktopExecutor
import dev.phonebridge.desktop.session.DesktopSessionHandler
import dev.phonebridge.desktop.state.DesktopState
import dev.phonebridge.desktop.state.InMemoryActivityLog
import dev.phonebridge.relay.Relay
import dev.phonebridge.relay.RelayConfig
import dev.phonebridge.relay.TlsConfig
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full-stack check without a phone or a browser: a bot pairs with the short code over a real embedded
 * relay, opens a real Noise session, and gets a command answered by the desktop executor.
 */
class DesktopRelayIntegrationTest {
    private val dir = Files.createTempDirectory("pb-desktop-integration").also { it.toFile().deleteOnExit() }
    private var now = 1_700_000_000_000L
    private val deviceIdentity = Identity.generate(PeerRole.DEVICE, "Test PC")
    private val botIdentity = Identity.generate(PeerRole.BOT, "Claude")
    private val registry = InMemoryBotRegistry(clock = { now })
    private val pairing = PairingManager(deviceIdentity, registry, clock = { now })
    private val state = DesktopState("Test PC")
    private val log = InMemoryActivityLog()
    private val fakeDevice = FakeDesktopDevice()
    private val arbiter = ControlArbiter(clock = { now })
    private val desktopExecutor = DesktopExecutor(
        device = fakeDevice,
        arbiter = arbiter,
        streamer = ScreenStreamer(fakeDevice, clock = { now }, pause = {}),
    )
    private var pending: PendingApproval? = null

    private lateinit var handler: DesktopSessionHandler

    private fun await(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within $timeoutMs ms")
    }

    @Test
    fun aBotPairsByShortCodeAndDrivesTheDesktop() {
        val relay = Relay.start(
            RelayConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                tls = TlsConfig.selfSigned(dir.resolve("relay").toString()),
                dataDir = dir.resolve("relay").toString(),
                moveSecret = "integration-secret",
            ),
        )
        val url = "wss://127.0.0.1:${relay.port}"
        val routes = LinkedBlockingQueue<RelayEnvelope>()
        val offers = LinkedBlockingQueue<PairingOffer>()
        var botClient: RelayClient? = null
        var deviceClient: RelayClient? = null
        try {
            handler = DesktopSessionHandler(
                identity = deviceIdentity,
                registry = registry,
                pairingManager = pairing,
                state = state,
                activity = log,
                executor = { bot: BotSession, request: AppRequest ->
                    desktopExecutor.handle(bot, request) { event -> handler.sendEvent(bot.botId, event) }
                },
                send = { to, payload -> deviceClient?.sendRouteBytes(to, payload) },
                onPairing = { approval -> pending = approval },
                onChange = {},
                clock = { now },
            )

            deviceClient = RelayClient(
                RelayClientConfig(url = url, identity = deviceIdentity, tlsPin = relay.fingerprint),
            ).apply {
                onRoute = { envelope -> handler.onRoute(envelope) }
                start()
                assertTrue(awaitConnected(15_000), "the PC must reach its own relay")
            }

            botClient = RelayClient(
                RelayClientConfig(url = url, identity = botIdentity, tlsPin = relay.fingerprint),
            ).apply {
                onRoute = { envelope -> routes.add(envelope) }
                onPairOffer = { envelope -> offers.add(envelope.offerAsPairingOffer()) }
                start()
                assertTrue(awaitConnected(15_000), "the bot must reach the relay")
            }

            // The code the user reads off the PC travels through the relay mailbox.
            val offer = pairing.createOffer(url, "Test PC")
            val offerJson = PbJson.encodeToJsonElement(PairingOffer.serializer(), offer).jsonObject
            deviceClient.pairStore(offer.code, offerJson)

            // pair_store is asynchronous; ask again until the mailbox has it.
            var lookedUp: PairingOffer? = null
            val lookupDeadline = System.currentTimeMillis() + 15_000
            while (lookedUp == null && System.currentTimeMillis() < lookupDeadline) {
                botClient.pairLookup(offer.code)
                lookedUp = offers.poll(500, TimeUnit.MILLISECONDS)
            }
            val resolved = assertNotNull(lookedUp, "pair_lookup must answer")
            assertEquals(offer.code, resolved.code)

            val sealed = PairingClient.createRequest(resolved, botIdentity, PermissionLevel.CONTROL)
            botClient.sendRouteBytes(deviceIdentity.peerId, sealed.sealed)

            await { pending != null }
            val approval = assertNotNull(pending)
            assertEquals("Claude", approval.botName)
            assertTrue(handler.approve(approval, PermissionLevel.CONTROL) != null)

            val resultPayload = awaitPairResult(routes, resolved)
            val result = PairingClient.openResult(resolved, resultPayload)
            assertTrue(result.ok)
            assertEquals(PermissionLevel.CONTROL, result.grantedLevel)
            assertEquals(deviceIdentity.peerId, result.deviceId)

            // Noise XX, initiated by the bot, through the relay.
            val botSession = E2ESession(NoiseRole.INITIATOR, botIdentity.keys)
            botClient.sendRouteBytes(deviceIdentity.peerId, botSession.writeHandshakeFrame())
            val secondHandshake = awaitFrame(routes, Frame.HANDSHAKE)
            botSession.readHandshakeFrame(secondHandshake)
            botClient.sendRouteBytes(deviceIdentity.peerId, botSession.writeHandshakeFrame())
            assertTrue(botSession.isEstablished)

            val request = AppMessages.request("integration-1", StatusArgs)
            botClient.sendRouteBytes(deviceIdentity.peerId, botSession.sealRequest(request))
            val responseFrame = awaitFrame(routes, Frame.TRANSPORT)
            val message = botSession.openAppMessage(responseFrame)
            assertTrue(message is AppMessage.Response)
            val response = message.response as AppResponse
            assertTrue(response.ok)
            assertEquals("integration-1", response.id)
            assertEquals("Test PC", PbJson.decodeFromString(
                StatusResult.serializer(),
                response.result.toString(),
            ).device)
            assertTrue(log.recent().any { it.cmd == "status" })
        } finally {
            botClient?.stop()
            deviceClient?.stop()
            relay.stop()
        }
    }

    /** Takes route payloads until the sealed `pair_result` opens with the offer secret. */
    private fun awaitPairResult(routes: LinkedBlockingQueue<RelayEnvelope>, offer: PairingOffer): ByteArray {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val envelope = routes.poll(1, TimeUnit.SECONDS) ?: continue
            val payload = runCatching { envelope.payloadBytes() }.getOrNull() ?: continue
            val opens = runCatching { PairingClient.openResult(offer, payload) }.isSuccess
            if (opens) return payload
        }
        throw AssertionError("the sealed pair_result never arrived")
    }

    /** Takes route payloads until one starts with [tag]. */
    private fun awaitFrame(routes: LinkedBlockingQueue<RelayEnvelope>, tag: Byte): ByteArray {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val envelope = routes.poll(1, TimeUnit.SECONDS) ?: continue
            val payload = runCatching { envelope.payloadBytes() }.getOrNull() ?: continue
            if (payload.isNotEmpty() && payload[0] == tag) return payload
        }
        throw AssertionError("no frame with tag $tag arrived")
    }

    @Test
    fun theOfferLinkAndFileCarryTheSameOffer() {
        val offer = pairing.createOffer("wss://relay.test:8443", "Test PC")
        val link = dev.phonebridge.core.pairing.PairingLinks.link(offer)
        val fromLink = dev.phonebridge.core.pairing.PairingLinks.parseLink(link)
        val fromFile = dev.phonebridge.core.pairing.PairingLinks.parseFile(
            dev.phonebridge.core.pairing.PairingLinks.fileContents(offer),
        )
        assertEquals(offer.secret, fromLink.secret)
        assertEquals(offer.code, fromLink.code)
        assertEquals(offer.deviceId, fromFile.deviceId)
        assertTrue(B64Url.decode(fromFile.secret).size == 32)
    }
}
