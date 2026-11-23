package dev.phonebridge.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.phonebridge.executor.Bounds
import dev.phonebridge.executor.UiNode
import dev.phonebridge.executor.UiTreeSnapshot

/**
 * Turns `AccessibilityNodeInfo` trees into the transport-neutral [UiNode] trees the executor uses,
 * and resolves protocol node ids (`0`, `0.1`, `0.3.0`, ...) back to live nodes.
 *
 * Ids are child-index paths from the root, exactly as [UiTreeSnapshot] assigns them, so a bot that
 * takes `ui_tree` can then send `tap {node_id}` or `type {node_id}` and the same path resolves here.
 */
class AccessibilityTreeReader(private val service: AccessibilityService) {

    private class Budget {
        var visited = 0
    }

    /** Snapshot of the active window, or null when no window is available. */
    fun readActiveWindow(): UiNode? {
        val root = try {
            service.rootInActiveWindow
        } catch (e: SecurityException) {
            // The service lost its grant between the check and the call.
            null
        } ?: return null
        return map(root, depth = 0, budget = Budget())
    }

    /**
     * Resolve `0.1.2` to the matching node. Returns null when the id is malformed, the screen changed
     * or the node was dropped by the depth/size cap.
     */
    fun findNode(nodeId: String): AccessibilityNodeInfo? {
        val segments = nodeId.split('.')
        if (segments.firstOrNull() != UiTreeSnapshot.ROOT_ID) return null

        var current = try {
            service.rootInActiveWindow
        } catch (e: SecurityException) {
            null
        } ?: return null

        for (segment in segments.drop(1)) {
            val index = segment.toIntOrNull() ?: return null
            if (index < 0 || index >= current.childCount) return null
            current = current.getChild(index) ?: return null
        }
        return current
    }

    private fun map(node: AccessibilityNodeInfo, depth: Int, budget: Budget): UiNode? {
        if (depth > MAX_DEPTH || budget.visited++ >= MAX_NODES) return null

        val rect = Rect().also { node.getBoundsInScreen(it) }
        val children = ArrayList<UiNode>(node.childCount.coerceAtLeast(0))
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            map(child, depth + 1, budget)?.let(children::add)
        }

        return UiNode(
            className = node.className?.toString(),
            // Never leak password fields, even though the platform already hides their text.
            text = if (node.isPassword) null else node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            focusable = node.isFocusable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            visible = node.isVisibleToUser,
            children = children,
        )
    }

    companion object {
        /** Deep enough for any real app; stops a pathological/cyclic tree. */
        const val MAX_DEPTH = 40

        /** Hard cap on nodes per snapshot, to keep `ui_tree` replies sane over the wire. */
        const val MAX_NODES = 3000
    }
}
