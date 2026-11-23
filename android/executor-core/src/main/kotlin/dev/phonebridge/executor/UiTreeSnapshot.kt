package dev.phonebridge.executor

/**
 * An immutable, filtered view of the accessibility tree with the protocol node ids assigned.
 *
 * Ids are assigned in pre-order (`n0`, `n1`, ...) over the *filtered* tree, so they are stable and
 * repeatable for the same filters and screen: a bot can take `ui_tree`, then send `tap {node_id}`
 * against exactly the ids it just saw. A new screenshot/screen state produces a new snapshot with
 * new ids, which is why the executor keeps the last snapshot it handed out.
 */
class UiTreeSnapshot private constructor(
    /** Root of the filtered tree, or null for an empty window. */
    val root: IndexedNode?,
    /** All nodes of the filtered tree in pre-order, keyed by id. */
    private val index: Map<String, IndexedNode>,
) {
    data class IndexedNode(
        val id: String,
        val node: UiNode,
        val depth: Int,
        val children: List<IndexedNode>,
    ) {
        val bounds: Bounds get() = node.bounds

        /** The protocol `Node` object. */
        fun toProtocolMap(): Map<String, Any?> = linkedMapOf(
            "id" to id,
            "cls" to node.className,
            "text" to node.text,
            "desc" to node.contentDescription,
            "res_id" to node.resourceId,
            "bounds" to bounds.toList(),
            "clickable" to node.clickable,
            "focusable" to node.focusable,
            "editable" to node.editable,
            "visible" to node.visible,
            "children" to children.map { it.toProtocolMap() },
        )
    }

    fun find(id: String): IndexedNode? = index[id]

    val size: Int get() = index.size

    private fun idNumber(id: String): Int = id.removePrefix("n").toIntOrNull() ?: Int.MAX_VALUE

    /** Pre-order list of every node in the snapshot. */
    fun nodes(): List<IndexedNode> = index.values.sortedBy { idNumber(it.id) }

    /** The protocol payload: `{ "root": Node }`, or null when the window was empty. */
    fun toProtocolTree(): Map<String, Any?> = linkedMapOf("root" to root?.toProtocolMap())

    companion object {
        /**
         * Filter the tree and assign ids.
         *
         * @param maxDepth keep nodes up to this depth only (0 = root only); null keeps everything.
         * @param visibleOnly drop nodes the service reported as not visible, plus their subtrees.
         */
        fun capture(root: UiNode?, maxDepth: Int? = null, visibleOnly: Boolean = false): UiTreeSnapshot {
            if (root == null) return UiTreeSnapshot(null, emptyMap())
            val preOrder = ArrayList<IndexedNode>()
            var counter = 0

            fun walk(node: UiNode, depth: Int): IndexedNode? {
                if (visibleOnly && !node.visible) return null
                if (maxDepth != null && depth > maxDepth) return null
                val id = "n${counter++}"
                val children = node.children.mapNotNull { child -> walk(child, depth + 1) }
                val indexed = IndexedNode(id = id, node = node, depth = depth, children = children)
                preOrder += indexed
                return indexed
            }

            val filteredRoot = walk(root, depth = 0)
            // `associateBy` keeps the pre-order sequence, so `nodes()` is in pre-order too.
            return UiTreeSnapshot(filteredRoot, preOrder.associateBy { it.id })
        }
    }
}
