package dev.phonebridge.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import dev.phonebridge.executor.BotSession
import dev.phonebridge.executor.Command
import dev.phonebridge.executor.CommandExecutor
import dev.phonebridge.executor.ControlArbiter
import dev.phonebridge.executor.ErrorCode
import dev.phonebridge.executor.FullCommandHandler
import dev.phonebridge.executor.Request
import dev.phonebridge.executor.Response
import dev.phonebridge.executor.ShellApkInstaller
import dev.phonebridge.executor.ShizukuFullCommandHandler

/**
 * The one place that wires the plain-Kotlin [CommandExecutor] to the Android device.
 *
 * The transport service decodes an encrypted `Request`, looks up the paired [BotSession], and calls
 * [handle]. Control arbitration lives here so it survives the accessibility service being restarted
 * by the system.
 *
 * Full-level commands are split between two owners:
 * - `relay_move` is served by the foreground service's controller, which owns the embedded relay. It
 *   registers through [setFullHandler] once the service is up.
 * - `install_apk`, `shell`, `settings` and `files` always go to [ShizukuFullCommandHandler], which
 *   runs them as the shell user through Shizuku and reports `shizuku_unavailable` (with the "restart
 *   Shizuku after a reboot" hint) whenever the helper is not running or not allowed.
 *
 * Keeping the Shizuku handler installed permanently means the relay coming or going never disables
 * the Full-level commands, and the relay lane keeps its own handler.
 */
object BridgeRuntime {

    private val lock = Any()

    /** One arbitration state for the whole app: one bot drives at a time, others queue FIFO. */
    val control: ControlArbiter = ControlArbiter()

    @Volatile
    private var commandExecutor: CommandExecutor? = null

    /** Installed by the foreground service's controller; null while the bridge is stopped. */
    @Volatile
    private var relayHandler: FullCommandHandler? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var mediaProjectionResultCode: Int? = null

    @Volatile
    private var mediaProjectionData: Intent? = null

    @Volatile
    private var mediaProjection: MediaProjection? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        this.appContext = appContext
        ShizukuBridge.init(appContext)
        if (commandExecutor != null) return
        synchronized(lock) {
            if (commandExecutor == null) {
                val shizuku = ShizukuBridge
                val shizukuCommands = ShizukuFullCommandHandler(
                    shell = shizuku,
                    installer = ShellApkInstaller(shell = shizuku, inspector = ApkInspectorAndroid(appContext)),
                )
                val full = FullCommandHandler { session, request ->
                    if (request.cmd == Command.RELAY_MOVE.wireName) {
                        relayHandler?.handle(session, request)
                            ?: Response.failure(
                                request.id,
                                ErrorCode.INTERNAL,
                                "relay_move is not available while the bridge is stopped",
                            )
                    } else {
                        shizukuCommands.handle(session, request)
                    }
                }
                commandExecutor = CommandExecutor(
                    device = AndroidDeviceControl(appContext),
                    control = control,
                    full = full,
                )
            }
        }
    }

    fun executorOrNull(): CommandExecutor? = commandExecutor

    /**
     * The relay controller owns `relay_move` and can only serve it once the foreground service and the
     * embedded relay exist, which is later than app start. It installs its handler here; the Shizuku
     * commands keep their own handler either way. Passing null means "relay not running".
     */
    fun setFullHandler(handler: FullCommandHandler?) {
        relayHandler = handler
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

    fun setMediaProjectionResult(resultCode: Int, data: Intent) {
        mediaProjectionResultCode = resultCode
        mediaProjectionData = data
        // Drop old projection if any.
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    fun clearMediaProjection() {
        mediaProjectionResultCode = null
        mediaProjectionData = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    fun hasMediaProjectionConsent(): Boolean {
        return mediaProjectionResultCode != null && mediaProjectionData != null
    }

    fun getOrCreateMediaProjection(): MediaProjection? {
        val ctx = appContext ?: return null
        val code = mediaProjectionResultCode ?: return null
        val data = mediaProjectionData ?: return null
        mediaProjection?.let { return it }
        val manager = ctx.getSystemService(MediaProjectionManager::class.java) ?: return null
        // Android 14+ needs a running foreground service of type mediaProjection before this call.
        BridgeHub.service?.enterForeground(withProjection = true)
        return try {
            val mp = manager.getMediaProjection(code, data)
            mediaProjection = mp
            mp
        } catch (_: SecurityException) {
            clearMediaProjection()
            null
        } catch (_: Exception) {
            clearMediaProjection()
            null
        }
    }
}
