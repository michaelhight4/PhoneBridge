package dev.phonebridge.desktop.device

import dev.phonebridge.core.model.FileOp
import dev.phonebridge.core.model.FilesResult
import dev.phonebridge.core.model.KeyName

/** One monitor of the desktop, in virtual-screen coordinates. */
data class ScreenInfo(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val primary: Boolean,
)

/** An encoded screenshot or stream frame. */
class ImageFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mime: String,
) {
    val size: Int
        get() = bytes.size
}

/** Which shell a `shell` command runs in. */
enum class ShellKind(val wire: String) {
    POWERSHELL("powershell"),
    CMD("cmd"),
    ;

    companion object {
        fun parse(text: String?): ShellKind? = when (text?.trim()?.lowercase()) {
            null, "", "powershell", "pwsh", "ps" -> POWERSHELL
            "cmd", "cmd.exe", "command" -> CMD
            else -> null
        }
    }
}

/** Captured output of a shell command; [timedOut] means the process was killed at the deadline. */
data class ShellOutput(
    val exit: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

/** A program install request: a winget id, a local installer path, or an https download. */
data class InstallRequest(
    val id: String? = null,
    val path: String? = null,
    val url: String? = null,
    val name: String? = null,
    val silent: Boolean = true,
)

/** What the installer did, for the `install` extension result. */
data class InstallResult(
    val ok: Boolean,
    val method: String,
    val detail: String,
    val exit: Int? = null,
    val started: Boolean = false,
    val packageId: String? = null,
)

/**
 * Everything the desktop agent can do to this PC, behind one seam.
 *
 * [dev.phonebridge.desktop.executor.DesktopExecutor] only talks to this interface, so every permission,
 * argument and control rule is tested with a fake and the real `SystemDevice` only holds AWT/process
 * code. Screen capture is exposed both as a one-shot [screenshot] and, for streaming, through
 * [ScreenSource] (implemented by `SystemDevice`).
 */
interface DesktopDevice {
    /** Friendly computer name shown in `status`. */
    val name: String

    fun screens(): List<ScreenInfo>

    fun isScreenOn(): Boolean

    /** Captures [screenId], or every monitor stitched together when it is null. */
    fun screenshot(screenId: String?, format: String, quality: Int, maxWidth: Int?): ImageFrame

    fun pointer(x: Int, y: Int)

    fun click(x: Int, y: Int)

    fun longPress(x: Int, y: Int, ms: Long)

    fun drag(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long)

    fun scroll(x: Int?, y: Int?, dx: Int, dy: Int)

    fun typeText(text: String)

    /** Core `key` command: a named key from docs/PROTOCOL.md mapped to a desktop shortcut. */
    fun key(name: KeyName)

    /** Desktop extension `key_combo`: `["ctrl","shift","s"]`. */
    fun keyCombo(keys: List<String>)

    fun shell(command: String, timeoutMs: Long, kind: ShellKind): ShellOutput

    fun files(op: FileOp, path: String, data: ByteArray?): FilesResult

    fun install(request: InstallRequest): InstallResult

    /** Opens a program by executable name or path (`open_app` on the desktop). */
    fun openProgram(target: String)
}

/** One-shot screen capture, split out so the streamer can be tested without AWT. */
interface ScreenSource {
    fun screens(): List<ScreenInfo>

    fun capture(screenId: String?, format: String, quality: Int, maxWidth: Int?): ImageFrame
}

/** Injection seam for the mouse and keyboard. */
interface InputSink {
    fun pointer(x: Int, y: Int)

    fun click(x: Int, y: Int)

    fun press(x: Int, y: Int, ms: Long)

    fun drag(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long)

    fun scroll(x: Int?, y: Int?, dx: Int, dy: Int)

    fun typeText(text: String)

    fun key(combo: KeyCombo)
}
