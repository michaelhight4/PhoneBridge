package dev.phonebridge.executor

/**
 * The command table from `docs/PROTOCOL.md`, encoded so the executor can check permissions, control
 * ownership and accessibility readiness before any device call happens.
 */
enum class Command(
    val wireName: String,
    val requiredLevel: PermissionLevel,
    /** True when the bot must hold control (see [ControlArbiter]). */
    val needsControl: Boolean,
    /** True when the AccessibilityService must be enabled and connected. */
    val needsAccessibility: Boolean,
) {
    STATUS("status", PermissionLevel.VIEW, needsControl = false, needsAccessibility = false),
    SCREENSHOT("screenshot", PermissionLevel.VIEW, needsControl = false, needsAccessibility = false),
    UI_TREE("ui_tree", PermissionLevel.VIEW, needsControl = false, needsAccessibility = true),
    NOTIFICATIONS("notifications", PermissionLevel.VIEW, needsControl = false, needsAccessibility = false),

    TAP("tap", PermissionLevel.CONTROL, needsControl = true, needsAccessibility = true),
    LONG_PRESS("long_press", PermissionLevel.CONTROL, needsControl = true, needsAccessibility = true),
    SWIPE("swipe", PermissionLevel.CONTROL, needsControl = true, needsAccessibility = true),
    TYPE("type", PermissionLevel.CONTROL, needsControl = true, needsAccessibility = true),
    KEY("key", PermissionLevel.CONTROL, needsControl = true, needsAccessibility = true),
    OPEN_APP("open_app", PermissionLevel.CONTROL, needsControl = true, needsAccessibility = false),

    REQUEST_CONTROL("request_control", PermissionLevel.CONTROL, needsControl = false, needsAccessibility = false),
    RELEASE_CONTROL("release_control", PermissionLevel.CONTROL, needsControl = false, needsAccessibility = false),

    INSTALL_APK("install_apk", PermissionLevel.FULL, needsControl = true, needsAccessibility = false),
    SHELL("shell", PermissionLevel.FULL, needsControl = true, needsAccessibility = false),
    FILES("files", PermissionLevel.FULL, needsControl = true, needsAccessibility = false),
    SETTINGS("settings", PermissionLevel.FULL, needsControl = true, needsAccessibility = false),
    RELAY_MOVE("relay_move", PermissionLevel.FULL, needsControl = true, needsAccessibility = false);

    companion object {
        private val byWire = entries.associateBy { it.wireName }

        fun fromWire(cmd: String?): Command? = byWire[cmd?.trim()]
    }
}

/**
 * Values accepted by the `key` command (`{ "key": "back|home|..." }`), with the Android
 * `AccessibilityService.GLOBAL_ACTION_*` constant each one maps to.
 *
 * [ENTER] has no global action: the Android side presses the IME action / key event on the focused
 * input instead ([globalActionCode] is null).
 * [LOCK] requires `Build.VERSION.SDK_INT >= 28`.
 */
enum class KeyCommand(val wireName: String, val globalActionCode: Int?) {
    BACK("back", 1),                 // GLOBAL_ACTION_BACK
    HOME("home", 2),                 // GLOBAL_ACTION_HOME
    RECENTS("recents", 3),           // GLOBAL_ACTION_RECENTS
    NOTIFICATIONS("notifications", 4), // GLOBAL_ACTION_NOTIFICATIONS
    QUICK_SETTINGS("quick_settings", 5), // GLOBAL_ACTION_QUICK_SETTINGS
    POWER_DIALOG("power_dialog", 6), // GLOBAL_ACTION_POWER_DIALOG
    LOCK("lock", 9),                 // GLOBAL_ACTION_LOCK_SCREEN (API 28+)
    ENTER("enter", null);

    companion object {
        private val byWire = entries.associateBy { it.wireName }

        fun fromWire(value: String?): KeyCommand? = byWire[value?.trim()?.lowercase()]

        /** Lowest API level on which [command] works. */
        fun minApiLevel(command: KeyCommand): Int = when (command) {
            LOCK -> 28
            else -> 21
        }
    }
}
