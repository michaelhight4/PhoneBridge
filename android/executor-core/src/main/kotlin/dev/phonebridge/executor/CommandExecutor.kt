package dev.phonebridge.executor

/**
 * Handles the Full-level commands (`install_apk`, `shell`, `files`, `settings`, `relay_move`) that
 * live outside this module: they need Shizuku / the relay lane. The Android app can register an
 * implementation later; until then the executor reports `shizuku_unavailable`.
 */
fun interface FullCommandHandler {
    fun handle(session: BotSession, request: Request): Response
}

/**
 * Turns a bot [Request] into a device call and a protocol [Response].
 *
 * Order of checks (each failure short-circuits well before the device is touched):
 * 1. `unknown_cmd` - the command is not in the PROTOCOL.md table.
 * 2. `permission_denied` - the bot's level is lower than the command needs.
 * 3. `accessibility_disabled` - the service is off and the command needs it.
 * 4. `not_controller` - the command needs control and another bot holds it.
 * 5. `bad_args` - required arguments are missing or malformed.
 * 6. dispatch to [DeviceControl]; a refused device call becomes `internal`.
 *
 * The executor keeps the last [UiTreeSnapshot] it handed out so `tap {node_id}` (and friends) can
 * resolve ids the bot actually saw. Everything here is plain Kotlin and unit tested on the JVM.
 */
