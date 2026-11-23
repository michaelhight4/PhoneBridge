package dev.phonebridge.ui

/** The persistent notification's title and text, independent of Android so it can be unit tested. */
data class NotificationContent(val title: String, val text: String)

/**
 * Text for the always-visible foreground notification. The spec requires it to name the bot that is
 * in control, or say plainly that nobody is:
 *
 * - `Alpha is in control`
 * - `No bot in control`
 *
 * Waiting bots are appended without hiding the controller: `Alpha is in control · 2 waiting`.
 */
object ControllerNotifications {
    const val TITLE = "PhoneBridge"
    const val NO_BOT = "No bot in control"

    fun text(controllerName: String?, waitingCount: Int = 0): String {
        val base = controllerName?.takeIf { it.isNotBlank() }?.let { "$it is in control" } ?: NO_BOT
        return if (waitingCount > 0) "$base · $waitingCount waiting" else base
    }

    fun content(controllerName: String?, waitingCount: Int = 0): NotificationContent =
        NotificationContent(TITLE, text(controllerName, waitingCount))
}
