package dev.phonebridge.executor

/**
 * The Full-level commands that need Shizuku: `install_apk`, `shell`, `settings` and `files`.
 *
 * The whole dispatch path is plain Kotlin: arguments are validated here (bad arguments never reach
 * the device), the `sh` scripts are built and quoted here, and the output is parsed here. Only the
 * two seams below touch Android: [ShizukuShell] runs the script as the shell user and [ApkInstaller]
 * drives the PackageInstaller session. The JVM tests fake both.
 *
 * Every one of these commands is Full level **and** needs control; the executor checks level and
 * control before calling this handler. If Shizuku is installed but not running (the normal state
 * after a reboot) the handler answers `shizuku_unavailable` with the "start Shizuku again" hint.
 */
class ShizukuFullCommandHandler(
    private val shell: ShizukuShell,
    private val installer: ApkInstaller,
    private val downloader: ApkDownloader = HttpsApkDownloader(),
    private val files: ShellFiles = ShellFiles(shell),
) : FullCommandHandler {

    override fun handle(session: BotSession, request: Request): Response {
        val command = Command.fromWire(request.cmd)
            ?: return Response.failure(request.id, ErrorCode.UNKNOWN_CMD, "unknown command '${request.cmd}'")

        // relay_move belongs to the relay lane; it does not need Shizuku.
        if (command == Command.RELAY_MOVE) {
            return Response.failure(
                request.id,
                ErrorCode.INTERNAL,
                "relay_move is owned by the relay module and is not wired into this build yet",
            )
        }

        val state = shell.state
        if (state != ShizukuState.READY) {
            return Response.failure(request.id, ErrorCode.SHIZUKU_UNAVAILABLE, state.message)
        }

        val args = Args(request.args)
        return try {
            when (command) {
                Command.INSTALL_APK -> installApk(request, args)
                Command.SHELL -> runShell(request, args)
                Command.SETTINGS -> settings(request, args)
                Command.FILES -> files(request, args)
                else -> Response.failure(
                    request.id,
                    ErrorCode.INTERNAL,
                    "'${request.cmd}' is not handled by the Shizuku command handler",
                )
            }
        } catch (e: BadArgsException) {
            Response.failure(request.id, ErrorCode.BAD_ARGS, e.message ?: "bad arguments")
        } catch (e: ShellCommandException) {
            Response.failure(request.id, ErrorCode.INTERNAL, e.message ?: "the shell command failed")
        }
    }

    // -------------------------------------------------------------- install_apk

    private fun installApk(request: Request, args: Args): Response {
        val url = args.string("url")
        val dataB64 = args.stringAny("data_b64", "dataB64")
        val requestedName = args.string("name")

        if ((url == null) == (dataB64 == null)) {
            throw BadArgsException("url", "send either 'url' or 'data_b64' (an inline APK also needs 'name')")
        }

        val apk = if (url != null) {
            if (!url.startsWith("https://")) throw BadArgsException("url", "only https:// URLs are accepted")
            try {
                downloader.download(url)
            } catch (e: Exception) {
                return Response.failure(
                    request.id,
                    ErrorCode.INTERNAL,
                    "downloading the APK failed: ${e.message ?: e::class.java.simpleName}",
                )
            }
        } else {
            if (requestedName.isNullOrBlank()) {
                throw BadArgsException("name", "an inline APK needs a name")
            }
            try {
                B64.decodeLoose(dataB64!!)
            } catch (e: IllegalArgumentException) {
                throw BadArgsException("data_b64", "not valid base64")
            }
        }

        if (apk.isEmpty()) {
            throw BadArgsException(if (url != null) "url" else "data_b64", "the APK is empty")
        }
        if (apk.size > ShellApkInstaller.MAX_APK_BYTES) {
            return Response.failure(
                request.id,
                ErrorCode.BAD_ARGS,
                "the APK is larger than ${ShellApkInstaller.MAX_APK_BYTES} bytes",
            )
        }

        val name = requestedName ?: url?.substringAfterLast('/')?.substringBefore('?')
        return when (val outcome = installer.install(apk, name ?: ShellQuote.DEFAULT_APK_NAME)) {
            is ApkInstallOutcome.Installed ->
                Response.success(request.id, linkedMapOf("package" to outcome.packageName))
            is ApkInstallOutcome.Failed ->
                Response.failure(request.id, ErrorCode.INTERNAL, outcome.reason)
        }
    }

    // ------------------------------------------------------------------- shell

    private fun runShell(request: Request, args: Args): Response {
        val command = ShellQuote.validateShellCommand(args.requiredString("command"))
        val timeout = args.longAny("timeout_ms", "timeoutMs") ?: ShizukuShell.DEFAULT_TIMEOUT_MS
        if (timeout < 1 || timeout > ShizukuShell.MAX_TIMEOUT_MS) {
            throw BadArgsException("timeout_ms", "expected 1..${ShizukuShell.MAX_TIMEOUT_MS}, got $timeout")
        }

        val result = shell.exec(command, timeout)
        if (result.timedOut) {
            return Response.failure(
                request.id,
                ErrorCode.TIMEOUT,
                "the command did not finish within $timeout ms",
            )
        }
        if (result.launchFailed) return shellUnavailable(request, result)
        return Response.success(request.id, result.toMap())
    }

    // ---------------------------------------------------------------- settings

    private fun settings(request: Request, args: Args): Response {
        val namespace = args.requiredString("namespace").trim().lowercase()
        if (namespace !in SETTINGS_NAMESPACES) {
            throw BadArgsException("namespace", "expected one of ${SETTINGS_NAMESPACES.joinToString("|")}")
        }
        val key = ShellQuote.validateSettingsKey(args.requiredString("key"))

        if (!args.contains("value")) {
            val result = shell.exec("settings get $namespace ${ShellQuote.quote(key)}", SETTINGS_TIMEOUT_MS)
            if (result.timedOut) {
                return Response.failure(
                    request.id,
                    ErrorCode.TIMEOUT,
                    "reading $namespace/$key did not finish within $SETTINGS_TIMEOUT_MS ms",
                )
            }
            if (result.launchFailed) return shellUnavailable(request, result)
            if (result.exit != 0) {
                return Response.failure(
                    request.id,
                    ErrorCode.INTERNAL,
                    "settings get $namespace $key failed: ${result.stderr.trim().ifBlank { "exit ${result.exit}" }}",
                )
            }
            val text = result.stdout.trim()
            val value: String? = if (text == "null") null else text
            return Response.success(request.id, linkedMapOf("value" to value))
        }

        val value = when (val raw = args.raw("value")) {
            is String -> raw
            is Number -> raw.toString()
            is Boolean -> raw.toString()
            else -> throw BadArgsException("value", "expected a string, number or boolean")
        }
        ShellQuote.validateSettingsValue(value)

        val script = "settings put $namespace ${ShellQuote.quote(key)} ${ShellQuote.quote(value)}"
        ShellQuote.checkScriptLength(script)
        val result = shell.exec(script, SETTINGS_TIMEOUT_MS)
        if (result.timedOut) {
            return Response.failure(
                request.id,
                ErrorCode.TIMEOUT,
                "writing $namespace/$key did not finish within $SETTINGS_TIMEOUT_MS ms",
            )
        }
        if (result.launchFailed) return shellUnavailable(request, result)
        if (result.exit != 0) {
            return Response.failure(
                request.id,
                ErrorCode.INTERNAL,
                "settings put $namespace $key failed: ${result.stderr.trim().ifBlank { "exit ${result.exit}" }}",
            )
        }
        return Response.success(request.id, linkedMapOf("value" to value))
    }

    // ------------------------------------------------------------------- files

    private fun files(request: Request, args: Args): Response {
        val op = args.requiredString("op").trim().lowercase()
        val path = ShellQuote.validatePath("path", args.requiredString("path"))
        val dataB64 = args.stringAny("data_b64", "dataB64")

        return when (op) {
            "list" -> {
                if (dataB64 != null) throw BadArgsException("data_b64", "data_b64 is only valid for write")
                val listing = files.list(path)
                val result = linkedMapOf<String, Any?>("op" to "list")
                result["entries"] = listing.entries.map { it.toMap() }
                if (listing.truncated) result["truncated"] = true
                Response.success(request.id, result)
            }

            "read" -> {
                if (dataB64 != null) throw BadArgsException("data_b64", "data_b64 is only valid for write")
                val bytes = files.read(path)
                Response.success(
                    request.id,
                    linkedMapOf(
                        "op" to "read",
                        "data_b64" to B64.encodeUrlNoPadding(bytes),
                        "bytes" to bytes.size,
                    ),
                )
            }

            "write" -> {
                if (dataB64 == null) throw BadArgsException("data_b64", "files write needs data_b64")
                val data = try {
                    B64.decodeLoose(dataB64)
                } catch (e: IllegalArgumentException) {
                    throw BadArgsException("data_b64", "not valid base64")
                }
                if (data.size > ShellFiles.MAX_WRITE_BYTES) {
                    throw BadArgsException(
                        "data_b64",
                        "the payload is larger than ${ShellFiles.MAX_WRITE_BYTES} bytes",
                    )
                }
                val written = files.write(path, data)
                Response.success(
                    request.id,
                    linkedMapOf("op" to "write", "ok" to true, "bytes" to written),
                )
            }

            "delete" -> {
                if (dataB64 != null) throw BadArgsException("data_b64", "data_b64 is only valid for write")
                if (path == "/") throw BadArgsException("path", "refusing to delete '/'")
                files.delete(path)
                Response.success(request.id, linkedMapOf("op" to "delete", "ok" to true))
            }

            else -> throw BadArgsException("op", "expected list, read, write or delete")
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun shellUnavailable(request: Request, result: ShellResult): Response {
        val state = shell.state
        return if (state != ShizukuState.READY) {
            Response.failure(request.id, ErrorCode.SHIZUKU_UNAVAILABLE, state.message)
        } else {
            Response.failure(
                request.id,
                ErrorCode.INTERNAL,
                result.stderr.ifBlank {
                    "Shizuku could not start the shell; open the Shizuku app, restart it, then retry"
                },
            )
        }
    }

    companion object {
        val SETTINGS_NAMESPACES: Set<String> = setOf("system", "secure", "global")
        const val SETTINGS_TIMEOUT_MS: Long = 15_000L
    }
}
