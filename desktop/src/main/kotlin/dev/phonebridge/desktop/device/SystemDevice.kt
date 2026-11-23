package dev.phonebridge.desktop.device

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.PhoneBridgeException
import dev.phonebridge.core.model.FileOp
import dev.phonebridge.core.model.FilesResult
import dev.phonebridge.core.model.KeyName

/** The real Windows device: AWT screens and input, real processes, the real file system. */
class SystemDevice(
    private val screens: ScreenSource = AwtScreenSource(),
    private val input: InputSink = RobotInput(),
    private val files: FileOps = FileOps(),
    private val shell: ShellRunner = ShellRunner(),
    private val installer: Installer = Installer(shell),
    override val name: String = computerName(),
) : DesktopDevice, ScreenSource by screens {

    override fun isScreenOn(): Boolean = screens.screens().isNotEmpty()

    override fun screenshot(screenId: String?, format: String, quality: Int, maxWidth: Int?): ImageFrame =
        screens.capture(screenId, format, quality, maxWidth)

    override fun pointer(x: Int, y: Int) = input.pointer(x, y)

    override fun click(x: Int, y: Int) = input.click(x, y)

    override fun longPress(x: Int, y: Int, ms: Long) = input.press(x, y, ms)

    override fun drag(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) = input.drag(x1, y1, x2, y2, ms)

    override fun scroll(x: Int?, y: Int?, dx: Int, dy: Int) = input.scroll(x, y, dx, dy)

    override fun typeText(text: String) = input.typeText(text)

    override fun key(name: KeyName) = input.key(KeyCombos.forCoreKey(name))

    override fun keyCombo(keys: List<String>) = input.key(KeyCombos.parse(keys))

    override fun shell(command: String, timeoutMs: Long, kind: ShellKind): ShellOutput =
        shell.run(command, timeoutMs, kind)

    override fun files(op: FileOp, path: String, data: ByteArray?): FilesResult = files.perform(op, path, data)

    override fun install(request: InstallRequest): InstallResult = installer.install(request)

    override fun openProgram(target: String) {
        val clean = target.trim()
        if (clean.isEmpty()) throw BadArgsException("open_app needs a program or package name")
        try {
            ProcessBuilder("cmd.exe", "/c", "start", "", clean).start()
        } catch (e: Exception) {
            throw PhoneBridgeException("could not start $clean: ${e.message}", e)
        }
    }

    companion object {
        fun computerName(): String =
            System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
                ?: "Windows PC"
    }
}
