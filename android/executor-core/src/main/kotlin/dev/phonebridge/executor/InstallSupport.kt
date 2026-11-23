package dev.phonebridge.executor

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Fetches an APK for `install_apk {url}`. Injected so tests never touch the network. */
fun interface ApkDownloader {
    /** Returns the whole APK, or throws [IOException] with a message fit for the error reply. */
    fun download(url: String): ByteArray
}

/**
 * Reads the package name out of an APK's manifest. The JVM tests fake this; the app uses
 * `PackageManager.getPackageArchiveInfo` on a temporary copy.
 */
fun interface ApkInspector {
    fun packageName(apk: ByteArray): String?
}

/** What an install attempt produced. */
sealed interface ApkInstallOutcome {
    data class Installed(val packageName: String) : ApkInstallOutcome
    data class Failed(val reason: String) : ApkInstallOutcome
}

/** Installs a downloaded/inline APK at Full level. */
fun interface ApkInstaller {
    fun install(apk: ByteArray, name: String): ApkInstallOutcome
}

/**
 * `install_apk` through a **PackageInstaller session created and driven by the shell** via Shizuku:
 *
 * ```
 * pm install-create -r -S <size>              -> Success: created install session [1234]
 * pm install-write -S <size> 1234 base.apk    (APK bytes on stdin)
 * pm install-commit 1234                      -> Success
 * ```
 *
 * This is the standard `pm` session flow and works with the privileges ADB/Sui/root grant to the
 * shell user. Nothing is written to shared storage, so there is no temp APK to clean up and no
 * `/data/local/tmp` permissions problem. A failure abandons the session so a half-written install
 * cannot linger.
 */
class ShellApkInstaller(
    private val shell: ShizukuShell,
    private val inspector: ApkInspector,
) : ApkInstaller {

    override fun install(apk: ByteArray, name: String): ApkInstallOutcome {
        if (apk.isEmpty()) return ApkInstallOutcome.Failed("the APK is empty")
        if (apk.size > MAX_APK_BYTES) {
            return ApkInstallOutcome.Failed("the APK is larger than $MAX_APK_BYTES bytes")
        }

        // Check that this really is an APK before touching the device, and learn the package name
        // that the result must report.
        val packageName = inspector.packageName(apk)
            ?: return ApkInstallOutcome.Failed("the file is not a readable APK (no package name in its manifest)")

        val splitName = ShellQuote.safeApkName(name)
        val create = shell.exec("pm install-create -r -S ${apk.size}", INSTALL_TIMEOUT_MS)
        failureOf(create, "pm install-create")?.let { return it }

        val sessionId = SESSION_ID.find(create.stdout)?.groupValues?.get(1)
            ?: return ApkInstallOutcome.Failed(
                "pm install-create returned no session id: ${create.stdout.trim().ifBlank { create.stderr.trim() }}",
            )

        val writeScript = "pm install-write -S ${apk.size} $sessionId ${ShellQuote.quote(splitName)}"
        val write = shell.exec(writeScript, INSTALL_TIMEOUT_MS, stdin = apk)
        failureOf(write, "pm install-write")?.let {
            abandon(sessionId)
            return it
        }

        val commit = shell.exec("pm install-commit $sessionId", INSTALL_TIMEOUT_MS)
        val commitOutput = (commit.stdout + "\n" + commit.stderr).trim()
        if (commit.timedOut) {
            abandon(sessionId)
            return ApkInstallOutcome.Failed("pm install-commit timed out after ${INSTALL_TIMEOUT_MS} ms")
        }
        if (commit.launchFailed) {
            return ApkInstallOutcome.Failed(commit.stderr.ifBlank { "the shell could not be started" })
        }
        if (commit.exit != 0 || commitOutput.contains("Failure")) {
            abandon(sessionId)
            return ApkInstallOutcome.Failed(
                commitOutput.ifBlank { "pm install-commit failed with exit ${commit.exit}" },
            )
        }

        return ApkInstallOutcome.Installed(packageName)
    }

    private fun failureOf(result: ShellResult, what: String): ApkInstallOutcome.Failed? = when {
        result.timedOut -> ApkInstallOutcome.Failed("$what timed out after $INSTALL_TIMEOUT_MS ms")
        result.launchFailed -> ApkInstallOutcome.Failed(result.stderr.ifBlank { "the shell could not be started" })
        result.exit != 0 -> ApkInstallOutcome.Failed(
            "$what failed with exit ${result.exit}: " +
                (result.stdout + "\n" + result.stderr).trim().ifBlank { "no output" },
        )
        else -> null
    }

    /** Best effort: an abandoned session is better than a stale one, but it is not worth failing over. */
    private fun abandon(sessionId: String) {
        runCatching { shell.exec("pm install-abandon $sessionId", ABANDON_TIMEOUT_MS) }
    }

    companion object {
        /** Large games are over 100 MB; leave room but keep a bot from filling the phone. */
        const val MAX_APK_BYTES: Long = 256L * 1024L * 1024L

        const val INSTALL_TIMEOUT_MS: Long = 300_000L
        const val ABANDON_TIMEOUT_MS: Long = 30_000L

        private val SESSION_ID = Regex("\\[(\\d+)]")
    }
}

/**
 * HTTPS-only downloader for `install_apk {url}`.
 *
 * Uses `HttpURLConnection` (JDK and Android), caps the size while reading, and follows redirects
 * only when they stay on `https://` so a bot cannot be bounced onto a plaintext or `file:` URL.
 */
class HttpsApkDownloader(
    private val maxBytes: Long = ShellApkInstaller.MAX_APK_BYTES,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxRedirects: Int = 5,
) : ApkDownloader {

    override fun download(url: String): ByteArray {
        var current = URI(url).toURL()
        repeat(maxRedirects + 1) {
            val connection = (current.openConnection() as? HttpURLConnection)
                ?: throw IOException("not an http(s) URL: $current")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive, */*")

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("redirect without a Location header from $current")
                    val next = current.toURI().resolve(location)
                    if (next.scheme != "https") {
                        throw IOException("refusing a redirect to a non-https URL: $next")
                    }
                    current = next.toURL()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code from $current")

                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw IOException("the APK is larger than $maxBytes bytes")

                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val output = java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        if (output.size() > maxBytes) {
                            throw IOException("the APK is larger than $maxBytes bytes")
                        }
                    }
                }
                return output.toByteArray()
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects (more than $maxRedirects) starting at $url")
    }
}
