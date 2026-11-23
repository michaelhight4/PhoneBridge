package dev.phonebridge.app

import android.content.Context
import dev.phonebridge.executor.BotSession
import dev.phonebridge.executor.CommandExecutor
import dev.phonebridge.executor.ControlArbiter
import dev.phonebridge.executor.ErrorCode
import dev.phonebridge.executor.FullCommandHandler
import dev.phonebridge.executor.Request
import dev.phonebridge.executor.Response

/**
 * The one place that wires the plain-Kotlin [CommandExecutor] to the Android device.
 *
 * The transport/relay service (a later task) decodes an encrypted `Request`, looks up the paired
 * [BotSession], and calls [handle]. Control arbitration lives here so it survives the accessibility
 * service being restarted by the system.
 */
object BridgeRuntime {

    private val lock = Any()

    /** One arbitration state for the whole app: one bot drives at a time, others queue FIFO. */
    val control: ControlArbiter = ControlArbiter()

    @Volatile
    private var commandExecutor: CommandExecutor? = null

    fun init(context: Context) {
        if (commandExecutor != null) return
        synchronized(lock) {
            if (commandExecutor == null) {
                commandExecutor = CommandExecutor(
                    device = AndroidDeviceControl(context.applicationContext),
                    control = control,
                )
            }
        }
    }

    fun executorOrNull(): CommandExecutor? = commandExecutor

    /**
     * The relay/Shizuku commands can only be served once the foreground service and the embedded
     * relay exist, which is later than app start. The service installs the handler here.
     */
    fun setFullHandler(handler: FullCommandHandler?) {
        synchronized(lock) {
            commandExecutor?.full = handler
        }
    }

    /** Execute one application-level request for a paired bot. */
    @Synchronized
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
