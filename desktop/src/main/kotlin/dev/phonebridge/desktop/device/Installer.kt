package dev.phonebridge.desktop.device

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.PhoneBridgeException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Fetches an installer; only https is accepted end to end. */
interface Downloader {
    /** Downloads [url] to [destination] and returns the byte count; throws when it exceeds [maxBytes]. */
    fun download(url: String, destination: Path, maxBytes: Long): Long
}

/** Small https-only downloader on the JDK's HttpClient. */
class HttpsDownloader(
    private val timeout: Duration = Duration.ofMinutes(5),
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : Downloader {
    override fun download(url: String, destination: Path, maxBytes: Long): Long {
        if (!url.trim().startsWith("https://", ignoreCase = true)) {
            throw BadArgsException("installer URLs must be https")
        }
        val request = try {
            HttpRequest.newBuilder(URI(url.trim()))
                .timeout(timeout)
                .header("User-Agent", "PhoneBridge-desktop")
                .GET()
                .build()
        } catch (e: Exception) {
            throw BadArgsException("installer URL is not valid: $url")
        }
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw PhoneBridgeException("could not download $url: ${e.message}", e)
        }
        if (!response.uri().scheme.equals("https", ignoreCase = true)) {
            throw BadArgsException("the download redirected away from https")
        }
        if (response.statusCode() !in 200..299) {
            throw PhoneBridgeException("download failed with HTTP ${response.statusCode()}")
        }
        response.body().use { input ->
            destination.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            var total = 0L
            try {
                Files.newOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw BadArgsException("installer is larger than $maxBytes bytes")
                        output.write(buffer, 0, read)
                    }
                }
            } catch (e: IOException) {
                throw PhoneBridgeException("could not save $url: ${e.message}", e)
            }
            return total
        }
    }
}

/**
 * Full-level `install` (desktop extension): a winget package, a local `.msi`/`.exe`, or an https
 * download of either. Winget and MSI installs run to completion with a deadline; a `.exe` installer is
 * launched for the user because most of them need a GUI.
 *
 * Only reachable at Full level, and only after the user paired that bot at Full on this PC.
 */
class Installer(
    private val runner: CommandRunner,
    private val downloader: Downloader = HttpsDownloader(),
    private val tempDir: Path = Path.of(System.getProperty("java.io.tmpdir", ".")),
    private val startDetached: (List<String>) -> Unit = { command -> ProcessBuilder(command).start().let { } },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun install(request: InstallRequest): InstallResult {
        val sources = listOfNotNull(
            request.id?.takeIf { it.isNotBlank() },
            request.path?.takeIf { it.isNotBlank() },
            request.url?.takeIf { it.isNotBlank() },
        )
        if (sources.size != 1) throw BadArgsException("install takes exactly one of id, path or url")

        val id = request.id?.trim()
        if (id != null) return winget(id)

        val url = request.url?.trim()
        if (url != null) {
            val name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: fileNameOf(url)
            val destination = tempDir.resolve("phonebridge-${clock()}-${sanitise(name)}")
            downloader.download(url, destination, MAX_DOWNLOAD_BYTES)
            return runInstaller(destination, request.silent)
        }

        val path = Path.of(request.path!!.trim())
        if (!Files.isRegularFile(path)) throw BadArgsException("installer not found: $path")
        return runInstaller(path, request.silent)
    }

    private fun winget(id: String): InstallResult {
        val command = listOf(
            "winget", "install",
            "--id", id,
            "--exact",
            "--silent",
            "--accept-package-agreements",
            "--accept-source-agreements",
            "--disable-interactivity",
        )
        val output = runner.runCommand(command, WINGET_TIMEOUT_MS)
        val ok = output.exit == 0 && !output.timedOut
        val detail = when {
            output.timedOut -> "winget did not finish within ${WINGET_TIMEOUT_MS / 60_000} minutes"
            ok -> "winget installed $id"
            else -> "winget exited with ${output.exit}: ${output.stderr.ifBlank { output.stdout }.trim().take(500)}"
        }
        return InstallResult(ok = ok, method = "winget", detail = detail, exit = output.exit, packageId = id)
    }

    private fun runInstaller(path: Path, silent: Boolean): InstallResult {
        val name = path.fileName.toString().lowercase()
        return when {
            name.endsWith(".msi") -> {
                val command = buildList {
                    add("msiexec.exe")
                    add("/i")
                    add(path.toString())
                    if (silent) {
                        add("/qn")
                        add("/norestart")
                    }
                }
                val output = runner.runCommand(command, MSI_TIMEOUT_MS)
                val ok = output.exit == 0 && !output.timedOut
                InstallResult(
                    ok = ok,
                    method = "msi",
                    detail = if (ok) "msiexec installed ${path.fileName}" else "msiexec exited with ${output.exit}",
                    exit = output.exit,
                )
            }

            name.endsWith(".exe") -> {
                startDetached(path.toAbsolutePath().toString().let { listOf(it) })
                InstallResult(
                    ok = true,
                    method = "exe",
                    detail = "launched ${path.fileName}; finish the installer window on the PC",
                    started = true,
                )
            }

            else -> throw BadArgsException("only .msi and .exe installers are supported: ${path.fileName}")
        }
    }

    companion object {
        const val WINGET_TIMEOUT_MS = 20 * 60 * 1000L
        const val MSI_TIMEOUT_MS = 20 * 60 * 1000L
        const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024

        fun fileNameOf(url: String): String {
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            val last = path.substringAfterLast('/', "")
            return last.takeIf { it.isNotEmpty() } ?: "installer.exe"
        }

        fun sanitise(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "installer.exe" }
    }
}
