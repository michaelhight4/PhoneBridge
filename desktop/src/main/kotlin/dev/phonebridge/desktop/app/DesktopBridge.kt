package dev.phonebridge.desktop.app

import dev.phonebridge.core.control.ControlArbiter
import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.pairing.BotRegistry
import dev.phonebridge.core.pairing.JsonFileBotRegistry
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.pairing.PairingLinks
import dev.phonebridge.core.pairing.PendingApproval
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.desktop.device.DesktopDevice
import dev.phonebridge.desktop.device.ScreenSource
import dev.phonebridge.desktop.device.ScreenStreamer
import dev.phonebridge.desktop.device.SystemDevice
import dev.phonebridge.desktop.executor.DesktopExecutor
import dev.phonebridge.desktop.session.DesktopSessionHandler
import dev.phonebridge.desktop.state.ActivityEntryFactory
import dev.phonebridge.desktop.state.ActivityLog
import dev.phonebridge.desktop.state.DesktopSnapshot
import dev.phonebridge.desktop.state.DesktopState
import dev.phonebridge.desktop.state.JsonFileActivityLog
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything the tray app, the windows and the relay threads share: identity, paired bots, pairing
 * offers, the command executor, the embedded heart and the activity log.
 *
 * `start()` brings the relay up (here or a custom URL) and `stop()` shuts everything down; the
 * constructor only loads state from disk, so tests and the UI can inspect it without a network.
 */
