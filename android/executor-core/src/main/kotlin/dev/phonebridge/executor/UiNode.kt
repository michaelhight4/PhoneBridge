package dev.phonebridge.executor

/** Screen rectangle in pixels, `[l, t, r, b]`, matching `AccessibilityNodeInfo.getBoundsInScreen`. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun toList(): List<Int> = listOf(left, top, right, bottom)

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/**
 * One node of the accessibility tree, in the transport-neutral shape used by the executor.
 *
 * The Android side builds these from `AccessibilityNodeInfo`; the executor turns them into the
 * protocol `Node` (`{id, cls, text, desc, res_id, bounds, clickable, focusable, editable,
 * children:[]}`) and uses the bounds to turn a `node_id` into tap coordinates.
 */
data class UiNode(
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val clickable: Boolean = false,
    val focusable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    /** False for nodes the service reports as `isVisibleToUser == false`; dropped by `visible_only`. */
    val visible: Boolean = true,
    val children: List<UiNode> = emptyList(),
) {
    /** Depth-first count including this node; handy in tests and for `max_depth` defaults. */
    fun count(): Int = 1 + children.sumOf { it.count() }

    companion object {
        /** Convenience builder used by tests and by adapters that only have a few fields. */
        fun leaf(
            text: String? = null,
            className: String = "android.widget.TextView",
            bounds: Bounds = Bounds.EMPTY,
            clickable: Boolean = false,
            editable: Boolean = false,
            visible: Boolean = true,
        ): UiNode = UiNode(
            className = className,
            text = text,
            bounds = bounds,
            clickable = clickable,
            editable = editable,
            visible = visible,
        )
    }
}
