package dev.phonebridge.e2e

import dev.phonebridge.executor.Bounds
import dev.phonebridge.executor.DeviceControl
import dev.phonebridge.executor.DeviceStatus
import dev.phonebridge.executor.InstalledApp
import dev.phonebridge.executor.KeyCommand
import dev.phonebridge.executor.LaunchResult
import dev.phonebridge.executor.NotificationItem
import dev.phonebridge.executor.Screenshot
import dev.phonebridge.executor.ScreenshotFormat
import dev.phonebridge.executor.UiNode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The fake phone's device seam: a scriptable [DeviceControl] that records everything the executor
 * asked it to do, so the e2e test can prove a request really reached the device (and that a rejected
 * one did not).
 *
 * It mirrors `android/executor-core`'s test fake, but lives in main sources so the harness and
 * [FakePhoneServer] share one implementation.
 */
class FakePhoneDevice(
    override var apiLevel: Int = 33,
    override var accessibilityEnabled: Boolean = true,
) : DeviceControl {

    data class SwipeCall(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long)
    data class ScreenshotCall(val format: ScreenshotFormat, val quality: Int?, val maxWidth: Int?)

    var tree: UiNode = Screen.tree()
    var screenshotResult: Screenshot = Screenshot(PNG_1X1_MIME, 1080, 2400, PNG_1X1_B64)
    var notificationItems: List<NotificationItem> = Screen.notifications()
    var deviceStatusValue: DeviceStatus = DeviceStatus(DEVICE_NAME, 88, true, false)
    var gesturesSucceed: Boolean = true

    val taps = CopyOnWriteArrayList<Pair<Int, Int>>()
    val longPresses = CopyOnWriteArrayList<Triple<Int, Int, Long>>()
    val swipes = CopyOnWriteArrayList<SwipeCall>()
    val texts = CopyOnWriteArrayList<Pair<String, String?>>()
    val keys = CopyOnWriteArrayList<KeyCommand>()
    val launches = CopyOnWriteArrayList<String>()
    val screenshotCalls = CopyOnWriteArrayList<ScreenshotCall>()

    override fun captureUiTree(): UiNode = tree

    override fun tap(x: Int, y: Int): Boolean {
        taps += x to y
        return gesturesSucceed
    }

    override fun longPress(x: Int, y: Int, durationMs: Long): Boolean {
        longPresses += Triple(x, y, durationMs)
        return gesturesSucceed
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        swipes += SwipeCall(x1, y1, x2, y2, durationMs)
        return gesturesSucceed
    }

    override fun setText(text: String, nodeId: String?): Boolean {
        texts += text to nodeId
        return gesturesSucceed
    }

    override fun performKey(key: KeyCommand): Boolean {
        keys += key
        return gesturesSucceed
    }

    override fun installedApps(): List<InstalledApp> = Screen.installedApps()

    override fun launchApp(packageName: String): LaunchResult {
        launches += packageName
        return if (Screen.installedApps().any { it.packageName == packageName }) {
            LaunchResult.LAUNCHED
        } else {
            LaunchResult.NOT_FOUND
        }
    }

    override fun captureScreenshot(format: ScreenshotFormat, quality: Int?, maxWidth: Int?): Screenshot? {
        screenshotCalls += ScreenshotCall(format, quality, maxWidth)
        // The fake returns the same 1x1 image for every format, with the mime the bot asked for.
        return screenshotResult.copy(mime = format.mime)
    }

    override fun readNotifications(sinceEpochMs: Long?): List<NotificationItem> =
        notificationItems.filter { sinceEpochMs == null || it.time >= sinceEpochMs }

    override fun deviceStatus(): DeviceStatus = deviceStatusValue

    companion object {
        const val DEVICE_NAME = "Fake Pixel 9"
        /** A 1x1 transparent PNG, small enough to inline in a single response. */
        const val PNG_1X1_MIME = "image/png"
        const val PNG_1X1_B64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
    }
}

/** The fake phone's screen content: an editable field, an OK button and a list. */
object Screen {

    fun bounds(l: Int, t: Int, r: Int, b: Int) = Bounds(l, t, r, b)

    /**
     * ```
     * 0   FrameLayout (0,0,1080,2400)
     *  0.0 EditText   (100,200,980,300)  editable
     *  0.1 Button     (100,400,500,500)  "OK", clickable
     *  0.2 TextView   (0,0,10,10)        "Hidden", not visible
     *  0.3 FrameLayout(600,600,1000,1000)
     *   0.3.0 ListView (600,600,1000,1000)
     * ```
     */
    fun tree(): UiNode = UiNode(
        className = "android.widget.FrameLayout",
        bounds = bounds(0, 0, 1080, 2400),
        children = listOf(
            UiNode(
                className = "android.widget.EditText",
                text = "",
                bounds = bounds(100, 200, 980, 300),
                focusable = true,
                editable = true,
            ),
            UiNode(
                className = "android.widget.Button",
                text = "OK",
                contentDescription = "Confirm",
                resourceId = "com.example:id/ok",
                bounds = bounds(100, 400, 500, 500),
                clickable = true,
                focusable = true,
            ),
            UiNode(
                className = "android.widget.TextView",
                text = "Hidden",
                bounds = bounds(0, 0, 10, 10),
                visible = false,
            ),
            UiNode(
                className = "android.widget.FrameLayout",
                bounds = bounds(600, 600, 1000, 1000),
                children = listOf(
                    UiNode(
                        className = "android.widget.ListView",
                        bounds = bounds(600, 600, 1000, 1000),
                        scrollable = true,
                    ),
                ),
            ),
        ),
    )

    fun installedApps(): List<InstalledApp> = listOf(
        InstalledApp("com.example.chat", "Chat"),
        InstalledApp("com.example.settings", "Settings"),
        InstalledApp("com.example.maps", "Maps"),
    )

    fun notifications(): List<NotificationItem> = listOf(
        NotificationItem("com.example.chat", "Ada", "See you at 6", 1_700_000_000_000L),
    )

    /** The centre of node `0.1` in [tree], i.e. where `tap {node_id:"0.1"}` must land. */
    val okButtonCenter: Pair<Int, Int> get() = 300 to 450
}
