package dev.phonebridge.executor

/**
 * A [ShizukuShell] that records every script and replays whatever the test tells it. This is the
 * "fake Shizuku shell" the Full-level command tests run against: no device, no binder, no SDK.
 */
class FakeShizukuShell(
    override var state: ShizukuState = ShizukuState.READY,
) : ShizukuShell {

    data class Call(val script: String, val timeoutMs: Long, val stdin: ByteArray?) {
        fun stdinText(): String? = stdin?.toString(Charsets.UTF_8)
    }

    val calls = mutableListOf<Call>()

    /** Set to decide the answer for each call; defaults to a successful empty run. */
    var respond: (Call) -> ShellResult = { ShellResult.ok("") }

    override fun exec(script: String, timeoutMs: Long, stdin: ByteArray?): ShellResult {
        val call = Call(script, timeoutMs, stdin)
        calls += call
        return respond(call)
    }

    fun last(): Call = calls.last()

    fun lastScript(): String = calls.last().script
}

/** A [ApkInstaller] that records the bytes it was given. */
class FakeApkInstaller(
    var outcome: ApkInstallOutcome = ApkInstallOutcome.Installed("com.example.installed"),
) : ApkInstaller {

    data class Call(val apk: ByteArray, val name: String)

    val calls = mutableListOf<Call>()

    override fun install(apk: ByteArray, name: String): ApkInstallOutcome {
        calls += Call(apk, name)
        return outcome
    }
}

/** An [ApkDownloader] that serves a canned APK or throws a canned error. */
class FakeApkDownloader(
    var apk: ByteArray = byteArrayOf(1, 2, 3, 4),
    var error: Exception? = null,
) : ApkDownloader {

    val urls = mutableListOf<String>()

    override fun download(url: String): ByteArray {
        urls += url
        error?.let { throw it }
        return apk
    }
}
