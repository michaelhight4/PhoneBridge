package dev.phonebridge.app

import android.content.Context
import dev.phonebridge.ui.BotListController
import dev.phonebridge.ui.LiveSessions
import dev.phonebridge.ui.RelayController

/**
 * A tiny service locator. The foreground service owns the live state (relay connection, pending
 * approvals, control arbiter); screens reach it through here. When the service is not running the
 * bot list and the activity log still work from their JSON files, which is what a user expects from
 * a Bluetooth-style list.
 */
object BridgeHub {

    @Volatile
    var service: BridgeService? = null

    val isRunning: Boolean get() = service != null

    fun controller(): BridgeController? = service?.controllerOrNull()

    fun botList(context: Context): BotListController =
        controller()?.botList ?: BotListController(
            registry = AppStores.registry(context),
            sessions = NoSessions,
            log = AppStores.activityLog(context),
        )

    fun relay(context: Context): RelayController =
        controller()?.relayController ?: RelayController(
            store = AppStores.relaySettings(context),
            heart = OfflineHeart,
            log = AppStores.activityLog(context),
        )

    /** No live sessions when the service is stopped. */
    private object NoSessions : LiveSessions {
        override fun isConnected(botId: String) = false
        override fun isController(botId: String) = false
        override fun queuePosition(botId: String): Int? = null
        override fun controllerId(): String? = null
    }

    /**
     * Lets the Relay screen keep a remote URL choice while the bridge is stopped. Starting the heart
     * on this phone needs the foreground service, so that action reports a clear error instead.
     */
    private object OfflineHeart : dev.phonebridge.ui.HeartControl {
        override fun startHeartHere(): String =
            throw IllegalStateException("Start PhoneBridge first, then move the heart here")

        override fun stopHeartHere() = Unit

        override fun useRelay(url: String, tlsPin: String?): Boolean = true
    }
}
