package dev.phonebridge.ui

import dev.phonebridge.core.pairing.BotRegistry
import dev.phonebridge.executor.ControlArbiter

/** What the bot list and the notification need to know about live sessions. */
interface LiveSessions {
    fun isConnected(botId: String): Boolean
    fun isController(botId: String): Boolean
    fun queuePosition(botId: String): Int?
    fun controllerId(): String?
}

/**
 * The device-side session bookkeeping shared by the foreground service, the bot list and the
 * notification:
 *
 * - tracks which paired bots are connected right now (a Noise session is up),
 * - mirrors the [ControlArbiter] (one controller, FIFO queue, 120 s idle release),
 * - writes one activity-log line per command and per kick,
 * - produces the notification text ("Alpha is in control" / "No bot in control"),
 * - implements "Kick everyone" (revoke the controller and the queue, close every live session).
 *
 * All of it is plain Kotlin with an injected clock, so the behaviour is unit tested on the JVM.
 */
class BridgeState(
    val control: ControlArbiter,
    private val registry: BotRegistry,
    private val log: ActivityLog,
    private val clock: () -> Long = System::currentTimeMillis,
) : LiveSessions {

    private val lock = Any()
    private val connected = LinkedHashMap<String, Long>()
    private var lastControllerId: String? = control.holder()

    /** Called with the controller's bot id (or null) whenever the controller changes. */
    var onControllerChanged: ((String?) -> Unit)? = null

    /** Called with every bot id whose live session must be closed after "Kick everyone". */
    var onBotKicked: ((List<String>) -> Unit)? = null

    override fun controllerId(): String? = control.holder()

    override fun isConnected(botId: String): Boolean = synchronized(lock) { connected.containsKey(botId) }

    override fun isController(botId: String): Boolean = control.holdsControl(botId)

    override fun queuePosition(botId: String): Int? = control.queuePosition(botId)

    /** A bot completed its Noise handshake and may send commands. */
    fun onBotConnected(botId: String) {
        synchronized(lock) { connected[botId] = clock() }
        registry.touch(botId, clock())
        refreshController()
    }

    /** A bot's session ended (socket closed, disabled, unpaired or kicked). */
    fun onBotDisconnected(botId: String) {
        synchronized(lock) { connected.remove(botId) }
        control.forget(botId)
        refreshController()
    }

    /** Every bot with a live session right now. */
    fun liveBots(): List<String> = synchronized(lock) { connected.keys.toList() }

    /** Records one command in the activity log and refreshes the bot's "last seen". */
    fun onCommand(
        botId: String,
        botName: String,
        cmd: String,
        ok: Boolean,
        code: String? = null,
        message: String? = null,
        detail: String? = null,
    ): ActivityEntry {
        val entry = log.record(botId, botName, cmd, ok, code, message, detail)
        registry.touch(botId, clock())
        refreshController()
        return entry
    }

    /** Notification text for the current arbiter state. */
    fun notificationContent(): NotificationContent =
        ControllerNotifications.content(controllerName(), control.queueSnapshot().size)

    fun controllerName(): String? {
        val id = control.holder() ?: return null
        return registry.get(id)?.name ?: id
    }

    /**
     * Compares the arbiter with what was last announced and fires [onControllerChanged] when it moved
     * (a command granted control, the idle timeout released it, or a queued bot was promoted).
     */
    fun refreshController(): String? {
        val current = control.holder()
        val previous = synchronized(lock) { lastControllerId }
        if (current != previous) {
            synchronized(lock) { lastControllerId = current }
            onControllerChanged?.invoke(current)
        }
        return current
    }

    /**
     * "Kick everyone": revokes the controller and clears the queue, closes every live session, logs it
     * per bot and updates the notification. Paired keys are kept; an unpair is a separate action.
     */
    fun kickEveryone(): List<String> {
        val kicked = control.kickAll()
        val affected = synchronized(lock) {
            val ids = (kicked + connected.keys).distinct()
            connected.clear()
            ids
        }
        for (botId in affected) {
            val name = registry.get(botId)?.name ?: botId
            log.record(botId, name, "kick", true, message = "Kicked by the device owner")
        }
        synchronized(lock) { lastControllerId = null }
        onBotKicked?.invoke(affected)
        onControllerChanged?.invoke(null)
        return affected
    }
}
