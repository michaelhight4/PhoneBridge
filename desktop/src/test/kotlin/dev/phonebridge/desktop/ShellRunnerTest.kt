package dev.phonebridge.desktop

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.desktop.device.ShellKind
import dev.phonebridge.desktop.device.ShellOutput
import dev.phonebridge.desktop.device.ShellProcess
import dev.phonebridge.desktop.device.ShellRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellRunnerTest {

    private class FakeProcess(
        stdout: String,
        stderr: String,
        private val exit: Int,
        private val finishes: Boolean,
    ) : ShellProcess {
        override val standardOut: InputStream = ByteArrayInputStream(stdout.toByteArray())
        override val standardErr: InputStream = ByteArrayInputStream(stderr.toByteArray())
        var killed = false

        override fun waitFor(millis: Long): Boolean = finishes
        override fun exitValue(): Int = exit
        override fun destroyForcibly() {
            killed = true
        }
    }

    @Test
    fun capturesBothStreamsAndTheExitCode() {
        val runner = ShellRunner(
            starter = { FakeProcess("out text", "err text", 3, true) },
            commandFor = { _, _ -> listOf("fake") },
        )
        val output = runner.run("anything", 1_000)
        assertEquals(3, output.exit)
        assertEquals("out text", output.stdout)
        assertEquals("err text", output.stderr)
        assertFalse(output.timedOut)
    }

    @Test
    fun killsTheProcessAtTheDeadline() {
        val process = FakeProcess("partial", "", 0, false)
        val runner = ShellRunner(
            starter = { process },
            commandFor = { _, _ -> listOf("fake") },
        )
        val output = runner.run("slow", 250)
        assertTrue(output.timedOut)
        assertTrue(process.killed, "the runner must destroy a command that outlives its timeout")
        assertEquals(-1, output.exit)
    }

    @Test
    fun capsStdoutAndStderr() {
        val runner = ShellRunner(
            starter = { FakeProcess("x".repeat(1_000), "y".repeat(1_000), 0, true) },
            maxStdoutBytes = 10,
            maxStderrBytes = 5,
            commandFor = { _, _ -> listOf("fake") },
        )
        val output = runner.run("chatty", 1_000)
        assertEquals(10, output.stdout.length)
        assertEquals(5, output.stderr.length)
        assertTrue(output.truncated)
    }

    @Test
    fun rejectsBadArguments() {
        val runner = ShellRunner(starter = { FakeProcess("", "", 0, true) })
        assertFailsWith<BadArgsException> { runner.run("  ", 1_000) }
        assertFailsWith<BadArgsException> { runner.run("dir", 0) }
        assertFailsWith<BadArgsException> { runner.run("dir", ShellRunner.MAX_TIMEOUT_MS + 1) }
    }

    @Test
    fun wrapsCommandsInUtf8Shells() {
        val powerShell = ShellRunner.commandLine("Write-Output hi", ShellKind.POWERSHELL)
        assertEquals("powershell.exe", powerShell.first())
        assertTrue(powerShell.last().contains("Write-Output hi"))
        assertTrue(powerShell.last().contains("UTF8"))

        val cmd = ShellRunner.commandLine("dir", ShellKind.CMD)
        assertEquals("cmd.exe", cmd.first())
        assertTrue(cmd.last().startsWith("chcp 65001"))
    }

    @Test
    fun runsRealPowerShellAndCmdCommands() {
        val runner = ShellRunner()
        val ps = runner.run("Write-Output 'pb-hello'", 60_000, ShellKind.POWERSHELL)
        assertEquals(0, ps.exit, "powershell stderr: ${ps.stderr}")
        assertTrue(ps.stdout.contains("pb-hello"), "stdout was '${ps.stdout}'")

        val cmd = runner.run("echo pb-cmd", 60_000, ShellKind.CMD)
        assertEquals(0, cmd.exit, "cmd stderr: ${cmd.stderr}")
        assertTrue(cmd.stdout.contains("pb-cmd"), "stdout was '${cmd.stdout}'")
    }

    @Test
    fun timesOutARealSlowCommand() {
        val output = ShellRunner().run("Start-Sleep -Seconds 30", 800, ShellKind.POWERSHELL)
        assertTrue(output.timedOut, "a 30 second sleep must be killed at 800 ms")
        assertEquals(-1, output.exit)
    }

    @Test
    fun reportsTheShellOutputTypeForAnExitCode() {
        val output: ShellOutput = ShellRunner(starter = { FakeProcess("", "", 1, true) }, commandFor = { _, _ -> listOf("x") })
            .run("x", 100)
        assertFalse(output.timedOut)
        assertEquals(1, output.exit)
    }
}