class DesktopBridge(
    val dataDir: Path = DesktopPaths.defaultDataDir(),
    private val log: (String) -> Unit = { println(it) },
    device: DesktopDevice? = null,
    screenSource: ScreenSource? = null,
) : AutoCloseable {
    private val directory: Path = DesktopPaths.prepare(dataDir)

    val identity: Identity = loadIdentity()
    val registry: BotRegistry = JsonFileBotRegistry(DesktopPaths.botsFile(directory))
    val pairing: PairingManager = PairingManager(identity, registry)
    val activity: ActivityLog = JsonFileActivityLog(DesktopPaths.activityFile(directory))
    val arbiter: ControlArbiter = ControlArbiter()

    private val desktopDevice: DesktopDevice = device ?: SystemDevice()
    private val screens: ScreenSource = screenSource ?: (desktopDevice as? ScreenSource) ?: SystemDevice()

    val state: DesktopState = DesktopState(desktopDevice.name) { snapshot -> publish(snapshot) }
    private val envelopeSink = AtomicReference<(RelayEnvelope) -> Unit>({})
    private val heartImpl = DesktopHeart(
        dataDir = directory,
        identity = identity,
        onEnvelope = { envelope -> envelopeSink.get()(envelope) },
        onStateChange = { url, connected -> state.setRelay(url, connected) },
        log = log,
    )
    val heart: HeartControl = heartImpl
    val heartController: HeartController = HeartController(
        store = DesktopSettingsStore(DesktopPaths.settingsFile(directory)),
        heart = heartImpl,
        log = activity,
    )

    val executor: DesktopExecutor = DesktopExecutor(
        device = desktopDevice,
        arbiter = arbiter,
        streamer = ScreenStreamer(screens),
        relayMove = { target -> relayMove(target) },
    )

    val sessions: DesktopSessionHandler = DesktopSessionHandler(
        identity = identity,
        registry = registry,
        pairingManager = pairing,
        state = state,
        activity = activity,
        executor = { bot, request -> executor.handle(bot, request) { event -> emitTo(bot.botId, event) } },
        send = { to, payload -> heartImpl.sendRoute(to, payload) },
        onPairing = { pending -> log("pairing request from ${pending.botName} (${pending.botFingerprint})") },
        onChange = { refreshSnapshot() },
    )

    @Volatile
    private var snapshotListener: ((DesktopSnapshot) -> Unit)? = null

    @Volatile
    private var heartbeat: Thread? = null

    @Volatile
    private var running = false

    init {
        envelopeSink.set { envelope -> sessions.onRoute(envelope) }
        // A reconnect creates a fresh relay session, so the ACL has to be published again.
        heartImpl.onConnected = { syncPeersToRelay() }
    }

    fun setSnapshotListener(listener: (DesktopSnapshot) -> Unit) {
        snapshotListener = listener
        listener(state.snapshot())
    }

    fun snapshot(): DesktopSnapshot = state.snapshot()

    fun deviceName(): String = desktopDevice.name

    fun fingerprint(): String = identity.fingerprint

    fun settings(): DesktopSettings = heartController.settings()

    /** Starts the relay (here or a custom URL) and the control / idle tick. */
    fun start() {
        if (running) return
        running = true
        val settings = heartController.settings()
        when (settings.host) {
            HeartHost.THIS_PC -> {
                val url = heartController.offerUrl()
                log("heart on this PC: ${url ?: "not running"}")
            }

            HeartHost.CUSTOM_URL -> {
                val result = heartController.setCustomRelay(settings.url, settings.tlsPin)
                if (result is RelayResult.Error) log("relay: ${result.message}")
            }
        }
        heartbeat = Thread({ tickLoop() }, "pb-desktop-tick").apply {
            isDaemon = true
            start()
        }
        refreshSnapshot()
    }

    fun stop() {
        running = false
        heartbeat?.interrupt()
        heartbeat = null
        sessions.closeAll()
        heartImpl.close()
    }

    override fun close() = stop()

    /** Creates an offer, publishes it under its short code and returns it (null when no relay is up). */
    fun createOffer(): PairingOffer? {
        val relayUrl = heartController.offerUrl()
        if (relayUrl == null) {
            log("pairing needs a relay; the heart is not running and no relay URL is set")
            return null
        }
        val offer = pairing.createOffer(relayUrl, desktopDevice.name, heartController.offerPin())
        val offerJson = PbJson.encodeToJsonElement(PairingOffer.serializer(), offer).jsonObject
        heartImpl.pairStore(offer.code, offerJson)
        activity.record(
            ActivityEntryFactory.make(
                botId = ACTOR,
                botName = desktopDevice.name,
                cmd = "pair_offer",
                ok = true,
                detail = "code ${offer.code}",
            ),
        )
        refreshSnapshot()
        return offer
    }

    fun cancelOffer(code: String) {
        pairing.cancelOffer(code)
        refreshSnapshot()
    }

    fun pendingApprovals(): List<PendingApproval> = pairing.pendingApprovals()

    fun liveOffers(): List<PairingOffer> = pairing.liveOffers()

    fun pairingLink(offer: PairingOffer): String = PairingLinks.link(offer)

    fun pairingFileText(offer: PairingOffer): String = PairingLinks.fileContents(offer)

    fun savePairingFile(offer: PairingOffer, target: Path): Path {
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(target, pairingFileText(offer).toByteArray(Charsets.UTF_8))
        return target
    }

    fun approve(pending: PendingApproval, level: PermissionLevel): Boolean {
        val approved = sessions.approve(pending, level) != null
        if (approved) syncPeersToRelay()
        return approved
    }

    fun deny(pending: PendingApproval): Boolean = sessions.deny(pending)

    fun unpair(botId: String) {
        sessions.closeSessions(listOf(botId), "unpaired by the user")
        pairing.revoke(botId)
        activity.record(
            ActivityEntryFactory.make(ACTOR, desktopDevice.name, "unpair", true, detail = "bot $botId"),
        )
        syncPeersToRelay()
        refreshSnapshot()
    }

    fun setEnabled(botId: String, enabled: Boolean) {
        registry.setEnabled(botId, enabled)
        if (!enabled) sessions.closeSessions(listOf(botId), "turned off by the user")
        activity.record(
            ActivityEntryFactory.make(
                ACTOR,
                desktopDevice.name,
                if (enabled) "bot_on" else "bot_off",
                true,
                detail = "bot $botId",
            ),
        )
        syncPeersToRelay()
        refreshSnapshot()
    }

    fun setLevel(botId: String, level: PermissionLevel) {
        registry.setLevel(botId, level)
        activity.record(
            ActivityEntryFactory.make(ACTOR, desktopDevice.name, "bot_level", true, detail = "bot $botId -> ${level.wire}"),
        )
        syncPeersToRelay()
        refreshSnapshot()
    }

    /** Allows or refuses this bot's store-and-forward messages to other paired bots. */
    fun setCanMessage(botId: String, canMessage: Boolean): Boolean {
        if (!registry.setCanMessage(botId, canMessage)) return false
        activity.record(
            ActivityEntryFactory.make(
                ACTOR,
                desktopDevice.name,
                if (canMessage) "bot_msg_on" else "bot_msg_off",
                true,
                detail = "bot $botId",
            ),
        )
        syncPeersToRelay()
        refreshSnapshot()
        return true
    }

    fun bots(): List<dev.phonebridge.core.pairing.BotRecord> = registry.list()

    /** "Kick everyone": revoke the controller, empty the queue, close every live session. */
    fun kickEveryone() {
        val kicked = arbiter.kickAll()
        val live = sessions.liveBotIds()
        sessions.closeSessions(live, "kicked by the user")
        state.onControlChanged(null, null, emptyList())
        sessions.broadcastControlChangedIfNeeded()
        activity.record(
            ActivityEntryFactory.make(
                ACTOR,
                desktopDevice.name,
                "kick_everyone",
                true,
                detail = "${live.size} sessions closed, ${kicked.size} lost control",
            ),
        )
        refreshSnapshot()
    }

    fun activityEntries(limit: Int = 200, botId: String? = null) = activity.recent(limit, botId)

    fun clearActivity() {
        activity.clear()
        refreshSnapshot()
    }

    fun moveHeartHere(): RelayResult {
        val result = heartController.moveHeartHere()
        if (result is RelayResult.Ok) {
            result.url?.let { sessions.broadcastRelayMoved(it) }
            log("heart moved to this PC: ${result.url}")
        }
        refreshSnapshot()
        return result
    }

    fun setCustomRelay(url: String?, tlsPin: String?): RelayResult {
        val result = heartController.setCustomRelay(url, tlsPin)
        if (result is RelayResult.Error) log("relay: ${result.message}")
        refreshSnapshot()
        return result
    }

    fun relayStatusText(): String = heartController.statusText()

    // ------------------------------------------------------------------ internals

    private fun syncPeersToRelay() {
        heartImpl.syncPeers(deviceName(), registry.list())
    }

    private fun relayMove(target: String): String? = when {
        target.equals("this", ignoreCase = true) -> {
            val result = moveHeartHere()
            (result as? RelayResult.Ok)?.url
        }

        target.startsWith("ws://") || target.startsWith("wss://") -> {
            val result = heartController.handOver(target)
            (result as? RelayResult.Ok)?.url
        }

        else -> {
            // Moving the heart to another paired peer is not implemented yet; the bot can pass its URL.
            log("relay_move to peer '$target' is not supported on the desktop yet")
            null
        }
    }

    private fun emitTo(botId: String, event: dev.phonebridge.core.model.AppEvent) {
        // The session handler owns the sealed channel; reaching it through this seam keeps the
        // executor free of transport details.
        sessions.sendEvent(botId, event)
    }

    private fun tickLoop() {
        while (running) {
            try {
                val changed = arbiter.tick()
                val controller = arbiter.controller
                val queue = arbiter.queueSnapshot()
                val name = controller?.let { registry.get(it)?.name }
                state.onControlChanged(controller, name, queue)
                if (changed != null || controller == null) sessions.broadcastControlChangedIfNeeded()
                refreshSnapshot()
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                log("tick: ${e.message}")
            }
        }
    }

    private fun refreshSnapshot() {
        val controller = arbiter.controller
        state.onControlChanged(controller, controller?.let { registry.get(it)?.name }, arbiter.queueSnapshot())
        publish(state.snapshot())
    }

    private fun publish(snapshot: DesktopSnapshot) {
        snapshotListener?.invoke(snapshot)
    }

    private fun loadIdentity(): Identity {
        val file = DesktopPaths.identityFile(directory)
        if (Files.isRegularFile(file)) {
            val text = runCatching { Files.readString(file) }.getOrNull()
            if (text != null) {
                val stored = runCatching { Identity.fromJson(text) }.getOrNull()
                if (stored != null && stored.role == PeerRole.DEVICE) {
                    return stored.withName(stored.name ?: deviceNameFallback())
                }
                log("identity file is unreadable; keeping it aside and creating a new identity")
                runCatching { Files.move(file, file.resolveSibling(file.fileName.toString() + ".corrupt")) }
            }
        }
        val created = Identity.generate(PeerRole.DEVICE, deviceNameFallback())
        Files.write(file, created.toJson().toByteArray(Charsets.UTF_8))
        return created
    }

    private fun deviceNameFallback(): String = System.getenv("COMPUTERNAME") ?: "Windows PC"

    companion object {
        const val ACTOR = "device"
        private const val TICK_MS = 2_000L

        /** A random base64url secret, for tests and for first-run setup. */
        fun freshSecret(): String = dev.phonebridge.core.util.B64Url.encode(Primitives.randomBytes(32))
    }
}
