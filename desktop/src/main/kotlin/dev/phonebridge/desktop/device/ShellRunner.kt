package dev.phonebridge.desktop.device

import dev.phonebridge.core.BadArgsException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A process the runner can watch, kept small so tests can fake it without a real OS process. */
interface ShellProcess {
    val standardOut: InputStream
    val standardErr: InputStream

    /** True when the process ended within [millis]. */
    fun waitFor(millis: Long): Boolean

    fun exitValue(): Int

    fun destroyForcibly()
}

/** Starts a process. The only implementation that touches the OS. */
fun interface ShellStarter {
    fun start(command: List<String>): ShellProcess
}

/** Runs a fully formed command line, used by the installer (winget, msiexec). */
interface CommandRunner {
    fun runCommand(command: List<String>, timeoutMs: Long): ShellOutput
}

/** Real processes through `ProcessBuilder`. */
object ProcessShellStarter : ShellStarter {
    override fun start(command: List<String>): ShellProcess {
        val process = ProcessBuilder(command).start()
        return object : ShellProcess {
            override val standardOut: InputStream = process.inputStream
            override val standardErr: InputStream = process.errorStream
            override fun waitFor(millis: Long): Boolean = process.waitFor(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
            override fun exitValue(): Int = process.exitValue()
            override fun destroyForcibly() {
                process.descendants().forEach { runCatching { it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }
    }
}

/**
 * Runs PowerShell or cmd with a hard timeout, separate stdout/stderr pipes and byte caps.
 *
 * Both pipes are drained on their own threads so a chatty installer cannot deadlock the runner; after
 * the caps only draining continues. Text is decoded as UTF-8 because the command wrapper switches the
 * console encoding first.
 */
class ShellRunner(
    private val starter: ShellStarter = ProcessShellStarter,
    private val maxStdoutBytes: Int = DEFAULT_MAX_STDOUT,
    private val maxStderrBytes: Int = DEFAULT_MAX_STDERR,
    private val commandFor: (String, ShellKind) -> List<String> = ::commandLine,
) : CommandRunner {

    init {
        require(maxStdoutBytes > 0) { "maxStdoutBytes must be positive" }
        require(maxStderrBytes > 0) { "maxStderrBytes must be positive" }
    }

    /** Runs [command] in [kind], killing it at [timeoutMs] (1 ms .. 1 hour). */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, kind: ShellKind = ShellKind.POWERSHELL): ShellOutput {
        if (command.isBlank()) throw BadArgsException("command must not be blank")
        if (timeoutMs !in 1..MAX_TIMEOUT_MS) throw BadArgsException("timeout_ms must be 1..$MAX_TIMEOUT_MS")
        return runCommand(commandFor(command, kind), timeoutMs)
    }

    override fun runCommand(command: List<String>, timeoutMs: Long): ShellOutput {
        require(command.isNotEmpty()) { "a command line must not be empty" }
        require(timeoutMs > 0) { "timeout must be positive" }
        val process = starter.start(command)
        val out = CappedReader(process.standardOut, maxStdoutBytes)
        val err = CappedReader(process.standardErr, maxStderrBytes)
        val outThread = Thread(out, "pb-shell-stdout").apply { isDaemon = true; start() }
        val errThread = Thread(err, "pb-shell-stderr").apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutMs)
        var exit = -1
        if (finished) {
            exit = runCatching { process.exitValue() }.getOrDefault(-1)
        } else {
            process.destroyForcibly()
            runCatching { process.waitFor(KILL_GRACE_MS) }
            // A killed command has no meaningful exit code; -1 marks "did not finish".
            exit = -1
        }
        outThread.join(READER_JOIN_MS)
        errThread.join(READER_JOIN_MS)
        return ShellOutput(
            exit = exit,
            stdout = out.text(),
            stderr = err.text(),
            timedOut = !finished,
            truncated = out.truncated || err.truncated,
        )
    }

    private class CappedReader(private val input: InputStream, private val cap: Int) : Runnable {
        private val buffer = ByteArrayOutputStream()

        @Volatile
        var truncated = false
            private set

        override fun run() {
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    val room = cap - buffer.size()
                    if (room <= 0) {
                        truncated = true
                        continue
                    }
                    val keep = minOf(room, read)
                    buffer.write(chunk, 0, keep)
                    if (keep < read) truncated = true
                }
            } catch (_: IOException) {
                // The process was killed: whatever arrived before the deadline is the output.
            } finally {
                runCatching { input.close() }
            }
        }

        fun text(): String = String(buffer.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 3_600_000L
        const val DEFAULT_MAX_STDOUT = 8 * 1024 * 1024
        const val DEFAULT_MAX_STDERR = 256 * 1024
        private const val KILL_GRACE_MS = 2_000L
        private const val READER_JOIN_MS = 2_000L

        /** The PowerShell / cmd wrapper, with UTF-8 console output so non-ASCII text survives. */
        fun commandLine(command: String, kind: ShellKind): List<String> = when (kind) {
            ShellKind.POWERSHELL -> listOf(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $command",
            )

            ShellKind.CMD -> listOf("cmd.exe", "/d", "/s", "/c", "chcp 65001>nul & $command")
        }
    }
}
