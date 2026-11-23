package dev.phonebridge.core.model

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.PermissionDeniedException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.UnknownCommandException
import dev.phonebridge.core.util.PbJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// ---------------------------------------------------------------------------------------------------
// Typed arguments
// ---------------------------------------------------------------------------------------------------

/** Marker for every typed command argument object. Commands without arguments are `data object`s. */
sealed interface CommandArgs {
    /** Throws [BadArgsException] when the arguments cannot be executed as written. */
    fun validate()
}

/** Marker for every typed command result. Commands that answer `{}` use [EmptyResult]. */
sealed interface CommandResult

@Serializable
data object EmptyResult : CommandResult

@Serializable
enum class KeyName {
    @SerialName("back")
    BACK,

    @SerialName("home")
    HOME,

    @SerialName("recents")
    RECENTS,

    @SerialName("notifications")
    NOTIFICATIONS,

    @SerialName("quick_settings")
    QUICK_SETTINGS,

    @SerialName("lock")
    LOCK,

    @SerialName("enter")
    ENTER,

    @SerialName("power_dialog")
    POWER_DIALOG,
}

@Serializable
enum class FileOp {
    @SerialName("list")
    LIST,

    @SerialName("read")
    READ,

    @SerialName("write")
    WRITE,

    @SerialName("delete")
    DELETE,
}

@Serializable
enum class SettingsNamespace {
    @SerialName("system")
    SYSTEM,

    @SerialName("secure")
    SECURE,

    @SerialName("global")
    GLOBAL,
}

@Serializable
data object StatusArgs : CommandArgs {
    override fun validate() = Unit
}

@Serializable
data class ScreenshotArgs(
    val format: String = "png",
    val quality: Int? = null,
    @SerialName("max_width")
    val maxWidth: Int? = null,
) : CommandArgs {
    override fun validate() {
        check(format == "png" || format == "jpeg") { "format must be png or jpeg" }
        quality?.let { check(it in 1..100) { "quality must be 1..100" } }
        maxWidth?.let { check(it in 16..16384) { "max_width must be 16..16384" } }
    }
}

@Serializable
data class UiTreeArgs(
    @SerialName("max_depth")
    val maxDepth: Int? = null,
    @SerialName("visible_only")
    val visibleOnly: Boolean = true,
) : CommandArgs {
    override fun validate() {
        maxDepth?.let { check(it in 1..1000) { "max_depth must be 1..1000" } }
    }
}

@Serializable
data class NotificationsArgs(
    val since: Long? = null,
) : CommandArgs {
    override fun validate() {
        since?.let { check(it >= 0) { "since must be an epoch millisecond timestamp" } }
    }
}

@Serializable
data class TapArgs(
    val x: Int? = null,
    val y: Int? = null,
    @SerialName("node_id")
    val nodeId: String? = null,
) : CommandArgs {
    override fun validate() {
        val hasPoint = x != null || y != null
        if (nodeId != null) check(!hasPoint) { "tap takes either x/y or node_id, not both" }
        if (nodeId == null) {
            check(x != null && y != null) { "tap needs x and y, or node_id" }
            check(x!! >= 0 && y!! >= 0) { "tap coordinates must not be negative" }
        }
    }
}

@Serializable
data class LongPressArgs(
    val x: Int? = null,
    val y: Int? = null,
    @SerialName("node_id")
    val nodeId: String? = null,
    val ms: Long? = null,
) : CommandArgs {
    override fun validate() {
        val hasPoint = x != null || y != null
        if (nodeId != null) {
            check(!hasPoint) { "long_press takes either x/y or node_id, not both" }
        } else {
            check(x != null && y != null) { "long_press needs x and y, or node_id" }
            check(x!! >= 0 && y!! >= 0) { "coordinates must not be negative" }
        }
        ms?.let { check(it in 1..60_000) { "ms must be 1..60000" } }
    }
}

