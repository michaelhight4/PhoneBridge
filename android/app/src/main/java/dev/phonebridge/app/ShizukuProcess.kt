package dev.phonebridge.app

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import moe.shizuku.server.IRemoteProcess
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * A [Process] backed by a process that Shizuku started as the shell/root user.
 *
 * Shizuku 13.1.5 removed its `Shizuku.newProcess` convenience helper (deprecated upstream in favour
 * of a UserService), but the service AIDL and `IRemoteProcess` are still shipped and still implement
 * the call. This adapter is the same thin wrapper the removed helper used — file-descriptor streams
 * plus the remote wait/exit/destroy calls — which keeps the timeout, stdin and output caps in
 * [dev.phonebridge.executor.ShellRunner] on the app side.
 *
 * If a future Shizuku drops `newProcess` from the service, this is the one class to replace with a
 * UserService binding; nothing above it in the executor changes.
 */
class ShizukuProcess(private val remote: IRemoteProcess) : Process() {

    private var stdin: OutputStream? = null
    private var stdout: InputStream? = null

    override fun getOutputStream(): OutputStream = stdin ?: wrap {
        ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream).also { stdin = it }
    }

    override fun getInputStream(): InputStream = stdout ?: wrap {
        ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream).also { stdout = it }
    }

    override fun getErrorStream(): InputStream = wrap {
        ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
    }

    override fun waitFor(): Int = wrap { remote.waitFor() }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
        wrap { remote.waitForTimeout(timeout, unit.toString()) }

    override fun exitValue(): Int = wrap { remote.exitValue() }

    override fun destroy() {
        try {
            remote.destroy()
        } catch (e: RemoteException) {
            // The process (or the whole service) is already gone; destroy is best effort.
        }
    }

    /** `IRemoteProcess` reports binder failures as [RemoteException]; Process must not. */
    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        throw RuntimeException("Shizuku process call failed", e)
    }
}
