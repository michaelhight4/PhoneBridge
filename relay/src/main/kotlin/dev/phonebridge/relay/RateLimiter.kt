package dev.phonebridge.relay

/**
 * Per-peer rate limit: a token bucket holding [maxMessages] messages per [windowMs], with a burst
 * capacity of [burst] messages. The relay drops (and reports) anything above the limit.
 */
data class RateLimitConfig(
    val maxMessages: Int = 240,
    val windowMs: Long = 1000,
    val burst: Int = 240
) {
    init {
        require(maxMessages > 0) { "maxMessages must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
        require(burst > 0) { "burst must be positive" }
    }
}

/** Token bucket. Not thread safe by design: one instance per session, used from that session's thread. */
internal class RateLimiter(private val config: RateLimitConfig) {
    private val capacity = config.burst.toDouble()
    private val refillPerNano = config.maxMessages.toDouble() / (config.windowMs.toDouble() * 1_000_000.0)
    private var tokens = capacity
    private var lastRefillNanos = System.nanoTime()

    fun tryAcquire(): Boolean {
        val now = System.nanoTime()
        val elapsed = (now - lastRefillNanos).coerceAtLeast(0L)
        lastRefillNanos = now
        tokens = (tokens + elapsed * refillPerNano).coerceAtMost(capacity)
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }
}