@Serializable
data class SwipeArgs(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val ms: Long? = null,
) : CommandArgs {
    override fun validate() {
        check(x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) { "coordinates must not be negative" }
        check(x1 != x2 || y1 != y2) { "swipe needs two different points" }
        ms?.let { check(it in 1..60_000) { "ms must be 1..60000" } }
    }
}

@Serializable
data class TypeArgs(
    val text: String,
    @SerialName("node_id")
    val nodeId: String? = null,
) : CommandArgs {
    override fun validate() {
        check(text.length <= 100_000) { "text is too long" }
    }
}

@Serializable
data class KeyArgs(
    val key: KeyName,
) : CommandArgs {
    override fun validate() = Unit
}

@Serializable
data class OpenAppArgs(
    @SerialName("package")
    val packageName: String? = null,
    val label: String? = null,
) : CommandArgs {
    override fun validate() {
        check((packageName != null) != (label != null)) { "open_app takes either package or label" }
        packageName?.let { check(it.isNotBlank()) { "package must not be blank" } }
        label?.let { check(it.isNotBlank()) { "label must not be blank" } }
    }
}

@Serializable
data object RequestControlArgs : CommandArgs {
    override fun validate() = Unit
}

@Serializable
data object ReleaseControlArgs : CommandArgs {
    override fun validate() = Unit
}

@Serializable
data class InstallApkArgs(
    val url: String? = null,
    @SerialName("data_b64")
    val dataB64: String? = null,
    val name: String? = null,
) : CommandArgs {
    override fun validate() {
        check((url != null) != (dataB64 != null)) { "install_apk takes either url or data_b64" }
        url?.let { check(it.startsWith("https://")) { "url must be https" } }
        if (dataB64 != null) check(!name.isNullOrBlank()) { "an inline APK needs a name" }
    }
}

@Serializable
data class ShellArgs(
    val command: String,
    @SerialName("timeout_ms")
    val timeoutMs: Long? = null,
) : CommandArgs {
    override fun validate() {
        check(command.isNotBlank()) { "command must not be blank" }
        timeoutMs?.let { check(it in 1..3_600_000) { "timeout_ms must be 1..3600000" } }
    }
}

@Serializable
data class FilesArgs(
    val op: FileOp,
    val path: String,
    @SerialName("data_b64")
    val dataB64: String? = null,
) : CommandArgs {
    override fun validate() {
        check(path.isNotBlank()) { "path must not be blank" }
        if (op == FileOp.WRITE) check(dataB64 != null) { "files write needs data_b64" }
        if (op != FileOp.WRITE) check(dataB64 == null) { "data_b64 is only valid for write" }
    }
}

@Serializable
data class SettingsArgs(
    val namespace: SettingsNamespace,
    val key: String,
    val value: String? = null,
) : CommandArgs {
    override fun validate() {
        check(key.isNotBlank()) { "key must not be blank" }
    }
}

@Serializable
data class RelayMoveArgs(
    val to: String,
) : CommandArgs {
    override fun validate() {
        check(to.isNotBlank()) { "to must be this, a peer id, or a URL" }
    }
}

// ---------------------------------------------------------------------------------------------------
// Typed results
// ---------------------------------------------------------------------------------------------------

@Serializable
data class StatusResult(
    val device: String? = null,
    val battery: Int? = null,
    @SerialName("screen_on")
    val screenOn: Boolean? = null,
    val controller: String? = null,
    val queue: List<String> = emptyList(),
    val shizuku: Boolean? = null,
) : CommandResult

@Serializable
data class ScreenshotResult(
    val mime: String,
    val width: Int,
    val height: Int,
    /** Present when the image fits in one message. */
    @SerialName("data_b64")
    val dataB64: String? = null,
    /** True when the pixels arrive as `chunk` events keyed by [chunkId]. */
    val chunked: Boolean = false,
    @SerialName("chunk_id")
    val chunkId: String? = null,
    val total: Int? = null,
    val size: Long? = null,
    val sha256: String? = null,
) : CommandResult

