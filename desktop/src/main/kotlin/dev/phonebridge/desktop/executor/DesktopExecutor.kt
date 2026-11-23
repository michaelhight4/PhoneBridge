package dev.phonebridge.desktop.executor

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.PermissionDeniedException
import dev.phonebridge.core.PhoneBridgeException
import dev.phonebridge.core.chunk.ChunkPayload
import dev.phonebridge.core.chunk.Chunker
import dev.phonebridge.core.control.ControlArbiter
import dev.phonebridge.core.model.AppEvent
import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.AppRequest
import dev.phonebridge.core.model.AppResponse
import dev.phonebridge.core.model.CommandType
import dev.phonebridge.core.model.Commands
import dev.phonebridge.core.model.EmptyResult
import dev.phonebridge.core.model.ErrorCode
import dev.phonebridge.core.model.FilesArgs
import dev.phonebridge.core.model.KeyArgs
import dev.phonebridge.core.model.LongPressArgs
import dev.phonebridge.core.model.NotificationsResult
import dev.phonebridge.core.model.OpenAppArgs
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.RelayMoveArgs
import dev.phonebridge.core.model.RequestControlResult
import dev.phonebridge.core.model.ScreenshotArgs
import dev.phonebridge.core.model.ScreenshotResult
import dev.phonebridge.core.model.ShellArgs
import dev.phonebridge.core.model.ShellResult
import dev.phonebridge.core.model.StatusResult
import dev.phonebridge.core.model.SwipeArgs
import dev.phonebridge.core.model.TapArgs
import dev.phonebridge.core.model.TypeArgs
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.desktop.device.DesktopDevice
import dev.phonebridge.desktop.device.InstallRequest
import dev.phonebridge.desktop.device.KeyCombos
import dev.phonebridge.desktop.device.ScreenStreamer
import dev.phonebridge.desktop.device.ShellKind
import dev.phonebridge.desktop.device.StreamRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The paired bot behind a request. */
data class BotSession(
    val botId: String,
    val name: String,
    val level: PermissionLevel,
)

/**
 * The desktop command dispatcher.
 *
 * Order matters and mirrors the phone executor: `unknown_cmd` â†’ `permission_denied` â†’
 * `not_controller` (for anything that changes the PC) â†’ `bad_args` â†’ the device call. Every branch is
 * exercised with a fake [DesktopDevice] in `desktop/src/test`, so no test touches a real screen, mouse
 * or shell.
 */
