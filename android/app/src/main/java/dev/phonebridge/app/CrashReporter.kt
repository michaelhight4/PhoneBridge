package dev.phonebridge.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on disk so the next launch can show it on the "Last crash" screen with a Copy
 * button. Nothing is sent anywhere; the report stays on the phone until the user copies or clears it.
 */
object CrashReporter {
    private const val TAG = "PhoneBridge"
    private const val FILE = "last_crash.txt"
    private const val SEEN = "last_crash.seen"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "Crash on thread ${thread.name}", error) }
            if (previous != null) previous.uncaughtException(thread, error) else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    /** Records a problem the app survived (for example a service that could not start). */
    fun recordHandled(context: Context, what: String, error: Throwable) {
        Log.e(TAG, what, error)
        runCatching { write(context.applicationContext, what, error) }
    }

    fun lastCrash(context: Context): String? {
        val file = File(context.filesDir, FILE)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /** True once per new crash report, so the screen opens by itself only one time. */
    fun takeUnseen(context: Context): Boolean {
        val crash = File(context.filesDir, FILE)
        val seen = File(context.filesDir, SEEN)
        if (!crash.isFile) return false
        if (seen.isFile && seen.lastModified() >= crash.lastModified()) return false
        runCatching { seen.writeText("seen") }
        return true
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, SEEN).delete()
    }

    private fun write(context: Context, what: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val report = buildString {
            appendLine("PhoneBridge $version")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(time)
            appendLine(what)
            appendLine()
            append(trace)
        }
        File(context.filesDir, FILE).writeText(report)
    }
}
