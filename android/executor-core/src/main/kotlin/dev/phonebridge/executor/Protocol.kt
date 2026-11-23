package dev.phonebridge.executor

/**
 * A single application-level request from a bot, mirroring `docs/PROTOCOL.md`:
 *
 * ```json
 * { "id": "<uuid>", "cmd": "tap", "args": { "x": 540, "y": 1200 } }
 * ```
 *
 * The executor deliberately takes the decoded form ([args] is already parsed) so that the whole
 * dispatch path stays plain Kotlin. The wire/JSON layer (owned by `:core`, or the Android
 * transport service) converts a `JsonObject` into this map; scalars may arrive as [Int], [Long],
 * [Double], [String] or [Boolean] and [Args] normalises them.
 */
data class Request(
    val id: String,
    val cmd: String,
    val args: Map<String, Any?> = emptyMap(),
)

/**
 * The executor's reply. Exactly one of [result] and [error] is set.
 *
 * ```json
 * { "id": "<uuid>", "ok": true,  "result": { } }
 * { "id": "<uuid>", "ok": false, "error": { "code": "permission_denied", "message": "needs Control" } }
 * ```
 */
data class Response(
    val id: String,
    val ok: Boolean,
    val result: Map<String, Any?>? = null,
    val error: ProtocolError? = null,
) {
    companion object {
        fun success(id: String, result: Map<String, Any?> = emptyMap()): Response =
            Response(id = id, ok = true, result = result)

        fun failure(id: String, code: ErrorCode, message: String): Response =
            Response(id = id, ok = false, error = ProtocolError(code, message))

        fun failure(id: String, error: ProtocolError): Response =
            Response(id = id, ok = false, error = error)
    }
}

/** `{ "code": "...", "message": "..." }` from `docs/PROTOCOL.md`. */
data class ProtocolError(val code: ErrorCode, val message: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf("code" to code.wire, "message" to message)
}

/** The error codes listed in `docs/PROTOCOL.md`. Do not invent new ones without extending the doc. */
enum class ErrorCode(val wire: String) {
    PERMISSION_DENIED("permission_denied"),
    NOT_CONTROLLER("not_controller"),
    BUSY("busy"),
    BAD_ARGS("bad_args"),
    UNKNOWN_CMD("unknown_cmd"),
    PEER_OFFLINE("peer_offline"),
    ACCESSIBILITY_DISABLED("accessibility_disabled"),
    PROJECTION_DENIED("projection_denied"),
    SHIZUKU_UNAVAILABLE("shizuku_unavailable"),
    TIMEOUT("timeout"),
    INTERNAL("internal"),
}
