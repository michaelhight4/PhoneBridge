package dev.phonebridge.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiTreeSnapshotTest {

    private val tree = Fixtures.screenTree()

    @Test
    fun `ids are assigned in pre-order and are stable across snapshots`() {
        val first = UiTreeSnapshot.capture(tree)
        val second = UiTreeSnapshot.capture(Fixtures.screenTree())

        val ids = first.nodes().map { it.id }
        assertEquals(listOf("n0", "n1", "n2", "n3", "n4", "n5"), ids)
        assertEquals(ids, second.nodes().map { it.id })
        assertEquals(6, first.size)
    }

    @Test
    fun `a node resolves to its class, id and bounds`() {
        val snapshot = UiTreeSnapshot.capture(tree)
        val button = assertNotNull(snapshot.find("n2"))
        assertEquals("android.widget.Button", button.node.className)
        assertEquals("OK", button.node.text)
        assertEquals(Fixtures.bounds(100, 400, 500, 500), button.bounds)
        assertEquals(300, button.bounds.centerX)
        assertEquals(450, button.bounds.centerY)
        assertNull(snapshot.find("n99"))
    }

    @Test
    fun `protocol map carries every documented field`() {
        val snapshot = UiTreeSnapshot.capture(tree)
        val root = assertNotNull(snapshot.find("n0")).toProtocolMap()

        assertEquals("n0", root["id"])
        assertEquals("android.widget.FrameLayout", root["cls"])
        assertNull(root["text"])
        assertNull(root["desc"])
        assertNull(root["res_id"])
        assertEquals(listOf(0, 0, 1080, 2400), root["bounds"])
        assertEquals(false, root["clickable"])
        assertEquals(false, root["focusable"])
        assertEquals(false, root["editable"])
        assertEquals(true, root["visible"])
        assertEquals(4, (root["children"] as List<*>).size)
    }

    @Test
    fun `max depth trims children`() {
        val snapshot = UiTreeSnapshot.capture(tree, maxDepth = 1)
        val root = assertNotNull(snapshot.find("n0")).toProtocolMap()
        val children = root["children"] as List<*>
        assertEquals(4, children.size)
        assertTrue(children.all { ((it as Map<*, *>)["children"] as List<*>).isEmpty() })
        // Nodes below the cut are not addressable at all.
        assertNull(snapshot.find("n5"))
    }

    @Test
    fun `visible only drops hidden nodes and their subtrees`() {
        val hiddenParent = UiNode(
            className = "android.widget.LinearLayout",
            visible = false,
            bounds = Fixtures.bounds(0, 0, 100, 100),
            children = listOf(UiNode.leaf(text = "inside hidden")),
        )
        val snapshot = UiTreeSnapshot.capture(
            UiNode(bounds = Fixtures.bounds(0, 0, 1080, 2400), children = listOf(hiddenParent, tree)),
            visibleOnly = true,
        )
        assertEquals("n0", snapshot.find("n0")?.id) // the wrapper root
        assertEquals(1 + 5, snapshot.size)
        assertTrue(snapshot.nodes().none { it.node.text == "inside hidden" })
    }

    @Test
    fun `an empty window produces an empty snapshot`() {
        val snapshot = UiTreeSnapshot.capture(null)
        assertNull(snapshot.root)
        assertEquals(0, snapshot.size)
        val protocol = snapshot.toProtocolTree()
        assertTrue(protocol.containsKey("root"))
        assertNull(protocol["root"])
    }

    @Test
    fun `bounds expose width, height and emptiness`() {
        val bounds = Bounds(10, 20, 110, 220)
        assertEquals(100, bounds.width)
        assertEquals(200, bounds.height)
        assertEquals(listOf(10, 20, 110, 220), bounds.toList())
        assertTrue(bounds.isEmpty.not())
        assertTrue(Bounds(5, 5, 5, 5).isEmpty)
        assertTrue(Bounds.EMPTY.isEmpty)
    }

    @Test
    fun `node count walks the whole tree`() {
        assertEquals(6, tree.count())
    }
}
