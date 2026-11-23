package dev.phonebridge.e2e

import dev.phonebridge.core.crypto.E2ESession
import dev.phonebridge.core.crypto.Frame
import dev.phonebridge.core.crypto.NoiseRole
import dev.phonebridge.core.crypto.SecureChannel
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.AppEvent
import dev.phonebridge.core.model.AppMessage
import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.AppRequest
import dev.phonebridge.core.model.AppResponse
import dev.phonebridge.core.model.CommandArgs
import dev.phonebridge.core.model.CommandType
import dev.phonebridge.core.model.PairResult
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.pairing.PairingClient
import dev.phonebridge.core.pairing.PairingLinks
import dev.phonebridge.relay.client.ReconnectPolicy
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A bot as an agent framework or the MCP server would be: it connects to the relay, pairs with one
 * of the three transfer forms, opens a Noise `XX` session with the phone and then sends the documented
 * JSON commands.
 *
 * Everything here uses the same `:core` types as a real bot, so a green run means the protocol works
 * end to end, not that a mock agreed with itself.
 */
class BotClient(
    val identity: Identity,
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    private var relay: RelayClient? = null

    private val pairAnswers = LinkedBlockingQueue<ByteArray>()
    private val pairOffers = LinkedBlockingQueue<PairingOffer>()
    private val handshakeFrames = LinkedBlockingQueue<ByteArray>()
    private val responses = LinkedBlockingQueue<AppResponse>()
    private val events = LinkedBlockingQueue<AppEvent>()

    /** Events already received, in arrival order, for assertions after the fact. */
    val seenEvents = java.util.concurrent.CopyOnWriteArrayList<AppEvent>()

    /** Responses already received, in arrival order. */
    val seenResponses = java.util.concurrent.CopyOnWriteArrayList<AppResponse>()

    /** The exact JSON of the last messages this bot produced/accepted, for the conformance samples. */
    var lastRequestJson: String? = null
        private set
    var lastResponseJson: String? = null
        private set
    var lastEventJson: String? = null
        private set
    var lastPairRequestJson: String? = null
        private set

    private var session: E2ESession? = null
    private var pendingOffer: PairingOffer? = null

    /** The peer id of the phone this bot talks to (set when it pairs). */
    var deviceId: String = ""
        private set

    /** The relay peer id of the server this bot is attached to right now. */
    val relayId: String? get() = relay?.relayId

    val isConnected: Boolean get() = relay?.isConnected == true

    val isEstablished: Boolean get() = session?.isEstablished == true

    /** Connects to a relay; [tlsPin] is the offer's `relay_pin` for a self-signed relay. */
    fun connect(relayUrl: String, tlsPin: String? = null): BotClient {
        check(relay == null) { "this bot is already connected to a relay" }
        val client = RelayClient(
            RelayClientConfig(
                url = relayUrl,
                identity = identity,
                tlsPin = tlsPin,
                reconnect = ReconnectPolicy(initialDelayMs = 50, maxDelayMs = 500, multiplier = 1.5, jitterMs = 0),
                outboxLimit = 1024,
                log = log,
            ),
        )
        client.onRoute = { envelope ->
            runCatching { onRoute(envelope) }
                .onFailure { log("bot ${identity.name}: route failed: ${it.message}") }
        }
        client.onPairOffer = { envelope ->
            runCatching { onPairOffer(envelope) }
                .onFailure { log("bot ${identity.name}: pair_offer failed: ${it.message}") }
        }
        client.onRelayMoved = { moved -> log("bot ${identity.name}: relay moved to ${moved.newUrl}") }
        client.onError = { error -> log("bot ${identity.name}: relay error ${error.code}") }
        client.start()
        check(client.awaitConnected(CONNECT_TIMEOUT_MS)) {
            "bot ${identity.name} could not reach the relay at $relayUrl"
        }
        relay = client
        return this
    }

    // ------------------------------------------------------------------ pairing

    /** `pair_lookup`: asks the relay for the offer behind a short code like `7K4-Q9B`. */
    fun lookupCode(code: String, timeoutMs: Long = TIMEOUT_MS): PairingOffer {
        relayClient().pairLookup(code)
        return E2e.await(pairOffers, "pair_offer for $code", timeoutMs)
    }

    /** Parses a `phonebridge://pair?d=...` link (what a QR code contains) into an offer. */
    fun offerFromLink(link: String): PairingOffer = PairingLinks.parseLink(link)

    /** Parses the contents of a shared `.pbpair` file into an offer. */
    fun offerFromFile(contents: String): PairingOffer = PairingLinks.parseFile(contents)

    /** Seals a `pair_request` and sends it; the phone answers only after the user taps Allow/Deny. */
    fun requestPairing(offer: PairingOffer, requestedLevel: PermissionLevel = PermissionLevel.CONTROL) {
        offer.validate()
        deviceId = offer.deviceId
        pendingOffer = offer
        val sealed = PairingClient.createRequest(
            offer = offer,
            botIdentity = identity,
            requestedLevel = requestedLevel,
            botName = identity.name,
        )
        lastPairRequestJson = sealed.requestJson()
        relayClient().sendRouteBytes(offer.deviceId, sealed.sealed)
    }

    /** Waits for the sealed `pair_result` and opens it with the offer secret. */
    fun awaitPairResult(timeoutMs: Long = TIMEOUT_MS): PairResult {
        val offer = pendingOffer ?: error("requestPairing() must be called before awaitPairResult()")
        val sealed = E2e.await(pairAnswers, "pair_result for ${offer.code}", timeoutMs)
        return PairingClient.openResult(offer, sealed)
    }

    // ------------------------------------------------------------------ session

    /** Runs the Noise `XX` handshake with the phone (this side is the initiator). */
    fun openSession(timeoutMs: Long = TIMEOUT_MS): SecureChannel {
        val target = deviceId.ifBlank { error("pair with a phone first") }
        val fresh = E2ESession(NoiseRole.INITIATOR, identity.keys)
        session = fresh
        relayClient().sendRouteBytes(target, fresh.writeHandshakeFrame())
        val reply = E2e.await(handshakeFrames, "Noise handshake reply from $target", timeoutMs)
        fresh.readHandshakeFrame(reply)
        relayClient().sendRouteBytes(target, fresh.writeHandshakeFrame())
        return fresh.channel
            ?: throw AssertionError("the Noise XX handshake did not complete")
    }

    // ----------------------------------------------------------------- commands

    /** Sends a typed command and waits for its response. */
    fun request(type: CommandType, args: CommandArgs, timeoutMs: Long = TIMEOUT_MS): AppResponse {
        val request = AppMessages.request(nextId(), type, args)
        return send(request, timeoutMs)
    }

    /** Sends a raw command with hand-written JSON args (for the negative cases). */
    fun requestRaw(cmd: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = TIMEOUT_MS): AppResponse =
        send(AppRequest(id = nextId(), cmd = cmd, args = args), timeoutMs)

    private fun send(request: AppRequest, timeoutMs: Long): AppResponse {
        val current = session ?: error("open a session with the phone first")
        check(current.isEstablished) { "the Noise session is not established yet" }
        lastRequestJson = AppMessages.encodeRequest(request)
        relayClient().sendRouteBytes(deviceId, current.sealRequest(request))
        return awaitResponse(request.id, timeoutMs)
    }

    /** Waits for an event whose name matches [name]; earlier non-matching events are kept in [seenEvents]. */
    fun awaitEvent(name: String, timeoutMs: Long = TIMEOUT_MS): AppEvent = awaitEventWhere(name, timeoutMs) { true }

    /** Waits for an event whose name (and data) matches. */
    fun awaitEventWhere(
        name: String,
        timeoutMs: Long = TIMEOUT_MS,
        predicate: (AppEvent) -> Boolean,
    ): AppEvent {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw AssertionError("timed out waiting for event '$name' (seen: ${seenEvents.map { it.event }})")
            val event = events.poll(remaining, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("timed out waiting for event '$name' (seen: ${seenEvents.map { it.event }})")
            if (event.event == name && predicate(event)) return event
        }
    }

    /** Every event seen so far with this name. */
    fun eventsNamed(name: String): List<AppEvent> = seenEvents.filter { it.event == name }

    override fun close() {
        relay?.stop()
        relay = null
    }

    // ----------------------------------------------------------------- internals

    private fun relayClient(): RelayClient = relay ?: error("connect() to a relay first")

    private fun nextId(): String = java.util.UUID.randomUUID().toString()

    private fun awaitResponse(id: String, timeoutMs: Long): AppResponse {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw AssertionError("timed out waiting for the response to $id")
            val response = responses.poll(remaining, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("timed out waiting for the response to $id")
            if (response.id == id) return response
        }
    }

    private fun onPairOffer(envelope: RelayEnvelope) {
        val offer = envelope.offer ?: return
        pairOffers.add(dev.phonebridge.core.util.PbJson.decodeFromJsonElement(PairingOffer.serializer(), offer))
    }

    private fun onRoute(envelope: RelayEnvelope) {
        if (envelope.type != RelayType.ROUTE) return
        val payload = envelope.payloadBytes()
        val current = session
        if (current == null) {
            // Before the Noise session exists, a route from the phone is the sealed pair_result.
            pairAnswers.add(payload)
            return
        }
        when (payload.firstOrNull()) {
            Frame.HANDSHAKE -> if (!current.isEstablished) handshakeFrames.add(payload)
            Frame.TRANSPORT -> {
                val message = AppMessages.parse(current.openText(payload))
                when (message) {
                    is AppMessage.Response -> {
                        lastResponseJson = AppMessages.encodeResponse(message.response)
                        seenResponses.add(message.response)
                        responses.add(message.response)
                    }
                    is AppMessage.Event -> {
                        lastEventJson = AppMessages.encodeEvent(message.event)
                        seenEvents.add(message.event)
                        events.add(message.event)
                    }
                    is AppMessage.Request -> log("bot ${identity.name}: ignoring a request from the phone")
                }
            }
            else -> log("bot ${identity.name}: ignoring an unknown e2e frame")
        }
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
