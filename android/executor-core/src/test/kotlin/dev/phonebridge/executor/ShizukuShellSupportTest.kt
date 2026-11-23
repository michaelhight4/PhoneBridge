package dev.phonebridge.executor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ShizukuShellSupportTest {

    // --------------------------------------------------------------- ShellQuote

    @Test
    fun `quote keeps every byte literal`() {
        assertEquals("'/sdcard/a b'", ShellQuote.quote("/sdcard/a b"))
        assertEquals("'/sdcard/a'\\''b'", ShellQuote.quote("/sdcard/a'b"))
        assertEquals("'a; reboot'", ShellQuote.quote("a; reboot"))
    }

    @Test
    fun `paths must be absolute single line strings`() {
        assertEquals("/sdcard/a b", ShellQuote.validatePath("path", "/sdcard/a b"))
        assertFailsWith<BadArgsException> { ShellQuote.validatePath("path", "sdcard/a") }
        assertFailsWith<BadArgsException> { ShellQuote.validatePath("path", "") }
        assertFailsWith<BadArgsException> { ShellQuote.validatePath("path", "/sdcard/a\nb") }
        assertFailsWith<BadArgsException> { ShellQuote.validatePath("path", "/sdcard/\u0000") }
        assertFailsWith<BadArgsException> { ShellQuote.validatePath("path", "/" + "a".repeat(ShellQuote.MAX_PATH_CHARS)) }
    }

    @Test
    fun `settings keys are restricted to the settings alphabet`() {
        assertEquals("screen_brightness", ShellQuote.validateSettingsKey("screen_brightness"))
        assertEquals("adb_enabled", ShellQuote.validateSettingsKey("adb_enabled"))
        assertEquals("a.b:c-d", ShellQuote.validateSettingsKey("a.b:c-d"))
        assertFailsWith<BadArgsException> { ShellQuote.validateSettingsKey("x; reboot") }
        assertFailsWith<BadArgsException> { ShellQuote.validateSettingsKey("") }
        assertFailsWith<BadArgsException> { ShellQuote.validateSettingsKey("a".repeat(ShellQuote.MAX_SETTINGS_KEY_CHARS + 1)) }
    }

    @Test
    fun `settings values are bounded single lines`() {
        assertEquals("", ShellQuote.validateSettingsValue(""))
        assertEquals("a b:c-d", ShellQuote.validateSettingsValue("a b:c-d"))
        assertFailsWith<BadArgsException> { ShellQuote.validateSettingsValue("a\rb") }
        assertFailsWith<BadArgsException> {
            ShellQuote.validateSettingsValue("v".repeat(ShellQuote.MAX_SETTINGS_VALUE_CHARS + 1))
        }
    }

    @Test
    fun `shell commands must not be blank`() {
        assertEquals("id", ShellQuote.validateShellCommand("id"))
        assertFailsWith<BadArgsException> { ShellQuote.validateShellCommand("   ") }
    }

    @Test
    fun `apk split names never contain separators`() {
        assertEquals("base.apk", ShellQuote.safeApkName(null))
        assertEquals("base.apk", ShellQuote.safeApkName("  "))
        assertEquals("app.apk", ShellQuote.safeApkName("app"))
        assertEquals("helper.apk", ShellQuote.safeApkName("helper.apk"))
        assertEquals("evil_rm.apk", ShellQuote.safeApkName("../../evil;rm"))
        assertEquals("dir_app.apk", ShellQuote.safeApkName("dir/app"))
    }

    // ---------------------------------------------------------------------- B64

    @Test
    fun `base64 decodes every accepted spelling`() {
        val expected = byteArrayOf(-5, -1, 1, 2)
        val url = B64.encodeUrlNoPadding(expected) // -_8BAg
        assertTrue(expected.contentEquals(B64.decodeLoose(url)))
        assertTrue(expected.contentEquals(B64.decodeLoose("+/8BAg==")))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(B64.decodeLoose("AQID")))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(B64.decodeLoose("AQ ID\n")))
        assertTrue(byteArrayOf(1, 2).contentEquals(B64.decodeLoose("AQI")))
        assertFailsWith<IllegalArgumentException> { B64.decodeLoose("A") }
        assertFailsWith<IllegalArgumentException> { B64.decodeLoose("!!!") }
    }

    @Test
    fun `base64 encoding is url safe without padding`() {
        assertEquals("AQID", B64.encodeUrlNoPadding(byteArrayOf(1, 2, 3)))
        assertEquals("-_8", B64.encodeUrlNoPadding(byteArrayOf(-5, -1)))
        assertFalse(B64.encodeUrlNoPadding(byteArrayOf(-5, -1)).contains('='))
    }

    // ---------------------------------------------------------------- ShellFiles

    private val shell = FakeShizukuShell()
    private val files = ShellFiles(shell)

    @Test
    fun `list stops at the entry cap and says so`() {
        shell.respond = {
            ShellResult.ok(
                """
                /sdcard/d/a
                0 1 10
                /sdcard/d/b
                0 2 20
                /sdcard/d/c
                0 3 30
                """.trimIndent() + "\n",
            )
        }
        val listing = files.list("/sdcard/d", maxEntries = 2)
        assertEquals(listOf("a", "b"), listing.entries.map { it.name })
        assertTrue(listing.truncated)
        assertEquals(listOf(10_000L, 20_000L), listing.entries.map { it.mtime })
    }

    @Test
    fun `list surfaces shell errors`() {
        shell.respond = { ShellResult(exit = 1, stderr = "permission denied") }
        val error = assertFailsWith<ShellCommandException> { files.list("/data") }
        assertContains(error.message!!, "permission denied")
    }

    @Test
    fun `list reports a launch failure and a timeout`() {
        shell.respond = { ShellResult.failure("binder died") }
        assertContains(assertFailsWith<ShellCommandException> { files.list("/sdcard") }.message!!, "binder died")
        shell.respond = { ShellResult(exit = -1, timedOut = true) }
        assertContains(assertFailsWith<ShellCommandException> { files.list("/sdcard") }.message!!, "timed out")
    }

    @Test
    fun `read asks for one byte more than the cap to detect a big file`() {
        shell.respond = { ShellResult(exit = 0, stdoutBytes = byteArrayOf(7, 8)) }
        val bytes = files.read("/sdcard/x", maxBytes = 2)
        assertTrue(byteArrayOf(7, 8).contentEquals(bytes))
        assertContains(shell.lastScript(), "head -c 3 -- '/sdcard/x'")
    }

    @Test
    fun `write sends data on stdin`() {
        shell.respond = { ShellResult.ok("") }
        val written = files.write("/sdcard/y", byteArrayOf(9, 9, 9))
        assertEquals(3, written)
        assertEquals("cat > '/sdcard/y'", shell.lastScript())
        assertTrue(byteArrayOf(9, 9, 9).contentEquals(shell.last().stdin))
    }

    @Test
    fun `delete removes recursively with a quoted path`() {
        shell.respond = { ShellResult.ok("") }
        files.delete("/sdcard/a b")
        assertEquals("rm -rf -- '/sdcard/a b'", shell.lastScript())
        shell.respond = { ShellResult(exit = 1, stderr = "Read-only file system") }
        assertContains(assertFailsWith<ShellCommandException> { files.delete("/system/x") }.message!!, "Read-only")
    }

    // ------------------------------------------------------ ShellApkInstaller

    private fun installShell(responder: (String) -> ShellResult): FakeShizukuShell {
        val fake = FakeShizukuShell()
        fake.respond = { responder(it.script) }
        return fake
    }

    private val sessionId = "42"

    @Test
    fun `install drives a pm session and returns the inspected package`() {
        val fake = installShell { script ->
            when {
                script.startsWith("pm install-create") -> ShellResult.ok("Success: created install session [$sessionId]\n")
                script.startsWith("pm install-write") -> ShellResult.ok("Success: streamed 4 bytes\n")
                script == "pm install-commit $sessionId" -> ShellResult.ok("Success\n")
                else -> ShellResult(exit = 1, stderr = "unexpected: $script")
            }
        }
        val installer = ShellApkInstaller(fake, ApkInspector { "com.example.app" })
        val apk = byteArrayOf(1, 2, 3, 4)
        val outcome = installer.install(apk, "app.apk")

        assertEquals(ApkInstallOutcome.Installed("com.example.app"), outcome)
        assertEquals(3, fake.calls.size)
        assertEquals("pm install-create -r -S 4", fake.calls[0].script)
        assertEquals("pm install-write -S 4 $sessionId 'app.apk'", fake.calls[1].script)
        assertTrue(apk.contentEquals(fake.calls[1].stdin))
        assertEquals("pm install-commit $sessionId", fake.calls[2].script)
    }

    @Test
    fun `a failed create stops immediately`() {
        val fake = installShell { script ->
            if (script.startsWith("pm install-create")) {
                ShellResult(exit = 1, stderr = "Error: couldn't create a session")
            } else {
                ShellResult.ok("")
            }
        }
        val installer = ShellApkInstaller(fake, ApkInspector { "com.example" })
        val outcome = installer.install(byteArrayOf(1), "a.apk")
        assertTrue(outcome is ApkInstallOutcome.Failed)
        assertContains(outcome.reason, "couldn't create a session")
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `a failed write abandons the session`() {
        val fake = installShell { script ->
            when {
                script.startsWith("pm install-create") -> ShellResult.ok("Success: created install session [7]\n")
                script.startsWith("pm install-write") -> ShellResult(exit = 1, stderr = "Error: not enough space")
                else -> ShellResult.ok("")
            }
        }
        val installer = ShellApkInstaller(fake, ApkInspector { "com.example" })
        val outcome = installer.install(byteArrayOf(1), "a.apk")
        assertTrue(outcome is ApkInstallOutcome.Failed)
        assertContains(outcome.reason, "not enough space")
        assertEquals("pm install-abandon 7", fake.lastScript())
    }

    @Test
    fun `a failed commit abandons the session even when pm exits zero`() {
        val fake = installShell { script ->
            when {
                script.startsWith("pm install-create") -> ShellResult.ok("Success: created install session [9]\n")
                script.startsWith("pm install-write") -> ShellResult.ok("Success: streamed 1 bytes\n")
                script.startsWith("pm install-commit") ->
                    ShellResult(exit = 0, stdoutBytes = "Failure [INSTALL_FAILED_INVALID_APK]\n".toByteArray())
                else -> ShellResult.ok("")
            }
        }
        val installer = ShellApkInstaller(fake, ApkInspector { "com.example" })
        val outcome = installer.install(byteArrayOf(1), "a.apk")
        assertTrue(outcome is ApkInstallOutcome.Failed)
        assertContains(outcome.reason, "INSTALL_FAILED_INVALID_APK")
        assertEquals("pm install-abandon 9", fake.lastScript())
    }

    @Test
    fun `an unreadable APK is refused before any shell call`() {
        val fake = installShell { ShellResult.ok("") }
        val installer = ShellApkInstaller(fake, ApkInspector { null })
        val outcome = installer.install(byteArrayOf(1), "a.apk")
        assertTrue(outcome is ApkInstallOutcome.Failed)
        assertContains(outcome.reason, "not a readable APK")
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `an empty APK is refused before any shell call`() {
        val fake = installShell { ShellResult.ok("") }
        val installer = ShellApkInstaller(fake, ApkInspector { "com.example" })
        val outcome = installer.install(ByteArray(0), "a.apk")
        assertTrue(outcome is ApkInstallOutcome.Failed)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `the split name cannot escape the session`() {
        val fake = installShell { script ->
            when {
                script.startsWith("pm install-create") -> ShellResult.ok("Success: created install session [3]\n")
                script.startsWith("pm install-write") -> ShellResult.ok("Success\n")
                else -> ShellResult.ok("Success\n")
            }
        }
        val installer = ShellApkInstaller(fake, ApkInspector { "com.example" })
        installer.install(byteArrayOf(1), "../../evil;rm -rf /")
        assertContains(fake.calls[1].script, "'evil_rm_-rf.apk'")
        assertFalse(fake.calls[1].script.contains(".."))
    }
}
