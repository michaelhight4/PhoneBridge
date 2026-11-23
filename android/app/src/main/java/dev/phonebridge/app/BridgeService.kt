package dev.phonebridge.app

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.phonebridge.ui.ControllerNotifications
import dev.phonebridge.ui.NotificationContent

/**
 * The bridge foreground service.
 *
 * It holds the encrypted outbound connection to the chosen relay (Wi-Fi or mobile data), routes
 * decrypted requests to the accessibility executor, hosts the embedded relay when this phone is the
 * chosen heart, and keeps the "who is in control" notification alive through Doze.
 *
 * `foregroundServiceType` is `connectedDevice|specialUse` in the manifest: connectedDevice covers
 * the link to the paired bot, specialUse covers running the relay for the user's own devices.
 */
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "dev.phonebridge.app.action.START"
        const val ACTION_STOP = "dev.phonebridge.app.action.STOP"
        const val ACTION_KICK_ALL = "dev.phonebridge.app.action.KICK_ALL"

        fun intent(context: Context, action: String): Intent =
            Intent(context, BridgeService::class.java).setAction(action)

        /** Starts (or restarts) the bridge. Safe to call from any activity. */
        fun start(context: Context) {
            val intent = intent(context, ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(intent(context, ACTION_STOP))
        }
    }

    @Volatile
    private var bridge: BridgeController? = null

    @Volatile
    private var lastContent: NotificationContent = ControllerNotifications.content(null)

    fun controllerOrNull(): BridgeController? = bridge

    override fun onCreate() {
        super.onCreate()
        BridgeNotifications.createChannels(this)
        BridgeRuntime.init(this)

        val controller = BridgeController(this) { updateNotification() }
        bridge = controller
        BridgeHub.service = this

        // The service must enter the foreground within a few seconds of starting; do that before the
        // relay connection work so the system never kills a slow start.
        startForeground(
            BridgeNotifications.BRIDGE_ID,
            BridgeNotifications.bridge(this, lastContent),
        )
        controller.start()
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                bridge?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_KICK_ALL -> {
                bridge?.kickEveryone()
                updateNotification()
            }
            ACTION_START, null -> {
                bridge?.start()
                updateNotification()
            }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bridge?.stop()
        bridge = null
        if (BridgeHub.service === this) BridgeHub.service = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Mirrors the arbiter state into the persistent notification (phone in control changes). */
    private fun updateNotification() {
        val controller = bridge ?: return
        val content = controller.bridgeState.notificationContent()
        lastContent = content
        getSystemService(NotificationManager::class.java)?.notify(
            BridgeNotifications.BRIDGE_ID,
            BridgeNotifications.bridge(this, content),
        )
    }
}