@Serializable
data class UiNode(
    val id: String? = null,
    @SerialName("cls")
    val cls: String? = null,
    val text: String? = null,
    val desc: String? = null,
    @SerialName("res_id")
    val resId: String? = null,
    /** `[left, top, right, bottom]`, exactly four entries. */
    val bounds: List<Int> = emptyList(),
    /** Whether the node is currently visible; optional so older senders can omit it. */
    val visible: Boolean? = null,
    val clickable: Boolean = false,
    val focusable: Boolean = false,
    val editable: Boolean = false,
    val children: List<UiNode> = emptyList(),
) {
    fun boundsRect(): List<Int> {
        if (bounds.size != 4) throw BadArgsException("ui node bounds must have 4 entries")
        return bounds
    }
}

@Serializable
data class UiTreeResult(
    val root: UiNode,
) : CommandResult

@Serializable
data class NotificationItem(
    val pkg: String,
    val title: String? = null,
    val text: String? = null,
    val time: Long? = null,
)

@Serializable
data class NotificationsResult(
    val items: List<NotificationItem> = emptyList(),
) : CommandResult

@Serializable
data class RequestControlResult(
    val granted: Boolean,
    val position: Int? = null,
) : CommandResult

@Serializable
data class InstallApkResult(
    @SerialName("package")
    val packageName: String,
) : CommandResult

@Serializable
data class ShellResult(
    val exit: Int,
    val stdout: String = "",
    val stderr: String = "",
) : CommandResult

@Serializable
data class FileEntry(
    val name: String,
    val path: String? = null,
    @SerialName("is_dir")
    val isDir: Boolean = false,
    val size: Long? = null,
    val mtime: Long? = null,
)

@Serializable
data class FilesResult(
    val op: String? = null,
    val entries: List<FileEntry>? = null,
    @SerialName("data_b64")
    val dataB64: String? = null,
    val bytes: Long? = null,
    val ok: Boolean? = null,
) : CommandResult

@Serializable
data class SettingsResult(
    val value: String? = null,
) : CommandResult

@Serializable
data class RelayMoveResult(
    @SerialName("new_url")
    val newUrl: String? = null,
) : CommandResult

// ---------------------------------------------------------------------------------------------------
// The command table: name, permission level, argument type, result type
// ---------------------------------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
private fun <T : CommandArgs> argsCodec(serializer: KSerializer<T>): KSerializer<CommandArgs> =
    serializer as KSerializer<CommandArgs>

@Suppress("UNCHECKED_CAST")
private fun <T : CommandResult> resultCodec(serializer: KSerializer<T>): KSerializer<CommandResult> =
    serializer as KSerializer<CommandResult>

/**
 * Every command in docs/PROTOCOL.md with its permission level. This is the single table the device, the
 * bot and the MCP server all enforce from.
 */
