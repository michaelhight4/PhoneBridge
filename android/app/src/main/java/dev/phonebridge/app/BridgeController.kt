package dev.phonebridge.app

import android.content.Context
import android.util.Log
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.pairing.BotRegistry
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.executor.BotSession
import dev.phonebridge.executor.ErrorCode
import dev.phonebridge.executor.FullCommandHandler
import dev.phonebridge.executor.Request
import dev.phonebridge.executor.Response
import dev.phonebridge.relay.Relay
import dev.phonebridge.relay.RelayConfig
import dev.phonebridge.relay.RelayHandle
import dev.phonebridge.relay.TlsConfig
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import dev.phonebridge.ui.ActivityLog
import dev.phonebridge.ui.ApprovalController
import dev.phonebridge.ui.BotListController
import dev.phonebridge.ui.BridgeState
import dev.phonebridge.ui.HeartControl
import dev.phonebridge.ui.PairingController
import dev.phonebridge.ui.RelayController
import dev.phonebridge.ui.RelayResult
import dev.phonebridge.ui.SessionHandler
import kotlinx.serialization.json.jsonObject

/**
 * Owns everything the foreground service runs:
 *
 * 1. this phone's identity, the paired-bot registry and the activity log,
 * 2. the outbound `:relay` [RelayClient] over Wi-Fi or mobile data (with its own reconnect/backoff),
 * 3. the embedded relay when this phone is the chosen heart,
 * 4. the pairing offer / approval / bot-list / relay state in `:android:ui-core`,
 * 5. routing decrypted requests to the accessibility-backed [dev.phonebridge.executor.CommandExecutor]
 *    through [BridgeRuntime].
 */
