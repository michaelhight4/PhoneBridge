package dev.phonebridge.desktop

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.desktop.device.CommandRunner
import dev.phonebridge.desktop.device.Downloader
import dev.phonebridge.desktop.device.HttpsDownloader
import dev.phonebridge.desktop.device.InstallRequest
import dev.phonebridge.desktop.device.Installer
import dev.phonebridge.desktop.device.ShellOutput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstallerTest {
    private class FakeRunner(var exit: Int = 0) : CommandRunner {
        val commands = mutableListOf<List<String>>()
        override fun runCommand(command: List<String>, timeoutMs: Long): ShellOutput {
            commands.add(command)
            return ShellOutput(exit, "ok", "")
        }
    }

    private class FakeDownloader : Downloader {
        val urls = mutableListOf<String>()
        override fun download(url: String, destination: Path, maxBytes: Long): Long {
            urls.add(url)
            Files.write(destination, ByteArray(4))
            return 4
        }
    }

    private val temp = Files.createTempDirectory("pb-install").also { it.toFile().deleteOnExit() }
    private val runner = FakeRunner()
    private val downloader = FakeDownloader()
    private val detached = mutableListOf<List<String>>()
    private val installer = Installer(
        runner = runner,
        downloader = downloader,
        tempDir = temp,
        startDetached = { detached.add(it) },
        clock = { 1234L },
    )

    @Test
    fun installsAWingetPackageSilently() {
        val result = installer.install(InstallRequest(id = "Google.Chrome"))
        assertTrue(result.ok)
        assertEquals("winget", result.method)
        val command = runner.commands.single()
        assertEquals("winget", command.first())
        assertTrue(command.contains("--id"))
        assertTrue(command.contains("Google.Chrome"))
        assertTrue(command.contains("--silent"))
        assertTrue(command.contains("--accept-package-agreements"))
    }

    @Test
    fun reportsAWingetFailure() {
        runner.exit = 5
        val result = installer.install(InstallRequest(id = "Missing.Package"))
        assertEquals(false, result.ok)
        assertEquals(5, result.exit)
        assertTrue(result.detail.contains("5"))
    }

    @Test
    fun runsAnMsiInstallerToCompletion() {
        val msi = temp.resolve("setup.msi").also { Files.write(it, ByteArray(1)) }
        val result = installer.install(InstallRequest(path = msi.toString()))
        assertTrue(result.ok)
        assertEquals("msi", result.method)
        val command = runner.commands.single()
        assertEquals("msiexec.exe", command.first())
        assertTrue(command.contains("/i"))
        assertTrue(command.contains(msi.toString()))
        assertTrue(command.contains("/qn"))
    }

    @Test
    fun launchesAnExeInstallerDetached() {
        val exe = temp.resolve("setup.exe").also { Files.write(it, ByteArray(1)) }
        val result = installer.install(InstallRequest(path = exe.toString()))
        assertTrue(result.ok)
        assertEquals("exe", result.method)
        assertTrue(result.started)
        assertEquals(listOf(exe.toString()), detached.single())
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun downloadsHttpsInstallersBeforeRunningThem() {
        val result = installer.install(InstallRequest(url = "https://example.test/apps/setup.exe"))
        assertTrue(result.ok)
        assertEquals("setup.exe", Installer.fileNameOf("https://example.test/apps/setup.exe"))
        assertEquals("https://example.test/apps/setup.exe", downloader.urls.single())
        assertTrue(result.started)
        assertTrue(detached.single().single().contains("phonebridge-1234-setup.exe"))
    }

    @Test
    fun validatesTheRequest() {
        assertFailsWith<BadArgsException> { installer.install(InstallRequest()) }
        assertFailsWith<BadArgsException> {
            installer.install(InstallRequest(id = "A", path = "b.exe"))
        }
        assertFailsWith<BadArgsException> {
            installer.install(InstallRequest(path = temp.resolve("missing.exe").toString()))
        }
        val zip = temp.resolve("archive.zip").also { Files.write(it, ByteArray(1)) }
        assertFailsWith<BadArgsException> { installer.install(InstallRequest(path = zip.toString())) }
        assertTrue(detached.isEmpty())
    }

    @Test
    fun onlyHttpsDownloadsAreAccepted() {
        assertFailsWith<BadArgsException> {
            HttpsDownloader().download("http://example.test/app.exe", temp.resolve("app.exe"), 1024)
        }
        assertFailsWith<BadArgsException> {
            HttpsDownloader().download("ftp://example.test/app.exe", temp.resolve("app.exe"), 1024)
        }
    }

    @Test
    fun derivesASafeFileNameFromAUrl() {
        assertEquals("installer.exe", Installer.fileNameOf("https://example.test/"))
        assertEquals("setup.msi", Installer.fileNameOf("https://example.test/a/b/setup.msi?x=1"))
        assertEquals("weird_name.exe", Installer.sanitise("weird name.exe"))
        assertEquals("installer.exe", Installer.sanitise(""))
    }
}
