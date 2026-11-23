package dev.phonebridge.desktop.device

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.PhoneBridgeException
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Mouse and keyboard through `java.awt.Robot`.
 *
 * Typing goes through the clipboard and Ctrl+V so any Unicode text arrives intact (Robot can only press
 * virtual keys); the previous clipboard text is restored afterwards. Dragging interpolates so Windows
 * applications register a real drag rather than a teleport.
 */
class RobotInput(
    private val robotProvider: () -> Robot = { Robot() },
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val clipboard: Toolkit = Toolkit.getDefaultToolkit(),
) : InputSink {
    private val robot: Robot by lazy {
        try {
            robotProvider()
        } catch (e: Exception) {
            throw PhoneBridgeException("mouse and keyboard control is unavailable: ${e.message}", e)
        }
    }

    override fun pointer(x: Int, y: Int) {
        robot.mouseMove(x, y)
    }

    override fun click(x: Int, y: Int) {
        robot.mouseMove(x, y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    override fun press(x: Int, y: Int, ms: Long) {
        robot.mouseMove(x, y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        try {
            sleeper(ms.coerceIn(1, 60_000))
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    override fun drag(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
        val duration = ms.coerceIn(1, 60_000)
        val steps = (duration / 10).coerceIn(2, 120).toInt()
        robot.mouseMove(x1, y1)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        try {
            for (step in 1..steps) {
                val x = x1 + (x2 - x1) * step / steps
                val y = y1 + (y2 - y1) * step / steps
                robot.mouseMove(x, y)
                sleeper(duration / steps)
            }
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    override fun scroll(x: Int?, y: Int?, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) throw BadArgsException("scroll needs dx or dy")
        if (x != null && y != null) robot.mouseMove(x, y)
        if (dx != 0) {
            // Robot has no horizontal wheel on every platform; Shift+wheel is the Windows convention.
            robot.keyPress(KeyEvent.VK_SHIFT)
            try {
                robot.mouseWheel(dx)
            } finally {
                robot.keyRelease(KeyEvent.VK_SHIFT)
            }
        }
        if (dy != 0) robot.mouseWheel(dy)
    }

    override fun typeText(text: String) {
        if (text.isEmpty()) return
        if (text.length > MAX_TEXT) throw BadArgsException("text is too long")
        val system = clipboard.systemClipboard
        val previous = runCatching {
            system.getContents(null)?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
                ?.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
        system.setContents(StringSelection(text), null)
        try {
            robot.keyPress(KeyEvent.VK_CONTROL)
            try {
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
            } finally {
                robot.keyRelease(KeyEvent.VK_CONTROL)
            }
            // Give the target window a moment to consume the paste before restoring the clipboard.
            sleeper(PASTE_SETTLE_MS)
        } finally {
            if (previous != null) runCatching { system.setContents(StringSelection(previous), null) }
        }
    }

    override fun key(combo: KeyCombo) {
        val pressed = ArrayList<Int>()
        try {
            if (combo.modifiers and KeyEvent.CTRL_DOWN_MASK != 0) {
                robot.keyPress(KeyEvent.VK_CONTROL)
                pressed.add(KeyEvent.VK_CONTROL)
            }
            if (combo.modifiers and KeyEvent.SHIFT_DOWN_MASK != 0) {
                robot.keyPress(KeyEvent.VK_SHIFT)
                pressed.add(KeyEvent.VK_SHIFT)
            }
            if (combo.modifiers and KeyEvent.ALT_DOWN_MASK != 0) {
                robot.keyPress(KeyEvent.VK_ALT)
                pressed.add(KeyEvent.VK_ALT)
            }
            if (combo.modifiers and KeyEvent.META_DOWN_MASK != 0) {
                robot.keyPress(KeyEvent.VK_WINDOWS)
                pressed.add(KeyEvent.VK_WINDOWS)
            }
            robot.keyPress(combo.keyCode)
            robot.keyRelease(combo.keyCode)
        } finally {
            for (code in pressed.asReversed()) runCatching { robot.keyRelease(code) }
        }
    }

    companion object {
        const val MAX_TEXT = 100_000
        private const val PASTE_SETTLE_MS = 60L
    }
}
