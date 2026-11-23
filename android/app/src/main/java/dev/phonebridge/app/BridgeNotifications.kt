package dev.phonebridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.phonebridge.ui.ApprovalView
import dev.phonebridge.ui.NotificationContent

/**
 * The two notifications the spec requires:
 *
 * - a persistent, low-priority one that always names the bot in control (or "No bot in control")
 *   with **Stop** and **Kick everyone** actions,
 * - a high-priority, full-screen "Allow this bot?" one for a pending pairing request.
 */
object BridgeNotifications {

    const val CHANNEL_BRIDGE = "bridge"
    const val CHANNEL_APPROVAL = "approval"

    const val BRIDGE_ID = 1001
    const val APPROVAL_ID = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val bridge = NotificationChannel(
            CHANNEL_BRIDGE,
            context.getString(R.string.channel_bridge_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_bridge_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(bridge)

        val approval = NotificationChannel(
            CHANNEL_APPROVAL,
            context.getString(R.string.channel_approval_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_approval_description)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        manager.createNotificationChannel(approval)
    }

    /** The ongoing notification. [content] comes from ui-core so its wording is unit tested. */
    fun bridge(context: Context, content: NotificationContent): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            pendingFlags(),
        )
        val stop = PendingIntent.getService(
            context,
            1,
            BridgeService.intent(context, BridgeService.ACTION_STOP),
            pendingFlags(),
        )
        val kick = PendingIntent.getService(
            context,
            2,
            BridgeService.intent(context, BridgeService.ACTION_KICK_ALL),
            pendingFlags(),
        )

        val builder = Notification.Builder(context, CHANNEL_BRIDGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(context.getString(R.string.notification_subtext))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.action_stop), stop)
            .addAction(android.R.drawable.ic_delete, context.getString(R.string.action_kick_everyone), kick)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** "Allow this bot?" — name, fingerprint and requested level, with a full-screen intent. */
    fun approval(context: Context, view: ApprovalView, secondsRemaining: Long): Notification {
        val details = PendingIntent.getActivity(
            context,
            3,
            ApprovalActivity.intent(context, view.key),
            pendingFlags(),
        )
        val text = context.getString(
            R.string.approval_notification_text,
            view.botName,
            view.fingerprint,
            view.requestedLabel,
            secondsRemaining.coerceAtLeast(0L),
        )
        return Notification.Builder(context, CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.approval_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(details)
            .setFullScreenIntent(details, true)
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(10 * 60 * 1000L)
            .addAction(android.R.drawable.ic_menu_edit, context.getString(R.string.action_review), details)
            .build()
    }

    fun dismissApproval(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(APPROVAL_ID)
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
