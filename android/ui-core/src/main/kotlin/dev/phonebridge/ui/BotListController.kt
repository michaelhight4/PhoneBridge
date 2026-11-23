package dev.phonebridge.ui

import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.pairing.BotRegistry
import dev.phonebridge.core.pairing.BotRecord

/** One row of the Bluetooth-style bot list. */
data class BotRow(
    val botId: String,
    val name: String,
    val fingerprint: String,
    val pairedAt: Long,
    val lastSeen: Long,
    val lastSeenText: String,
    val enabled: Boolean,
    val level: PermissionLevel,
    val connected: Boolean,
    val inControl: Boolean,
    /** 1-based queue position while waiting for control, else null. */
    val queuePosition: Int?,
)

/**
 * The bot list screen: name, fingerprint, last seen, on/off switch, permission level and unpair.
 * Changes are persisted through the `:core` [BotRegistry] (the JSON-file one in the app), and the
 * service is told to drop the live session when a bot is switched off or unpaired.
 */
class BotListController(
    private val registry: BotRegistry,
    private val sessions: LiveSessions,
    private val log: ActivityLog,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The service closes this bot's session and sends it a `kicked`/disabled event. */
    private val onBotDisabled: (String) -> Unit = {},
    private val onBotUnpaired: (String) -> Unit = {},
) {

    fun rows(): List<BotRow> {
        val now = clock()
        return registry.list().map { record ->
            BotRow(
                botId = record.botId,
                name = record.name,
                fingerprint = record.fingerprint,
                pairedAt = record.pairedAt,
                lastSeen = record.lastSeen,
                lastSeenText = lastSeenText(record.lastSeen, now),
                enabled = record.enabled,
                level = record.level,
                connected = sessions.isConnected(record.botId),
                inControl = sessions.isController(record.botId),
                queuePosition = sessions.queuePosition(record.botId),
            )
        }
    }

    fun find(botId: String): BotRow? = rows().firstOrNull { it.botId == botId }

    /** The user's on/off switch. Switching off closes any live session immediately. */
    fun setEnabled(botId: String, enabled: Boolean): Boolean {
        val record = registry.get(botId) ?: return false
        if (record.enabled == enabled) return true
        if (!registry.setEnabled(botId, enabled)) return false
        if (!enabled) onBotDisabled(botId)
        log.record(
            botId,
            record.name,
            if (enabled) "bot_enabled" else "bot_disabled",
            true,
            message = if (enabled) "Allowed to connect again" else "Switched off; its live session was closed",
        )
        return true
    }

    /** Change the permission level; lowering takes effect on the bot's next command. */
    fun setLevel(botId: String, level: PermissionLevel): Boolean {
        val record = registry.get(botId) ?: return false
        if (record.level == level) return true
        if (!registry.setLevel(botId, level)) return false
        log.record(
            botId,
            record.name,
            "level_changed",
            true,
            message = "Now ${level.wire} (was ${record.level.wire})",
        )
        return true
    }

    /**
     * Unpair one bot. Its key is removed and its session closed; every other bot keeps working.
     */
    fun unpair(botId: String): Boolean {
        val record: BotRecord = registry.get(botId) ?: return false
        val removed = registry.remove(botId)
        if (!removed) return false
        onBotUnpaired(botId)
        log.record(botId, record.name, "unpair", true, message = "Removed from this phone")
        return true
    }

    fun isEmpty(): Boolean = registry.list().isEmpty()

    companion object {
        /** Short relative time for the list; `never` before the first connection. */
        fun lastSeenText(lastSeen: Long, now: Long = System.currentTimeMillis()): String {
            if (lastSeen <= 0L) return "never"
            val elapsed = (now - lastSeen).coerceAtLeast(0L)
            return when {
                elapsed < 60_000L -> "just now"
                elapsed < 3_600_000L -> "${elapsed / 60_000L} min ago"
                elapsed < 86_400_000L -> "${elapsed / 3_600_000L} h ago"
                else -> "${elapsed / 86_400_000L} d ago"
            }
        }
    }
}
