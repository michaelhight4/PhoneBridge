package dev.phonebridge.executor

/**
 * An immutable, filtered view of the accessibility tree with the protocol node ids assigned.
 *
 * Ids are structural paths from the root: `0`, `0.0`, `0.1`, `0.3.0`, ... so a node keeps its id no
 * matter which `ui_tree` filter (`max_depth`, `visible_only`) produced the snapshot, and both the
 * executor and the Android side can resolve the same id by walking child indexes. A bot can take
 * `ui_tree`, then send `tap {node_id}` or `type {node_id}` against exactly the ids it just saw.
 */
class UiTreeSnapshot private constructor(
    /** Root of the tree, or null for an empty window. */
    val root: IndexedNode?,
    /** All exported nodes keyed by id. */
    private val index: Map<String, IndexedNode>,
    /** Ids in pre-order, so [nodes] is deterministic. */
    private val order: List<String>,
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

    /** Pre-order list of every node in the snapshot. */
    fun nodes(): List<IndexedNode> = order.mapNotNull { index[it] }

    /** The protocol payload: `{ "root": Node }`, or null when the window was empty. */
    fun toProtocolTree(): Map<String, Any?> = linkedMapOf("root" to root?.toProtocolMap())

    companion object {
        const val ROOT_ID: String = "0"

        /**
         * Filter the tree and assign ids.
         *
         * @param maxDepth keep nodes up to this depth only (0 = root only); null keeps everything.
         * @param visibleOnly drop nodes the service reported as not visible, plus their subtrees.
         */
        fun capture(root: UiNode?, maxDepth: Int? = null, visibleOnly: Boolean = false): UiTreeSnapshot {
            if (root == null) return UiTreeSnapshot(null, emptyMap(), emptyList())

            val order = ArrayList<String>()
            val index = HashMap<String, IndexedNode>()

            fun walk(node: UiNode, path: String, depth: Int): IndexedNode? {
                if (visibleOnly && !node.visible) return null
                if (maxDepth != null && depth > maxDepth) return null
                order += path
                val children = node.children.mapIndexedNotNull { childIndex, child ->
                    walk(child, "$path.$childIndex", depth + 1)
                }
                val indexed = IndexedNode(id = path, node = node, depth = depth, children = children)
                index[path] = indexed
                return indexed
            }

            val filteredRoot = walk(root, ROOT_ID, depth = 0)
            return UiTreeSnapshot(filteredRoot, index, order)
        }
    }
}