enum class CommandType(
    val wire: String,
    val level: PermissionLevel,
    val argsSerializer: KSerializer<CommandArgs>,
    val resultSerializer: KSerializer<CommandResult>,
) {
    STATUS("status", PermissionLevel.VIEW, argsCodec(StatusArgs.serializer()), resultCodec(StatusResult.serializer())),
    SCREENSHOT("screenshot", PermissionLevel.VIEW, argsCodec(ScreenshotArgs.serializer()), resultCodec(ScreenshotResult.serializer())),
    UI_TREE("ui_tree", PermissionLevel.VIEW, argsCodec(UiTreeArgs.serializer()), resultCodec(UiTreeResult.serializer())),
    NOTIFICATIONS("notifications", PermissionLevel.VIEW, argsCodec(NotificationsArgs.serializer()), resultCodec(NotificationsResult.serializer())),
    TAP("tap", PermissionLevel.CONTROL, argsCodec(TapArgs.serializer()), resultCodec(EmptyResult.serializer())),
    LONG_PRESS("long_press", PermissionLevel.CONTROL, argsCodec(LongPressArgs.serializer()), resultCodec(EmptyResult.serializer())),
    SWIPE("swipe", PermissionLevel.CONTROL, argsCodec(SwipeArgs.serializer()), resultCodec(EmptyResult.serializer())),
    TYPE("type", PermissionLevel.CONTROL, argsCodec(TypeArgs.serializer()), resultCodec(EmptyResult.serializer())),
    KEY("key", PermissionLevel.CONTROL, argsCodec(KeyArgs.serializer()), resultCodec(EmptyResult.serializer())),
    OPEN_APP("open_app", PermissionLevel.CONTROL, argsCodec(OpenAppArgs.serializer()), resultCodec(EmptyResult.serializer())),
    REQUEST_CONTROL("request_control", PermissionLevel.CONTROL, argsCodec(RequestControlArgs.serializer()), resultCodec(RequestControlResult.serializer())),
    RELEASE_CONTROL("release_control", PermissionLevel.CONTROL, argsCodec(ReleaseControlArgs.serializer()), resultCodec(EmptyResult.serializer())),
    INSTALL_APK("install_apk", PermissionLevel.FULL, argsCodec(InstallApkArgs.serializer()), resultCodec(InstallApkResult.serializer())),
    SHELL("shell", PermissionLevel.FULL, argsCodec(ShellArgs.serializer()), resultCodec(ShellResult.serializer())),
    FILES("files", PermissionLevel.FULL, argsCodec(FilesArgs.serializer()), resultCodec(FilesResult.serializer())),
    SETTINGS("settings", PermissionLevel.FULL, argsCodec(SettingsArgs.serializer()), resultCodec(SettingsResult.serializer())),
    RELAY_MOVE("relay_move", PermissionLevel.FULL, argsCodec(RelayMoveArgs.serializer()), resultCodec(RelayMoveResult.serializer())),
    ;

    /** Commands that change the device need the caller to hold control. */
    val needsControl: Boolean
        get() = level.rank >= PermissionLevel.CONTROL.rank
}

/** Helpers around the command table and JSON argument/result coding. */
object Commands {
    val all: List<CommandType> = CommandType.entries

    fun fromWire(cmd: String): CommandType? = CommandType.entries.firstOrNull { it.wire == cmd }

    /** The level needed for [cmd], or null when the command does not exist. */
    fun requiredLevel(cmd: String): PermissionLevel? = fromWire(cmd)?.level

    fun isKnown(cmd: String): Boolean = fromWire(cmd) != null

    /** Throws [PermissionDeniedException] when [botLevel] may not run [cmd]. */
    fun check(botLevel: PermissionLevel, cmd: String) {
        val type = fromWire(cmd) ?: throw UnknownCommandException(cmd)
        if (!botLevel.allows(type.level)) throw PermissionDeniedException(type.level, botLevel)
    }

    fun encodeArgs(type: CommandType, args: CommandArgs): JsonObject = try {
        PbJson.encodeToJsonElement(type.argsSerializer, args).jsonObject
    } catch (e: ClassCastException) {
        throw BadArgsException("args do not match ${type.wire}", e)
    } catch (e: SerializationException) {
        throw BadArgsException("args do not match ${type.wire}", e)
    }

    fun decodeArgs(type: CommandType, args: JsonElement?): CommandArgs {
        val element = args ?: JsonObject(emptyMap())
        val decoded = try {
            PbJson.decodeFromJsonElement(type.argsSerializer, element)
        } catch (e: SerializationException) {
            throw BadArgsException("bad args for ${type.wire}", e)
        } catch (e: IllegalArgumentException) {
            throw BadArgsException("bad args for ${type.wire}", e)
        }
        decoded.validate()
        return decoded
    }

    fun encodeResult(type: CommandType, result: CommandResult): JsonObject = try {
        PbJson.encodeToJsonElement(type.resultSerializer, result).jsonObject
    } catch (e: ClassCastException) {
        throw BadArgsException("result does not match ${type.wire}", e)
    }

