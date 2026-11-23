package dev.phonebridge.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import dev.phonebridge.executor.ApkInspector
import dev.phonebridge.executor.ShizukuShell
import dev.phonebridge.executor.ShizukuState
import dev.phonebridge.executor.ShellResult
import dev.phonebridge.executor.ShellRunner
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The single process-wide Shizuku state: binder lifecycle, the user's permission and the shell the
 * executor uses.
 *
 * Detection is deliberately honest:
 * - binder alive + permission granted -> [ShizukuState.READY]
 * - binder alive but no permission -> [ShizukuState.NO_PERMISSION] (ask from the UI)
 * - binder dead and the app is installed -> [ShizukuState.NOT_RUNNING]; Android stops Shizuku at
 *   every reboot, so the message tells the user to restart it, and every Full command answers
 *   `shizuku_unavailable` with that hint.
 * - binder dead and the app is missing -> [ShizukuState.NOT_INSTALLED]
 *
 * `docs/PROTOCOL.md` §6.1 reports the same thing as `status.shizuku` (true only when READY).
 */
object ShizukuBridge : ShizukuShell {

    /** Shizuku's and Sui's package; both expose the same provider contract. */
    const val SHIZUKU_PACKAGE: String = "moe.shizuku.privileged.api"
    const val DOWNLOAD_PAGE: String = "https://shizuku.rikka.app/download/"

    private const val TAG = "PhoneBridge/Shizuku"
    private const val REQUEST_PERMISSION_CODE = 0x5B12

    private val observers = CopyOnWriteArrayList<(ShizukuState) -> Unit>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var registered = false

    /** Called once from [BridgeApp]; safe to call again. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
            // Shizuku can start or die while the app is running (the user restarts it, Android kills
            // it at boot). Keep the app's view and the UI in sync without polling.
            Shizuku.addBinderReceivedListener { notifyObservers() }
            Shizuku.addBinderDeadListener { notifyObservers() }
            Shizuku.addRequestPermissionResultListener { _, grantResult ->
                Log.i(TAG, "permission result: $grantResult")
                notifyObservers()
            }
        }
    }

    override val state: ShizukuState
        get() = computeState()

    /** True only when the service is alive and the user granted PhoneBridge access. */
    fun isReady(): Boolean = state == ShizukuState.READY

    fun addObserver(observer: (ShizukuState) -> Unit) {
        observers += observer
    }

    fun removeObserver(observer: (ShizukuState) -> Unit) {
        observers -= observer
    }

    private fun notifyObservers() {
        val current = state
        observers.forEach { observer -> runCatching { observer(current) } }
    }

    /**
     * Asks the user to allow PhoneBridge in Shizuku. Call from an Activity (Shizuku shows its own
     * dialog); the answer arrives on the permission listener registered in [init] and the observers
     * are notified.
     */
    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
        } catch (t: Throwable) {
            // The binder died between the check and the request; the UI refresh shows NOT_RUNNING.
            Log.w(TAG, "requestPermission failed", t)
            notifyObservers()
        }
    }

    /** Opens the Shizuku app, or its download page when it is not installed. */
    fun openShizukuOrDownload(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
                .onSuccess { return }
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun computeState(): ShizukuState {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            return if (isShizukuInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) return ShizukuState.UNSUPPORTED
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuState.READY else ShizukuState.NO_PERMISSION
    }

    private fun isShizukuInstalled(): Boolean {
        val context = appContext ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * Runs `sh -c script` as the shell user through Shizuku and bounds it with [ShellRunner].
     *
     * Shizuku 13.1.5 no longer exposes `Shizuku.newProcess`, but its service AIDL still provides
     * `newProcess`; [ShizukuProcess] adapts the returned `IRemoteProcess` to [Process], so the
     * timeout, stdin and output caps stay in the tested plain-Kotlin runner.
     */
    override fun exec(script: String, timeoutMs: Long, stdin: ByteArray?): ShellResult {
        if (computeState() != ShizukuState.READY) {
            return ShellResult.failure(state.message)
        }
        return try {
            val service = IShizukuService.Stub.asInterface(
                Shizuku.getBinder() ?: throw IllegalStateException("Shizuku service binder is missing"),
            )
            val remote = service.newProcess(arrayOf("sh", "-c", script), null, null)
            ShellRunner.run(ShizukuProcess(remote), timeoutMs, stdin)
        } catch (t: Throwable) {
            Log.w(TAG, "shell failed", t)
            ShellResult.failure("Shizuku is not available: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}

/**
 * Reads a package name out of APK bytes with the platform's own parser, so `install_apk` can report
 * `{package}` and refuse garbage before it reaches PackageInstaller.
 */
class ApkInspectorAndroid(context: Context) : ApkInspector {

    private val appContext = context.applicationContext

    override fun packageName(apk: ByteArray): String? {
        if (apk.isEmpty()) return null
        val file = try {
            File.createTempFile("pb-inspect-", ".apk", appContext.cacheDir)
        } catch (e: Exception) {
            return null
        }
        return try {
            file.writeBytes(apk)
            @Suppress("DEPRECATION")
            val info = appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            info?.packageName
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }
}
