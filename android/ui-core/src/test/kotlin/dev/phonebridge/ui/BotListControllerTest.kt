package dev.phonebridge.ui

import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.pairing.InMemoryBotRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotListControllerTest {

    private var now = 1_700_000_000_000L
    private val registry = InMemoryBotRegistry(clock = { now })
    private val log = InMemoryActivityLog(clock = { now })
    private val alpha = TestBots.record("Alpha", lastSeen = now - 30_000L)
    private val beta = TestBots.record("Beta", level = PermissionLevel.VIEW, lastSeen = 0L)

    private val disabled = mutableListOf<String>()
    private val unpaired = mutableListOf<String>()

    init {
        registry.put(alpha)
        registry.put(beta)
    }

    private fun controller(live: LiveSessions = FakeLiveSessions()): BotListController = BotListController(
        registry = registry,
        sessions = live,
        log = log,
        clock = { now },
        onBotDisabled = { disabled.add(it) },
        onBotUnpaired = { unpaired.add(it) },
    )

    @Test
    fun listsEveryPairedBotWithFingerprintAndLastSeen() {
        val rows = controller().rows()
        assertEquals(2, rows.size)
        TestBots.assertBotListConsistent(rows)
        assertEquals("just now", rows.first { it.botId == alpha.botId }.lastSeenText)
        assertEquals("never", rows.first { it.botId == beta.botId }.lastSeenText)
        assertEquals(PermissionLevel.VIEW, rows.first { it.botId == beta.botId }.level)
    }

    @Test
    fun showsConnectionControlAndQueueState() {
        val live = FakeLiveSessions(
            connected = setOf(alpha.botId),
            controller = alpha.botId,
            queue = mapOf(beta.botId to 1),
        )
        val rows = controller(live).rows()
        assertTrue(TestBots.isControllerRow(rows, alpha.botId))
        assertTrue(rows.first { it.botId == alpha.botId }.connected)
        assertEquals(1, rows.first { it.botId == beta.botId }.queuePosition)
        assertFalse(rows.first { it.botId == beta.botId }.connected)
    }

    @Test
    fun switchingABotOffClosesItsSession() {
        val controller = controller()
        assertTrue(controller.setEnabled(alpha.botId, false))
        assertFalse(registry.get(alpha.botId)!!.enabled)
        assertEquals(listOf(alpha.botId), disabled)
        assertTrue(log.entriesFor(alpha.botId).any { it.cmd == "bot_disabled" })

        assertTrue(controller.setEnabled(alpha.botId, true))
        assertTrue(registry.get(alpha.botId)!!.enabled)
        assertTrue(log.entriesFor(alpha.botId).any { it.cmd == "bot_enabled" })
    }

    @Test
    fun changingTheLevelPersists() {
        val controller = controller()
        assertTrue(controller.setLevel(beta.botId, PermissionLevel.FULL))
        assertEquals(PermissionLevel.FULL, registry.get(beta.botId)!!.level)
        assertTrue(log.entriesFor(beta.botId).any { it.cmd == "level_changed" })
    }

    @Test
    fun unpairingOneBotNeverTouchesTheOther() {
        val controller = controller()
        assertTrue(controller.unpair(alpha.botId))
        assertEquals(listOf(alpha.botId), unpaired)
        assertEquals(null, registry.get(alpha.botId))
        assertEquals("Beta", registry.get(beta.botId)!!.name)
        assertEquals(1, controller.rows().size)
        assertFalse(controller.isEmpty())
    }

    @Test
    fun relativeLastSeen() {
        assertEquals("never", BotListController.lastSeenText(0L, now))
        assertEquals("just now", BotListController.lastSeenText(now - 10_000L, now))
        assertEquals("5 min ago", BotListController.lastSeenText(now - 5 * 60_000L, now))
        assertEquals("3 h ago", BotListController.lastSeenText(now - 3 * 3_600_000L, now))
        assertEquals("2 d ago", BotListController.lastSeenText(now - 2 * 86_400_000L, now))
    }
}

/** Test double for the session state; the real one is [BridgeState]. */
internal class FakeLiveSessions(
    val connected: Set<String> = emptySet(),
    var controller: String? = null,
    val queue: Map<String, Int> = emptyMap(),
) : LiveSessions {
    override fun isConnected(botId: String): Boolean = connected.contains(botId)
    override fun isController(botId: String): Boolean = controller == botId
    override fun queuePosition(botId: String): Int? = queue[botId]
    override fun controllerId(): String? = controller
}
