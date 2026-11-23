package dev.phonebridge.desktop.ui

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * The tray icon and its menu.
 *
 * Every menu entry is here so the app is usable without opening the main window, and the
 * "who is in control" line is both the tooltip and the first menu entry. Controller changes are shown
 * as a tray balloon (toast) as well.
 */
class TrayApp(
    private val actions: Actions,
    private val title: String = "PhoneBridge",
) {
    data class Actions(
        val open: () -> Unit,
        val pair: () -> Unit,
        val bots: () -> Unit,
        val activity: () -> Unit,
        val toggleIndicator: () -> Unit,
        val kickEveryone: () -> Unit,
        val quit: () -> Unit,
    )

    private var icon: TrayIcon? = null
    private var statusItem: java.awt.MenuItem? = null
    private var lastStatus: String = ""

    fun isSupported(): Boolean = SystemTray.isSupported()

    /** Adds the icon; returns false when this desktop has no system tray. */
    fun install(): Boolean {
        if (!SystemTray.isSupported()) return false
        val trayIcon = TrayIcon(Branding.trayImage(), title)
        trayIcon.isImageAutoSize = true
        trayIcon.toolTip = "$title — no bot in control"

        val menu = java.awt.PopupMenu()
        val status = java.awt.MenuItem("No bot in control")
        status.isEnabled = false
        statusItem = status
        menu.add(status)
        menu.addSeparator()
        menu.add(menuItem("Open PhoneBridge", actions.open))
        menu.add(menuItem("Pair a bot...", actions.pair))
        menu.add(menuItem("Bots...", actions.bots))
        menu.add(menuItem("Activity log...", actions.activity))
        menu.add(menuItem("Show / hide indicator", actions.toggleIndicator))
        menu.addSeparator()
        menu.add(menuItem("Kick everyone", actions.kickEveryone))
        menu.addSeparator()
        menu.add(menuItem("Quit", actions.quit))
        trayIcon.popupMenu = menu
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1) actions.open()
            }
        })
        return try {
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Updates the tooltip, the first menu line and, when [announce] is set, shows a balloon. */
    fun updateStatus(text: String, announce: Boolean = false) {
        SwingUtilities.invokeLater {
            val trayIcon = icon ?: return@invokeLater
            trayIcon.toolTip = "$title — $text"
            statusItem?.label = text
            if (announce && text != lastStatus) notify("PhoneBridge", text)
            lastStatus = text
        }
    }

    /** A tray balloon (toast) for a controller change or a pairing request. */
    fun notify(headline: String, body: String) {
        SwingUtilities.invokeLater {
            icon?.displayMessage(headline, body, TrayIcon.MessageType.INFO)
        }
    }

    fun close() {
        val trayIcon = icon ?: return
        runCatching { SystemTray.getSystemTray().remove(trayIcon) }
        icon = null
    }

    private fun menuItem(label: String, action: () -> Unit): java.awt.MenuItem =
        java.awt.MenuItem(label).also { it.addActionListener { action() } }
}
