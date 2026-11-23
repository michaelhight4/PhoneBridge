package dev.phonebridge.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import dev.phonebridge.executor.KeyCommand
import dev.phonebridge.executor.Screenshot
import dev.phonebridge.executor.ScreenshotFormat
import dev.phonebridge.executor.UiNode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The device-control half of PhoneBridge.
 *
 * Everything a bot can do to the phone goes through here:
 * - read the UI tree ([readTree]) in the executor's [UiNode] shape,
 * - tap / long-press / swipe with [dispatchGesture],
 * - type into the focused or a given editable node (`ACTION_SET_TEXT`, clipboard paste fallback),
 * - global actions for `key` (`performGlobalAction`),
 * - an accessibility screenshot on API 30+ (`takeScreenshot`).
 *
 * The generic parts (permissions, arguments, control) live in `:android:executor-core`; this class
 * only translates device calls. It never decides who is allowed to do what.
 */
class BridgeAccessibilityService : AccessibilityService() {

    private val treeReader by lazy { AccessibilityTreeReader(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Re-assert the flags from accessibility_service_config.xml: some OEM builds reset them when
        // the service is re-enabled, and without FLAG_REPORT_VIEW_IDS the res_id field goes missing.
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }
        AccessibilityBridge.attach(this)
        Log.i(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // PhoneBridge reads the tree when a bot asks for it; no event bookkeeping is needed yet.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityBridge.detach(this)
        Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI tree

    /** Snapshot of the active window for `ui_tree`. */
    fun readTree(): UiNode? = if (AccessibilityBridge.isConnected) treeReader.readActiveWindow() else null

    // ---------------------------------------------------------------- gestures

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(0L, TAP_DURATION_MS, path)
    }

    fun longPress(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(0L, durationMs, path)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(0L, durationMs, path)
    }

    private fun dispatch(startTimeMs: Long, durationMs: Long, path: Path): Boolean {
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startTimeMs, durationMs))
            .build()
        return dispatchGesture(description, null, null)
    }

    // -------------------------------------------------------------------- text

    /**
     * Type [text] into [nodeId] when one is given, otherwise into the focused input.
     *
     * Uses `ACTION_SET_TEXT` first and falls back to clipboard + `ACTION_PASTE` for editors that
     * ignore `ACTION_SET_TEXT` (some WebViews and OEM dialers). When a `node_id` was given but no
     * longer resolves, this fails instead of typing into whatever happens to be focused.
     */
    fun typeText(text: String, nodeId: String?): Boolean {
        if (nodeId != null) {
            val requested = treeReader.findNode(nodeId) ?: return false
            if (requested.isEditable && setText(requested, text)) return true
            if (!requested.isFocused) requested.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (requested.isEditable && setText(requested, text)) return true
            return paste(requested, text)
        }

        val focused = focusedInput() ?: return false
        if (focused.isEditable && setText(focused, text)) return true
        return paste(focused, text)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** Clipboard fallback: copy the text, then ask [node] to paste it. */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        val copied = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
        }.isSuccess
        if (!copied) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun focusedInput(): AccessibilityNodeInfo? = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (e: SecurityException) {
        null
    }

    // --------------------------------------------------------------------- keys

    /** Global actions for `key`; see [KeyCommand]. */
    fun performKey(key: KeyCommand): Boolean {
        val globalAction = key.globalActionCode
        if (globalAction != null) return performGlobalAction(globalAction)
        return when (key) {
            KeyCommand.ENTER -> pressEnter()
            else -> false
        }
    }

    /**
     * `enter` cannot be faked with the accessibility APIs alone: there is no key-injection and no
     * IME-enter node action on the platform, so this build reports failure and the bot gets an
     * `internal` error. The Full-level Shizuku path (`input keyevent 66`) will cover it later.
     */
    private fun pressEnter(): Boolean {
        Log.w(TAG, "key=enter needs the Shizuku input path, which is not wired in yet")
        return false
    }

    // -------------------------------------------------------------- screenshot

    /**
     * Accessibility screenshot (API 30+), scaled to [maxWidth] and encoded as PNG or JPEG.
     *
     * Blocking: waits up to [SCREENSHOT_TIMEOUT_MS] for the system callback, so call it off the main
     * thread (the transport layer already does).
     */
    fun captureScreenshot(format: ScreenshotFormat, quality: Int?, maxWidth: Int?): Screenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bitmap = takeAccessibilityScreenshot() ?: return null
        return try {
            encode(bitmap, format, quality, maxWidth)
        } finally {
            bitmap.recycle()
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun takeAccessibilityScreenshot(): Bitmap? {
        val latch = CountDownLatch(1)
        var captured: Bitmap? = null

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                try {
                    captured = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "takeScreenshot failed with error $errorCode")
                latch.countDown()
            }
        })

        if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "takeScreenshot timed out")
            return null
        }
        return captured
    }

    private fun encode(bitmap: Bitmap, format: ScreenshotFormat, quality: Int?, maxWidth: Int?): Screenshot? {
        val scaled = scaleToWidth(bitmap, maxWidth)
        val output = ByteArrayOutputStream()
        val compressFormat = when (format) {
            ScreenshotFormat.PNG -> Bitmap.CompressFormat.PNG
            ScreenshotFormat.JPEG -> Bitmap.CompressFormat.JPEG
        }
        val compressQuality = when (format) {
            ScreenshotFormat.JPEG -> (quality ?: DEFAULT_JPEG_QUALITY).coerceIn(1, 100)
            ScreenshotFormat.PNG -> 100 // ignored for PNG
        }
        if (!scaled.compress(compressFormat, compressQuality, output)) {
            if (scaled !== bitmap) scaled.recycle()
            return null
        }
        val data = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        val result = Screenshot(
            mime = format.mime,
            width = scaled.width,
            height = scaled.height,
            dataBase64 = data,
        )
        if (scaled !== bitmap) scaled.recycle()
        return result
    }

    private fun scaleToWidth(bitmap: Bitmap, maxWidth: Int?): Bitmap {
        if (maxWidth == null || bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
    }

    companion object {
        private const val TAG = "PhoneBridge.Acc"
        private const val TAP_DURATION_MS = 60L
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_JPEG_QUALITY = 90
        private const val CLIPBOARD_LABEL = "PhoneBridge"
    }
}