    fun decodeResult(type: CommandType, element: JsonElement?): CommandResult {
        val payload = element ?: JsonObject(emptyMap())
        return try {
            PbJson.decodeFromJsonElement(type.resultSerializer, payload)
        } catch (e: SerializationException) {
            throw BadArgsException("bad result for ${type.wire}", e)
        }
    }
}

private fun check(condition: Boolean, message: () -> String) {
    if (!condition) throw BadArgsException(message())
}

// ---------------------------------------------------------------------------------------------------
// Application messages (inside the end-to-end session)
// ---------------------------------------------------------------------------------------------------

/** `{ "id": "...", "cmd": "...", "args": { ... } }` */
@Serializable
data class AppRequest(
    val id: String,
    val cmd: String,
    val args: JsonObject = JsonObject(emptyMap()),
) {
    /** The command type, or null for an unknown name. */
    val type: CommandType?
        get() = Commands.fromWire(cmd)
}

/** `{ "id": "...", "ok": true, "result": {...} }` or `{ "id": "...", "ok": false, "error": {...} }` */
@Serializable
data class AppResponse(
    val id: String,
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: ErrorBody? = null,
) {
    companion object {
        fun success(id: String, result: JsonObject): AppResponse = AppResponse(id = id, ok = true, result = result)

        fun failure(id: String, error: ErrorBody): AppResponse = AppResponse(id = id, ok = false, error = error)

        fun failure(id: String, code: ErrorCode, message: String? = null): AppResponse =
            AppResponse(id = id, ok = false, error = ErrorBody.of(code, message))
    }
}

@Serializable
data class ErrorBody(
    val code: String,
    val message: String? = null,
) {
    val errorCode: ErrorCode?
        get() = ErrorCode.fromWire(code)

    companion object {
        fun of(code: ErrorCode, message: String? = null): ErrorBody = ErrorBody(code.wire, message)

        fun of(code: String, message: String? = null): ErrorBody = ErrorBody(code, message)
    }
}

