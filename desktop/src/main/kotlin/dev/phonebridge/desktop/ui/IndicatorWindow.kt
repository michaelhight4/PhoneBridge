package dev.phonebridge.desktop.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * The always-visible indicator: a small always-on-top strip that names the bot in control.
 *
 * It stays above other windows so the user can always see who is driving, opens the main window when
 * clicked, closes on Esc and hides with its own X button (the tray keeps running).
 */
class IndicatorWindow(private val onOpen: () -> Unit) {
    private val frame = JFrame("PhoneBridge indicator")
    private val statusLabel = JLabel("No bot in control")
    private val dot = JLabel("\u25CF")

    init {
        frame.isUndecorated = true
        frame.isAlwaysOnTop = true
        frame.defaultCloseOperation = JFrame.HIDE_ON_CLOSE
        frame.background = Color(0x20, 0x20, 0x20)

        val root = JPanel(BorderLayout())
        root.background = Color(0x20, 0x20, 0x20)
        root.border = BorderFactory.createLineBorder(Color(0x2E, 0x7D, 0x32), 1)

        dot.foreground = Color(0x9E, 0x9E, 0x9E)
        dot.font = dot.font.deriveFont(Font.BOLD, 14f)
        statusLabel.foreground = Color.WHITE
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 12f)

        val text = JPanel(GridBagLayout())
        text.isOpaque = false
        val constraints = GridBagConstraints()
        constraints.gridy = 0
        constraints.insets = Insets(0, 8, 0, 2)
        text.add(dot, constraints)
        constraints.insets = Insets(0, 0, 0, 8)
        text.add(statusLabel, constraints)

        val openLabel = JLabel("Open")
        openLabel.foreground = Color(0x9E, 0xCE, 0xA0)
        openLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        openLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) = onOpen()
        })

        val close = JButton("\u00D7")
        close.isFocusable = false
        close.isContentAreaFilled = false
        close.isBorderPainted = false
        close.foreground = Color(0xE0, 0xE0, 0xE0)
        close.toolTipText = "Hide the indicator (the tray icon stays)"
        close.addActionListener { frame.isVisible = false }

        val right = JPanel()
        right.isOpaque = false
        right.add(openLabel)
        right.add(Box.createHorizontalStrut(4))
        right.add(close)

        root.add(text, BorderLayout.CENTER)
        root.add(right, BorderLayout.EAST)
        frame.contentPane = root
        frame.minimumSize = Dimension(280, 40)
        frame.pack()
        frame.size = Dimension(maxOf(frame.width, 320), maxOf(frame.height, 40))

        root.registerKeyboardAction(
            { frame.isVisible = false },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
    }

    fun setStatus(text: String, connected: Boolean) {
        SwingUtilities.invokeLater {
            statusLabel.text = text
            dot.foreground = if (connected) Color(0x66, 0xBB, 0x6A) else Color(0x9E, 0x9E, 0x9E)
        }
    }

    fun show() {
        SwingUtilities.invokeLater {
            positionBottomRight()
            frame.isVisible = true
            frame.toFront()
        }
    }

    fun hide() {
        SwingUtilities.invokeLater { frame.isVisible = false }
    }

    fun isVisible(): Boolean = frame.isVisible

    fun dispose() {
        SwingUtilities.invokeLater { frame.dispose() }
    }

    private fun positionBottomRight() {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        if (GraphicsEnvironment.isHeadless()) return
        val bounds = environment.defaultScreenDevice.defaultConfiguration.bounds
        val x = bounds.x + bounds.width - frame.width - 24
        val y = bounds.y + bounds.height - frame.height - 72
        frame.setLocation(x, y)
    }
}
