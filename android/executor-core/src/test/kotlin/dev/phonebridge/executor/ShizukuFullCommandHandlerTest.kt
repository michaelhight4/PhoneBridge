package dev.phonebridge.executor

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShizukuFullCommandHandlerTest {

    private val shell = FakeShizukuShell()
    private val installer = FakeApkInstaller()
    private val downloader = FakeApkDownloader()
    private val handler = ShizukuFullCommandHandler(shell, installer, downloader)

    private fun run(vararg args: Pair<String, Any?>, cmd: String): Response =
        handler.handle(Fixtures.fullBot(), Request(id = "req-1", cmd = cmd, args = mapOf(*args)))

    // ------------------------------------------------------------ availability

    @Test
    fun `every full command reports shizuku_unavailable when Shizuku is not running`() {
        shell.state = ShizukuState.NOT_RUNNING
        for (cmd in listOf("install_apk", "shell", "settings", "files")) {
            val response = run(cmd = cmd)
            assertFalse(response.ok, "expected $cmd to fail")
            assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, response.error?.code, "for $cmd")
            assertContains(response.error!!.message, "restart", ignoreCase = true)
            assertContains(response.error.message, "reboot", ignoreCase = true)
        }
        assertTrue(shell.calls.isEmpty(), "nothing may reach the shell while Shizuku is down")
    }

    @Test
    fun `a missing Shizuku install points at where to get it`() {
        shell.state = ShizukuState.NOT_INSTALLED
        val response = run(cmd = "shell", args = arrayOf("command" to "id"))
        assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, response.error?.code)
        assertContains(response.error!!.message, "Install")
        assertContains(response.error.message, "shizuku.rikka.app")
    }

    @Test
    fun `missing Shizuku permission says how to grant it`() {
        shell.state = ShizukuState.NO_PERMISSION
        val response = run(cmd = "settings", args = arrayOf("namespace" to "secure", "key" to "x"))
        assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, response.error?.code)
        assertContains(response.error!!.message, "allow", ignoreCase = true)
    }

    @Test
    fun `relay_move is refused without touching Shizuku`() {
        shell.state = ShizukuState.NOT_RUNNING
        val response = run(cmd = "relay_move", args = arrayOf("to" to "this"))
        assertEquals(ErrorCode.INTERNAL, response.error?.code)
        assertContains(response.error!!.message, "relay")
        assertTrue(shell.calls.isEmpty())
    }

    // ------------------------------------------------------------------- shell

    @Test
    fun `shell returns exit stdout and stderr`() {
        shell.respond = { ShellResult(exit = 0, stdoutBytes = "uid=2000(shell)\n".toByteArray(), stderr = "") }
        val response = run(cmd = "shell", args = arrayOf("command" to "id"))
        assertTrue(response.ok, "${response.error}")
        assertEquals(0, response.result?.get("exit"))
        assertEquals("uid=2000(shell)\n", response.result?.get("stdout"))
        assertEquals("", response.result?.get("stderr"))
        assertEquals("id", shell.lastScript())
        assertEquals(ShizukuShell.DEFAULT_TIMEOUT_MS, shell.last().timeoutMs)
    }

    @Test
    fun `shell honours timeout_ms and the timeout_ms alias`() {
        shell.respond = { ShellResult.ok("ok") }
        assertTrue(run(cmd = "shell", args = arrayOf("command" to "sleep 1", "timeout_ms" to 5_000L)).ok)
        assertEquals(5_000L, shell.last().timeoutMs)
        assertTrue(run(cmd = "shell", args = arrayOf("command" to "sleep 1", "timeoutMs" to 2_500L)).ok)
        assertEquals(2_500L, shell.last().timeoutMs)
    }

    @Test
    fun `a shell command that runs out of time is a timeout error`() {
        shell.respond = { ShellResult(exit = -1, timedOut = true, stderr = "") }
        val response = run(cmd = "shell", args = arrayOf("command" to "sleep 1000", "timeout_ms" to 100))
        assertEquals(ErrorCode.TIMEOUT, response.error?.code)
        assertContains(response.error!!.message, "did not finish")
    }

    @Test
    fun `shell rejects blank commands and out of range timeouts`() {
        assertEquals(ErrorCode.BAD_ARGS, run(cmd = "shell", args = arrayOf("command" to "  ")).error?.code)
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "shell", args = arrayOf("command" to "id", "timeout_ms" to 0)).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "shell", args = arrayOf("command" to "id", "timeout_ms" to 3_600_001L)).error?.code,
        )
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `a shell that cannot start is reported and a dead Shizuku is shizuku_unavailable`() {
        shell.respond = { ShellResult.failure("binder died") }
        val internal = run(cmd = "shell", args = arrayOf("command" to "id"))
        assertEquals(ErrorCode.INTERNAL, internal.error?.code)
        assertContains(internal.error!!.message, "binder died")

        val flaky = object : ShizukuShell {
            var execCount = 0
            override val state: ShizukuState
                get() = if (execCount == 0) ShizukuState.READY else ShizukuState.NOT_RUNNING

            override fun exec(script: String, timeoutMs: Long, stdin: ByteArray?): ShellResult {
                execCount++
                return ShellResult.failure("binder died")
            }
        }
        val response = ShizukuFullCommandHandler(flaky, installer, downloader)
            .handle(Fixtures.fullBot(), Request("r", "shell", mapOf("command" to "id")))
        assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, response.error?.code)
        assertContains(response.error!!.message, "restart", ignoreCase = true)
    }

    // ---------------------------------------------------------------- settings

    @Test
    fun `settings get reads the namespace and key`() {
        shell.respond = { ShellResult.ok("0\n") }
        val response = run(cmd = "settings", args = arrayOf("namespace" to "secure", "key" to "airplane_mode_on"))
        assertTrue(response.ok, "${response.error}")
        assertEquals("0", response.result?.get("value"))
        assertEquals("settings get secure 'airplane_mode_on'", shell.lastScript())
    }

    @Test
    fun `settings get maps null to a null value`() {
        shell.respond = { ShellResult.ok("null\n") }
        val response = run(cmd = "settings", args = arrayOf("namespace" to "system", "key" to "unset_key"))
        assertTrue(response.ok)
        assertTrue(response.result!!.containsKey("value"))
        assertNull(response.result["value"])
    }

    @Test
    fun `settings put writes string number and boolean values`() {
        shell.respond = { ShellResult.ok("") }

        val number = run(
            cmd = "settings",
            args = arrayOf("namespace" to "system", "key" to "screen_brightness", "value" to 128),
        )
        assertTrue(number.ok, "${number.error}")
        assertEquals("settings put system 'screen_brightness' '128'", shell.lastScript())
        assertEquals("128", number.result?.get("value"))

        val flag = run(cmd = "settings", args = arrayOf("namespace" to "global", "key" to "auto_time", "value" to true))
        assertTrue(flag.ok, "${flag.error}")
        assertEquals("settings put global 'auto_time' 'true'", shell.lastScript())
        assertEquals("true", flag.result?.get("value"))

        val text = run(
            cmd = "settings",
            args = arrayOf("namespace" to "secure", "key" to "enabled_accessibility_services", "value" to "a:b"),
        )
        assertTrue(text.ok, "${text.error}")
        assertEquals("settings put secure 'enabled_accessibility_services' 'a:b'", shell.lastScript())
        assertEquals("a:b", text.result?.get("value"))
    }

    @Test
    fun `settings rejects unknown namespaces keys and injected values`() {
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "settings", args = arrayOf("namespace" to "vendor", "key" to "x")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "settings", args = arrayOf("namespace" to "system", "key" to "x; reboot")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "settings", args = arrayOf("namespace" to "system", "key" to "x", "value" to "a\nb")).error?.code,
        )
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `settings still reports a failing shell command`() {
        shell.respond = { ShellResult(exit = 1, stderr = "Error: no such setting") }
        val response = run(cmd = "settings", args = arrayOf("namespace" to "secure", "key" to "x"))
        assertEquals(ErrorCode.INTERNAL, response.error?.code)
        assertContains(response.error!!.message, "no such setting")
    }

    // ------------------------------------------------------------------- files

    @Test
    fun `files list parses names with spaces paths types sizes and mtimes`() {
        shell.respond = {
            ShellResult.ok(
                """
                /sdcard/Downloads
                1 4096 1700000000
                /sdcard/Downloads/report final.txt
                0 12 1700000100
                """.trimIndent() + "\n",
            )
        }
        val response = run(cmd = "files", args = arrayOf("op" to "list", "path" to "/sdcard/Downloads"))
        assertTrue(response.ok, "${response.error}")
        assertEquals("list", response.result?.get("op"))
        val entries = response.result!!["entries"] as List<*>
        assertEquals(2, entries.size)

        val dir = entries[0] as Map<*, *>
        assertEquals("Downloads", dir["name"])
        assertEquals("/sdcard/Downloads", dir["path"])
        assertEquals(true, dir["is_dir"])
        assertEquals(4096L, dir["size"])
        assertEquals(1_700_000_000_000L, dir["mtime"])

        val file = entries[1] as Map<*, *>
        assertEquals("report final.txt", file["name"])
        assertEquals(false, file["is_dir"])
        assertEquals(12L, file["size"])
        assertContains(shell.lastScript(), "'/sdcard/Downloads'")
    }

    @Test
    fun `files list reports a missing directory`() {
        shell.respond = { ShellResult(exit = 2, stderr = "not a directory: /nope") }
        val response = run(cmd = "files", args = arrayOf("op" to "list", "path" to "/nope"))
        assertEquals(ErrorCode.INTERNAL, response.error?.code)
        assertContains(response.error!!.message, "not a directory")
    }

    @Test
    fun `files read returns base64url content and a byte count`() {
        shell.respond = { ShellResult(exit = 0, stdoutBytes = byteArrayOf(1, 2, 3)) }
        val response = run(cmd = "files", args = arrayOf("op" to "read", "path" to "/sdcard/a.bin"))
        assertTrue(response.ok, "${response.error}")
        assertEquals("read", response.result?.get("op"))
        assertEquals("AQID", response.result?.get("data_b64"))
        assertEquals(3, response.result?.get("bytes"))
        assertContains(shell.lastScript(), "head -c")
        assertContains(shell.lastScript(), "'/sdcard/a.bin'")
    }

    @Test
    fun `files read refuses a file above the cap`() {
        shell.respond = { ShellResult(exit = 0, stdoutBytes = ByteArray(ShellFiles.MAX_READ_BYTES + 1)) }
        val response = run(cmd = "files", args = arrayOf("op" to "read", "path" to "/sdcard/huge.bin"))
        assertEquals(ErrorCode.INTERNAL, response.error?.code)
        assertContains(response.error!!.message, "larger than")
    }

    @Test
    fun `files write sends the decoded bytes on stdin`() {
        shell.respond = { ShellResult.ok("") }
        val response = run(
            cmd = "files",
            args = arrayOf("op" to "write", "path" to "/sdcard/out.bin", "data_b64" to "AQIDBA"),
        )
        assertTrue(response.ok, "${response.error}")
        assertEquals("write", response.result?.get("op"))
        assertEquals(true, response.result?.get("ok"))
        assertEquals(4, response.result?.get("bytes"))
        assertEquals("cat > '/sdcard/out.bin'", shell.lastScript())
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(shell.last().stdin))
    }

    @Test
    fun `files delete removes a path and refuses the root`() {
        shell.respond = { ShellResult.ok("") }
        val response = run(cmd = "files", args = arrayOf("op" to "delete", "path" to "/sdcard/old"))
        assertTrue(response.ok, "${response.error}")
        assertEquals("delete", response.result?.get("op"))
        assertEquals(true, response.result?.get("ok"))
        assertEquals("rm -rf -- '/sdcard/old'", shell.lastScript())

        val root = run(cmd = "files", args = arrayOf("op" to "delete", "path" to "/"))
        assertEquals(ErrorCode.BAD_ARGS, root.error?.code)
        assertEquals(1, shell.calls.size)
    }

    @Test
    fun `files validates op path and data_b64`() {
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "files", args = arrayOf("op" to "move", "path" to "/sdcard/x")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "files", args = arrayOf("op" to "list", "path" to "relative/dir")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "files", args = arrayOf("op" to "write", "path" to "/sdcard/x")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "files", args = arrayOf("op" to "read", "path" to "/sdcard/x", "data_b64" to "AQID")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "files", args = arrayOf("op" to "write", "path" to "/sdcard/x", "data_b64" to "!!!")).error?.code,
        )
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `a quoted path cannot inject a second command`() {
        shell.respond = { ShellResult.ok("") }
        val evil = "/sdcard/x'; reboot; echo '"
        val response = run(cmd = "files", args = arrayOf("op" to "delete", "path" to evil))
        assertTrue(response.ok, "${response.error}")
        assertEquals("rm -rf -- ${ShellQuote.quote(evil)}", shell.lastScript())
        // The injected text stays inside single quotes; the only unquoted separators are our own.
        assertContains(shell.lastScript(), "'\\''; reboot; echo '\\'''")
    }

    // -------------------------------------------------------------- install_apk

    @Test
    fun `install_apk downloads an https URL and reports the package`() {
        val response = run(cmd = "install_apk", args = arrayOf("url" to "https://example.com/app.apk"))
        assertTrue(response.ok, "${response.error}")
        assertEquals("com.example.installed", response.result?.get("package"))
        assertEquals(listOf("https://example.com/app.apk"), downloader.urls)
        assertEquals(1, installer.calls.size)
        assertEquals("app.apk", installer.calls.single().name)
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(installer.calls.single().apk))
    }

    @Test
    fun `install_apk accepts an inline base64 APK with a name`() {
        val response = run(
            cmd = "install_apk",
            args = arrayOf("data_b64" to "AQID", "name" to "helper.apk"),
        )
        assertTrue(response.ok, "${response.error}")
        assertEquals("helper.apk", installer.calls.single().name)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(installer.calls.single().apk))
        assertTrue(downloader.urls.isEmpty())
    }

    @Test
    fun `install_apk validates url and inline shapes`() {
        assertEquals(ErrorCode.BAD_ARGS, run(cmd = "install_apk", args = arrayOf()).error?.code)
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "install_apk", args = arrayOf("url" to "http://example.com/app.apk")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "install_apk", args = arrayOf("url" to "https://e.app/a.apk", "data_b64" to "AQID")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "install_apk", args = arrayOf("data_b64" to "AQID")).error?.code,
        )
        assertEquals(
            ErrorCode.BAD_ARGS,
            run(cmd = "install_apk", args = arrayOf("data_b64" to "!!!", "name" to "a.apk")).error?.code,
        )
        assertTrue(installer.calls.isEmpty())
        assertTrue(downloader.urls.isEmpty())
    }

    @Test
    fun `install_apk reports a download failure`() {
        downloader.error = IOException("404 Not Found")
        val response = run(cmd = "install_apk", args = arrayOf("url" to "https://example.com/missing.apk"))
        assertEquals(ErrorCode.INTERNAL, response.error?.code)
        assertContains(response.error!!.message, "404 Not Found")
        assertTrue(installer.calls.isEmpty())
    }

    @Test
    fun `install_apk reports an installer failure`() {
        installer.outcome = ApkInstallOutcome.Failed("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        val response = run(
            cmd = "install_apk",
            args = arrayOf("data_b64" to "AQID", "name" to "x.apk"),
        )
        assertEquals(ErrorCode.INTERNAL, response.error?.code)
        assertContains(response.error!!.message, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
    }
}
