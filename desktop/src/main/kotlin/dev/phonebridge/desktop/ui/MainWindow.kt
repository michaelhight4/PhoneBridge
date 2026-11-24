package dev.phonebridge.desktop.ui

import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.pairing.BotRecord
import dev.phonebridge.core.pairing.PendingApproval
import dev.phonebridge.desktop.app.DesktopBridge
import dev.phonebridge.desktop.app.HeartHost
import dev.phonebridge.desktop.app.RelayResult
import dev.phonebridge.desktop.state.DesktopSnapshot
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.table.DefaultTableModel
import javax.swing.filechooser.FileNameExtensionFilter
import java.nio.file.Path

/**
 * The main window: status, pairing (QR, short code, link and `.pbpair` file), the Bluetooth-style bot
 * list, the activity log and the relay (heart) choice.
 *
 * Closing the window hides it to the tray; Esc does the same. Quitting is the tray's job.
 */
class MainWindow(
    private val bridge: DesktopBridge,
    private val onHide: () -> Unit,
) {
    private val frame = JFrame("PhoneBridge")
    private val tabs = JTabbedPane()
    private val statusLine = JLabel("Starting...")
    private val deviceLine = JLabel()
    private val relayLine = JLabel()
    private val controllerLine = JLabel()
    private val connectedLine = JLabel()

    private val codeLabel = JLabel("No code")
    private val countdownLabel = JLabel(" ")
    private val pairingMessage = JLabel(" ")
    private val qrLabel = JLabel()
    private val linkField = JTextField()
    private var currentOffer: PairingOffer? = null

    private val pendingModel = DefaultTableModel(arrayOf("Bot", "Fingerprint", "Requests"), 0)
    private val pendingTable = JTable(pendingModel)
    private val levelCombo = JComboBox(arrayOf("view", "control", "full"))
    private var pending: List<PendingApproval> = emptyList()

    private val botsModel = DefaultTableModel(
        arrayOf("Bot", "Fingerprint", "Level", "On", "Messaging", "Last seen"),
        0,
    )
    private val botsTable = JTable(botsModel)
    private var bots: List<BotRecord> = emptyList()

    private val logArea = JTextArea()
    private val logFilter = JComboBox(arrayOf("All bots"))
    private var logBotIds: List<String?> = listOf(null)

    private val relayThisPc = JRadioButton("This PC", true)
    private val relayCustom = JRadioButton("Custom relay")
    private val relayUrlField = JTextField()
    private val relayPinField = JPasswordField()
    private val relayStatus = JLabel(" ")

    private val timer = Timer(1_000) { tick() }

    init {
        frame.iconImage = Branding.appImage(64)
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) = onHide()
        })
        frame.contentPane = buildContent()
        frame.size = Dimension(880, 620)
        frame.minimumSize = Dimension(720, 520)
        frame.setLocationRelativeTo(null)
        frame.rootPane.registerKeyboardAction(
            { onHide() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
        timer.start()
        refreshPanels()
    }

    fun showWindow() {
        SwingUtilities.invokeLater {
            frame.isVisible = true
            frame.toFront()
            refreshPanels()
            if (pairingTabShowing()) ensureOffer()
        }
    }

    fun hideWindow() {
        SwingUtilities.invokeLater { frame.isVisible = false }
    }

    /** Opens the window on a given tab: 0 status, 1 pairing, 2 bots, 3 activity, 4 relay. */
    fun focusTab(index: Int) {
        SwingUtilities.invokeLater {
            if (index in 0 until tabs.tabCount) tabs.selectedIndex = index
            frame.isVisible = true
            frame.toFront()
            if (pairingTabShowing()) ensureOffer()
        }
    }

    fun dispose() {
        timer.stop()
        SwingUtilities.invokeLater { frame.dispose() }
    }

    // ------------------------------------------------------------------ layout

    private fun buildContent(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        root.add(header(), BorderLayout.NORTH)

        tabs.addTab("Status", statusTab())
        tabs.addTab("Pair a bot", pairingTab())
        tabs.addTab("Bots", botsTab())
        tabs.addTab("Activity log", activityTab())
        tabs.addTab("Relay", relayTab())
        tabs.addChangeListener { if (pairingTabShowing()) ensureOffer() }
        root.add(tabs, BorderLayout.CENTER)
        return root
    }

    private fun header(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        statusLine.font = statusLine.font.deriveFont(Font.BOLD, 20f)
        controllerLine.font = controllerLine.font.deriveFont(Font.BOLD, 13f)
        for (label in listOf(deviceLine, relayLine, connectedLine)) {
            label.font = label.font.deriveFont(Font.PLAIN, 12f)
            label.foreground = Color(0x44, 0x44, 0x44)
        }
        panel.add(statusLine)
        panel.add(controllerLine)
        panel.add(deviceLine)
        panel.add(relayLine)
        panel.add(connectedLine)
        panel.add(Box.createVerticalStrut(4))
        return panel
    }

    private fun statusTab(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        val device = JLabel("Device: ${bridge.deviceName()}")
        device.font = device.font.deriveFont(Font.PLAIN, 14f)
        val fingerprint = JLabel("Fingerprint: ${bridge.fingerprint()}")
        fingerprint.font = fingerprint.font.deriveFont(Font.PLAIN, 14f)
        val hint = JLabel(
            "<html>Pair a bot from the <b>Pair a bot</b> tab. Only paired bots can see or control this PC,<br>" +
                "each at its own permission level. The indicator above always names the bot in control.</html>",
        )
        hint.font = hint.font.deriveFont(Font.PLAIN, 12f)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8))
        buttons.add(JButton("Move heart here").apply { addActionListener { moveHeartHere() } })
        buttons.add(JButton("Kick everyone").apply { addActionListener { kickEveryone() } })
        buttons.add(
            JButton("Show connected bots").apply {
                addActionListener { JOptionPane.showMessageDialog(frame, connectedSummary(), "Connected bots", JOptionPane.INFORMATION_MESSAGE) }
            },
        )

        panel.add(device)
        panel.add(Box.createVerticalStrut(4))
        panel.add(fingerprint)
        panel.add(Box.createVerticalStrut(12))
        panel.add(hint)
        panel.add(Box.createVerticalStrut(8))
        panel.add(buttons)
        return panel
    }

    private fun pairingTab(): JComponent {
        pendingTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) syncPendingLevel()
        }
        return PairingTabLayout.build(
            codeLabel = codeLabel,
            countdownLabel = countdownLabel,
            pairingMessage = pairingMessage,
            qrLabel = qrLabel,
            linkField = linkField,
            pendingTable = pendingTable,
            levelCombo = levelCombo,
            actions = PairingTabLayout.Actions(
                newCode = ::newOffer,
                copyLink = ::copyLink,
                saveFile = ::savePairingFile,
                cancelCode = ::cancelOffer,
                allow = ::approveSelected,
                deny = ::denySelected,
            ),
        )
    }

    /** Opening the pairing tab always shows a live code, so "No code" is never a dead end. */
    private fun ensureOffer() {
        if (currentOffer == null) newOffer()
    }

    private fun pairingTabShowing(): Boolean = frame.isVisible && tabs.selectedIndex == PAIRING_TAB

    private fun botsTab(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        botsTable.fillsViewportHeight = true
        panel.add(JScrollPane(botsTable), BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6))
        buttons.add(JButton("Turn on / off").apply { addActionListener { toggleSelectedBot() } })
        buttons.add(JButton("Toggle messaging").apply { addActionListener { toggleMessaging() } })
        buttons.add(JButton("Change level").apply { addActionListener { changeSelectedLevel() } })
        buttons.add(JButton("Unpair").apply { addActionListener { unpairSelected() } })
        buttons.add(JButton("Kick everyone").apply { addActionListener { kickEveryone() } })
        panel.add(buttons, BorderLayout.SOUTH)
        return panel
    }

    private fun activityTab(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        logArea.isEditable = false
        logArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        panel.add(JScrollPane(logArea), BorderLayout.CENTER)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6))
        controls.add(JLabel("Bot"))
        controls.add(logFilter)
        controls.add(JButton("Refresh").apply { addActionListener { reloadLog() } })
        controls.add(JButton("Clear").apply { addActionListener { clearLog() } })
        logFilter.addActionListener { reloadLog() }
        panel.add(controls, BorderLayout.SOUTH)
        return panel
    }

    private fun relayTab(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        val constraints = GridBagConstraints()
        constraints.insets = Insets(4, 4, 4, 4)
        constraints.anchor = GridBagConstraints.WEST
        constraints.gridx = 0
        constraints.gridy = 0

        val hostGroup = javax.swing.ButtonGroup()
        hostGroup.add(relayThisPc)
        hostGroup.add(relayCustom)
        val hostPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        hostPanel.add(JLabel("Heart runs on:"))
        hostPanel.add(relayThisPc)
        hostPanel.add(relayCustom)
        relayUrlField.columns = 32
        relayPinField.columns = 32

        panel.add(hostPanel, constraints)
        constraints.gridy++
        val urlRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        urlRow.add(JLabel("Relay URL"))
        urlRow.add(relayUrlField)
        panel.add(urlRow, constraints)
        constraints.gridy++
        val pinRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        pinRow.add(JLabel("Certificate pin"))
        pinRow.add(relayPinField)
        panel.add(pinRow, constraints)
        constraints.gridy++
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6))
        buttons.add(JButton("Apply").apply { addActionListener { applyRelaySettings() } })
        buttons.add(JButton("Move heart here").apply { addActionListener { moveHeartHere() } })
        panel.add(buttons, constraints)
        constraints.gridy++
        panel.add(relayStatus, constraints)
        constraints.gridy++
        val hint = JLabel(
            "<html>\"This PC\" starts the embedded relay on this machine and shows its certificate pin in<br>" +
                "new pairing codes. A custom relay must be <b>wss://</b> (plain ws:// only on this PC).</html>",
        )
        hint.font = hint.font.deriveFont(Font.PLAIN, 12f)
        panel.add(hint, constraints)

        val settings = bridge.settings()
        if (settings.host == HeartHost.CUSTOM_URL) {
            relayCustom.isSelected = true
            relayUrlField.text = settings.url.orEmpty()
            relayPinField.text = settings.tlsPin.orEmpty()
        }
        return panel
    }

    // ------------------------------------------------------------------ actions

    private fun newOffer() {
        val offer = bridge.createOffer()
        if (offer == null) {
            pairingMessage.text = "No relay is running. Start the heart on the Relay tab first."
            return
        }
        currentOffer = offer
        codeLabel.text = offer.code
        linkField.text = bridge.pairingLink(offer)
        qrLabel.icon = ImageIcon(QrCode.image(bridge.pairingLink(offer), PairingTabLayout.QR_SIZE - 12))
        pairingMessage.text = "The code is one-time and expires in ten minutes."
    }

    private fun cancelOffer() {
        currentOffer?.let { bridge.cancelOffer(it.code) }
        clearOffer("Code cancelled.")
    }

    private fun clearOffer(message: String) {
        currentOffer = null
        codeLabel.text = "No code"
        countdownLabel.text = " "
        linkField.text = ""
        qrLabel.icon = null
        pairingMessage.text = message
    }

    private fun copyLink() {
        val offer = currentOffer ?: return
        val selection = java.awt.datatransfer.StringSelection(bridge.pairingLink(offer))
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
        pairingMessage.text = "Pairing link copied."
    }

    private fun savePairingFile() {
        val offer = currentOffer ?: return
        val chooser = JFileChooser()
        chooser.fileFilter = FileNameExtensionFilter("PhoneBridge pairing file", "pbpair")
        chooser.selectedFile = java.io.File("phonebridge-${offer.code.replace('-', '_')}.pbpair")
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return
        val target = chooser.selectedFile.toPath()
        try {
            bridge.savePairingFile(offer, Path.of(target.toString()))
            pairingMessage.text = "Saved ${target.fileName}."
        } catch (e: Exception) {
            pairingMessage.text = "Could not save the file: ${e.message}"
        }
    }

    private fun approveSelected() {
        val selected = selectedPending() ?: return
        val level = PermissionLevel.parse(levelCombo.selectedItem?.toString().orEmpty()) ?: PermissionLevel.CONTROL
        if (bridge.approve(selected, level)) {
            pairingMessage.text = "Approved ${selected.botName} at ${PermissionLevel.min(level, selected.requestedLevel).wire}."
            reloadPending()
        } else {
            pairingMessage.text = "That request is no longer pending."
        }
    }

    private fun denySelected() {
        val selected = selectedPending() ?: return
        bridge.deny(selected)
        pairingMessage.text = "Denied ${selected.botName}."
        reloadPending()
    }

    private fun selectedPending(): PendingApproval? {
        val index = pendingTable.selectedRow
        return if (index in pending.indices) pending[index] else null
    }

    private fun syncPendingLevel() {
        val selected = selectedPending() ?: return
        levelCombo.selectedItem = selected.requestedLevel.wire
    }

    private fun toggleSelectedBot() {
        val bot = selectedBot() ?: return
        bridge.setEnabled(bot.botId, !bot.enabled)
        reloadBots()
    }

    private fun toggleMessaging() {
        val bot = selectedBot() ?: return
        bridge.setCanMessage(bot.botId, !bot.canMessage)
        reloadBots()
    }

    private fun changeSelectedLevel() {
        val bot = selectedBot() ?: return
        val levels = arrayOf("view", "control", "full")
        val chosen = JOptionPane.showInputDialog(
            frame,
            "Permission level for ${bot.name}",
            "Permission level",
            JOptionPane.QUESTION_MESSAGE,
            null,
            levels,
            bot.level.wire,
        ) as? String ?: return
        PermissionLevel.parse(chosen)?.let {
            bridge.setLevel(bot.botId, it)
            reloadBots()
        }
    }

    private fun unpairSelected() {
        val bot = selectedBot() ?: return
        val answer = JOptionPane.showConfirmDialog(
            frame,
            "Unpair ${bot.name}? Its key is deleted; the other bots are untouched.",
            "Unpair bot",
            JOptionPane.OK_CANCEL_OPTION,
        )
        if (answer != JOptionPane.OK_OPTION) return
        bridge.unpair(bot.botId)
        reloadBots()
    }

    private fun selectedBot(): BotRecord? {
        val index = botsTable.selectedRow
        return if (index in bots.indices) bots[index] else null
    }

    private fun kickEveryone() {
        bridge.kickEveryone()
        reloadBots()
        pairingMessage.text = "Every bot was kicked; pairings are kept."
    }

    private fun applyRelaySettings() {
        if (relayThisPc.isSelected) {
            moveHeartHere()
            return
        }
        val result = bridge.setCustomRelay(relayUrlField.text, relayPinField.text)
        relayStatus.text = when (result) {
            is RelayResult.Ok -> "Relay: ${result.url}"
            is RelayResult.Error -> result.message
        }
    }

    private fun moveHeartHere() {
        val result = bridge.moveHeartHere()
        relayStatus.text = when (result) {
            is RelayResult.Ok -> "Heart now beats on this PC: ${result.url}"
            is RelayResult.Error -> result.message
        }
        relayThisPc.isSelected = true
        refreshPanels()
    }

    private fun clearLog() {
        bridge.clearActivity()
        reloadLog()
    }

    // ------------------------------------------------------------------ refresh

    private fun tick() {
        val offer = currentOffer
        if (offer != null) {
            val remaining = ((offer.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            countdownLabel.text = if (remaining > 0) "Expires in ${remaining / 60}:${"%02d".format(remaining % 60)}" else "Expired"
            if (remaining == 0L) {
                clearOffer("The code expired. Click New code for another one.")
                if (pairingTabShowing()) newOffer()
            }
        }
        reloadPending()
        reloadBots()
        if (logArea.isShowing) reloadLog()
    }

    private fun refreshPanels() {
        reloadPending()
        reloadBots()
        reloadLog()
        relayStatus.text = bridge.relayStatusText()
        refreshSnapshot(bridge.snapshot())
    }

    fun refreshSnapshot(snapshot: DesktopSnapshot) {
        statusLine.text = snapshot.statusText
        controllerLine.text = if (snapshot.relayConnected) "Relay connected" else "Relay connecting..."
        deviceLine.text = "Device: ${snapshot.deviceName}  ·  fingerprint ${bridge.fingerprint()}"
        relayLine.text = "Relay: ${snapshot.heartUrl ?: "not running"}"
        connectedLine.text = if (snapshot.connectedBots.isEmpty()) {
            "No bot is connected"
        } else {
            "Connected: ${snapshot.connectedBots.joinToString { it.take(8).uppercase() }}"
        }
    }

    private fun reloadPending() {
        val live = bridge.pendingApprovals()
        if (live.size == pending.size && live.map { it.botId } == pending.map { it.botId }) return
        pending = live
        pendingModel.rowCount = 0
        for (approval in pending) {
            pendingModel.addRow(arrayOf(approval.botName, approval.botFingerprint, approval.requestedLevel.wire))
        }
        if (pending.isNotEmpty() && pendingTable.selectedRow < 0) {
            pendingTable.selectionModel.setSelectionInterval(0, 0)
            syncPendingLevel()
        }
    }

    private fun reloadBots() {
        val live = bridge.bots()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        bots = live
        botsModel.rowCount = 0
        for (bot in live) {
            botsModel.addRow(
                arrayOf(
                    bot.name,
                    bot.fingerprint,
                    bot.level.wire,
                    if (bot.enabled) "on" else "off",
                    if (bot.canMessage) "on" else "off",
                    if (bot.lastSeen > 0) stamp.format(Instant.ofEpochMilli(bot.lastSeen)) else "never",
                ),
            )
        }
        val names = listOf<String?>(null) + live.map { it.botId }
        if (names != logBotIds) {
            logBotIds = names
            val selected = logFilter.selectedIndex
            logFilter.removeAllItems()
            logFilter.addItem("All bots")
            for (bot in live) logFilter.addItem(bot.name)
            if (selected in 0 until logFilter.itemCount) logFilter.selectedIndex = selected
        }
    }

    private fun reloadLog() {
        val filterIndex = logFilter.selectedIndex
        val botId = if (filterIndex > 0 && filterIndex - 1 < bots.size) bots[filterIndex - 1].botId else null
        val entries = bridge.activityEntries(300, botId)
        logArea.text = if (entries.isEmpty()) "No activity yet." else entries.joinToString("\n") { it.line() }
        logArea.caretPosition = 0
    }

    private fun connectedSummary(): String {
        val snapshot = bridge.snapshot()
        if (snapshot.connectedBots.isEmpty()) return "No bot is connected."
        return snapshot.connectedBots.joinToString("\n") { id ->
            val record = bots.firstOrNull { it.botId == id }
            "${record?.name ?: id} — ${record?.fingerprint ?: ""} (${record?.level?.wire ?: "?"})"
        }
    }

    private companion object {
        const val PAIRING_TAB = 1
    }
}
