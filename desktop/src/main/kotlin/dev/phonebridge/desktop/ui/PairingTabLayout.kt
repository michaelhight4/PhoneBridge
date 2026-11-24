package dev.phonebridge.desktop.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants

/**
 * Layout of the "Pair a bot" tab, kept apart from [MainWindow] so it can be laid out and checked in a
 * headless unit test.
 *
 * The tab used to put the approval table in BorderLayout.SOUTH, where a JTable scroll pane asks for
 * about 400 px by default. At the default window size that took all the height, and the centre panel
 * (QR, link, New code and the other buttons) got none and vanished. Now the approval table asks for
 * only a few rows, and the code/QR/buttons area sits in its own scroll pane, so nothing is squeezed out
 * on small windows. Every list still scrolls.
 */
object PairingTabLayout {
    const val QR_SIZE = 180
    const val PENDING_ROWS = 4

    class Actions(
        val newCode: () -> Unit,
        val copyLink: () -> Unit,
        val saveFile: () -> Unit,
        val cancelCode: () -> Unit,
        val allow: () -> Unit,
        val deny: () -> Unit,
    )

    fun build(
        codeLabel: JLabel,
        countdownLabel: JLabel,
        pairingMessage: JLabel,
        qrLabel: JLabel,
        linkField: JTextField,
        pendingTable: JTable,
        levelCombo: JComboBox<String>,
        actions: Actions,
    ): JComponent {
        codeLabel.font = codeLabel.font.deriveFont(Font.BOLD, 34f)
        countdownLabel.font = countdownLabel.font.deriveFont(Font.PLAIN, 12f)
        pairingMessage.font = pairingMessage.font.deriveFont(Font.PLAIN, 12f)
        linkField.isEditable = false
        linkField.columns = 28
        linkField.maximumSize = Dimension(Int.MAX_VALUE, linkField.preferredSize.height)

        val qrPanel = JPanel(BorderLayout())
        qrPanel.border = BorderFactory.createTitledBorder("QR code")
        qrLabel.horizontalAlignment = JLabel.CENTER
        qrLabel.verticalAlignment = JLabel.CENTER
        qrLabel.preferredSize = Dimension(QR_SIZE, QR_SIZE)
        qrPanel.add(qrLabel, BorderLayout.CENTER)

        val right = JPanel()
        right.layout = BoxLayout(right, BoxLayout.Y_AXIS)
        right.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
        fun add(component: JComponent) {
            component.alignmentX = JComponent.LEFT_ALIGNMENT
            right.add(component)
        }
        add(JLabel("Short code (type it into the bot)"))
        add(codeLabel)
        add(countdownLabel)
        add(Box.createVerticalStrut(6) as JComponent)
        add(JLabel("Or send the bot this pairing link"))
        add(linkField)
        add(Box.createVerticalStrut(6) as JComponent)
        // A grid, not a FlowLayout: FlowLayout inside a BoxLayout does not wrap, so a narrow window used
        // to cut buttons off instead of showing them.
        val buttons = JPanel(GridLayout(2, 2, 6, 6))
        buttons.add(JButton(NEW_CODE).apply { addActionListener { actions.newCode() } })
        buttons.add(JButton(COPY_LINK).apply { addActionListener { actions.copyLink() } })
        buttons.add(JButton(SAVE_FILE).apply { addActionListener { actions.saveFile() } })
        buttons.add(JButton(CANCEL_CODE).apply { addActionListener { actions.cancelCode() } })
        buttons.maximumSize = Dimension(460, buttons.preferredSize.height)
        add(buttons)
        add(Box.createVerticalStrut(6) as JComponent)
        add(pairingMessage)

        val center = JPanel(BorderLayout(12, 0))
        center.add(qrPanel, BorderLayout.WEST)
        center.add(right, BorderLayout.CENTER)
        val centerScroll = JScrollPane(
            center,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        )
        centerScroll.border = BorderFactory.createEmptyBorder()
        centerScroll.name = CENTER_NAME

        val pendingPanel = JPanel(BorderLayout(8, 4))
        pendingPanel.border = BorderFactory.createTitledBorder("Allow this bot? (a bot that used the code shows up here)")
        pendingTable.fillsViewportHeight = true
        pendingTable.preferredScrollableViewportSize =
            Dimension(400, pendingTable.rowHeight * PENDING_ROWS)
        pendingPanel.add(JScrollPane(pendingTable), BorderLayout.CENTER)
        val decision = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        decision.add(JLabel("Level"))
        decision.add(levelCombo)
        decision.add(JButton(ALLOW).apply { addActionListener { actions.allow() } })
        decision.add(JButton(DENY).apply { addActionListener { actions.deny() } })
        pendingPanel.add(decision, BorderLayout.SOUTH)

        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        panel.add(centerScroll, BorderLayout.CENTER)
        panel.add(pendingPanel, BorderLayout.SOUTH)
        return panel
    }

    const val NEW_CODE = "New code"
    const val COPY_LINK = "Copy link"
    const val SAVE_FILE = "Save .pbpair file"
    const val CANCEL_CODE = "Cancel code"
    const val ALLOW = "Allow"
    const val DENY = "Deny"
    const val CENTER_NAME = "pairing-center"
}
