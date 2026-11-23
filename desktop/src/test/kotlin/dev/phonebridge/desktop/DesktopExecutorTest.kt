package dev.phonebridge.desktop

import dev.phonebridge.core.control.ControlArbiter
import dev.phonebridge.core.model.AppEvent
import dev.phonebridge.core.model.AppRequest
import dev.phonebridge.core.model.AppResponse
import dev.phonebridge.core.model.CommandType
import dev.phonebridge.core.model.Commands
import dev.phonebridge.core.model.FileOp
import dev.phonebridge.core.model.KeyName
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.RequestControlResult
import dev.phonebridge.core.model.ScreenshotResult
import dev.phonebridge.core.model.StatusResult
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.desktop.device.ScreenStreamer
import dev.phonebridge.desktop.device.ShellKind
import dev.phonebridge.desktop.device.ShellOutput
import dev.phonebridge.desktop.executor.BotSession
import dev.phonebridge.desktop.executor.DesktopExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopExecutorTest {
    private var now = 1_000L
    private val device = FakeDesktopDevice()
    private val arbiter = ControlArbiter(clock = { now })
    private val events = mutableListOf<AppEvent>()
    private val streamer = ScreenStreamer(device, clock = { now }, pause = {}, newStreamId = { "stream-1" })
    private val executor = DesktopExecutor(
        device = device,
        arbiter = arbiter,
        streamer = streamer,
        relayMove = { target -> if (target == "this") "wss://127.0.0.1:8443" else null },
    )
    private val bot = BotSession("bot1", "Claude", PermissionLevel.CONTROL)
    private val viewBot = BotSession("bot2", "Viewer", PermissionLevel.VIEW)

    private fun request(cmd: String, args: JsonObject = JsonObject(emptyMap()), id: String = "r1") =
        AppRequest(id, cmd, args)

    private fun args(json: String): JsonObject = PbJson.parseToJsonElement(json).jsonObject

    private fun run(request: AppRequest, session: BotSession = bot): AppResponse =
        executor.handle(session, request) { event -> events.add(event) }

    private fun grantControl(session: BotSession = bot) {
        arbiter.request(session.botId)
    }

    @Test
    fun anUnknownCommandIsRefused() {
        val response = run(request("explode"))
        assertFalse(response.ok)
        assertEquals("unknown_cmd", response.error?.code)
        assertEquals("r1", response.id)
    }

    @Test
    fun aViewBotCannotSendInput() {
        val response = run(request("tap", args("""{"x":5,"y":6}""")), viewBot)
        assertEquals("permission_denied", response.error?.code)
        assertTrue(device.clicks.isEmpty())
    }

    @Test
    fun controlNeedsTheControlToken() {
        val response = run(request("tap", args("""{"x":5,"y":6}""")))
        assertEquals("not_controller", response.error?.code)
        assertTrue(device.clicks.isEmpty())

        grantControl()
        val allowed = run(request("tap", args("""{"x":5,"y":6}""")))
        assertTrue(allowed.ok)
        assertEquals(listOf(5 to 6), device.clicks)
    }

    @Test
    fun badArgumentsAreRejectedBeforeTheDeviceIsTouched() {
        grantControl()
        val response = run(request("tap", args("""{"x":5}""")))
        assertEquals("bad_args", response.error?.code)
        assertTrue(device.clicks.isEmpty())
    }

    @Test
    fun statusNamesTheDeviceAndTheController() {
        grantControl()
        val response = run(request("status"))
        assertTrue(response.ok)
        val status = Commands.decodeResult(CommandType.STATUS, response.result) as StatusResult
        assertEquals("Test PC", status.device)
        assertEquals(bot.botId, status.controller)
        assertEquals(true, status.screenOn)
        assertTrue(status.queue.isEmpty())
    }

    @Test
    fun screenshotsComeBackInlineWhenSmall() {
        grantControl()
        val response = run(request("screenshot", args("""{"format":"jpeg","quality":40,"max_width":640}""")))
        assertTrue(response.ok, response.error?.message)
        val shot = Commands.decodeResult(CommandType.SCREENSHOT, response.result) as ScreenshotResult
        assertEquals("image/jpeg", shot.mime)
        assertEquals(100, shot.width)
        assertNotNull(shot.dataB64)
        assertEquals(false, shot.chunked)
        assertEquals("jpeg", device.captures.single().format)
        assertEquals(40, device.captures.single().quality)
        assertEquals(640, device.captures.single().maxWidth)
    }

    @Test
    fun aBigScreenshotIsChunkedIntoEvents() {
        device.frame = dev.phonebridge.desktop.device.ImageFrame(
            ByteArray(DesktopExecutor.MAX_INLINE_BYTES + 5) { 3 },
            4000,
            3000,
            "image/png",
        )
        val response = run(request("screenshot"))
        assertTrue(response.ok)
        val shot = Commands.decodeResult(CommandType.SCREENSHOT, response.result) as ScreenshotResult
        assertTrue(shot.chunked)
        assertEquals(shot.total, events.size)
        assertTrue(events.all { it.event == "chunk" })
        assertNotNull(shot.sha256)
    }

    @Test
    fun aScreenshotCanAskForAMonitor() {
        val response = run(request("screenshot", args("""{"screen_id":"1"}""")))
        assertTrue(response.ok)
        assertEquals("1", device.captures.single().screenId)
        val screens = run(request("screens"))
        val list = screens.result!!["screens"] as JsonArray
        assertEquals(2, list.size)
        assertEquals("0", list[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(list[0].jsonObject["primary"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun tapLongPressAndSwipeReachTheMouse() {
        grantControl()
        assertTrue(run(request("tap", args("""{"x":10,"y":20}"""))).ok)
        assertTrue(run(request("long_press", args("""{"x":30,"y":40,"ms":750}"""))).ok)
        assertTrue(run(request("swipe", args("""{"x1":1,"y1":2,"x2":3,"y2":4,"ms":250}"""))).ok)

        assertEquals(listOf(10 to 20), device.clicks)
        assertEquals(listOf(Triple(30, 40, 750L)), device.presses)
        assertEquals(listOf(listOf(1, 2, 3, 4, 250)), device.drags)
    }

    @Test
    fun nodeIdsAreNotSupportedOnTheDesktop() {
        grantControl()
        val response = run(request("tap", args("""{"node_id":"0.1"}""")))
        assertEquals("bad_args", response.error?.code)
        assertTrue(response.error?.message!!.contains("desktop"))
    }

    @Test
    fun typingKeysAndOpeningProgramsWork() {
        grantControl()
        assertTrue(run(request("type", args("""{"text":"hello"}"""))).ok)
        assertEquals("hello", device.typed.toString())

        assertTrue(run(request("key", args("""{"key":"enter"}"""))).ok)
        assertEquals(listOf(KeyName.ENTER), device.keys)

        assertTrue(run(request("open_app", args("""{"label":"notepad"}"""))).ok)
        assertEquals(listOf("notepad"), device.opened)
    }

    @Test
    fun controlIsGrantedQueuedAndReleased() {
        val first = run(request("request_control", id = "1"))
        val granted = Commands.decodeResult(CommandType.REQUEST_CONTROL, first.result) as RequestControlResult
        assertTrue(granted.granted)

        val second = BotSession("bot9", "Later", PermissionLevel.CONTROL)
        val queued = run(request("request_control", id = "2"), second)
        val waiting = Commands.decodeResult(CommandType.REQUEST_CONTROL, queued.result) as RequestControlResult
        assertFalse(waiting.granted)
        assertEquals(1, waiting.position)

        assertTrue(run(request("release_control"), bot).ok)
        assertTrue(arbiter.isController(second.botId))
        assertTrue(run(request("tap", args("""{"x":1,"y":1}""")), second).ok)
    }

    @Test
    fun shellReturnsOutputAndTimesOut() {
        val fullBot = BotSession("full1", "Full", PermissionLevel.FULL)
        grantControl(fullBot)
        val ok = run(request("shell", args("""{"command":"dir"}""")), fullBot)
        assertTrue(ok.ok)
        assertEquals("hello", ok.result!!["stdout"]!!.jsonPrimitive.content)
        assertEquals(Triple("dir", 60_000L, ShellKind.POWERSHELL), device.shells.single())

        device.shellOutput = ShellOutput(exit = -1, stdout = "", stderr = "", timedOut = true)
        val timeout = run(request("shell", args("""{"command":"pause","timeout_ms":500}""")), fullBot)
        assertFalse(timeout.ok)
        assertEquals("timeout", timeout.error?.code)
    }

    @Test
    fun filesDecodeTheBase64PayloadOnlyForWrites() {
        val fullBot = BotSession("full1", "Full", PermissionLevel.FULL)
        grantControl(fullBot)
        device.filesResult = dev.phonebridge.core.model.FilesResult(op = "write", ok = true, bytes = 3)
        val response = run(request("files", args("""{"op":"write","path":"C:\\x.txt","data_b64":"AQID"}""")), fullBot)
        assertTrue(response.ok)
        val call = device.fileCalls.single()
        assertEquals(FileOp.WRITE, call.first)
        assertEquals("C:\\x.txt", call.second)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(call.third!!))

        val bad = run(request("files", args("""{"op":"write","path":"C:\\x.txt","data_b64":"!!!not base64"}""")), fullBot)
        assertEquals("bad_args", bad.error?.code)
    }

    @Test
    fun androidOnlyCommandsAreRefusedClearly() {
        grantControl()
        val fullBot = BotSession("full1", "Full", PermissionLevel.FULL)
        arbiter.kickAll()
        grantControl(fullBot)
        assertEquals("unknown_cmd", run(request("ui_tree"), fullBot).error?.code)
        assertEquals("unknown_cmd", run(request("settings", args("""{"namespace":"system","key":"x"}""")), fullBot).error?.code)
        assertEquals("unknown_cmd", run(request("install_apk", args("""{"url":"https://e/x.apk"}""")), fullBot).error?.code)
    }

    @Test
    fun relayMoveToThisIsAnswered() {
        val fullBot = BotSession("full1", "Full", PermissionLevel.FULL)
        grantControl(fullBot)
        val response = run(request("relay_move", args("""{"to":"this"}""")), fullBot)
        assertTrue(response.ok)
        assertEquals("wss://127.0.0.1:8443", response.result!!["new_url"]!!.jsonPrimitive.content)

        val peer = run(request("relay_move", args("""{"to":"abc"}""")), fullBot)
        assertFalse(peer.ok)
    }

    @Test
    fun moveDragScrollAndKeyComboDriveTheInputSink() {
        grantControl()
        assertTrue(run(request("move", args("""{"x":11,"y":12}"""))).ok)
        assertTrue(run(request("drag", args("""{"x1":0,"y1":0,"x2":100,"y2":100,"ms":80}"""))).ok)
        assertTrue(run(request("scroll", args("""{"dx":0,"dy":-3}"""))).ok)
        assertTrue(run(request("key_combo", args("""{"keys":["ctrl","shift","s"]}"""))).ok)

        assertEquals(listOf(11 to 12), device.moves)
        assertEquals(listOf(listOf(0, 0, 100, 100, 80)), device.drags)
        assertEquals(listOf(listOf(null, null, 0, -3)), device.scrolls)
        assertEquals(listOf(listOf("ctrl", "shift", "s")), device.combos)
    }

    @Test
    fun extensionArgumentsAreValidated() {
        grantControl()
        assertEquals("bad_args", run(request("move", args("""{"x":1}"""))).error?.code)
        assertEquals("bad_args", run(request("drag", args("""{"x1":1,"y1":1,"x2":1,"y2":1}"""))).error?.code)
        assertEquals("bad_args", run(request("scroll", args("""{"dx":0,"dy":0}"""))).error?.code)
        assertEquals("bad_args", run(request("scroll", args("""{"x":5,"dy":1}"""))).error?.code)
        assertEquals("bad_args", run(request("scroll", args("""{"dx":100}"""))).error?.code)
        assertEquals("bad_args", run(request("key_combo", args("""{"keys":[]}"""))).error?.code)
        assertEquals("bad_args", run(request("key_combo", args("""{"keys":["a","b"]}"""))).error?.code)
        assertTrue(device.moves.isEmpty())
    }

    @Test
    fun screenStreamEmitsFramesAndSummarises() {
        val response = run(request("screen_stream", args("""{"fps":10,"frames":3,"quality":30}""")))
        assertTrue(response.ok, response.error?.message)
        assertEquals("3", response.result!!["frames"]!!.jsonPrimitive.content)
        assertEquals("stream-1", response.result!!["stream_id"]!!.jsonPrimitive.content)
        assertEquals(3, events.size)
        assertEquals(listOf(0, 1, 2), events.map { it.data["index"]!!.jsonPrimitive.int })
        assertTrue(events.all { it.event == "frame" })
        assertTrue(events.all { it.data["data_b64"]!!.jsonPrimitive.content.isNotEmpty() })
    }

    @Test
    fun installIsFullLevelAndNeedsControl() {
        val fullBot = BotSession("full1", "Full", PermissionLevel.FULL)
        val denied = run(request("install", args("""{"id":"Google.Chrome"}""")), fullBot)
        assertEquals("not_controller", denied.error?.code)

        grantControl(fullBot)
        val response = run(request("install", args("""{"id":"Google.Chrome","silent":true}""")), fullBot)
        assertTrue(response.ok)
        assertEquals("Google.Chrome", device.installs.single().id)
        assertTrue(device.installs.single().silent)

        val view = run(request("install", args("""{"id":"x"}""")), viewBot)
        assertEquals("permission_denied", view.error?.code)
    }

    @Test
    fun aViewBotMayStillTakeScreenshotsAndStream() {
        val shot = run(request("screenshot"), viewBot)
        assertTrue(shot.ok)
        val stream = run(request("screen_stream", args("""{"fps":5,"frames":2}""")), viewBot)
        assertTrue(stream.ok)
    }

    @Test
    fun deviceFailuresBecomeInternalErrors() {
        device.failure = IllegalStateException("no display")
        val response = run(request("screenshot"))
        assertFalse(response.ok)
        assertEquals("internal", response.error?.code)
        assertTrue(response.error?.message!!.contains("no display"))
    }
}
