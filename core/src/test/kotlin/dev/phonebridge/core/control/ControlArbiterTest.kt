package dev.phonebridge.core.control

import dev.phonebridge.core.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlArbiterTest {
    private val clock = FakeClock()
    private val arbiter = ControlArbiter(clock = { clock.now })

    @Test
    fun firstRequesterGetsControlAndTheRestQueueFifo() {
        val first = arbiter.request("bot-a")
        assertTrue(first.granted)
        assertEquals("bot-a", first.controller)
        assertNull(first.position)
        assertEquals("bot-a", arbiter.controller)

        val second = arbiter.request("bot-b")
        assertFalse(second.granted)
        assertEquals("bot-a", second.controller)
        assertEquals(1, second.position)

        val third = arbiter.request("bot-c")
        assertEquals(2, third.position)
        assertEquals(listOf("bot-b", "bot-c"), arbiter.queueSnapshot())

        // Asking again keeps the original place instead of queueing twice.
        assertEquals(1, arbiter.request("bot-b").position)
        assertEquals(listOf("bot-b", "bot-c"), arbiter.queueSnapshot())

        // The controller re-requesting just refreshes its hold.
        val refresh = arbiter.request("bot-a")
        assertTrue(refresh.granted)
        assertEquals(listOf("bot-b", "bot-c"), arbiter.queueSnapshot())
    }

    @Test
    fun releasePromotesTheNextBot() {
        arbiter.request("bot-a")
        arbiter.request("bot-b")
        arbiter.request("bot-c")

        assertTrue(arbiter.release("bot-a"))
        assertEquals("bot-b", arbiter.controller)
        assertEquals(listOf("bot-c"), arbiter.queueSnapshot())

        // A queued bot can leave without affecting the controller.
        assertTrue(arbiter.release("bot-c"))
        assertNull(arbiter.position("bot-c"))
        assertEquals("bot-b", arbiter.controller)

        assertFalse(arbiter.release("nobody"))
    }

    @Test
    fun controlAutoReleasesAfterTwoIdleMinutes() {
        arbiter.request("bot-a")
        arbiter.request("bot-b")

        clock.advance(119_000)
        assertTrue(arbiter.isController("bot-a"))
        assertEquals(1_000, arbiter.idleRemainingMs())

        clock.advance(1_001)
        assertFalse(arbiter.isController("bot-a"))
        assertEquals("bot-b", arbiter.controller)
        assertEquals(120_000L, ControlArbiter.DEFAULT_IDLE_TIMEOUT_MS)
        assertTrue(arbiter.canAct("bot-b"))
        assertFalse(arbiter.canAct("bot-a"))

        // bot-b has to touch too, or it also loses control.
        clock.advance(120_000)
        assertNull(arbiter.controller)
    }

    @Test
    fun activityResetsTheIdleTimer() {
        arbiter.request("bot-a")
        clock.advance(100_000)
        assertTrue(arbiter.touch("bot-a"))
        clock.advance(110_000)
        assertTrue(arbiter.isController("bot-a"), "111 s of idle is still fine after the touch")
        clock.advance(10_000)
        assertFalse(arbiter.isController("bot-a"))
        assertFalse(arbiter.touch("bot-a"), "a released bot cannot touch")
    }

    @Test
    fun tickReportsWhoLostControl() {
        arbiter.request("bot-a")
        assertNull(arbiter.tick())
        clock.advance(120_000)
        assertEquals("bot-a", arbiter.tick())
        assertNull(arbiter.controller)
        assertNull(arbiter.tick())
    }

    @Test
    fun kickAllRevokesEverything() {
        arbiter.request("bot-a")
        arbiter.request("bot-b")
        arbiter.request("bot-c")
        val kicked = arbiter.kickAll()
        assertEquals(listOf("bot-a", "bot-b", "bot-c"), kicked)
        assertNull(arbiter.controller)
        assertTrue(arbiter.queueSnapshot().isEmpty())

        // New requests start clean.
        assertTrue(arbiter.request("bot-d").granted)
        assertEquals("bot-d", arbiter.controller)
        assertTrue(arbiter.kickAll().contains("bot-d"))
    }

    @Test
    fun onChangeMirrorsTheState() {
        val states = ArrayList<ControlArbiter.State>()
        arbiter.onChange = { states.add(it) }
        arbiter.request("bot-a")
        arbiter.request("bot-b")
        assertEquals(listOf("bot-b"), states.last().queue)
        assertEquals("bot-a", states.last().controller)
        arbiter.kickAll()
        assertNull(states.last().controller)
        assertTrue(states.last().queue.isEmpty())
    }
}
