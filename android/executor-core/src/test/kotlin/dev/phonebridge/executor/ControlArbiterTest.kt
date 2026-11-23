package dev.phonebridge.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlArbiterTest {

    private var now = 1_000_000L
    private val arbiter = ControlArbiter(clock = { now })

    @Test
    fun `control is granted when free`() {
        assertNull(arbiter.holder())
        assertEquals(ControlArbiter.RequestResult.Granted, arbiter.requestControl("bot-a"))
        assertEquals("bot-a", arbiter.holder())
        assertTrue(arbiter.holdsControl("bot-a"))
        assertFalse(arbiter.holdsControl("bot-b"))
    }

    @Test
    fun `second bot queues fifo`() {
        arbiter.requestControl("bot-a")
        assertEquals(ControlArbiter.RequestResult.Queued(1), arbiter.requestControl("bot-b"))
        assertEquals(ControlArbiter.RequestResult.Queued(2), arbiter.requestControl("bot-c"))
        assertEquals(1, arbiter.queuePosition("bot-b"))
        assertEquals(listOf("bot-b", "bot-c"), arbiter.queueSnapshot())
        // Asking twice does not queue twice.
        assertEquals(ControlArbiter.RequestResult.Queued(1), arbiter.requestControl("bot-b"))
        assertEquals(2, arbiter.queueSnapshot().size)
    }

    @Test
    fun `release promotes the next waiter`() {
        arbiter.requestControl("bot-a")
        arbiter.requestControl("bot-b")
        assertTrue(arbiter.releaseControl("bot-a"))
        assertEquals("bot-b", arbiter.holder())
        assertEquals(emptyList<String>(), arbiter.queueSnapshot())
        assertFalse(arbiter.releaseControl("bot-a"))
    }

    @Test
    fun `control auto-releases after the idle timeout`() {
        arbiter.requestControl("bot-a")
        arbiter.requestControl("bot-b")

        now += ControlArbiter.DEFAULT_IDLE_TIMEOUT_MS - 1
        assertTrue(arbiter.holdsControl("bot-a"), "still inside the idle window")

        now += 1
        assertFalse(arbiter.holdsControl("bot-a"), "idle timeout must release control")
        assertEquals("bot-b", arbiter.holder(), "the queued bot takes over")
    }

    @Test
    fun `activity keeps control alive`() {
        arbiter.requestControl("bot-a")
        repeat(5) {
            now += ControlArbiter.DEFAULT_IDLE_TIMEOUT_MS - 1_000
            arbiter.noteActivity("bot-a")
        }
        assertTrue(arbiter.holdsControl("bot-a"))
    }

    @Test
    fun `activity from a bot without control does not steal the timer`() {
        arbiter.requestControl("bot-a")
        now += 10_000
        arbiter.noteActivity("bot-b")
        now += ControlArbiter.DEFAULT_IDLE_TIMEOUT_MS
        assertFalse(arbiter.holdsControl("bot-a"))
    }

    @Test
    fun `expireIfIdle reports the promoted bot`() {
        arbiter.requestControl("bot-a")
        arbiter.requestControl("bot-b")
        now += ControlArbiter.DEFAULT_IDLE_TIMEOUT_MS
        assertEquals("bot-b", arbiter.expireIfIdle())
        assertNull(arbiter.expireIfIdle())
    }

    @Test
    fun `forget removes a queued or controlling bot`() {
        arbiter.requestControl("bot-a")
        arbiter.requestControl("bot-b")
        arbiter.requestControl("bot-c")

        arbiter.forget("bot-b")
        assertEquals(listOf("bot-c"), arbiter.queueSnapshot())

        arbiter.forget("bot-a")
        assertEquals("bot-c", arbiter.holder())
    }

    @Test
    fun `request after the holder is forgotten starts fresh`() {
        arbiter.requestControl("bot-a")
        arbiter.forget("bot-a")
        assertNull(arbiter.holder())
        assertEquals(ControlArbiter.RequestResult.Granted, arbiter.requestControl("bot-b"))
    }
}
