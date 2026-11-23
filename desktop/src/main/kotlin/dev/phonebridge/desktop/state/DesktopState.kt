package dev.phonebridge.desktop.state

/** One consistent view of the agent for the tray icon, the indicator window and the main window. */
data class DesktopSnapshot(
    val deviceName: String,
    val connectedBots: List<String>,
    val controller: String?,
    val controllerName: String?,
    val queue: List<String>,
    val heartUrl: String?,
    val relayConnected: Boolean,
) {
    val controllerText: String
        get() = when {
            controller == null -> "No bot in control"
            else -> "${controllerName ?: shortId(controller)} is in control"
        }

    /** Notification / tray text: the bot in control, plus how many wait. */
    val statusText: String
        get() = if (queue.isEmpty()) controllerText else "$controllerText · ${queue.size} waiting"

    private fun shortId(id: String): String = if (id.length > 8) id.take(8).uppercase() else id.uppercase()
}

/**
 * Shared live state: which bots are connected, who holds control, the queue, and where the heart beats.
 *
 * Everything is synchronised and the listener receives an immutable snapshot outside the lock, so the
 * Swing side can marshal one object onto the EDT without racing the relay thread.
 */
class DesktopState(
    val deviceName: String,
    private val listener: (DesktopSnapshot) -> Unit = {},
) {
    private val lock = Any()
    private val connected = LinkedHashSet<String>()
    private var controller: String? = null
    private var controllerName: String? = null
    private var queue: List<String> = emptyList()
    private var heartUrl: String? = null
    private var relayConnected = false

    fun snapshot(): DesktopSnapshot = synchronized(lock) { snapshotLocked() }

    fun onBotConnected(botId: String) = changing { connected.add(botId) }

    fun onBotDisconnected(botId: String) = changing { connected.remove(botId) }

    fun isConnected(botId: String): Boolean = synchronized(lock) { botId in connected }

    fun onControlChanged(controller: String?, name: String?, queue: List<String>) = changing {
        val changed = this.controller != controller || this.controllerName != name || this.queue != queue
        this.controller = controller
        this.controllerName = name
        this.queue = queue.toList()
        changed
    }

    fun setRelay(url: String?, connectedNow: Boolean) = changing {
        val changed = heartUrl != url || relayConnected != connectedNow
        heartUrl = url
        relayConnected = connectedNow
        changed
    }

    fun controller(): String? = synchronized(lock) { controller }

    fun controllerName(): String? = synchronized(lock) { controllerName }

    private fun changing(block: () -> Boolean) {
        val snapshot = synchronized(lock) { if (block()) snapshotLocked() else null }
        if (snapshot != null) listener(snapshot)
    }

    private fun snapshotLocked(): DesktopSnapshot = DesktopSnapshot(
        deviceName = deviceName,
        connectedBots = connected.toList(),
        controller = controller,
        controllerName = controllerName,
        queue = queue,
        heartUrl = heartUrl,
        relayConnected = relayConnected,
    )
}
