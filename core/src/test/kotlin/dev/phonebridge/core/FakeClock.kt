package dev.phonebridge.core

/** Controllable clock for pair expiry and the control arbiter. */
class FakeClock(var now: Long = 1_700_000_000_000L) : () -> Long {
    override fun invoke(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}
