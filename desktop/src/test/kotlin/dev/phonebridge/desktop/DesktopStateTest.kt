package dev.phonebridge.desktop

import dev.phonebridge.desktop.state.DesktopSnapshot
import dev.phonebridge.desktop.state.DesktopState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStateTest {

    @Test
    fun tracksConnectedBotsAndNotifies() {
        val snapshots = mutableListOf<DesktopSnapshot>()
        val state = DesktopState("Test PC") { snapshots.add(it) }

        state.onBotConnected("bot1")
        state.onBotConnected("bot1")
        state.onBotConnected("bot2")
        state.onBotDisconnected("bot1")
        state.onBotDisconnected("missing")

        assertEquals(listOf("bot2"), state.snapshot().connectedBots)
        assertEquals(3, snapshots.size, "duplicate and unknown transitions must not notify")
        assertTrue(state.isConnected("bot2"))
        assertFalse(state.isConnected("bot1"))
    }

    @Test
    fun reportsWhoIsInControlAndHowManyWait() {
        val state = DesktopState("Test PC")
        assertNull(state.snapshot().controller)
        assertEquals("No bot in control", state.snapshot().controllerText)
        assertEquals("No bot in control", state.snapshot().statusText)

        state.onControlChanged("abc123def456", "Claude", emptyList())
        assertEquals("Claude is in control", state.snapshot().statusText)

        state.onControlChanged("abc123def456", null, listOf("x", "y"))
        assertEquals("ABC123DE is in control · 2 waiting", state.snapshot().statusText)
    }

    @Test
    fun tracksTheRelay() {
        val state = DesktopState("Test PC")
        assertFalse(state.snapshot().relayConnected)
        state.setRelay("wss://127.0.0.1:8443", true)
        assertTrue(state.snapshot().relayConnected)
        assertEquals("wss://127.0.0.1:8443", state.snapshot().heartUrl)
        state.setRelay("wss://127.0.0.1:8443", true)
        state.setRelay(null, false)
        assertNull(state.snapshot().heartUrl)
    }

    @Test
    fun anUnchangedControlStateDoesNotNotify() {
        var notifications = 0
        val state = DesktopState("Test PC") { notifications++ }
        state.onControlChanged("a", "Alpha", emptyList())
        state.onControlChanged("a", "Alpha", emptyList())
        assertEquals(1, notifications)
    }
}
