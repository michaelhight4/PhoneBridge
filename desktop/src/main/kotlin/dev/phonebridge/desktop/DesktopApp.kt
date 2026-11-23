package dev.phonebridge.desktop

import dev.phonebridge.desktop.app.DesktopBridge
import dev.phonebridge.desktop.app.DesktopPaths
import dev.phonebridge.desktop.state.DesktopSnapshot
import dev.phonebridge.desktop.ui.IndicatorWindow
import dev.phonebridge.desktop.ui.MainWindow
import dev.phonebridge.desktop.ui.TrayApp
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Glue between the bridge and the desktop surface: tray icon, always-on-top indicator, main window and
 * the tray balloon that names the bot in control.
 *
 * The relay is started on a background thread so the window is responsive while the heart boots, and
 * quitting always closes sessions and stops the relay before the process exits.
 */
class DesktopApp(
    private val options: DesktopOptions,
    private val out: (String) -> Unit = { println(it) },
) {
    private var bridge: DesktopBridge? = null
    private var window: MainWindow? = null
    private var indicator: IndicatorWindow? = null
    private var tray: TrayApp? = null

    private val quitLatch = CountDownLatch(1)
    private val quitting = AtomicBoolean(false)
    private var lastController: String? = null

    fun start() {
        val headless = GraphicsEnvironment.isHeadless()
        val dataDir = options.dataDir ?: DesktopPaths.defaultDataDir()
        out("PhoneBridge desktop agent starting (data: $dataDir)")
        val bridge = DesktopBridge(dataDir, log = { line -> out(line) })
        this.bridge = bridge
        out("device ${bridge.deviceName()} (${bridge.fingerprint()})")

        if (!headless) {
            SwingUtilities.invokeAndWait {
                val mainWindow = MainWindow(bridge) { window?.hideWindow() }
                window = mainWindow
                val indicatorWindow = IndicatorWindow { showWindow() }
                indicator = indicatorWindow
                val trayApp = TrayApp(
                    TrayApp.Actions(
                        open = { showWindow() },
                        pair = { mainWindow.focusTab(1) },
                        bots = { mainWindow.focusTab(2) },
                        activity = { mainWindow.focusTab(3) },
                        toggleIndicator = { toggleIndicator() },
                        kickEveryone = { bridge.kickEveryone() },
                        quit = { quit(0) },
                    ),
                    title = "PhoneBridge — ${bridge.deviceName()}",
                )
                tray = trayApp
                if (!trayApp.install()) out("no system tray on this desktop; the window and indicator still work")
                indicatorWindow.show()
                if (options.showWindow) mainWindow.showWindow()
            }
        } else {
            out("headless session: running without windows; use a desktop session to pair bots")
        }
        bridge.setSnapshotListener { snapshot -> SwingUtilities.invokeLater { onSnapshot(snapshot) } }

        Thread {
            try {
                bridge.start()
                out("relay: ${bridge.relayStatusText()}")
            } catch (e: Exception) {
                out("relay start failed: ${e.message}")
            }
            SwingUtilities.invokeLater { onSnapshot(bridge.snapshot()) }
        }.apply {
            name = "pb-desktop-start"
            isDaemon = true
            start()
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                if (!quitting.getAndSet(true)) {
                    runCatching { bridge.stop() }
                }
            },
        )
    }

    private fun onSnapshot(snapshot: DesktopSnapshot) {
        window?.refreshSnapshot(snapshot)
        tray?.updateStatus(snapshot.statusText, announce = false)
        indicator?.setStatus(snapshot.statusText, snapshot.controller != null)
        val controller = snapshot.controller
        if (controller != lastController) {
            val previous = lastController
            lastController = controller
            if (previous != null || controller != null) {
                val body = snapshot.statusText
                tray?.notify("PhoneBridge", body)
                out("controller: $body")
            }
        }
    }

    fun showWindow() {
        window?.showWindow()
        indicator?.show()
    }

    private fun toggleIndicator() {
        val current = indicator ?: return
        if (current.isVisible()) current.hide() else current.show()
    }

    fun quit(code: Int) {
        if (!quitting.getAndSet(true)) {
            out("shutting down")
            runCatching { window?.dispose() }
            runCatching { indicator?.dispose() }
            runCatching { tray?.close() }
            runCatching { bridge?.stop() }
            quitLatch.countDown()
        }
        // Swing keeps non-daemon threads alive; exit explicitly once cleanup ran.
        Thread {
            Thread.sleep(250)
            System.exit(code)
        }.apply { isDaemon = false; start() }
    }

    fun awaitQuit() = quitLatch.await()
}
