package dev.phonebridge.core.control

/**
 * One controller at a time, everyone else in a FIFO queue.
 *
 * A controller auto-releases after [idleTimeoutMs] without [touch] (120 s by default, as the spec says)
 * and the next bot in the queue is promoted. Time is injected so tests can move the clock instead of
 * sleeping.
 */
class ControlArbiter(
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Answer to `request_control`: granted now, or queued at [position] behind [controller]. */
    data class Grant(
        val granted: Boolean,
        val controller: String?,
        val position: Int?,
    )

    /** Current arbiter state, pushed to [onChange]. */
    data class State(
        val controller: String? = null,
        val queue: List<String> = emptyList(),
    )

    private val queue = ArrayDeque<String>()
    private var current: String? = null
    private var lastActivityMs = 0L
    private val lock = Any()

    /** Called whenever the controller or the queue changes; the app mirrors this to its notification. */
    var onChange: ((State) -> Unit)? = null

    init {
        require(idleTimeoutMs > 0) { "idle timeout must be positive" }
    }

    val controller: String?
        get() = synchronized(lock) {
            expireIfIdle()
            current
        }

    fun queueSnapshot(): List<String> = synchronized(lock) {
        expireIfIdle()
        queue.toList()
    }

    fun state(): State = synchronized(lock) {
        expireIfIdle()
        State(current, queue.toList())
    }

    /**
     * `request_control`. Grants if free, refreshes a controller's own hold, otherwise queues FIFO and
     * reports the 1-based position.
     */
    fun request(botId: String): Grant = synchronized(lock) {
        require(botId.isNotBlank()) { "bot id must not be blank" }
        val now = clock()
        expireIfIdle(now)
        if (current == botId) {
            lastActivityMs = now
            return Grant(granted = true, controller = botId, position = null)
        }
        if (current == null) {
            current = botId
            lastActivityMs = now
            notifyChange()
            return Grant(granted = true, controller = botId, position = null)
        }
        if (botId !in queue) {
            queue.addLast(botId)
            notifyChange()
        }
        Grant(granted = false, controller = current, position = positionOf(botId))
    }

    /** Releases control or leaves the queue. True when the bot held or queued something. */
    fun release(botId: String): Boolean = synchronized(lock) {
        if (current == botId) {
            releaseCurrent(clock())
            return true
        }
        val removed = queue.remove(botId)
        if (removed) notifyChange()
        removed
    }

    /** Records real activity from the controller, resetting the idle timer. */
    fun touch(botId: String): Boolean = synchronized(lock) {
        val now = clock()
        expireIfIdle(now)
        if (current != botId) return false
        lastActivityMs = now
        true
    }

    fun isController(botId: String): Boolean = synchronized(lock) {
        expireIfIdle()
        current == botId
    }

    /** True when [botId] may run a Control-level command right now. */
    fun canAct(botId: String): Boolean = isController(botId)

    /** 1-based queue position, or null when not waiting. */
    fun position(botId: String): Int? = synchronized(lock) {
        expireIfIdle()
        positionOf(botId)
    }

    /** Idle-timeout check for a periodic tick; returns the bot that lost control, if any. */
    fun tick(): String? = synchronized(lock) { expireIfIdle(clock()) }

    /** Time left before the controller goes idle, or null when nobody holds control. */
    fun idleRemainingMs(): Long? = synchronized(lock) {
        val holder = current ?: return null
        val elapsed = clock() - lastActivityMs
        (idleTimeoutMs - elapsed).coerceAtLeast(0)
    }

    /**
     * "Kick everyone": revokes the controller and clears the queue. Returns every bot that lost
     * something, so the device can send them `kicked` and log it.
     */
    fun kickAll(): List<String> = synchronized(lock) {
        val kicked = ArrayList<String>()
        current?.let { kicked.add(it) }
        kicked.addAll(queue)
        current = null
        queue.clear()
        lastActivityMs = 0L
        if (kicked.isNotEmpty()) notifyChange()
        kicked
    }

    private fun expireIfIdle(now: Long = clock()): String? {
        val holder = current ?: return null
        if (now - lastActivityMs < idleTimeoutMs) return null
        return releaseCurrent(now)
    }

    private fun releaseCurrent(now: Long): String? {
        val released = current ?: return null
        current = queue.removeFirstOrNull()
        lastActivityMs = if (current != null) now else 0L
        notifyChange()
        return released
    }

    private fun positionOf(botId: String): Int? {
        val index = queue.indexOf(botId)
        return if (index < 0) null else index + 1
    }

    private fun notifyChange() {
        onChange?.invoke(State(current, queue.toList()))
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
    }
}
