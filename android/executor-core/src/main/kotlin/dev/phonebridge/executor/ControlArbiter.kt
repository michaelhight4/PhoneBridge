package dev.phonebridge.executor

/**
 * Control arbitration from `docs/PROTOCOL.md`:
 *
 * - One bot drives at a time; the others queue FIFO.
 * - Control auto-releases after 120 s without a Control/Full command from the holder.
 * - View-level reads do not take control and are never blocked by this class.
 *
 * All methods are safe to call from several threads. Time is injected so tests can move it.
 */
class ControlArbiter(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
) {
    private val lock = Any()
    private var holderId: String? = null
    private var lastActivityMs: Long = 0L
    private val queue = ArrayDeque<String>()

    /** Outcome of [requestControl]. */
    sealed interface RequestResult {
        data object Granted : RequestResult
        data class Queued(val position: Int) : RequestResult
    }

    /** Id of the bot currently in control, or null. Applies the idle timeout first. */
    fun holder(): String? = synchronized(lock) {
        expireIfIdleLocked()
        holderId
    }

    /** True when [botId] currently holds control. Applies the idle timeout first. */
    fun holdsControl(botId: String): Boolean = synchronized(lock) {
        expireIfIdleLocked()
        holderId == botId
    }

    /**
     * Ask for control: granted if free (or already held by this bot), otherwise queued FIFO.
     * [RequestResult.Queued.position] is 1-based among waiting bots.
     */
    fun requestControl(botId: String): RequestResult = synchronized(lock) {
        expireIfIdleLocked()
        if (holderId == botId) return RequestResult.Granted
        if (queue.contains(botId)) return RequestResult.Queued(queue.indexOf(botId) + 1)
        if (holderId == null) {
            grantLocked(botId)
            return RequestResult.Granted
        }
        queue.addLast(botId)
        RequestResult.Queued(queue.size)
    }

    /** Give control up. Returns false when [botId] was not the holder. */
    fun releaseControl(botId: String): Boolean = synchronized(lock) {
        expireIfIdleLocked()
        if (holderId != botId) return false
        holderId = null
        promoteLocked()
        true
    }

    /** Restart the idle timer after a Control/Full command from the holder. */
    fun noteActivity(botId: String) {
        synchronized(lock) {
            if (holderId == botId) lastActivityMs = clock()
        }
    }

    /** Drop a bot entirely (unpaired, kicked or disconnected): frees or de-queues it. */
    fun forget(botId: String) = synchronized(lock) {
        queue.remove(botId)
        if (holderId == botId) {
            holderId = null
            promoteLocked()
        }
    }

    /**
     * "Kick everyone" from the device notification: drops the controller and the whole queue.
     * Returns every bot that lost control or a queue slot, so the caller can close their sessions,
     * send `kicked` and log it. Paired keys are untouched (unpairing is a separate user action).
     */
    fun kickAll(): List<String> = synchronized(lock) {
        val kicked = ArrayList<String>(queue.size + 1)
        holderId?.let { kicked.add(it) }
        kicked.addAll(queue)
        holderId = null
        queue.clear()
        lastActivityMs = 0L
        kicked
    }

    /** 1-based queue position, or null when the bot is not waiting. */
    fun queuePosition(botId: String): Int? = synchronized(lock) {
        val index = queue.indexOf(botId)
        if (index >= 0) index + 1 else null
    }

    /** Bots waiting for control, in order. */
    fun queueSnapshot(): List<String> = synchronized(lock) { queue.toList() }

    /**
     * Release an idle holder and hand control to the next queued bot. Returns the bot that was just
     * granted control (so the caller can emit `control_changed` / `queue_position` events), or null.
     */
    fun expireIfIdle(): String? = synchronized(lock) { expireIfIdleLocked() }

    private fun expireIfIdleLocked(): String? {
        holderId ?: return null
        if (clock() - lastActivityMs < idleTimeoutMs) return null
        holderId = null
        // Hand control to the next waiter; returns null when the queue was empty (control is free).
        return promoteLocked()
    }

    private fun grantLocked(botId: String) {
        holderId = botId
        lastActivityMs = clock()
    }

    private fun promoteLocked(): String? {
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            grantLocked(next)
            return next
        }
        return null
    }

    companion object {
        /** `docs/PROTOCOL.md`: control auto-releases after 120 s idle. */
        const val DEFAULT_IDLE_TIMEOUT_MS: Long = 120_000L
    }
}
