package dev.phonebridge.desktop

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.model.KeyName
import dev.phonebridge.desktop.device.KeyCombos
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyComboTest {

    @Test
    fun parsesModifiersAndKeyIntoALabel() {
        val combo = KeyCombos.parse(listOf("ctrl", "shift", "s"))
        assertEquals(KeyEvent.VK_S, combo.keyCode)
        assertEquals(KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK, combo.modifiers)
        assertEquals("ctrl+shift+s", combo.label)
    }

    @Test
    fun acceptsAlternativeModifierNames() {
        val combo = KeyCombos.parse(listOf("control", "alt", "Meta", "Enter"))
        assertEquals(KeyEvent.VK_ENTER, combo.keyCode)
        assertEquals(
            KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or KeyEvent.META_DOWN_MASK,
            combo.modifiers,
        )
    }

    @Test
    fun parsesFunctionKeysAndNamedKeys() {
        assertEquals(KeyEvent.VK_F12, KeyCombos.parse(listOf("f12")).keyCode)
        assertEquals(KeyEvent.VK_PAGE_DOWN, KeyCombos.parse(listOf("pagedown")).keyCode)
        assertEquals(KeyEvent.VK_ESCAPE, KeyCombos.parse(listOf("esc")).keyCode)
        assertEquals(KeyEvent.VK_UP, KeyCombos.parse(listOf("up")).keyCode)
        assertEquals(KeyEvent.VK_1, KeyCombos.parse(listOf("1")).keyCode)
    }

    @Test
    fun rejectsBadCombos() {
        assertFailsWith<BadArgsException> { KeyCombos.parse(emptyList()) }
        assertFailsWith<BadArgsException> { KeyCombos.parse(listOf("ctrl")) }
        assertFailsWith<BadArgsException> { KeyCombos.parse(listOf("a", "b")) }
        assertFailsWith<BadArgsException> { KeyCombos.parse(listOf("ctrl", "nonsense")) }
        assertFailsWith<BadArgsException> { KeyCombos.parse(listOf("")) }
        assertFailsWith<BadArgsException> { KeyCombos.parse(listOf("f25")) }
    }

    @Test
    fun mapsEveryProtocolKeyToADesktopShortcut() {
        val expected = mapOf(
            KeyName.ENTER to KeyEvent.VK_ENTER,
            KeyName.BACK to KeyEvent.VK_LEFT,
            KeyName.HOME to KeyEvent.VK_D,
            KeyName.RECENTS to KeyEvent.VK_TAB,
            KeyName.NOTIFICATIONS to KeyEvent.VK_N,
            KeyName.QUICK_SETTINGS to KeyEvent.VK_A,
            KeyName.LOCK to KeyEvent.VK_L,
            KeyName.POWER_DIALOG to KeyEvent.VK_X,
        )
        for ((key, code) in expected) {
            assertEquals(code, KeyCombos.forCoreKey(key).keyCode, "key $key")
            assertTrue(KeyCombos.forCoreKey(key).label.isNotBlank())
        }
        assertEquals(KeyEvent.ALT_DOWN_MASK, KeyCombos.forCoreKey(KeyName.BACK).modifiers)
        assertEquals(
            KeyEvent.META_DOWN_MASK,
            KeyCombos.forCoreKey(KeyName.LOCK).modifiers,
            "Win+L must hold the Windows key",
        )
    }
}
