package dev.phonebridge.desktop.device

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.model.KeyName
import java.awt.event.KeyEvent

/**
 * One key press or shortcut: a virtual key code plus AWT modifier masks.
 *
 * Parsing is pure so it is unit tested without touching the real keyboard; only [RobotInput] turns a
 * combo into actual events.
 */
data class KeyCombo(
    val keyCode: Int,
    val modifiers: Int,
    val label: String,
) {
    val modifierNames: List<String>
        get() = buildList {
            if (modifiers and KeyEvent.CTRL_DOWN_MASK != 0) add("ctrl")
            if (modifiers and KeyEvent.SHIFT_DOWN_MASK != 0) add("shift")
            if (modifiers and KeyEvent.ALT_DOWN_MASK != 0) add("alt")
            if (modifiers and KeyEvent.META_DOWN_MASK != 0) add("win")
        }

    companion object
}

/**
 * Parses `key_combo` key lists (`["ctrl", "shift", "s"]`) and maps the protocol's [KeyName] values to
 * sensible Windows shortcuts.
 */
object KeyCombos {
    const val MAX_KEYS = 8

    /** Protocol `key` -> desktop shortcut. Documented in `desktop/README.md`. */
    fun forCoreKey(name: KeyName): KeyCombo = when (name) {
        KeyName.ENTER -> parse(listOf("enter"))
        KeyName.BACK -> parse(listOf("alt", "left"))
        // Windows has no dedicated Home/Recents window key: Home shows the desktop, Recents switches.
        KeyName.HOME -> parse(listOf("win", "d"))
        KeyName.RECENTS -> parse(listOf("alt", "tab"))
        KeyName.NOTIFICATIONS -> parse(listOf("win", "n"))
        KeyName.QUICK_SETTINGS -> parse(listOf("win", "a"))
        KeyName.LOCK -> parse(listOf("win", "l"))
        KeyName.POWER_DIALOG -> parse(listOf("win", "x"))
    }

    fun parse(keys: List<String>): KeyCombo {
        if (keys.isEmpty()) throw BadArgsException("keys must not be empty")
        if (keys.size > MAX_KEYS) throw BadArgsException("at most $MAX_KEYS keys")
        var modifiers = 0
        var keyCode: Int? = null
        var keyLabel: String? = null
        for (raw in keys) {
            val token = raw.trim().lowercase()
            if (token.isEmpty()) throw BadArgsException("key names must not be blank")
            val modifier = modifierMask(token)
            if (modifier != null) {
                modifiers = modifiers or modifier
                continue
            }
            if (keyCode != null) throw BadArgsException("only one non-modifier key is allowed per combo")
            val code = keyCode(token) ?: throw BadArgsException("unknown key: $raw")
            keyCode = code
            keyLabel = token
        }
        val code = keyCode ?: throw BadArgsException("a key combo needs one non-modifier key")
        val label = (modifierNames(modifiers) + keyLabel!!).joinToString("+")
        return KeyCombo(code, modifiers, label)
    }

    fun modifierNames(mask: Int): List<String> = buildList {
        if (mask and KeyEvent.CTRL_DOWN_MASK != 0) add("ctrl")
        if (mask and KeyEvent.SHIFT_DOWN_MASK != 0) add("shift")
        if (mask and KeyEvent.ALT_DOWN_MASK != 0) add("alt")
        if (mask and KeyEvent.META_DOWN_MASK != 0) add("win")
    }

    private fun modifierMask(token: String): Int? = when (token) {
        "ctrl", "control" -> KeyEvent.CTRL_DOWN_MASK
        "shift" -> KeyEvent.SHIFT_DOWN_MASK
        "alt", "option" -> KeyEvent.ALT_DOWN_MASK
        "win", "meta", "super", "cmd", "command" -> KeyEvent.META_DOWN_MASK
        else -> null
    }

    private fun keyCode(token: String): Int? {
        namedKey(token)?.let { return it }
        if (token.length == 1) {
            val char = token[0]
            val code = KeyEvent.getExtendedKeyCodeForChar(char.code)
            if (code != KeyEvent.VK_UNDEFINED) return code
        }
        // A single letter or digit is always covered above; anything longer must be named.
        return null
    }

    private fun namedKey(token: String): Int? = when (token) {
        "enter", "return" -> KeyEvent.VK_ENTER
        "space" -> KeyEvent.VK_SPACE
        "tab" -> KeyEvent.VK_TAB
        "esc", "escape" -> KeyEvent.VK_ESCAPE
        "backspace", "back_space" -> KeyEvent.VK_BACK_SPACE
        "delete", "del" -> KeyEvent.VK_DELETE
        "insert", "ins" -> KeyEvent.VK_INSERT
        "home" -> KeyEvent.VK_HOME
        "end" -> KeyEvent.VK_END
        "pageup", "page_up", "pgup" -> KeyEvent.VK_PAGE_UP
        "pagedown", "page_down", "pgdn", "pgdown" -> KeyEvent.VK_PAGE_DOWN
        "up", "arrow_up" -> KeyEvent.VK_UP
        "down", "arrow_down" -> KeyEvent.VK_DOWN
        "left", "arrow_left" -> KeyEvent.VK_LEFT
        "right", "arrow_right" -> KeyEvent.VK_RIGHT
        "printscreen", "print_screen", "prtsc", "prtscr" -> KeyEvent.VK_PRINTSCREEN
        "pause", "break" -> KeyEvent.VK_PAUSE
        "capslock", "caps_lock" -> KeyEvent.VK_CAPS_LOCK
        "numlock", "num_lock" -> KeyEvent.VK_NUM_LOCK
        "scrolllock", "scroll_lock" -> KeyEvent.VK_SCROLL_LOCK
        "menu", "contextmenu" -> KeyEvent.VK_CONTEXT_MENU
        "plus", "add" -> KeyEvent.VK_ADD
        "minus", "subtract" -> KeyEvent.VK_SUBTRACT
        else -> functionKey(token)
    }

    private fun functionKey(token: String): Int? {
        if (!token.startsWith("f")) return null
        val number = token.drop(1).toIntOrNull() ?: return null
        if (number !in 1..24) return null
        return KeyEvent.VK_F1 + (number - 1)
    }
}