/** `{ "event": "notification", "data": { ... } }` */
@Serializable
data class AppEvent(
    val event: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** Event names; `chunk` is the documented PhoneBridge extension for large payloads. */
object EventNames {
    const val NOTIFICATION = "notification"
    const val CONTROL_CHANGED = "control_changed"
    const val QUEUE_POSITION = "queue_position"
    const val KICKED = "kicked"
    const val RELAY_MOVED = "relay_moved"
    const val CHUNK = "chunk"

    val ALL: Set<String> = setOf(NOTIFICATION, CONTROL_CHANGED, QUEUE_POSITION, KICKED, RELAY_MOVED, CHUNK)
}

@Serializable
data class QueuePositionData(
    val position: Int,
)

@Serializable
data class ControlChangedData(
    val controller: String? = null,
    val name: String? = null,
)

@Serializable
data class KickedData(
    val reason: String? = null,
)

@Serializable
data class RelayMovedData(
    @SerialName("new_url")
    val newUrl: String,
    val token: String? = null,
    val expires: Long? = null,
)

@Serializable
data class NotificationEventData(
    val item: NotificationItem,
)

/**
 * Codec for the three application message shapes. The relay never sees these; they live inside the
 * encrypted payload.
 */
object AppMessages {
    fun request(id: String, type: CommandType, args: CommandArgs): AppRequest =
        AppRequest(id = id, cmd = type.wire, args = Commands.encodeArgs(type, args))

    fun request(id: String, args: CommandArgs): AppRequest = request(id, args.commandType(), args)

    fun response(id: String, type: CommandType, result: CommandResult): AppResponse =
        AppResponse.success(id, Commands.encodeResult(type, result))

    fun errorResponse(id: String, code: ErrorCode, message: String? = null): AppResponse =
        AppResponse.failure(id, ErrorBody.of(code, message))

    fun queuePosition(position: Int): AppEvent =
        AppEvent(EventNames.QUEUE_POSITION, PbJson.encodeToJsonElement(QueuePositionData.serializer(), QueuePositionData(position)).jsonObject)

    fun controlChanged(controller: String?, name: String? = null): AppEvent =
        AppEvent(
            EventNames.CONTROL_CHANGED,
            PbJson.encodeToJsonElement(ControlChangedData.serializer(), ControlChangedData(controller, name)).jsonObject,
        )

    fun kicked(reason: String? = null): AppEvent =
        AppEvent(EventNames.KICKED, PbJson.encodeToJsonElement(KickedData.serializer(), KickedData(reason)).jsonObject)

    fun relayMoved(newUrl: String, token: String? = null, expires: Long? = null): AppEvent =
        AppEvent(
            EventNames.RELAY_MOVED,
            PbJson.encodeToJsonElement(RelayMovedData.serializer(), RelayMovedData(newUrl, token, expires)).jsonObject,
        )

    fun notification(item: NotificationItem): AppEvent =
        AppEvent(
            EventNames.NOTIFICATION,
            PbJson.encodeToJsonElement(NotificationEventData.serializer(), NotificationEventData(item)).jsonObject,
        )

    fun encodeRequest(request: AppRequest): String = PbJson.encodeToString(AppRequest.serializer(), request)

    fun encodeResponse(response: AppResponse): String = PbJson.encodeToString(AppResponse.serializer(), response)

    fun encodeEvent(event: AppEvent): String = PbJson.encodeToString(AppEvent.serializer(), event)

    /** Parses any application message by looking at which of `cmd` / `ok` / `event` is present. */
    fun parse(json: String): AppMessage {
        val element = try {
            PbJson.parseToJsonElement(json)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("malformed application message", e)
        }
        val obj = element as? JsonObject ?: throw ProtocolException("application message is not an object")
        return parseObject(obj)
    }

    fun parseObject(obj: JsonObject): AppMessage {
        return when {
            obj.containsKey("cmd") -> try {
                AppMessage.Request(PbJson.decodeFromJsonElement(AppRequest.serializer(), obj))
            } catch (e: SerializationException) {
                throw ProtocolException("malformed request", e)
            }

            obj.containsKey("ok") -> try {
                AppMessage.Response(PbJson.decodeFromJsonElement(AppResponse.serializer(), obj))
            } catch (e: SerializationException) {
                throw ProtocolException("malformed response", e)
            }

            obj.containsKey("event") -> try {
                AppMessage.Event(PbJson.decodeFromJsonElement(AppEvent.serializer(), obj))
            } catch (e: SerializationException) {
                throw ProtocolException("malformed event", e)
            }

            else -> throw ProtocolException("application message has no cmd/ok/event field")
        }
    }
}

/** Which command a typed argument object belongs to. */
private fun CommandArgs.commandType(): CommandType = when (this) {
    StatusArgs -> CommandType.STATUS
    is ScreenshotArgs -> CommandType.SCREENSHOT
    is UiTreeArgs -> CommandType.UI_TREE
    is NotificationsArgs -> CommandType.NOTIFICATIONS
    is TapArgs -> CommandType.TAP
    is LongPressArgs -> CommandType.LONG_PRESS
    is SwipeArgs -> CommandType.SWIPE
    is TypeArgs -> CommandType.TYPE
    is KeyArgs -> CommandType.KEY
    is OpenAppArgs -> CommandType.OPEN_APP
    RequestControlArgs -> CommandType.REQUEST_CONTROL
    ReleaseControlArgs -> CommandType.RELEASE_CONTROL
    is InstallApkArgs -> CommandType.INSTALL_APK
    is ShellArgs -> CommandType.SHELL
    is FilesArgs -> CommandType.FILES
    is SettingsArgs -> CommandType.SETTINGS
    is RelayMoveArgs -> CommandType.RELAY_MOVE
}

/** Result of [AppMessages.parse]: exactly one of the three application shapes. */
sealed interface AppMessage {
    data class Request(val request: AppRequest) : AppMessage
    data class Response(val response: AppResponse) : AppMessage
    data class Event(val event: AppEvent) : AppMessage
}
