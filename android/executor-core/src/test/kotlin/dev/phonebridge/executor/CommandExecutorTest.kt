package dev.phonebridge.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandExecutorTest {

    private val device = FakeDevice()
    private val executor = CommandExecutor(device)

    private fun run(
        session: BotSession = Fixtures.controlBot(),
        cmd: String,
        vararg args: Pair<String, Any?>,
    ): Response = executor.execute(session, Request(id = "req-1", cmd = cmd, args = mapOf(*args)))

    private fun takeControl(session: BotSession = Fixtures.controlBot()) {
        val response = run(session, "request_control")
        assertTrue(response.ok, "expected control to be granted: ${response.error}")
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    fun `unknown command is rejected`() {
        val response = run(cmd = "launch_missiles")
        assertFalse(response.ok)
        assertEquals(ErrorCode.UNKNOWN_CMD, response.error?.code)
        assertEquals("req-1", response.id)
    }

    @Test
    fun `view bot cannot use control commands`() {
        val response = run(Fixtures.viewBot(), "tap", "x" to 1, "y" to 2)
        assertFalse(response.ok)
        assertEquals(ErrorCode.PERMISSION_DENIED, response.error?.code)
        assertEquals("needs Control", response.error?.message)
        assertTrue(device.taps.isEmpty())
    }

    @Test
    fun `control bot cannot use full commands`() {
        val response = run(Fixtures.controlBot(), "shell", "command" to "id")
        assertFalse(response.ok)
        assertEquals(ErrorCode.PERMISSION_DENIED, response.error?.code)
        assertEquals("needs Full", response.error?.message)
    }

    @Test
    fun `control command without control is not_controller`() {
        val response = run(Fixtures.controlBot(), "tap", "x" to 10, "y" to 20)
        assertFalse(response.ok)
        assertEquals(ErrorCode.NOT_CONTROLLER, response.error?.code)
        assertTrue(device.taps.isEmpty())
    }

    @Test
    fun `accessibility commands fail when the service is off`() {
        device.accessibilityEnabled = false
        takeControl()
        val uiTree = run(Fixtures.controlBot(), "ui_tree")
        assertEquals(ErrorCode.ACCESSIBILITY_DISABLED, uiTree.error?.code)

        val tap = run(Fixtures.controlBot(), "tap", "x" to 10, "y" to 20)
        assertEquals(ErrorCode.ACCESSIBILITY_DISABLED, tap.error?.code)
        assertTrue(device.taps.isEmpty())
    }

    @Test
    fun `status reports device, controller and queue`() {
        val response = run(Fixtures.viewBot(), "status")
        assertTrue(response.ok)
        val result = requireNotNull(response.result)
        assertEquals("Pixel Test", result["device"])
        assertEquals(87, result["battery"])
        assertEquals(true, result["screen_on"])
        assertNull(result["controller"])
        assertEquals(emptyList<String>(), result["queue"])
        assertEquals(false, result["shizuku"])

        takeControl()
        val afterGrant = requireNotNull(run(Fixtures.viewBot(), "status").result)
        assertEquals("bot-control", afterGrant["controller"])
    }

    // ------------------------------------------------------------------- ui_tree

    @Test
    fun `ui_tree exports protocol nodes with stable ids`() {
        val response = run(Fixtures.viewBot(), "ui_tree")
        assertTrue(response.ok, "ui_tree failed: ${response.error}")

        val root = requireNotNull(response.result!!["root"] as? Map<*, *>)
        assertEquals("0", root["id"])
        assertEquals("android.widget.FrameLayout", root["cls"])
        assertEquals(listOf(0, 0, 1080, 2400), root["bounds"])

        // visible_only defaults to true (docs/PROTOCOL.md 6.1), so the hidden node is absent while
        // the ids of the remaining nodes stay the structural paths the bot can tap.
        val children = requireNotNull(root["children"] as? List<*>)
        assertEquals(3, children.size)
        val edit = requireNotNull(children[0] as? Map<*, *>)
        assertEquals("0.0", edit["id"])
        assertEquals(true, edit["editable"])
        val button = requireNotNull(children[1] as? Map<*, *>)
        assertEquals("0.1", button["id"])
        assertEquals("OK", button["text"])
        assertEquals("Confirm", button["desc"])
        assertEquals("com.example:id/ok", button["res_id"])
        assertEquals(true, button["clickable"])

        // Nested node keeps its pre-order id.
        val nested = requireNotNull(children[2] as? Map<*, *>)
        val list = requireNotNull((nested["children"] as? List<*>)?.first() as? Map<*, *>)
        assertEquals("0.3.0", list["id"])

        // Ids are repeatable for the same screen.
        val second = run(Fixtures.viewBot(), "ui_tree")
        val secondRoot = requireNotNull(second.result!!["root"] as? Map<*, *>)
        assertEquals(root["id"], secondRoot["id"])
        assertEquals(5, executor.lastSnapshot()?.size)
    }

    @Test
    fun `ui_tree honours max_depth and visible_only`() {
        val shallow = run(Fixtures.viewBot(), "ui_tree", "max_depth" to 1)
        val root = requireNotNull(shallow.result!!["root"] as? Map<*, *>)
        val children = requireNotNull(root["children"] as? List<*>)
        val firstChild = requireNotNull(children[0] as? Map<*, *>)
        assertEquals(emptyList<Any>(), firstChild["children"])

        val visible = run(Fixtures.viewBot(), "ui_tree", "visible_only" to true)
        val visibleRoot = requireNotNull(visible.result!!["root"] as? Map<*, *>)
        assertEquals(3, (visibleRoot["children"] as? List<*>)?.size) // hidden node dropped
        assertEquals(5, executor.lastSnapshot()?.size) // n0, n1, n2, n4, n5

        val everything = run(Fixtures.viewBot(), "ui_tree", "visible_only" to false)
        val allRoot = requireNotNull(everything.result!!["root"] as? Map<*, *>)
        assertEquals(4, (allRoot["children"] as? List<*>)?.size)
        assertEquals(6, executor.lastSnapshot()?.size)

        val tooDeep = run(Fixtures.viewBot(), "ui_tree", "max_depth" to 0)
        assertFalse(tooDeep.ok)
        assertEquals(dev.phonebridge.executor.ErrorCode.BAD_ARGS, tooDeep.error?.code)
    }

    @Test
    fun `ui_tree returns internal when the window cannot be read`() {
        device.tree = null
        val response = run(Fixtures.viewBot(), "ui_tree")
        assertFalse(response.ok)
        assertEquals(ErrorCode.INTERNAL, response.error?.code)
    }

    // ---------------------------------------------------------------------- tap

    @Test
    fun `tap by coordinates is routed to the device`() {
        takeControl()
        val response = run(cmd = "tap", args = arrayOf("x" to 540, "y" to 1200))
        assertTrue(response.ok, "${response.error}")
        assertEquals(listOf(540 to 1200), device.taps)
    }

    @Test
    fun `tap accepts numeric strings and doubles`() {
        takeControl()
        val response = run(cmd = "tap", args = arrayOf("x" to "540", "y" to 1200.0))
        assertTrue(response.ok)
        assertEquals(listOf(540 to 1200), device.taps)
    }

    @Test
    fun `tap by node id taps the node centre`() {
        run(Fixtures.viewBot(), "ui_tree")
        takeControl()
        val response = run(cmd = "tap", args = arrayOf("node_id" to "0.1"))
        assertTrue(response.ok, "${response.error}")
        assertEquals(listOf(300 to 450), device.taps) // (100,400,500,500) centre
    }

    @Test
    fun `tap with an unknown node id is bad_args`() {
        run(Fixtures.viewBot(), "ui_tree")
        takeControl()
        val response = run(cmd = "tap", args = arrayOf("node_id" to "9.9"))
        assertFalse(response.ok)
        assertEquals(ErrorCode.BAD_ARGS, response.error?.code)
        assertTrue(device.taps.isEmpty())
    }

    @Test
    fun `tap without coordinates is bad_args`() {
        takeControl()
        val response = run(cmd = "tap", args = arrayOf("x" to 1))
        assertFalse(response.ok)
        assertEquals(ErrorCode.BAD_ARGS, response.error?.code)
        assertTrue(device.taps.isEmpty())
    }

    @Test
    fun `a refused gesture is internal`() {
        takeControl()
        device.gesturesSucceed = false
        val response = run(cmd = "tap", args = arrayOf("x" to 1, "y" to 2))
        assertFalse(response.ok)
        assertEquals(ErrorCode.INTERNAL, response.error?.code)
    }

    // --------------------------------------------------- long_press, swipe, type

    @Test
    fun `long_press uses the default duration and honours ms`() {
        takeControl()
        assertTrue(run(cmd = "long_press", args = arrayOf("x" to 10, "y" to 20)).ok)
        assertTrue(run(cmd = "long_press", args = arrayOf("x" to 30, "y" to 40, "ms" to 1200L)).ok)
        assertEquals(
            listOf(
                Triple(10, 20, CommandExecutor.DEFAULT_LONG_PRESS_MS),
                Triple(30, 40, 1200L),
            ),
            device.longPresses,
        )
    }

    @Test
    fun `swipe is routed with coordinates and duration`() {
        takeControl()
        val response = run(cmd = "swipe", args = arrayOf("x1" to 100, "y1" to 1000, "x2" to 900, "y2" to 200))
        assertTrue(response.ok, "${response.error}")
        assertEquals(
            listOf(FakeDevice.SwipeCall(100, 1000, 900, 200, CommandExecutor.DEFAULT_SWIPE_MS)),
            device.swipes,
        )
    }

    @Test
    fun `swipe with identical points is bad_args`() {
        takeControl()
        val response = run(cmd = "swipe", args = arrayOf("x1" to 5, "y1" to 5, "x2" to 5, "y2" to 5))
        assertEquals(ErrorCode.BAD_ARGS, response.error?.code)
        assertTrue(device.swipes.isEmpty())
    }

    @Test
    fun `type targets the focused node or the given node id`() {
        run(Fixtures.viewBot(), "ui_tree")
        takeControl()
        assertTrue(run(cmd = "type", args = arrayOf("text" to "hello")).ok)
        assertTrue(run(cmd = "type", args = arrayOf("text" to "hi", "node_id" to "0.0")).ok)
        assertTrue(run(cmd = "type", args = arrayOf("text" to "")).ok) // clearing a field
        assertEquals(
            listOf("hello" to null, "hi" to "0.0", "" to null),
            device.texts,
        )
    }

    @Test
    fun `type needs a text argument`() {
        takeControl()
        val response = run(cmd = "type")
        assertEquals(ErrorCode.BAD_ARGS, response.error?.code)
        assertTrue(device.texts.isEmpty())
    }

    // ---------------------------------------------------------------------- key

    @Test
    fun `key routes every documented global action`() {
        takeControl()
        for (key in listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "enter")) {
            val response = run(cmd = "key", args = arrayOf("key" to key))
            assertTrue(response.ok, "key '$key' failed: ${response.error}")
        }
        assertEquals(
            listOf(
                KeyCommand.BACK,
                KeyCommand.HOME,
                KeyCommand.RECENTS,
                KeyCommand.NOTIFICATIONS,
                KeyCommand.QUICK_SETTINGS,
                KeyCommand.POWER_DIALOG,
                KeyCommand.ENTER,
            ),
            device.keys,
        )
    }

    @Test
    fun `key lock is allowed from API 28`() {
        takeControl()
        val onPie = run(cmd = "key", args = arrayOf("key" to "lock"))
        assertTrue(onPie.ok, "${onPie.error}")
        assertEquals(listOf(KeyCommand.LOCK), device.keys)

        device.apiLevel = 26
        val onOreo = run(cmd = "key", args = arrayOf("key" to "lock"))
        assertEquals(ErrorCode.BAD_ARGS, onOreo.error?.code)
        assertEquals(1, device.keys.size)
    }

    @Test
    fun `key rejects unknown names`() {
        takeControl()
        val response = run(cmd = "key", args = arrayOf("key" to "volume_up"))
        assertEquals(ErrorCode.BAD_ARGS, response.error?.code)
        assertTrue(device.keys.isEmpty())
    }

    // ----------------------------------------------------------------- open_app

    @Test
    fun `open_app by package launches it`() {
        takeControl()
        val response = run(cmd = "open_app", args = arrayOf("package" to "com.example.maps"))
        assertTrue(response.ok, "${response.error}")
        assertEquals(listOf("com.example.maps"), device.launches)
    }

    @Test
    fun `open_app by label resolves the package`() {
        takeControl()
        val response = run(cmd = "open_app", args = arrayOf("label" to "chat"))
        assertTrue(response.ok, "${response.error}")
        assertEquals(listOf("com.example.chat"), device.launches)
    }

    @Test
    fun `open_app with an ambiguous label is bad_args`() {
        takeControl()
        val response = run(cmd = "open_app", args = arrayOf("label" to "Ma"))
        assertEquals(ErrorCode.BAD_ARGS, response.error?.code)
        assertTrue(response.error!!.message.contains("com.example.maps.pro"))
        assertTrue(device.launches.isEmpty())
    }

    @Test
    fun `open_app with an unknown label is bad_args`() {
        takeControl()
        val response = run(cmd = "open_app", args = arrayOf("label" to "nothing here"))
        assertEquals(ErrorCode.BAD_ARGS, response.error?.code)
    }

    @Test
    fun `open_app needs a package or a label`() {
        takeControl()
        val response = run(cmd = "open_app")
        assertEquals(ErrorCode.BAD_ARGS, response.error?.code)
    }

    @Test
    fun `open_app maps a missing or unlaunchable package`() {
        takeControl()
        device.launchResult = LaunchResult.NOT_FOUND
        assertEquals(ErrorCode.BAD_ARGS, run(cmd = "open_app", args = arrayOf("package" to "x.y")).error?.code)

        device.launchResult = LaunchResult.NOT_LAUNCHABLE
        assertEquals(ErrorCode.INTERNAL, run(cmd = "open_app", args = arrayOf("package" to "x.y")).error?.code)
    }

    // --------------------------------------------------------------- screenshot

    @Test
    fun `screenshot uses the accessibility screenshot API`() {
        val response = run(Fixtures.viewBot(), "screenshot", "format" to "jpeg", "quality" to 70, "max_width" to 720)
        assertTrue(response.ok, "${response.error}")
        assertEquals(FakeDevice.ScreenshotCall(ScreenshotFormat.JPEG, 70, 720), device.screenshotCalls.single())
        val result = requireNotNull(response.result)
        assertEquals("image/png", result["mime"])
        assertEquals(1080, result["width"])
        assertEquals(2400, result["height"])
        assertEquals("iVBORw0KGgo=", result["data_b64"])
    }

    @Test
    fun `screenshot defaults to png and rejects bad quality or format`() {
        assertTrue(run(Fixtures.viewBot(), "screenshot").ok)
        assertEquals(ScreenshotFormat.PNG, device.screenshotCalls.first().format)

        assertEquals(ErrorCode.BAD_ARGS, run(Fixtures.viewBot(), "screenshot", "quality" to 0).error?.code)
        assertEquals(ErrorCode.BAD_ARGS, run(Fixtures.viewBot(), "screenshot", "format" to "gif").error?.code)
    }

    @Test
    fun `screenshot needs accessibility or projection consent`() {
        device.apiLevel = 29
        val older = run(Fixtures.viewBot(), "screenshot")
        assertEquals(ErrorCode.PROJECTION_DENIED, older.error?.code)

        device.apiLevel = 33
        device.accessibilityEnabled = false
        val disabled = run(Fixtures.viewBot(), "screenshot")
        assertEquals(ErrorCode.ACCESSIBILITY_DISABLED, disabled.error?.code)
        assertTrue(device.screenshotCalls.isEmpty())
    }

    // ------------------------------------------------------------ notifications

    @Test
    fun `notifications maps items and filters by since`() {
        val response = run(Fixtures.viewBot(), "notifications", "since" to 1_600_000_000_000L)
        assertTrue(response.ok, "${response.error}")
        assertEquals(listOf<Long?>(1_600_000_000_000L), device.notificationQueries)
        val items = requireNotNull(response.result!!["items"] as? List<*>)
        val first = requireNotNull(items.first() as? Map<*, *>)
        assertEquals("com.example.chat", first["pkg"])
        assertEquals("Ada", first["title"])
        assertEquals("See you at 6", first["text"])
        assertEquals(1_700_000_000_000L, first["time"])
    }

    // -------------------------------------------------------- control arbitration

    @Test
    fun `request_control queues behind the current controller`() {
        takeControl(Fixtures.controlBot("bot-a"))
        val queued = executor.execute(
            Fixtures.controlBot("bot-b"),
            Request("req-2", "request_control"),
        )
        assertTrue(queued.ok)
        assertEquals(false, queued.result?.get("granted"))
        assertEquals(1, queued.result?.get("position"))

        val second = executor.execute(Fixtures.controlBot("bot-a"), Request("req-3", "request_control"))
        assertEquals(true, second.result?.get("granted"))
    }

    @Test
    fun `release_control frees control for the next bot`() {
        takeControl(Fixtures.controlBot("bot-a"))
        executor.execute(Fixtures.controlBot("bot-b"), Request("req-2", "request_control"))

        val released = executor.execute(Fixtures.controlBot("bot-a"), Request("req-3", "release_control"))
        assertTrue(released.ok, "${released.error}")

        val result = executor.execute(Fixtures.controlBot("bot-b"), Request("req-4", "tap", mapOf("x" to 1, "y" to 2)))
        assertTrue(result.ok, "${result.error}")
    }

    @Test
    fun `release_control by a non-controller is not_controller`() {
        val response = executor.execute(Fixtures.controlBot("bot-z"), Request("req-9", "release_control"))
        assertEquals(ErrorCode.NOT_CONTROLLER, response.error?.code)
    }

    @Test
    fun `view bots cannot request control`() {
        val response = executor.execute(Fixtures.viewBot(), Request("req-8", "request_control"))
        assertEquals(ErrorCode.PERMISSION_DENIED, response.error?.code)
    }

    // ---------------------------------------------------------------- full level

    @Test
    fun `full commands report shizuku_unavailable without a handler`() {
        val session = Fixtures.fullBot()
        for (cmd in listOf("install_apk", "shell", "files", "settings")) {
            val response = executor.execute(session, Request("req-$cmd", cmd))
            assertEquals(ErrorCode.SHIZUKU_UNAVAILABLE, response.error?.code, "for $cmd")
        }
        val relay = executor.execute(session, Request("req-relay", "relay_move"))
        assertEquals(ErrorCode.INTERNAL, relay.error?.code)
    }

    @Test
    fun `full commands are delegated when a handler is registered`() {
        val seen = mutableListOf<Request>()
        val withHandler = CommandExecutor(
            device = device,
            full = FullCommandHandler { _, request ->
                seen += request
                Response.success(request.id, mapOf("handled" to true))
            },
        )
        val response = withHandler.execute(Fixtures.fullBot(), Request("req-5", "shell", mapOf("command" to "id")))
        assertTrue(response.ok)
        assertEquals(true, response.result?.get("handled"))
        assertEquals("shell", seen.single().cmd)
    }

    @Test
    fun `full bot still needs control for gestures`() {
        val response = executor.execute(Fixtures.fullBot(), Request("req-6", "tap", mapOf("x" to 1, "y" to 2)))
        assertEquals(ErrorCode.NOT_CONTROLLER, response.error?.code)
    }
}
