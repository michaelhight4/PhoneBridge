package dev.phonebridge.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The executor and the Shizuku handler wired together, as the app wires them: permission and control
 * are enforced before anything reaches the (fake) shell.
 */
class ShizukuExecutorIntegrationTest {

    private val device = FakeDevice()
    private val shell = FakeShizukuShell()
    private val executor = CommandExecutor(
        device = device,
        full = ShizukuFullCommandHandler(shell, FakeApkInstaller(), FakeApkDownloader()),
    )

    @Test
    fun `a full bot must hold control before shell runs`() {
        val session = Fixtures.fullBot()
        val refused = executor.execute(session, Request("r1", "shell", mapOf("command" to "id")))
        assertEquals(ErrorCode.NOT_CONTROLLER, refused.error?.code)
        assertTrue(shell.calls.isEmpty())

        val granted = executor.execute(session, Request("r2", "request_control"))
        assertEquals(true, granted.result?.get("granted"))

        shell.respond = { ShellResult.ok("uid=2000(shell)\n") }
        val ok = executor.execute(session, Request("r3", "shell", mapOf("command" to "id")))
        assertTrue(ok.ok, "${ok.error}")
        assertEquals("id", shell.lastScript())
    }

    @Test
    fun `control and view bots never reach the Shizuku handler`() {
        for (level in listOf(PermissionLevel.VIEW, PermissionLevel.CONTROL)) {
            val session = BotSession("bot-$level", "bot", level)
            executor.execute(session, Request("c-$level", "request_control"))
            val response = executor.execute(session, Request("s-$level", "shell", mapOf("command" to "id")))
            assertEquals(ErrorCode.PERMISSION_DENIED, response.error?.code, "for $level")
        }
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `a Shizuku outage is reported as shizuku_unavailable not as success`() {
        val session = Fixtures.fullBot()
        executor.execute(session, Request("r1", "request_control"))
        shell.state = ShizukuState.NOT_RUNNING

        val response = executor.execute(session, Request("r2", "settings", mapOf("namespace" to "secure", "key" to "x")))
        assertFalse(response.ok)
        assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, response.error?.code)
        assertTrue(shell.calls.isEmpty())
    }
}
