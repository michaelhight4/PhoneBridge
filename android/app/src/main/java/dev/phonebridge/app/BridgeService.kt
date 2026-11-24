package dev.phonebridge.app

import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
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
 * `foregroundServiceType` is `mediaProjection|specialUse` in the manifest. The service starts as
 * specialUse only (the link and the relay for the user's own devices) and adds mediaProjection only
 * after the user grants screen capture: on Android 14+ asking for the mediaProjection type before that
 * consent throws a SecurityException, which used to crash the app on start ("keeps stopping").
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
        if (!enterForeground(withProjection = false)) {
            stopSelf()
            return
        }
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

    /**
     * Enters (or updates) the foreground state. [withProjection] adds the mediaProjection type and
     * must only be true after the user granted screen capture. Returns false instead of crashing when
     * Android refuses, and records why on the Last crash screen.
     */
    fun enterForeground(withProjection: Boolean): Boolean {
        val notification = BridgeNotifications.bridge(this, lastContent)
        return try {
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    startForeground(BridgeNotifications.BRIDGE_ID, notification, type)
                }
                Build.VERSION.SDK_INT >= 29 -> {
                    val type = if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
                    startForeground(BridgeNotifications.BRIDGE_ID, notification, type)
                }
                else -> startForeground(BridgeNotifications.BRIDGE_ID, notification)
            }
            true
        } catch (error: Exception) {
            CrashReporter.recordHandled(this, "The bridge service could not enter the foreground (projection=$withProjection)", error)
            false
        }
    }

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
