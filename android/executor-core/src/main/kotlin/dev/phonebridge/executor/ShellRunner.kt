package dev.phonebridge.executor

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a [Process] to completion with a hard deadline, capturing stdout and stderr without letting a
 * chatty command exhaust the app's memory.
 *
 * The Android side hands in the process it got from Shizuku; the JVM tests hand in a fake. Keeping
 * this here (plain Kotlin, no `android.*`) means the timeout, truncation and stdin behaviour are
 * covered by ordinary unit tests.
 */
object ShellRunner {

    /** 8 MiB is the largest application payload that still fits in one Noise transport message. */
    const val MAX_STDOUT_BYTES: Int = 8 * 1024 * 1024

    /** stderr is diagnostic text; enough for a stack trace, not a log file. */
    const val MAX_STDERR_BYTES: Int = 256 * 1024

    /** How long to wait for the output pumps after the process exited or was killed. */
    private const val JOIN_TIMEOUT_MS = 2_000L

    /**
     * Waits for [process], killing it at [timeoutMs]. [stdin] is written on its own thread so a
     * process that writes before it reads cannot deadlock against us.
     *
     * stdout beyond [MAX_STDOUT_BYTES] and stderr beyond [MAX_STDERR_BYTES] are read and dropped
     * rather than buffered, so the pipe never fills up and the child never blocks on us.
     */
    fun run(
        process: Process,
        timeoutMs: Long,
        stdin: ByteArray? = null,
        maxStdoutBytes: Int = MAX_STDOUT_BYTES,
        maxStderrBytes: Int = MAX_STDERR_BYTES,
    ): ShellResult {
        val stdout = BoundedBuffer(maxStdoutBytes)
        val stderr = BoundedBuffer(maxStderrBytes)

        val stdoutPump = thread(name = "pb-shell-stdout", isDaemon = true) {
            drain(process.inputStream, stdout)
        }
        val stderrPump = thread(name = "pb-shell-stderr", isDaemon = true) {
            drain(process.errorStream, stderr)
        }
        val stdinWriter = stdin?.let { bytes ->
            thread(name = "pb-shell-stdin", isDaemon = true) {
                try {
                    process.outputStream.use { it.write(bytes) }
                } catch (e: java.io.IOException) {
                    // The command may exit before reading its input; that is not an error for us.
                }
            }
        }

        val finished = try {
            process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        var timedOut = false
        if (!finished) {
            timedOut = true
            // Give the child a moment to die from SIGTERM before the hard kill, so well-behaved
            // commands can flush and exit cleanly.
            process.destroy()
            val died = try {
                process.waitFor(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!died) process.destroyForcibly()
        }

        joinQuietly(stdoutPump)
        joinQuietly(stderrPump)
        stdinWriter?.let { joinQuietly(it) }

        val exit = if (timedOut) {
            ShellResult.EXIT_NOT_STARTED
        } else {
            try {
                process.exitValue()
            } catch (e: IllegalThreadStateException) {
                ShellResult.EXIT_NOT_STARTED
            }
        }

        return ShellResult(
            exit = exit,
            stdoutBytes = stdout.toByteArray(),
            stderr = String(stderr.toByteArray(), Charsets.UTF_8),
            timedOut = timedOut,
        )
    }

    private fun joinQuietly(worker: Thread) {
        try {
            worker.join(JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun drain(source: InputStream, sink: BoundedBuffer) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            source.use { input ->
                while (true) {
                    val read = try {
                        input.read(buffer)
                    } catch (e: InterruptedIOException) {
                        break
                    } catch (e: java.io.IOException) {
                        break
                    }
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
        } catch (e: java.io.IOException) {
            // The stream was closed because the process was killed; whatever was captured stands.
        }
    }

    /** A byte sink that keeps at most [limit] bytes but keeps reading past it. */
    private class BoundedBuffer(private val limit: Int) {
        private val buffer = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        private var kept = 0

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            val room = limit - kept
            if (room <= 0) return
            val take = minOf(room, length)
            buffer.write(bytes, offset, take)
            kept += take
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }
}