class CommandExecutor(
    private val device: DeviceControl,
    private val control: ControlArbiter = ControlArbiter(),
    private val full: FullCommandHandler? = null,
) {
    private var snapshot: UiTreeSnapshot? = null

    /** The snapshot the last `ui_tree` produced, for adapters that want to render it. */
    fun lastSnapshot(): UiTreeSnapshot? = snapshot

    fun execute(session: BotSession, request: Request): Response {
        val command = Command.fromWire(request.cmd)
            ?: return Response.failure(
                request.id,
                ErrorCode.UNKNOWN_CMD,
                "unknown command '${request.cmd}'",
            )

        if (!session.level.allows(command.requiredLevel)) {
            return Response.failure(
                request.id,
                ErrorCode.PERMISSION_DENIED,
                "needs ${command.requiredLevel.label}",
            )
        }

        if (command.needsAccessibility && !device.accessibilityEnabled) {
            return accessibilityDisabled(request, command)
        }

        if (command.needsControl && !control.holdsControl(session.botId)) {
            return Response.failure(
                request.id,
                ErrorCode.NOT_CONTROLLER,
                "${session.name} does not hold control; send request_control first",
            )
        }

        val response = try {
            dispatch(command, session, request, Args(request.args))
        } catch (e: BadArgsException) {
            Response.failure(request.id, ErrorCode.BAD_ARGS, e.message ?: "bad arguments")
        }

        // A successful Control/Full command keeps control alive; a failed one does not.
        if (response.ok && command.needsControl) control.noteActivity(session.botId)
        return response
    }

    private fun dispatch(command: Command, session: BotSession, request: Request, args: Args): Response =
        when (command) {
            Command.STATUS -> status(request)
            Command.SCREENSHOT -> screenshot(request, args)
            Command.UI_TREE -> uiTree(request, args)
            Command.NOTIFICATIONS -> notifications(request, args)
            Command.TAP -> tap(request, args)
            Command.LONG_PRESS -> longPress(request, args)
            Command.SWIPE -> swipe(request, args)
            Command.TYPE -> type(request, args)
            Command.KEY -> key(request, args)
            Command.OPEN_APP -> openApp(request, args)
            Command.REQUEST_CONTROL -> requestControl(session, request)
            Command.RELEASE_CONTROL -> releaseControl(session, request)
            Command.INSTALL_APK,
            Command.SHELL,
            Command.FILES,
            Command.SETTINGS,
            Command.RELAY_MOVE,
            -> delegateFull(session, request)
        }

    // ---------------------------------------------------------------- View level

    private fun status(request: Request): Response {
        val status = device.deviceStatus()
        return Response.success(
            request.id,
            linkedMapOf(
                "device" to status.device,
                "battery" to status.battery,
                "screen_on" to status.screenOn,
                "controller" to control.holder(),
                "queue" to control.queueSnapshot(),
                "shizuku" to status.shizuku,
            ),
        )
    }

    private fun screenshot(request: Request, args: Args): Response {
        val format = ScreenshotFormat.fromWire(args.string("format") ?: ScreenshotFormat.PNG.wire)
            ?: throw BadArgsException("format", "expected png or jpeg")
        val quality = args.int("quality")?.also { requireInRange("quality", it, 1, 100) }
        val maxWidth = args.int("max_width")?.also { requireInRange("max_width", it, 1, 100_000) }

        // The accessibility screenshot API needs Android 11; older devices fall back to
        // MediaProjection, which needs an explicit user grant (projection_denied until then).
        if (device.apiLevel in 1..29) {
            return Response.failure(
                request.id,
                ErrorCode.PROJECTION_DENIED,
                "accessibility screenshots need Android 11 (API 30); grant screen capture in the app",
            )
        }
        if (!device.accessibilityEnabled) return accessibilityDisabled(request, Command.SCREENSHOT)

        val shot = device.captureScreenshot(format, quality, maxWidth)
            ?: return Response.failure(request.id, ErrorCode.INTERNAL, "the device returned no image")
        return Response.success(request.id, shot.toMap())
    }

    private fun uiTree(request: Request, args: Args): Response {
        val maxDepth = args.int("max_depth")?.also { requireMin("max_depth", it, 0) }
        val visibleOnly = args.booleanOr("visible_only", false)
        val fresh = UiTreeSnapshot.capture(device.captureUiTree(), maxDepth, visibleOnly)
        if (fresh.root == null) {
            snapshot = fresh
            return Response.failure(
                request.id,
                ErrorCode.INTERNAL,
                "the window has no visible nodes (screen off, locked, or a secure app)",
            )
        }
        snapshot = fresh
        return Response.success(request.id, fresh.toProtocolTree())
    }

    private fun notifications(request: Request, args: Args): Response {
        val since = args.long("since")?.also { requireMin("since", it, 0) }
        val items = device.readNotifications(since).map { it.toMap() }
        return Response.success(request.id, linkedMapOf("items" to items))
    }

    // ------------------------------------------------------------- Control level

    private fun tap(request: Request, args: Args): Response {
        val nodeId = args.stringAny("node_id", "nodeId")
        val ok = if (nodeId != null) {
            val node = resolveNode(nodeId)
            device.tap(node.bounds.centerX, node.bounds.centerY)
        } else {
            device.tap(args.requiredInt("x"), args.requiredInt("y"))
        }
        return if (ok) Response.success(request.id) else failed(request, "tap")
    }

    private fun longPress(request: Request, args: Args): Response {
        val durationMs = args.longOr("ms", DEFAULT_LONG_PRESS_MS).also { requireInRangeLong("ms", it, 1, 60_000) }
        val nodeId = args.stringAny("node_id", "nodeId")
        val ok = if (nodeId != null) {
            val node = resolveNode(nodeId)
            device.longPress(node.bounds.centerX, node.bounds.centerY, durationMs)
        } else {
            device.longPress(args.requiredInt("x"), args.requiredInt("y"), durationMs)
        }
        return if (ok) Response.success(request.id) else failed(request, "long_press")
    }

    private fun swipe(request: Request, args: Args): Response {
        val x1 = args.requiredInt("x1")
        val y1 = args.requiredInt("y1")
        val x2 = args.requiredInt("x2")
        val y2 = args.requiredInt("y2")
        if (x1 == x2 && y1 == y2) throw BadArgsException("x1", "start and end points are identical")
        val durationMs = args.longOr("ms", DEFAULT_SWIPE_MS).also { requireInRangeLong("ms", it, 1, 60_000) }
        return if (device.swipe(x1, y1, x2, y2, durationMs)) {
            Response.success(request.id)
        } else {
            failed(request, "swipe")
        }
    }

    private fun type(request: Request, args: Args): Response {
        // An empty string is a legitimate request (clear the field), so this is not requiredString().
        val text = args.string("text") ?: throw BadArgsException("text", "required string is missing")
        val nodeId = args.stringAny("node_id", "nodeId")
        if (nodeId != null) resolveNode(nodeId) // fail fast on stale ids
        return if (device.setText(text, nodeId)) Response.success(request.id) else failed(request, "type")
    }

    private fun key(request: Request, args: Args): Response {
        val name = args.requiredString("key")
        val key = KeyCommand.fromWire(name)
            ?: throw BadArgsException(
                "key",
                "expected one of " + KeyCommand.entries.joinToString("|") { it.wireName },
            )
        val minApi = KeyCommand.minApiLevel(key)
        if (device.apiLevel in 1 until minApi) {
            throw BadArgsException("key", "'${key.wireName}' needs Android ${apiLevelToVersion(minApi)} (API $minApi)")
        }
        return if (device.performKey(key)) Response.success(request.id) else failed(request, "key '${key.wireName}'")
    }

    private fun openApp(request: Request, args: Args): Response {
        val requestedPackage = args.stringAny("package", "package_name", "pkg")
        val requestedLabel = args.string("label")
        if (requestedPackage == null && requestedLabel == null) {
            throw BadArgsException("package", "send either 'package' or 'label'")
        }

        val packageName = when {
            requestedPackage != null -> requestedPackage
            else -> when (val match = OpenAppResolver.resolve(requestedLabel!!, device.installedApps())) {
                is OpenAppResolver.Result.Found -> match.packageName
                is OpenAppResolver.Result.NotFound ->
                    throw BadArgsException("label", "no installed app matches '$requestedLabel'")
                is OpenAppResolver.Result.Ambiguous ->
                    throw BadArgsException(
                        "label",
                        "label '$requestedLabel' is ambiguous: ${match.candidates.joinToString(", ")}",
                    )
            }
        }

        return when (val launched = device.launchApp(packageName)) {
            LaunchResult.LAUNCHED -> Response.success(request.id)
            LaunchResult.NOT_FOUND ->
                throw BadArgsException("package", "no package '$packageName' is installed")
            LaunchResult.NOT_LAUNCHABLE ->
                Response.failure(request.id, ErrorCode.INTERNAL, "'$packageName' has no launcher activity")
        }
    }

    private fun requestControl(session: BotSession, request: Request): Response =
        when (val result = control.requestControl(session.botId)) {
            ControlArbiter.RequestResult.Granted ->
                Response.success(request.id, linkedMapOf("granted" to true))
            is ControlArbiter.RequestResult.Queued ->
                Response.success(request.id, linkedMapOf("granted" to false, "position" to result.position))
        }

    private fun releaseControl(session: BotSession, request: Request): Response =
        if (control.releaseControl(session.botId)) {
            Response.success(request.id)
        } else {
            Response.failure(request.id, ErrorCode.NOT_CONTROLLER, "${session.name} does not hold control")
        }

    // ---------------------------------------------------------------- Full level

    private fun delegateFull(session: BotSession, request: Request): Response {
        full?.let { handler -> return handler.handle(session, request) }
        return if (request.cmd == Command.RELAY_MOVE.wireName) {
            Response.failure(
                request.id,
                ErrorCode.INTERNAL,
                "relay_move is owned by the relay module and is not wired into this build yet",
            )
        } else {
            Response.failure(
                request.id,
                ErrorCode.SHIZUKU_UNAVAILABLE,
                "'${request.cmd}' needs Shizuku, which is not wired into this build yet",
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolve a `node_id` the bot saw in a `ui_tree` reply. Falls back to a fresh snapshot when no
     * tree was sent yet; ids are pre-order, so an unknown id means the screen changed.
     */
    private fun resolveNode(nodeId: String): UiTreeSnapshot.IndexedNode {
        val current = snapshot ?: UiTreeSnapshot.capture(device.captureUiTree()).also { snapshot = it }
        val node = current.find(nodeId)
            ?: throw BadArgsException("node_id", "unknown node id '$nodeId'; take a new ui_tree and retry")
        if (node.bounds.isEmpty) {
            throw BadArgsException("node_id", "node '$nodeId' has no area on screen")
        }
        return node
    }

    private fun accessibilityDisabled(request: Request, command: Command): Response =
        Response.failure(
            request.id,
            ErrorCode.ACCESSIBILITY_DISABLED,
            "the PhoneBridge accessibility service is off; '${command.wireName}' needs it (Settings > Accessibility)",
        )

    private fun failed(request: Request, what: String): Response =
        Response.failure(request.id, ErrorCode.INTERNAL, "the device refused $what")

    private fun requireInRange(field: String, value: Int, min: Int, max: Int) {
        if (value < min || value > max) throw BadArgsException(field, "expected $min..$max, got $value")
    }

    private fun requireInRangeLong(field: String, value: Long, min: Long, max: Long) {
        if (value < min || value > max) throw BadArgsException(field, "expected $min..$max, got $value")
    }

    private fun requireMin(field: String, value: Int, min: Int) {
        if (value < min) throw BadArgsException(field, "expected >= $min, got $value")
    }

    private fun requireMin(field: String, value: Long, min: Long) {
        if (value < min) throw BadArgsException(field, "expected >= $min, got $value")
    }

    /** `28` -> `9`, for readable error messages. */
    private fun apiLevelToVersion(api: Int): Int = when (api) {
        26 -> 8
        27 -> 8
        28 -> 9
        29 -> 10
        30 -> 11
        31, 32 -> 12
        33 -> 13
        34 -> 14
        35 -> 15
        36 -> 16
        else -> api
    }

    companion object {
        const val DEFAULT_LONG_PRESS_MS: Long = 600L
        const val DEFAULT_SWIPE_MS: Long = 300L
    }
}

/** `View` / `Control` / `Full`, matching `docs/SPEC.md`. */
val PermissionLevel.label: String
    get() = when (this) {
        PermissionLevel.VIEW -> "View"
        PermissionLevel.CONTROL -> "Control"
        PermissionLevel.FULL -> "Full"
    }
