package dev.phonebridge.desktop.ui

import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless check that the "Pair a bot" tab shows its controls at the default window size. The tab
 * area of the 880x620 window is roughly 830x400 after the header, the tab strip and the borders.
 * This test came from a live bug where only "No code" and the approval box were visible.
 */
class PairingTabLayoutTest {
    private fun build(): Container {
        val actions = PairingTabLayout.Actions({}, {}, {}, {}, {}, {})
        return PairingTabLayout.build(
            JLabel("No code"),
            JLabel(" "),
            JLabel(" "),
            JLabel(),
            JTextField(),
            JTable(DefaultTableModel(arrayOf("Bot", "Fingerprint", "Requests"), 0)),
            JComboBox(arrayOf("view", "control", "full")),
            actions,
        ) as Container
    }

    private fun layoutAll(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutAll)
        }
    }

    private fun find(root: Container, predicate: (Component) -> Boolean): List<Component> {
        val out = mutableListOf<Component>()
        fun walk(c: Component) {
            if (predicate(c)) out += c
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out
    }

    /** Bounds of [c] in [root] coordinates, clipped by every ancestor (so a squeezed-out panel is empty). */
    private fun visibleBounds(root: Container, c: Component): Rectangle {
        var rect = Rectangle(0, 0, c.width, c.height)
        var node: Component = c
        while (node !== root) {
            val parent = node.parent ?: return Rectangle()
            rect.translate(node.x, node.y)
            rect = rect.intersection(Rectangle(0, 0, parent.width, parent.height))
            node = parent
        }
        return rect
    }

    private fun assertControlsVisible(width: Int, height: Int) {
        val root = build()
        root.setSize(width, height)
        layoutAll(root)
        for (text in listOf(
            PairingTabLayout.NEW_CODE,
            PairingTabLayout.COPY_LINK,
            PairingTabLayout.SAVE_FILE,
            PairingTabLayout.CANCEL_CODE,
            PairingTabLayout.ALLOW,
            PairingTabLayout.DENY,
        )) {
            val button = find(root) { it is JButton && it.text == text }.single()
            val visible = visibleBounds(root, button)
            assertTrue(
                visible.width >= button.width && visible.height >= button.height && button.height > 0,
                "'$text' should be fully visible at ${width}x$height, but its visible area was $visible of ${button.size}",
            )
        }
        val code = find(root) { it is JLabel && it.text == "No code" }.single()
        assertTrue(visibleBounds(root, code).height > 0, "the code label should be visible at ${width}x$height")
    }

    @Test fun everythingShowsAtTheDefaultWindowSize() = assertControlsVisible(830, 400)

    @Test fun everythingShowsAtTheReportedLiveSize() = assertControlsVisible(814, 396)

    @Test fun everythingShowsAtTheMinimumWindowSize() {
        // 720x520 minimum window: the tab area is about 670x300. The centre scrolls if needed, but the
        // approval buttons must stay on screen and the centre must keep a usable height.
        val root = build()
        root.setSize(670, 300)
        layoutAll(root)
        val center = find(root) { it.name == PairingTabLayout.CENTER_NAME }.single()
        assertTrue(center.height >= 80, "the code/QR area keeps a usable height, got ${center.height}")
        for (text in listOf(PairingTabLayout.ALLOW, PairingTabLayout.DENY)) {
            val button = find(root) { it is JButton && it.text == text }.single()
            assertTrue(visibleBounds(root, button).height == button.height && button.height > 0, "'$text' visible")
        }
    }
}
