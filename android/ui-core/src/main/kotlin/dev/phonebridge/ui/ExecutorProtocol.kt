package dev.phonebridge.ui

import dev.phonebridge.core.model.AppRequest
import dev.phonebridge.core.model.AppResponse
import dev.phonebridge.core.model.ErrorBody
import dev.phonebridge.executor.ErrorCode
import dev.phonebridge.executor.Request
import dev.phonebridge.executor.Response
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Converts between the wire messages in `:core` (`AppRequest` / `AppResponse`, kotlinx JSON) and the
 * plain-Kotlin request the Android executor consumes. Keeping this here, rather than inside the
 * service, means the mapping is unit tested on the JVM.
 */
object ExecutorProtocol {

    fun toExecutor(request: AppRequest): Request =
        Request(
            id = request.id,
            cmd = request.cmd,
            args = request.args.mapValues { (_, value) -> toKotlin(value) },
        )

    /** `:core` permission levels and the executor's own enum are separate types; map by name. */
    fun toExecutor(level: dev.phonebridge.core.model.PermissionLevel): dev.phonebridge.executor.PermissionLevel =
        when (level) {
            dev.phonebridge.core.model.PermissionLevel.VIEW -> dev.phonebridge.executor.PermissionLevel.VIEW
            dev.phonebridge.core.model.PermissionLevel.CONTROL -> dev.phonebridge.executor.PermissionLevel.CONTROL
            dev.phonebridge.core.model.PermissionLevel.FULL -> dev.phonebridge.executor.PermissionLevel.FULL
        }

    fun toAppResponse(response: Response): AppResponse {
        if (response.ok) {
            val result = response.result ?: emptyMap()
            return AppResponse(id = response.id, ok = true, result = toJson(result) as JsonObject)
        }
        val error = response.error
        return AppResponse(
            id = response.id,
            ok = false,
            error = ErrorBody(
                code = error?.code?.wire ?: ErrorCode.INTERNAL.wire,
                message = error?.message,
            ),
        )
    }

    /** JSON -> the `Int`/`Long`/`Double`/`String`/`Boolean`/`List`/`Map` forms `executor.Args` reads. */
    fun toKotlin(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { (_, value) -> toKotlin(value) }
        is JsonArray -> element.map { toKotlin(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
    }

    /** Executor result map -> JSON. Handles the primitives, lists and nested maps results actually use. */
    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value.toInt())
        is Byte -> JsonPrimitive(value.toInt())
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(
            value.entries.associate { (key, item) -> key.toString() to toJson(item) },
        )
        is Iterable<*> -> JsonArray(value.map { toJson(it) })
        is Array<*> -> JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(value.toString())
    }

    /**
     * A short, non-sensitive summary for the activity log. Typed text, shell commands and file
     * contents are deliberately never copied into the log.
     */
    fun detailFor(request: Request): String? {
        val args = request.args
        fun int(name: String): Int? = (args[name] as? Number)?.toInt()
        fun str(name: String): String? = args[name] as? String
        return when (request.cmd) {
            "tap", "long_press" -> {
                val node = str("node_id") ?: str("nodeId")
                if (node != null) "node $node" else {
                    val x = int("x")
                    val y = int("y")
                    if (x != null && y != null) "($x,$y)" else null
                }
            }
            "swipe" -> {
                val x1 = int("x1")
                val y1 = int("y1")
                val x2 = int("x2")
                val y2 = int("y2")
                if (x1 != null && y1 != null && x2 != null && y2 != null) "($x1,$y1) → ($x2,$y2)" else null
            }
            "type" -> {
                val text = str("text")
                if (text == null) null else "${text.length} character(s)"
            }
            "key" -> str("key")
            "open_app" -> str("package") ?: str("label") ?: str("package_name") ?: str("pkg")
            "request_control", "release_control", "status", "screenshot", "ui_tree", "notifications" -> null
            else -> null
        }
    }
}