class BridgeController(
    private val context: Context,
    private val onStateChanged: () -> Unit,
) : HeartControl {

    private val clock: () -> Long = { System.currentTimeMillis() }

    val identity: Identity = AppStores.identity(context)
    val registry: BotRegistry = AppStores.registry(context)
    val activityLog: ActivityLog = AppStores.activityLog(context)

    private val pairingManager = PairingManager(identity, registry, clock)
    val bridgeState = BridgeState(BridgeRuntime.control, registry, activityLog, clock)
    val relayController = RelayController(AppStores.relaySettings(context), this, activityLog, clock)

    val pairingController = PairingController(
        newOffer = { createOffer() },
        cancelOffer = pairingManager::cancelOffer,
        clock = clock,
    )

    val approvalController = ApprovalController(
        manager = pairingManager,
        sendAnswer = { botId, sealedBase64 -> relayClient?.sendRoute(botId, sealedBase64) },
        log = activityLog,
        clock = clock,
        onChange = { onStateChanged() },
    )

    val botList = BotListController(
        registry = registry,
        sessions = bridgeState,
        log = activityLog,
        clock = clock,
        onBotDisabled = { botId -> closeSession(botId, "disabled by the device owner") },
        onBotUnpaired = { botId -> closeSession(botId, "unpaired from the device") },
    )

    @Volatile
    private var relayClient: RelayClient? = null

    @Volatile
    private var relayHandle: RelayHandle? = null

    @Volatile
    private var started = false

    private val sessionHandler = SessionHandler(
        identity = identity,
        registry = registry,
        pairingManager = pairingManager,
        state = bridgeState,
        executor = { botSession: BotSession, request: Request -> BridgeRuntime.handle(botSession, request) },
        send = { to, payload -> relayClient?.sendRoute(to, B64Url.encode(payload)) },
        onPairing = { approval, expiresAtMs ->
            val view = approvalController.onRequest(approval, expiresAtMs)
            postApproval(view)
        },
        onSessionsChanged = { onStateChanged() },
    )

    private val fullHandler = FullCommandHandler { session: BotSession, request: Request ->
        when (request.cmd) {
            "relay_move" -> handleRelayMove(session, request)
            else -> Response.failure(
                request.id,
                ErrorCode.SHIZUKU_UNAVAILABLE,
                "'${request.cmd}' needs Shizuku, which is not wired into this build yet",
            )
        }
    }

    init {
        bridgeState.onControllerChanged = { _ ->
            sessionHandler.broadcastControlChangedIfNeeded()
            onStateChanged()
        }
        bridgeState.onBotKicked = { ids -> sessionHandler.closeSessions(ids, "kicked by the device owner") }
    }

    /** Idempotent: starts the embedded heart (if chosen) and the outbound relay connection. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        BridgeRuntime.setFullHandler(fullHandler)
        val url = relayController.offerUrl()
        if (url.isNullOrBlank()) {
            Log.w(TAG, "no relay configured yet; the bridge stays offline until the user picks one")
            onStateChanged()
            return
        }
        ensureClient(url, relayHandle?.fingerprint ?: relayController.settings().tlsPin)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        relayClient?.stop()
        relayClient = null
        relayHandle?.stop()
        relayHandle = null
        sessionHandler.closeAll()
        BridgeRuntime.setFullHandler(null)
    }

    /** "Kick everyone" from the notification or the bot list. */
    fun kickEveryone(): List<String> {
        val affected = bridgeState.kickEveryone()
        notificationManager()?.let { manager ->
            manager.notify(
                BridgeNotifications.BRIDGE_ID,
                BridgeNotifications.bridge(context, bridgeState.notificationContent()),
            )
        }
        return affected
    }

    // ---------------------------------------------------------- HeartControl
    override fun startHeartHere(): String {
        relayHandle?.takeIf { it.isRunning }?.let { handle ->
            ensureClient(handle.url, handle.fingerprint)
            return handle.url
        }
        val dataDir = AppStores.relayDataDir(context)
        val config = RelayConfig(
            port = 0,
            bindAddress = "0.0.0.0",
            advertisedHost = LocalAddress.ipv4(),
            tls = TlsConfig.selfSigned(dataDir.path),
            dataDir = dataDir.path,
            log = { message -> Log.d(TAG, "relay: $message") },
        )
        val handle = Relay.start(config)
        relayHandle = handle
        ensureClient(handle.url, handle.fingerprint)
        return handle.url
    }

    override fun stopHeartHere() {
        relayHandle?.stop()
        relayHandle = null
    }

    override fun useRelay(url: String, tlsPin: String?): Boolean {
        connectClient(url, tlsPin)
        return true
    }

    // -------------------------------------------------------------- internals

    private fun createOffer(): PairingOffer {
        val relayUrl = relayController.offerUrl()
            ?: throw IllegalStateException("Choose where the heart runs before pairing")
        // The bot must pin a self-signed relay's certificate, so the offer has to carry it too.
        val tlsPin = relayHandle?.fingerprint ?: relayController.settings().tlsPin
        ensureClient(relayUrl, tlsPin)
        val offer = pairingManager.createOffer(relayUrl, AppStores.deviceName(), relayPin = tlsPin)
        // Publish the offer to the relay's short-code mailbox, so a bot given `7K4-Q9B` can find it.
        runCatching {
            val json = PbJson.encodeToJsonElement(PairingOffer.serializer(), offer).jsonObject
            relayClient?.pairStore(offer.code, json)
        }
        return offer
    }

    private fun connectClient(url: String, tlsPin: String?) {
        val previous = relayClient
        previous?.stop()
        val config = RelayClientConfig(
            url = url,
            identity = identity,
            tlsPin = tlsPin,
            log = { message -> Log.d(TAG, "client: $message") },
        )
        val client = RelayClient(config)
        client.onRoute = { envelope ->
            runCatching { sessionHandler.onRoute(envelope) }
                .onFailure { Log.w(TAG, "dropped one relay frame: ${it.message}") }
        }
        client.onStateChanged = { _, _ -> onStateChanged() }
        client.onError = { error -> Log.w(TAG, "relay error ${error.code}: ${error.message ?: ""}") }
        relayClient = client
        client.start()
        onStateChanged()
    }

    /** Reuses the current client when it is already pointed at [url]. */
    private fun ensureClient(url: String, tlsPin: String?) {
        val current = relayClient
        if (current != null && current.url == url) return
        connectClient(url, tlsPin)
    }

    private fun closeSession(botId: String, reason: String) {
        sessionHandler.closeSessions(listOf(botId), reason)
        bridgeState.onBotDisconnected(botId)
        onStateChanged()
    }

    private fun postApproval(view: dev.phonebridge.ui.ApprovalView) {
        val seconds = view.secondsRemaining(clock())
        notificationManager()?.notify(
            BridgeNotifications.APPROVAL_ID,
            BridgeNotifications.approval(context, view, seconds),
        )
        onStateChanged()
    }

    private fun handleRelayMove(session: BotSession, request: Request): Response {
        val to = (request.args["to"] as? String)?.trim().orEmpty()
        val result = when {
            to == "this" -> relayController.moveHeartHere(session.botId, session.name)
            to.startsWith("ws://") || to.startsWith("wss://") ->
                relayController.handOverTo(to, session.botId, session.name)
            else -> RelayResult.Error("relay_move needs \"this\" or a ws:// / wss:// URL")
        }
        return when (result) {
            is RelayResult.Ok -> Response.success(
                request.id,
                linkedMapOf("new_url" to (result.url ?: relayController.settings().url)),
            )
            is RelayResult.Error -> Response.failure(request.id, ErrorCode.INTERNAL, result.message)
        }
    }

    private fun notificationManager() =
        context.getSystemService(android.app.NotificationManager::class.java)

    private companion object {
        const val TAG = "PhoneBridge"
    }
}
