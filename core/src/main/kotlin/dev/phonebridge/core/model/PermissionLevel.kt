package dev.phonebridge.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Permission levels are strictly ordered: `VIEW < CONTROL < FULL`.
 *
 * - **View**: screenshots, UI tree, notifications, status.
 * - **Control**: View plus taps, swipes, typing, keys, opening apps and holding control.
 * - **Full**: Control plus installing APKs, shell, files, settings and moving the relay.
 */
@Serializable
enum class PermissionLevel(val rank: Int) {
    @SerialName("view")
    VIEW(0),

    @SerialName("control")
    CONTROL(1),

    @SerialName("full")
    FULL(2),
    ;

    /** True when a bot at this level may run something that requires [required]. */
    fun allows(required: PermissionLevel): Boolean = rank >= required.rank

    val wire: String
        get() = when (this) {
            VIEW -> "view"
            CONTROL -> "control"
            FULL -> "full"
        }

    companion object {
        /** Parses `view`/`control`/`full` case-insensitively; returns null for anything else. */
        fun parse(text: String): PermissionLevel? = when (text.trim().lowercase()) {
            "view" -> VIEW
            "control" -> CONTROL
            "full" -> FULL
            else -> null
        }

        fun parseOrThrow(text: String): PermissionLevel =
            parse(text) ?: throw IllegalArgumentException("unknown permission level: $text")

        /** The lowest of two levels, used when the user lowers a bot's request. */
        fun min(a: PermissionLevel, b: PermissionLevel): PermissionLevel = if (a.rank <= b.rank) a else b
    }
}
