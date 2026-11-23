package dev.phonebridge.ui

import dev.phonebridge.core.pairing.InMemoryBotRegistry
import dev.phonebridge.executor.ControlArbiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeStateTest {

    private var now = 1_700_000_000_000L
    private val registry = InMemoryBotRegistry(clock = { now })
    private val log = InMemoryActivityLog(clock = { now })
    private val arbiter = ControlArbiter(clock = { now })
    private val alpha = TestBots.record("Alpha", lastSeen = 0L)
    private val beta = TestBots.record("Beta", lastSeen = 0L)
    private val changes = mutableListOf<String?>()
    private val kicked = mutableListOf<List<String>>()

    private val state = BridgeState(arbiter, registry, log, clock = { now }).also {
        it.onControllerChanged = { id -> changes.add(id) }
        it.onBotKicked = { ids -> kicked.add(ids) }
        registry.put(alpha)
        registry.put(beta)
    }

    @Test
    fun notificationNamesTheControllerAndUpdatesWhenItChanges() {
        assertEquals("No bot in control", state.notificationContent().text)

        state.onBotConnected(alpha.botId)
        arbiter.requestControl(alpha.botId)
        state.refreshController()
        assertEquals("Alpha is in control", state.notificationContent().text)
        assertEquals(listOf<String?>(alpha.botId), changes)

        assertTrue(arbiter.releaseControl(alpha.botId))
        state.refreshController()
        assertEquals("No bot in control", state.notificationContent().text)
        assertEquals(listOf<String?>(alpha.botId, null), changes)
    }

    @Test
    fun waitingBotsAreCountedInTheNotification() {
        state.onBotConnected(alpha.botId)
        state.onBotConnected(beta.botId)
        arbiter.requestControl(alpha.botId)
        arbiter.requestControl(beta.botId)
        state.refreshController()
        assertEquals("Alpha is in control · 1 waiting", state.notificationContent().text)
    }

    @Test
    fun theIdleTimeoutReleasesControlAndUpdatesTheNotification() {
        state.onBotConnected(alpha.botId)
        arbiter.requestControl(alpha.botId)
        state.refreshController()

        now += ControlArbiter.DEFAULT_IDLE_TIMEOUT_MS + 1
        state.refreshController()
        assertNull(state.controllerId())
        assertEquals("No bot in control", state.notificationContent().text)
    }

    @Test
    fun kickEveryoneClosesSessionsAndClearsTheQueue() {
        state.onBotConnected(alpha.botId)
        state.onBotConnected(beta.botId)
        arbiter.requestControl(alpha.botId)
        arbiter.requestControl(beta.botId)
        state.refreshController()

        val affected = state.kickEveryone()
        assertEquals(setOf(alpha.botId, beta.botId), affected.toSet())
        assertNull(state.controllerId())
        assertTrue(state.liveBots().isEmpty())
        assertEquals("No bot in control", state.notificationContent().text)
        assertEquals(1, kicked.size)
        assertTrue(arbiter.queueSnapshot().isEmpty())
        assertTrue(log.entries().filter { it.cmd == "kick" }.size == 2)
        assertFalse(kicked.single().isEmpty())
    }

    @Test
    fun commandsAreLoggedPerBot() {
        state.onBotConnected(alpha.botId)
        state.onCommand(alpha.botId, "Alpha", "tap", ok = true, detail = "(10,20)")
        state.onCommand(beta.botId, "Beta", "screenshot", ok = false, code = "not_controller")

        val alphaLog = log.entriesFor(alpha.botId)
        assertEquals(1, alphaLog.size)
        assertEquals("tap — ok: (10,20)", alphaLog.single().summary())
        assertEquals("not_controller", log.entriesFor(beta.botId).single().code)
        assertEquals(now, registry.get(alpha.botId)!!.lastSeen)
    }

    @Test
    fun disconnectForgetsControlAndPromotesTheNextBot() {
        state.onBotConnected(alpha.botId)
        state.onBotConnected(beta.botId)
        arbiter.requestControl(alpha.botId)
        arbiter.requestControl(beta.botId)

        state.onBotDisconnected(alpha.botId)
        assertFalse(state.isConnected(alpha.botId))
        assertTrue(state.isController(beta.botId))
        assertEquals("Beta is in control", state.notificationContent().text)
    }
}
