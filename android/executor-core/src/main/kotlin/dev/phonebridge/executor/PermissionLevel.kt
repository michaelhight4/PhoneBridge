package dev.phonebridge.executor

/**
 * Per-bot permission level, ordered from least to most powerful (see `docs/SPEC.md`):
 *
 * - [VIEW]: screenshots, UI tree, notifications, status.
 * - [CONTROL]: View + tap, swipe, type, key, open apps.
 * - [FULL]: Control + install APKs, settings, shell, files, relay_move.
 */
enum class PermissionLevel(val wire: String, private val rank: Int) {
    VIEW("view", 0),
    CONTROL("control", 1),
    FULL("full", 2);

    /** True when a bot at this level may run commands that require [required]. */
    fun allows(required: PermissionLevel): Boolean = rank >= required.rank

    companion object {
        fun fromWire(value: String?): PermissionLevel? =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * A paired bot driving the device. [botId] is the bot's `peer_id` (see PROTOCOL.md);
 * [name] is the self-reported name shown in the notification and activity log.
 */
data class BotSession(
    val botId: String,
    val name: String,
    val level: PermissionLevel,
)
