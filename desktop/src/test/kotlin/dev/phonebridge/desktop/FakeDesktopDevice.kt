package dev.phonebridge.desktop

import dev.phonebridge.core.model.FileOp
import dev.phonebridge.core.model.FilesResult
import dev.phonebridge.core.model.KeyName
import dev.phonebridge.desktop.device.DesktopDevice
import dev.phonebridge.desktop.device.ImageFrame
import dev.phonebridge.desktop.device.InstallRequest
import dev.phonebridge.desktop.device.InstallResult
import dev.phonebridge.desktop.device.ScreenInfo
import dev.phonebridge.desktop.device.ScreenSource
import dev.phonebridge.desktop.device.ShellKind
import dev.phonebridge.desktop.device.ShellOutput

/** Recording fake: every device touch lands in a list a test can assert on. */
class FakeDesktopDevice : DesktopDevice, ScreenSource {
    override val name: String = "Test PC"

    val clicks = mutableListOf<Pair<Int, Int>>()
    val moves = mutableListOf<Pair<Int, Int>>()
    val presses = mutableListOf<Triple<Int, Int, Long>>()
    val drags = mutableListOf<List<Int>>()
    val scrolls = mutableListOf<List<Int?>>()
    val typed = StringBuilder()
    val keys = mutableListOf<KeyName>()
    val combos = mutableListOf<List<String>>()
    val shells = mutableListOf<Triple<String, Long, ShellKind>>()
    val installs = mutableListOf<InstallRequest>()
    val fileCalls = mutableListOf<Triple<FileOp, String, ByteArray?>>()
    val opened = mutableListOf<String>()
    val captures = mutableListOf<CaptureRequest>()

    data class CaptureRequest(val screenId: String?, val format: String, val quality: Int, val maxWidth: Int?)

    var screenList: List<ScreenInfo> = listOf(
        ScreenInfo("0", 0, 0, 1920, 1080, true),
        ScreenInfo("1", 1920, 0, 1280, 1024, false),
    )
    var frame: ImageFrame = ImageFrame(ByteArray(32) { 7 }, 100, 60, "image/jpeg")
    var shellOutput = ShellOutput(exit = 0, stdout = "hello", stderr = "")
    var filesResult: FilesResult = FilesResult(op = "list")
    var installResult = InstallResult(ok = true, method = "winget", detail = "ok")
    var failure: Exception? = null

    override fun screens(): List<ScreenInfo> = screenList

    override fun isScreenOn(): Boolean = screenList.isNotEmpty()

    override fun screenshot(screenId: String?, format: String, quality: Int, maxWidth: Int?): ImageFrame {
        failure?.let { throw it }
        captures.add(CaptureRequest(screenId, format, quality, maxWidth))
        return frame
    }

    override fun capture(screenId: String?, format: String, quality: Int, maxWidth: Int?): ImageFrame =
        screenshot(screenId, format, quality, maxWidth)

    override fun pointer(x: Int, y: Int) {
        moves.add(x to y)
    }

    override fun click(x: Int, y: Int) {
        clicks.add(x to y)
    }

    override fun longPress(x: Int, y: Int, ms: Long) {
        presses.add(Triple(x, y, ms))
    }

    override fun drag(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
        drags.add(listOf(x1, y1, x2, y2, ms.toInt()))
    }

    override fun scroll(x: Int?, y: Int?, dx: Int, dy: Int) {
        scrolls.add(listOf(x, y, dx, dy))
    }

    override fun typeText(text: String) {
        typed.append(text)
    }

    override fun key(name: KeyName) {
        keys.add(name)
    }

    override fun keyCombo(keys: List<String>) {
        combos.add(keys)
    }

    override fun shell(command: String, timeoutMs: Long, kind: ShellKind): ShellOutput {
        shells.add(Triple(command, timeoutMs, kind))
        return shellOutput
    }

    override fun files(op: FileOp, path: String, data: ByteArray?): FilesResult {
        fileCalls.add(Triple(op, path, data))
        return filesResult
    }

    override fun install(request: InstallRequest): InstallResult {
        installs.add(request)
        return installResult
    }

    override fun openProgram(target: String) {
        opened.add(target)
    }
}