class DesktopExecutor(
    private val device: DesktopDevice,
    private val arbiter: ControlArbiter,
    private val streamer: ScreenStreamer,
    /** `relay_move` target â†’ the relay URL peers should use; null when the move is not supported. */
    private val relayMove: ((String) -> String?)? = null,
) {
    fun handle(
        bot: BotSession,
        request: AppRequest,
        emit: (AppEvent) -> Unit = {},
    ): AppResponse {
        val extension = ExtensionCommand.fromWire(request.cmd)
        return if (extension != null) handleExtension(bot, extension, request, emit) else handleCore(bot, request, emit)
    }

    // ------------------------------------------------------------------ protocol commands

    private fun handleCore(bot: BotSession, request: AppRequest, emit: (AppEvent) -> Unit): AppResponse {
        val type = Commands.fromWire(request.cmd)
            ?: return failure(request.id, ErrorCode.UNKNOWN_CMD, "unknown command: ${request.cmd}")
        if (!bot.level.allows(type.level)) {
            return failure(request.id, ErrorCode.PERMISSION_DENIED, "needs ${type.level.wire}, bot has ${bot.level.wire}")
        }
        val holdsControl = type == CommandType.REQUEST_CONTROL || type == CommandType.RELEASE_CONTROL ||
            !type.needsControl || arbiter.isController(bot.botId)
        if (!holdsControl) {
            return failure(request.id, ErrorCode.NOT_CONTROLLER, "another bot holds control of this PC")
        }
        val args = try {
            Commands.decodeArgs(type, request.args)
        } catch (e: BadArgsException) {
            return failure(request.id, ErrorCode.BAD_ARGS, e.message)
        }
        val response = try {
            when (type) {
                CommandType.STATUS -> success(type, request.id, status())
                CommandType.SCREENSHOT -> screenshot(request, args as ScreenshotArgs, emit)
                CommandType.UI_TREE -> failure(
                    request.id,
                    ErrorCode.UNKNOWN_CMD,
                    "the desktop agent has no UI tree; use `screens` and `screenshot`",
                )

                CommandType.NOTIFICATIONS -> success(type, request.id, NotificationsResult())
                CommandType.TAP -> {
                    val tap = args as TapArgs
                    if (tap.nodeId != null) {
                        failure(request.id, ErrorCode.BAD_ARGS, "the desktop has no node ids; use x and y")
                    } else {
                        device.click(tap.x!!, tap.y!!)
                        success(type, request.id, EmptyResult)
                    }
                }

                CommandType.LONG_PRESS -> {
                    val press = args as LongPressArgs
                    if (press.nodeId != null) {
                        failure(request.id, ErrorCode.BAD_ARGS, "the desktop has no node ids; use x and y")
                    } else {
                        device.longPress(press.x!!, press.y!!, press.ms ?: DEFAULT_LONG_PRESS_MS)
                        success(type, request.id, EmptyResult)
                    }
                }

                CommandType.SWIPE -> {
                    val swipe = args as SwipeArgs
                    device.drag(swipe.x1, swipe.y1, swipe.x2, swipe.y2, swipe.ms ?: DEFAULT_SWIPE_MS)
                    success(type, request.id, EmptyResult)
                }

                CommandType.TYPE -> {
                    device.typeText((args as TypeArgs).text)
                    success(type, request.id, EmptyResult)
                }

                CommandType.KEY -> {
                    device.key((args as KeyArgs).key)
                    success(type, request.id, EmptyResult)
                }

                CommandType.OPEN_APP -> {
                    val open = args as OpenAppArgs
                    device.openProgram(open.packageName ?: open.label!!)
                    success(type, request.id, EmptyResult)
                }

                CommandType.REQUEST_CONTROL -> {
                    val grant = arbiter.request(bot.botId)
                    success(type, request.id, RequestControlResult(granted = grant.granted, position = grant.position))
                }

                CommandType.RELEASE_CONTROL -> {
                    arbiter.release(bot.botId)
                    success(type, request.id, EmptyResult)
                }

                CommandType.INSTALL_APK -> failure(
                    request.id,
                    ErrorCode.UNKNOWN_CMD,
                    "install_apk is Android-only; use the desktop `install` command",
                )

                CommandType.SHELL -> {
                    val shell = args as ShellArgs
                    val output = device.shell(shell.command, shell.timeoutMs ?: DEFAULT_SHELL_TIMEOUT_MS, ShellKind.POWERSHELL)
                    if (output.timedOut) {
                        failure(
                            request.id,
                            ErrorCode.TIMEOUT,
                            "the command did not finish within ${shell.timeoutMs ?: DEFAULT_SHELL_TIMEOUT_MS} ms",
                        )
                    } else {
                        success(type, request.id, ShellResult(exit = output.exit, stdout = output.stdout, stderr = output.stderr))
                    }
                }

                CommandType.FILES -> {
                    val files = args as FilesArgs
                    val data = files.dataB64?.let {
                        try {
                            B64Url.decode(it)
                        } catch (e: IllegalArgumentException) {
                            throw BadArgsException("data_b64 is not base64url")
                        }
                    }
                    success(type, request.id, device.files(files.op, files.path, data))
                }

                CommandType.SETTINGS -> failure(
                    request.id,
                    ErrorCode.UNKNOWN_CMD,
                    "the desktop agent has no Android settings namespaces",
                )

                CommandType.RELAY_MOVE -> {
                    val move = args as RelayMoveArgs
                    val url = relayMove?.invoke(move.to)
                    if (url == null) {
                        failure(request.id, ErrorCode.INTERNAL, "this PC cannot move the relay to ${move.to}")
                    } else {
                        success(type, request.id, dev.phonebridge.core.model.RelayMoveResult(newUrl = url))
                    }
                }
            }
        } catch (e: BadArgsException) {
            failure(request.id, ErrorCode.BAD_ARGS, e.message)
        } catch (e: PermissionDeniedException) {
            failure(request.id, ErrorCode.PERMISSION_DENIED, e.message)
        } catch (e: PhoneBridgeException) {
            failure(request.id, ErrorCode.INTERNAL, e.message)
        } catch (e: Exception) {
            failure(request.id, ErrorCode.INTERNAL, e.message ?: "the PC could not run the command")
        }
        if (response.ok && type.needsControl) arbiter.touch(bot.botId)
        return response
    }

    private fun status(): StatusResult = StatusResult(
        device = device.name,
        battery = null,
        screenOn = device.isScreenOn(),
        controller = arbiter.controller,
        queue = arbiter.queueSnapshot(),
        shizuku = null,
    )

    private fun screenshot(request: AppRequest, args: ScreenshotArgs, emit: (AppEvent) -> Unit): AppResponse {
        val screenId = JsonArgs(request.args).string("screen_id")
        val frame = device.screenshot(screenId, args.format, args.quality ?: DEFAULT_JPEG_QUALITY, args.maxWidth)
        if (frame.size <= MAX_INLINE_BYTES) {
            return success(
                CommandType.SCREENSHOT,
                request.id,
                ScreenshotResult(
                    mime = frame.mime,
                    width = frame.width,
                    height = frame.height,
                    dataB64 = B64Url.encode(frame.bytes),
                ),
            )
        }
        val chunkSet = Chunker.chunk(frame.bytes)
        for (chunk in chunkSet.chunks) emit(chunkEvent(chunk))
        return success(
            CommandType.SCREENSHOT,
            request.id,
            ScreenshotResult(
                mime = frame.mime,
                width = frame.width,
                height = frame.height,
                chunked = true,
                chunkId = chunkSet.chunkId,
                total = chunkSet.total,
                size = chunkSet.size,
                sha256 = chunkSet.sha256,
            ),
        )
    }

    private fun chunkEvent(chunk: ChunkPayload): AppEvent =
        AppEvent(
            "chunk",
            PbJson.encodeToJsonElement(ChunkPayload.serializer(), chunk).let { it as JsonObject },
        )

    // ------------------------------------------------------------------ desktop extensions

    private fun handleExtension(
        bot: BotSession,
        extension: ExtensionCommand,
        request: AppRequest,
        emit: (AppEvent) -> Unit,
    ): AppResponse {
        if (!bot.level.allows(extension.level)) {
            return failure(request.id, ErrorCode.PERMISSION_DENIED, "needs ${extension.level.wire}, bot has ${bot.level.wire}")
        }
        if (extension.needsControl && !arbiter.isController(bot.botId)) {
            return failure(request.id, ErrorCode.NOT_CONTROLLER, "another bot holds control of this PC")
        }
        val args = JsonArgs(request.args)
        val response = try {
            when (extension) {
                ExtensionCommand.SCREENS -> screens(request)
                ExtensionCommand.SCREEN_STREAM -> screenStream(request, args, emit)
                ExtensionCommand.MOVE -> {
                    val x = requireInt(args, "x")
                    val y = requireInt(args, "y")
                    device.pointer(x, y)
                    ok(request)
                }

                ExtensionCommand.DRAG -> drag(request, args)
                ExtensionCommand.SCROLL -> scroll(request, args)
                ExtensionCommand.KEY_COMBO -> {
                    val keys = args.stringList("keys") ?: throw BadArgsException("key_combo needs keys")
                    // Validate names here so a bad combo is bad_args on every platform, not only
                    // when the real keyboard backend parses it.
                    KeyCombos.parse(keys)
                    device.keyCombo(keys)
                    ok(request)
                }

                ExtensionCommand.INSTALL -> install(request, args)
            }
        } catch (e: BadArgsException) {
            failure(request.id, ErrorCode.BAD_ARGS, e.message)
        } catch (e: PhoneBridgeException) {
            failure(request.id, ErrorCode.INTERNAL, e.message)
        } catch (e: Exception) {
            failure(request.id, ErrorCode.INTERNAL, e.message ?: "the PC could not run the command")
        }
        if (response.ok && extension.needsControl) arbiter.touch(bot.botId)
        return response
    }

    private fun screens(request: AppRequest): AppResponse {
        val list = device.screens()
        val json = buildJsonObject {
            putJsonArray("screens") {
                for (screen in list) {
                    add(
                        buildJsonObject {
                            put("id", screen.id)
                            put("x", screen.x)
                            put("y", screen.y)
                            put("width", screen.width)
                            put("height", screen.height)
                            put("primary", screen.primary)
                        },
                    )
                }
            }
        }
        return AppResponse.success(request.id, json)
    }

    private fun screenStream(request: AppRequest, args: JsonArgs, emit: (AppEvent) -> Unit): AppResponse {
        val streamRequest = StreamRequest(
            fps = args.int("fps") ?: StreamRequest.DEFAULT_FPS,
            frames = args.int("frames") ?: StreamRequest.DEFAULT_FRAMES,
            quality = args.int("quality") ?: StreamRequest.DEFAULT_QUALITY,
            maxWidth = if (args.has("max_width")) args.int("max_width") else StreamRequest.DEFAULT_MAX_WIDTH,
            format = args.string("format") ?: "jpeg",
        )
        streamRequest.validate()
        val screenId = args.string("screen_id")
        val total = streamRequest.frames
        val result = streamer.run(streamRequest, screenId) { streamId, frame, index ->
            val payload = buildJsonObject {
                put("stream_id", streamId)
                put("index", index)
                put("total", total)
                put("mime", frame.mime)
                put("width", frame.width)
                put("height", frame.height)
                put("data_b64", B64Url.encode(frame.bytes))
            }
            emit(AppEvent("frame", payload))
            true
        }
        val json = buildJsonObject {
            put("stream_id", result.streamId)
            put("frames", result.framesSent)
            put("width", result.width)
            put("height", result.height)
            put("mime", result.mime)
            put("stopped_early", result.stoppedEarly)
        }
        return AppResponse.success(request.id, json)
    }

    private fun drag(request: AppRequest, args: JsonArgs): AppResponse {
        val x1 = requireInt(args, "x1")
        val y1 = requireInt(args, "y1")
        val x2 = requireInt(args, "x2")
        val y2 = requireInt(args, "y2")
        if (x1 == x2 && y1 == y2) throw BadArgsException("drag needs two different points")
        val ms = args.long("ms") ?: DEFAULT_SWIPE_MS
        if (ms !in 1..60_000) throw BadArgsException("ms must be 1..60000")
        device.drag(x1, y1, x2, y2, ms)
        return ok(request)
    }

    private fun scroll(request: AppRequest, args: JsonArgs): AppResponse {
        val dx = args.int("dx") ?: 0
        val dy = args.int("dy") ?: 0
        if (dx == 0 && dy == 0) throw BadArgsException("scroll needs dx or dy")
        if (dx !in -50..50 || dy !in -50..50) throw BadArgsException("dx and dy must be -50..50 wheel steps")
        val x = args.int("x")
        val y = args.int("y")
        if ((x == null) != (y == null)) throw BadArgsException("scroll takes both x and y, or neither")
        device.scroll(x, y, dx, dy)
        return ok(request)
    }

    private fun install(request: AppRequest, args: JsonArgs): AppResponse {
        val installRequest = InstallRequest(
            id = args.string("id"),
            path = args.string("path"),
            url = args.string("url"),
            name = args.string("name"),
            silent = args.boolean("silent", true),
        )
        val result = device.install(installRequest)
        val json = buildJsonObject {
            put("ok", result.ok)
            put("method", result.method)
            put("detail", result.detail)
            result.exit?.let { put("exit", it) }
            put("started", result.started)
        }
        return AppResponse.success(request.id, json)
    }

    // ------------------------------------------------------------------ helpers

    private fun requireInt(args: JsonArgs, name: String): Int =
        args.int(name) ?: throw BadArgsException("$name is required")

    private fun ok(request: AppRequest): AppResponse =
        AppResponse.success(request.id, JsonObject(emptyMap()))

    private fun success(type: CommandType, id: String, result: dev.phonebridge.core.model.CommandResult): AppResponse =
        AppResponse.success(id, Commands.encodeResult(type, result))

    private fun failure(id: String, code: ErrorCode, message: String?): AppResponse =
        AppMessages.errorResponse(id, code, message)

    companion object {
        const val MAX_INLINE_BYTES = 2 * 1024 * 1024
        const val DEFAULT_JPEG_QUALITY = 75
        const val DEFAULT_SHELL_TIMEOUT_MS = 60_000L
        const val DEFAULT_LONG_PRESS_MS = 1_000L
        const val DEFAULT_SWIPE_MS = 300L
    }
}
