package dev.phonebridge.e2e

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.pairing.JsonFileBotRegistry
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.executor.CommandExecutor
import dev.phonebridge.executor.ControlArbiter
import dev.phonebridge.executor.ErrorCode
import dev.phonebridge.executor.FullCommandHandler
import dev.phonebridge.executor.Request
import dev.phonebridge.executor.Response
import dev.phonebridge.relay.client.ReconnectPolicy
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import dev.phonebridge.ui.ActivityLog
import dev.phonebridge.ui.ApprovalController
import dev.phonebridge.ui.ApprovalOutcome
import dev.phonebridge.ui.ApprovalView
import dev.phonebridge.ui.BridgeState
import dev.phonebridge.ui.InMemoryActivityLog
import dev.phonebridge.ui.SessionHandler
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue

/**
 * The phone side of the v1 path, assembled exactly the way `BridgeService` assembles it on Android —
 * only the `android.*` pieces are replaced by a fake:
 *
 * - a real identity and the on-disk bot registry (`bots.v1.json`),
 * - `:core`'s [PairingManager] for offers and approvals, wrapped by the real [ApprovalController],
 * - the real [SessionHandler] (Noise `XX` responder, key pinning, sealed requests, `kicked`),
 * - the plain-Kotlin [CommandExecutor] from `:android:executor-core` over a [FakePhoneDevice],
 * - [BridgeState] (control mirror, activity log, "Kick everyone", notification text),
 * - the real `:relay` [RelayClient].
 *
 * Nothing here talks to the fake phone's internals from a test without going through the relay and
 * the encrypted session.
 */
