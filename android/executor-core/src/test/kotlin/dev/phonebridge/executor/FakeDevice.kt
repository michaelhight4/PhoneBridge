package dev.phonebridge.executor

/**
 * A scriptable [DeviceControl] that records what the executor asked it to do, so the JVM tests can
 * assert on routing, coordinates and defaults without an Android device.
 */
class FakeDevice(
    override var apiLevel: Int = 33,
    override var accessibilityEnabled: Boolean = true,
) : DeviceControl {

    data class SwipeCall(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long)
    data class ScreenshotCall(val format: ScreenshotFormat, val quality: Int?, val maxWidth: Int?)

    var tree: UiNode? = Fixtures.screenTree()
    var treeCaptures: Int = 0

    val taps = mutableListOf<Pair<Int, Int>>()
    val longPresses = mutableListOf<Triple<Int, Int, Long>>()
    val swipes = mutableListOf<SwipeCall>()
    val texts = mutableListOf<Pair<String, String?>>()
    val keys = mutableListOf<KeyCommand>()
    val launches = mutableListOf<String>()
    val screenshotCalls = mutableListOf<ScreenshotCall>()
    val notificationQueries = mutableListOf<Long?>()

    var installed: List<InstalledApp> = Fixtures.installedApps()
    var launchResult: LaunchResult = LaunchResult.LAUNCHED
    var screenshotResult: Screenshot? =
        Screenshot("image/png", 1080, 2400, "iVBORw0KGgo=")
    var notificationItems: List<NotificationItem> = listOf(
        NotificationItem("com.example.chat", "Ada", "See you at 6", 1_700_000_000_000L),
    )
    var deviceStatusValue: DeviceStatus = DeviceStatus("Pixel Test", 87, true, false)

    /** When false every gesture/text/key call reports failure, to test the `internal` path. */
    var gesturesSucceed: Boolean = true

    override fun captureUiTree(): UiNode? {
        treeCaptures++
        return tree
    }

    override fun tap(x: Int, y: Int): Boolean = gesturesSucceed.also { taps += x to y }

    override fun longPress(x: Int, y: Int, durationMs: Long): Boolean =
        gesturesSucceed.also { longPresses += Triple(x, y, durationMs) }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        gesturesSucceed.also { swipes += SwipeCall(x1, y1, x2, y2, durationMs) }

    override fun setText(text: String, nodeId: String?): Boolean =
        gesturesSucceed.also { texts += text to nodeId }

    override fun performKey(key: KeyCommand): Boolean =
        gesturesSucceed.also { keys += key }

    override fun installedApps(): List<InstalledApp> = installed

    override fun launchApp(packageName: String): LaunchResult {
        launches += packageName
        return launchResult
    }

    override fun captureScreenshot(format: ScreenshotFormat, quality: Int?, maxWidth: Int?): Screenshot? {
        screenshotCalls += ScreenshotCall(format, quality, maxWidth)
        return screenshotResult
    }

    override fun readNotifications(sinceEpochMs: Long?): List<NotificationItem> {
        notificationQueries += sinceEpochMs
        return notificationItems
    }

    override fun deviceStatus(): DeviceStatus = deviceStatusValue
}

/** Shared trees and app lists for the tests. */
object Fixtures {

    fun bounds(l: Int, t: Int, r: Int, b: Int) = Bounds(l, t, r, b)

    /**
     * ```
     * n0 FrameLayout (0,0,1080,2400)
     *  n1 EditText   (100,200,980,300) editable
     *  n2 Button     (100,400,500,500) "OK" clickable
     *  n3 TextView   (0,0,10,10) "Hidden", visible = false
     *  n4 FrameLayout(600,600,1000,1000)
     *   n5 ListView  (600,600,1000,1000) scrollable
     * ```
     */
    fun screenTree(): UiNode = UiNode(
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
        InstalledApp("com.example.maps.pro", "Maps Pro"),
    )

    fun viewBot(id: String = "bot-view") = BotSession(botId = id, name = "Viewer", level = PermissionLevel.VIEW)
    fun controlBot(id: String = "bot-control") =
        BotSession(botId = id, name = "Controller", level = PermissionLevel.CONTROL)
    fun fullBot(id: String = "bot-full") = BotSession(botId = id, name = "Admin", level = PermissionLevel.FULL)
}
