package dev.phonebridge.desktop.executor

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.model.PermissionLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Commands the desktop agent adds on top of docs/PROTOCOL.md, and their permission levels.
 *
 * They are additive: a peer that does not understand them answers `unknown_cmd` as the protocol
 * requires, and nothing about the existing command names or shapes changes. HANDOFF.md records them.
 */
enum class ExtensionCommand(val wire: String, val level: PermissionLevel) {
    /** List monitors with their virtual-screen bounds, so a bot knows where to click. */
    SCREENS("screens", PermissionLevel.VIEW),

    /** Capture JPEG/PNG frames at a capped frame rate and deliver them as `frame` events. */
    SCREEN_STREAM("screen_stream", PermissionLevel.VIEW),

    /** Move the mouse without clicking. */
    MOVE("move", PermissionLevel.CONTROL),

    /** Press, move and release with an explicit duration (a desktop drag). */
    DRAG("drag", PermissionLevel.CONTROL),

    /** Mouse wheel at a point: `dx` is horizontal (Shift+wheel), `dy` vertical. */
    SCROLL("scroll", PermissionLevel.CONTROL),

    /** Key combinations such as `["ctrl","shift","s"]`. */
    KEY_COMBO("key_combo", PermissionLevel.CONTROL),

    /** Install a program: a winget id, a local `.msi`/`.exe`, or an https download. */
    INSTALL("install", PermissionLevel.FULL),
    ;

    /** Commands that act on the PC need the caller to hold control. */
    val needsControl: Boolean
        get() = level.rank >= PermissionLevel.CONTROL.rank

    companion object {
        fun fromWire(cmd: String): ExtensionCommand? = entries.firstOrNull { it.wire == cmd }
    }
}

/** Typed reads of a JSON args object that turn shape errors into `bad_args`, never crashes. */
class JsonArgs(private val obj: JsonObject) {
    fun has(name: String): Boolean = obj.containsKey(name)

    fun string(name: String): String? {
        val value = primitive(name) ?: return null
        if (!value.isString) throw BadArgsException("$name must be a string")
        return value.content
    }

    fun int(name: String): Int? {
        val value = primitive(name) ?: return null
        if (value.isString) throw BadArgsException("$name must be a number")
        return value.intOrNull ?: throw BadArgsException("$name must be an integer")
    }

    fun long(name: String): Long? {
        val value = primitive(name) ?: return null
        if (value.isString) throw BadArgsException("$name must be a number")
        return value.content.toLongOrNull() ?: throw BadArgsException("$name must be an integer")
    }

    fun boolean(name: String, default: Boolean = false): Boolean {
        val value = primitive(name) ?: return default
        if (value.isString) throw BadArgsException("$name must be true or false")
        return value.booleanOrNull ?: throw BadArgsException("$name must be true or false")
    }

    fun stringList(name: String): List<String>? {
        val element = obj[name] ?: return null
        val array = element as? JsonArray ?: throw BadArgsException("$name must be a list")
        return array.map { item ->
            val primitive = item as? JsonPrimitive ?: throw BadArgsException("$name must be a list of strings")
            if (!primitive.isString) throw BadArgsException("$name must be a list of strings")
            primitive.content
        }
    }

    private fun primitive(name: String): JsonPrimitive? {
        val element = obj[name] ?: return null
        return element as? JsonPrimitive ?: throw BadArgsException("$name must be a simple value")
    }
}
