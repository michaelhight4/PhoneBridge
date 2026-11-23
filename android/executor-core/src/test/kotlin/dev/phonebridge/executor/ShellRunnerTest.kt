package dev.phonebridge.executor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellRunnerTest {

    /** A [Process] whose streams and exit behaviour are decided by the test. */
    private class FakeProcess(
        private val stdout: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val stderr: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val exitCode: Int = 0,
        private val finishes: Boolean = true,
    ) : Process() {

        val stdin = ByteArrayOutputStream()
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            if (!finishes) throw IllegalThreadStateException("still running")
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finishes || destroyed

        override fun exitValue(): Int {
            if (!finishes) throw IllegalThreadStateException("still running")
            return exitCode
        }

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        override fun isAlive(): Boolean = !finishes && !destroyed
    }

    @Test
    fun `captures stdout stderr and the exit code`() {
        val process = FakeProcess(
            stdout = ByteArrayInputStream("uid=2000(shell)\n".toByteArray()),
            stderr = ByteArrayInputStream("warning\n".toByteArray()),
            exitCode = 3,
        )
        val result = ShellRunner.run(process, timeoutMs = 5_000)
        assertEquals(3, result.exit)
        assertEquals("uid=2000(shell)\n", result.stdout)
        assertEquals("warning\n", result.stderr)
        assertFalse(result.timedOut)
        assertFalse(result.launchFailed)
    }

    @Test
    fun `writes stdin and closes it`() {
        val process = FakeProcess()
        val result = ShellRunner.run(process, timeoutMs = 5_000, stdin = byteArrayOf(1, 2, 3, 4))
        assertEquals(0, result.exit)
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(process.stdin.toByteArray()))
    }

    @Test
    fun `kills a command that overruns its deadline`() {
        val process = FakeProcess(finishes = false)
        val started = System.currentTimeMillis()
        val result = ShellRunner.run(process, timeoutMs = 50)
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result.timedOut)
        assertEquals(ShellResult.EXIT_NOT_STARTED, result.exit)
        assertTrue(process.destroyed)
        assertTrue(elapsed < 5_000, "the runner must not wait for a stuck process (took $elapsed ms)")
    }

    @Test
    fun `truncates huge output instead of buffering it all`() {
        val huge = ByteArray(64 * 1024) { 'x'.code.toByte() }
        val process = FakeProcess(stdout = ByteArrayInputStream(huge))
        val result = ShellRunner.run(process, timeoutMs = 5_000, maxStdoutBytes = 1_024)
        assertEquals(1_024, result.stdoutBytes.size)
    }

    @Test
    fun `result maps to the protocol shell shape`() {
        val result = ShellResult(exit = 1, stdoutBytes = "out".toByteArray(), stderr = "err")
        assertEquals(mapOf("exit" to 1, "stdout" to "out", "stderr" to "err"), result.toMap())
    }
}