class FakePhone(
    val identity: Identity = Identity.generate(PeerRole.DEVICE, DEVICE_NAME),
    val relayUrl: String,
    private val tlsPin: String? = null,
    val dataDir: Path = E2e.tempDir("pb-phone"),
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    val registry = JsonFileBotRegistry(dataDir.resolve("bots.v1.json"))
    val activityLog: ActivityLog = InMemoryActivityLog()
    val control = ControlArbiter()
    val device = FakePhoneDevice()
    val executor = CommandExecutor(device, control)
    val state = BridgeState(control, registry, activityLog)
    val pairing = PairingManager(identity, registry)

    private val relay: RelayClient = RelayClient(
        RelayClientConfig(
            url = relayUrl,
            identity = identity,
            tlsPin = tlsPin,
            reconnect = ReconnectPolicy(initialDelayMs = 50, maxDelayMs = 500, multiplier = 1.5, jitterMs = 0),
            outboxLimit = 1024,
            log = log,
        ),
    )

    private val stored = LinkedBlockingQueue<String>()

    /** The device-side "Allow this bot?" flow that the Android approval screen drives. */
    val approvals = ApprovalController(
        manager = pairing,
        sendAnswer = { botId, sealedBase64 -> relay.sendRoute(botId, sealedBase64) },
        log = activityLog,
    )

    val handler = SessionHandler(
        identity = identity,
        registry = registry,
        pairingManager = pairing,
        state = state,
        executor = { botSession, request -> executor.execute(botSession, request) },
        send = { to, payload -> relay.sendRouteBytes(to, payload) },
        onPairing = { approval, expiresAtMs -> approvals.onRequest(approval, expiresAtMs) },
        onSessionsChanged = { log("phone: sessions changed") },
    )

    /**
     * Performs the `relay_move` hand-over. Set by the test (or the manual server) so the fake phone
     * can hand its relay to another one; returns the URL peers were told to move to.
     */
    var relayMover: ((String) -> String)? = null

    /** The relay peer id this phone is attached to right now (changes after `relay_move`). */
    val relayId: String? get() = relay.relayId

    val isConnected: Boolean get() = relay.isConnected

    init {
        relay.onRoute = { envelope ->
            runCatching { handler.onRoute(envelope) }
                .onFailure { log("phone: route failed: ${it.message}") }
        }
        relay.onMessage = { envelope -> if (envelope.type == RelayType.PAIR_STORED) stored.add(envelope.code ?: "") }
        relay.onRelayMoved = { moved -> log("phone: relay moved to ${moved.newUrl}") }
        relay.onError = { error -> log("phone: relay error ${error.code} ${error.message ?: ""}") }

        // "Kick everyone" from the notification: revoke control and close every live session.
        state.onBotKicked = { botIds -> handler.closeSessions(botIds, "kicked by the device owner") }
        state.onControllerChanged = { handler.broadcastControlChangedIfNeeded() }

        executor.full = FullCommandHandler { _, request -> handleFull(request) }
    }

    fun start(): FakePhone {
        relay.start()
        check(relay.awaitConnected(BotClient.CONNECT_TIMEOUT_MS)) {
            "the fake phone could not reach the relay at $relayUrl"
        }
        return this
    }

    // ------------------------------------------------------------------- pairing

    /** A fresh one-time offer; [relayPin] is the relay certificate pin for a self-signed relay. */
    fun newOffer(relayPin: String? = tlsPin): PairingOffer =
        pairing.createOffer(relayUrl = relayUrl, deviceName = identity.name, relayPin = relayPin)

    /** `pair_store`: publishes the offer under its short code and waits for `pair_stored`. */
    fun publishOffer(offer: PairingOffer, timeoutMs: Long = BotClient.TIMEOUT_MS) {
        val json = PbJson.encodeToJsonElement(PairingOffer.serializer(), offer).jsonObject
        relay.pairStore(offer.code, json)
        var seen = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!seen && System.currentTimeMillis() < deadline) {
            val code = stored.poll(deadline - System.currentTimeMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: break
            seen = code == offer.normalisedCode || code == offer.code
        }
        check(seen) { "the relay never acknowledged the pairing offer for ${offer.code}" }
    }

    /** Requests waiting on the "Allow this bot?" screen. */    fun pendingApprovals(): List<ApprovalView> = approvals.views()

    /** Blocks until a request from [botId] is waiting for the user. */
    fun awaitApproval(botId: String, timeoutMs: Long = BotClient.TIMEOUT_MS): ApprovalView {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            approvals.views().firstOrNull { it.botId == botId }?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no pairing request from $botId arrived (pending: ${approvals.views().map { it.botId }})")
    }

    /** Taps Allow, optionally at a lower level than requested. */
    fun approve(botId: String, level: dev.phonebridge.core.model.PermissionLevel? = null): ApprovalOutcome {
        val view = approvals.views().firstOrNull { it.botId == botId }
            ?: return ApprovalOutcome.Failed("no pending request from $botId")
        return approvals.approve(view.key, level)
    }

    /** Taps Deny. */
    fun deny(botId: String): ApprovalOutcome {
        val view = approvals.views().firstOrNull { it.botId == botId }
            ?: return ApprovalOutcome.Failed("no pending request from $botId")
        return approvals.deny(view.key)
    }

    // -------------------------------------------------------------------- device

    /** The "Kick everyone" button in the notification. */
    fun kickEveryone(): List<String> = state.kickEveryone()

    fun liveBots(): List<String> = state.liveBots()

    /** Notification text, e.g. `Alice is in control` or `No bot in control`. */
    fun notificationText(): String = state.notificationContent().text

    override fun close() {
        handler.closeAll()
        relay.stop()
    }

    // ---------------------------------------------------------------- full level

    /**
     * The Full-level handler. The real app plugs in Shizuku (shell/files/settings/install_apk) and the
     * embedded relay; the fake phone only implements `relay_move`, and answers the Shizuku commands the
     * way a phone without Shizuku does.
     */
    private fun handleFull(request: Request): Response = when (request.cmd) {
        "relay_move" -> relayMove(request)
        else -> Response.failure(
            request.id,
            ErrorCode.SHIZUKU_UNAVAILABLE,
            "'${request.cmd}' needs Shizuku, which the fake phone does not provide",
        )
    }

    private fun relayMove(request: Request): Response {
        val to = request.args["to"] as? String
            ?: return Response.failure(request.id, ErrorCode.BAD_ARGS, "relay_move needs 'to'")
        val mover = relayMover
            ?: return Response.failure(request.id, ErrorCode.INTERNAL, "this fake phone cannot move the heart")
        if (!to.startsWith("ws://") && !to.startsWith("wss://")) {
            return Response.failure(
                request.id,
                ErrorCode.BAD_ARGS,
                "the fake phone only hands the heart to a URL, not to '$to'",
            )
        }
        // Answer first, then hand over: a real hand-over must not swallow the response that tells the
        // bot where its connection went.
        val thread = Thread({
            runCatching {
                Thread.sleep(150)
                mover(to)
            }.onFailure { log("phone: relay_move to $to failed: ${it.message}") }
        }, "pb-fake-relay-move")
        thread.isDaemon = true
        thread.start()
        return Response.success(request.id, linkedMapOf("new_url" to to))
    }

    companion object {
        const val DEVICE_NAME = "Fake Pixel 9"
    }
}
