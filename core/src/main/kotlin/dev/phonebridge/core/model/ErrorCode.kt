package dev.phonebridge.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Error codes from docs/PROTOCOL.md, plus the keep-alive ones the app layer can emit. */
@Serializable
enum class ErrorCode(val wire: String) {
    @SerialName("permission_denied")
    PERMISSION_DENIED("permission_denied"),

    @SerialName("not_controller")
    NOT_CONTROLLER("not_controller"),

    @SerialName("busy")
    BUSY("busy"),

    @SerialName("bad_args")
    BAD_ARGS("bad_args"),

    @SerialName("unknown_cmd")
    UNKNOWN_CMD("unknown_cmd"),

    @SerialName("peer_offline")
    PEER_OFFLINE("peer_offline"),

    @SerialName("accessibility_disabled")
    ACCESSIBILITY_DISABLED("accessibility_disabled"),

    @SerialName("projection_denied")
    PROJECTION_DENIED("projection_denied"),

    @SerialName("shizuku_unavailable")
    SHIZUKU_UNAVAILABLE("shizuku_unavailable"),

    @SerialName("timeout")
    TIMEOUT("timeout"),

    @SerialName("internal")
    INTERNAL("internal"),
    ;

    companion object {
        /** Tolerant lookup so an unknown code from a newer peer becomes null instead of an exception. */
        fun fromWire(text: String): ErrorCode? = entries.firstOrNull { it.wire == text }
    }
}
