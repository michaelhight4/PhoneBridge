package dev.phonebridge.app

import android.content.Context
import dev.phonebridge.executor.BotSession
import dev.phonebridge.executor.CommandExecutor
import dev.phonebridge.executor.ControlArbiter
import dev.phonebridge.executor.ErrorCode
import dev.phonebridge.executor.Request
import dev.phonebridge.executor.Response
import dev.phonebridge.executor.ShellApkInstaller
import dev.phonebridge.executor.ShizukuFullCommandHandler

/**
 * The one place that wires the plain-Kotlin [CommandExecutor] to the Android device.
 *
 * The transport/relay service (a later task) decodes an encrypted `Request`, looks up the paired
 * [BotSession], and calls [handle]. Control arbitration lives here so it survives the accessibility
 * service being restarted by the system.
 *
 * Full-level commands go to [ShizukuFullCommandHandler], which runs them as the shell user through
 * Shizuku; the handler reports `shizuku_unavailable` (with the "restart Shizuku after a reboot"
 * hint) whenever the helper is not running or not allowed.
 */
object BridgeRuntime {

    private val lock = Any()

    /** One arbitration state for the whole app: one bot drives at a time, others queue FIFO. */
    val control: ControlArbiter = ControlArbiter()

    @Volatile
    private var commandExecutor: CommandExecutor? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        ShizukuBridge.init(appContext)
        if (commandExecutor != null) return
        synchronized(lock) {
            if (commandExecutor == null) {
                val shizuku = ShizukuBridge
                commandExecutor = CommandExecutor(
                    device = AndroidDeviceControl(appContext),
                    control = control,
                    full = ShizukuFullCommandHandler(
                        shell = shizuku,
                        installer = ShellApkInstaller(shell = shizuku, inspector = ApkInspectorAndroid(appContext)),
                    ),
                )
            }
        }
    }

    fun executorOrNull(): CommandExecutor? = commandExecutor

    /** Execute one application-level request for a paired bot. */
    fun handle(session: BotSession, request: Request): Response {
        val executor = commandExecutor
            ?: return Response.failure(
                request.id,
                ErrorCode.INTERNAL,
                "PhoneBridge is still starting up; try again in a moment",
            )
        return executor.execute(session, request)
    }

    /** Called when a bot unpairs, disconnects or is kicked: it must not keep or hold a queue slot. */
    fun onBotDisconnected(botId: String) {
        control.forget(botId)
    }
}
