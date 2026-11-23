package dev.phonebridge.executor

/**
 * What the app currently knows about the Shizuku service. Only [READY] can run the Full-level
 * commands; everything else is reported to the bot as `shizuku_unavailable` with [message], which is
 * written for the person holding the phone, not for the bot.
 *
 * Shizuku is started by the user (root, wireless debugging or adb). Android stops it at every
 * reboot, so "start it again" is by far the most common fix — the app cannot do it for them.
 */
enum class ShizukuState(val wire: String, val available: Boolean, val message: String) {
    READY("ready", true, ""),

    NOT_INSTALLED(
        "not_installed",
        false,
        "Shizuku is not installed. Install it from https://shizuku.rikka.app/download/, start it, " +
            "then open PhoneBridge and allow Shizuku access.",
    ),

    NOT_RUNNING(
        "not_running",
        false,
        "Shizuku is not running. Open the Shizuku app and restart it (Android stops it at every " +
            "reboot), then retry.",
    ),

    NO_PERMISSION(
        "no_permission",
        false,
        "PhoneBridge has not been granted Shizuku access. Open PhoneBridge, tap 'Shizuku access' and " +
            "allow it, then retry.",
    ),

    UNSUPPORTED(
        "unsupported",
        false,
        "This Shizuku version is too old. Update the Shizuku app, start it, then retry.",
    ),
    ;

    companion object {
        fun fromWire(value: String?): ShizukuState? =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * One command run as the shell user through Shizuku.
 *
 * [stdoutBytes] is raw because `files read` moves binary data through the same pipe; [stderr] is
 * always text. [timedOut] means the runner killed the process at the deadline. [launchFailed] means
 * the process never started at all, usually because Shizuku died between the availability check and
 * the call.
 */
class ShellResult(
    val exit: Int,
    val stdoutBytes: ByteArray = ByteArray(0),
    val stderr: String = "",
    val timedOut: Boolean = false,
    val launchFailed: Boolean = false,
) {
    val stdout: String get() = String(stdoutBytes, Charsets.UTF_8)

    /** Protocol `shell` result: `{exit, stdout, stderr}` (stdout/stderr as UTF-8 text). */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "exit" to exit,
        "stdout" to stdout,
        "stderr" to stderr,
    )

    fun exitDescription(): String = when {
        launchFailed -> "the command could not be started"
        timedOut -> "the command was stopped at its timeout deadline"
        else -> "exit $exit"
    }

    companion object {
        /** Exit code used when no process could be started. */
        const val EXIT_NOT_STARTED: Int = -1

        fun ok(stdout: String = ""): ShellResult =
            ShellResult(exit = 0, stdoutBytes = stdout.toByteArray(Charsets.UTF_8))

        fun failure(message: String): ShellResult =
            ShellResult(exit = EXIT_NOT_STARTED, stderr = message, launchFailed = true)
    }
}

/**
 * The seam between the plain-Kotlin Full-level commands and Shizuku.
 *
 * The Android app implements this with `Shizuku.newProcess("sh -c …")`; the JVM tests use a fake
 * that records scripts and replays canned output. Every command string built here is a plain
 * `sh` script, so quoting and validation must happen on this side of the seam (see [ShellQuote]).
 */
interface ShizukuShell {

    /** Availability of the service right now. */
    val state: ShizukuState

    /**
     * Run [script] with `sh -c` as the shell user.
     *
     * [stdin] (when present) is written to the process and the pipe is closed. The process is killed
     * when [timeoutMs] elapses; the result then carries [ShellResult.timedOut].
     */
    fun exec(script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, stdin: ByteArray? = null): ShellResult

    companion object {
        /** Default for `shell` when the bot does not send `timeout_ms`. */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L

        /** `docs/PROTOCOL.md`: `shell.timeout_ms` is 1..3600000. */
        const val MAX_TIMEOUT_MS: Long = 3_600_000L
    }
}
