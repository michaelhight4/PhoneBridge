package dev.phonebridge.executor

/** Image formats accepted by the `screenshot` command. */
enum class ScreenshotFormat(val wire: String, val mime: String) {
    PNG("png", "image/png"),
    JPEG("jpeg", "image/jpeg");

    companion object {
        fun fromWire(value: String?): ScreenshotFormat? {
            val normalised = value?.trim()?.lowercase()
            return entries.firstOrNull { it.wire == normalised }
        }
    }
}

/** `screenshot` result payload: `{ mime, width, height, data_b64 }`. */
data class Screenshot(
    val mime: String,
    val width: Int,
    val height: Int,
    val dataBase64: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mime" to mime,
        "width" to width,
        "height" to height,
        "data_b64" to dataBase64,
    )
}

/** One `notifications` item: `{ pkg, title, text, time }`. */
data class NotificationItem(
    val pkg: String,
    val title: String?,
    val text: String?,
    val time: Long,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "pkg" to pkg,
        "title" to title,
        "text" to text,
        "time" to time,
    )
}

/** Device facts reported by `status`. */
data class DeviceStatus(
    val device: String,
    val battery: Int?,
    val screenOn: Boolean,
    val shizuku: Boolean,
)

/** An installed package as seen by the PackageManager, used by `open_app {label}`. */
data class InstalledApp(val packageName: String, val label: String)

/** Outcome of asking the PackageManager to launch a package. */
enum class LaunchResult { LAUNCHED, NOT_FOUND, NOT_LAUNCHABLE }

/**
 * The seam between the plain-Kotlin executor and the Android device.
 *
 * `BridgeAccessibilityService` (via `AndroidDeviceControl`) implements this for real; the JVM tests
 * use a fake. Nothing in this module imports `android.*`, so every branch of the executor can be
 * unit tested on a plain JDK without an Android SDK.
 *
 * Return-value contract: `false` / null means "the device refused or nothing happened"
 * (`internal`); the executor has already validated arguments, permissions and control by then.
 */
interface DeviceControl {

    /** `Build.VERSION.SDK_INT` of the device (0 in tests). Used for API-gated commands (`lock`). */
    val apiLevel: Int

    /** True when the AccessibilityService is enabled in Settings and connected. */
    val accessibilityEnabled: Boolean

    /** Snapshot of the active window as a plain tree, or null when no window could be read. */
    fun captureUiTree(): UiNode?

    fun tap(x: Int, y: Int): Boolean

    fun longPress(x: Int, y: Int, durationMs: Long): Boolean

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    /**
     * Type [text] into [nodeId] (from the last `ui_tree`) or into the focused editable node when
     * null. The Android implementation uses `ACTION_SET_TEXT`, with a clipboard-paste fallback.
     */
    fun setText(text: String, nodeId: String?): Boolean

    /** Global action / IME key, see [KeyCommand]. */
    fun performKey(key: KeyCommand): Boolean

    /** Every launchable package with its visible label. */
    fun installedApps(): List<InstalledApp>

    /** Start [packageName]'s launcher activity. */
    fun launchApp(packageName: String): LaunchResult

    /** Accessibility screenshot (`takeScreenshot`, API 30+) or MediaProjection once available. */
    fun captureScreenshot(format: ScreenshotFormat, quality: Int?, maxWidth: Int?): Screenshot?

    /** True when a MediaProjection capture path is granted/ready (separate from accessibility). */
    fun mediaProjectionReady(): Boolean = false

    /** Notifications posted at or after [sinceEpochMs] (all of them when null). */
    fun readNotifications(sinceEpochMs: Long?): List<NotificationItem>

    /** Battery, screen state and Shizuku availability for `status`. */
    fun deviceStatus(): DeviceStatus
}
