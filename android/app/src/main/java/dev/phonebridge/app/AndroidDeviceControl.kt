package dev.phonebridge.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import dev.phonebridge.executor.DeviceControl
import dev.phonebridge.executor.DeviceStatus
import dev.phonebridge.executor.InstalledApp
import dev.phonebridge.executor.KeyCommand
import dev.phonebridge.executor.LaunchResult
import dev.phonebridge.executor.NotificationItem
import dev.phonebridge.executor.Screenshot
import dev.phonebridge.executor.ScreenshotFormat
import dev.phonebridge.executor.UiNode

/**
 * [DeviceControl] backed by the live [BridgeAccessibilityService].
 *
 * The service can be unbound at any moment, so every accessibility call looks it up instead of
 * caching it; when it is gone [accessibilityEnabled] is false and the executor answers
 * `accessibility_disabled` without ever getting here.
 *
 * Package/status queries use the application context, so `status` and `open_app` keep working while
 * the service is off.
 */
class AndroidDeviceControl(private val context: Context) : DeviceControl {

    private val service: BridgeAccessibilityService? get() = AccessibilityBridge.service

    override val apiLevel: Int get() = Build.VERSION.SDK_INT

    override val accessibilityEnabled: Boolean get() = service != null

    override fun captureUiTree(): UiNode? = service?.readTree()

    override fun tap(x: Int, y: Int): Boolean = service?.tap(x, y) ?: false

    override fun longPress(x: Int, y: Int, durationMs: Long): Boolean =
        service?.longPress(x, y, durationMs) ?: false

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        service?.swipe(x1, y1, x2, y2, durationMs) ?: false

    override fun setText(text: String, nodeId: String?): Boolean =
        service?.typeText(text, nodeId) ?: false

    override fun performKey(key: KeyCommand): Boolean = service?.performKey(key) ?: false

    /**
     * Launchable packages with their visible labels, for `open_app {label}`. Only launcher
     * activities are queried (see the `<queries>` entry in the manifest), so we never need
     * QUERY_ALL_PACKAGES.
     */
    override fun installedApps(): List<InstalledApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0).mapNotNull { resolveInfo ->
            val activity = resolveInfo.activityInfo ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(packageManager)?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: activity.packageName
            InstalledApp(packageName = activity.packageName, label = label)
        }.distinctBy { it.packageName }
    }

    /** Start the package's launcher activity, distinguishing "not installed" from "no launcher". */
    override fun launchApp(packageName: String): LaunchResult {
        val packageManager = context.packageManager
        val installed = try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return LaunchResult.NOT_FOUND

        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult.NOT_LAUNCHABLE
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LaunchResult.LAUNCHED
        } catch (e: ActivityNotFoundException) {
            LaunchResult.NOT_LAUNCHABLE
        } catch (e: SecurityException) {
            LaunchResult.NOT_LAUNCHABLE
        }
    }

    override fun captureScreenshot(format: ScreenshotFormat, quality: Int?, maxWidth: Int?): Screenshot? =
        service?.captureScreenshot(format, quality, maxWidth)

    /**
     * Notification history needs a NotificationListenerService, which is a separate task. Until it
     * exists the device honestly reports no items instead of inventing data.
     */
    override fun readNotifications(sinceEpochMs: Long?): List<NotificationItem> = emptyList()

    override fun deviceStatus(): DeviceStatus {
        val battery = runCatching {
            context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
        }.getOrNull()

        val powerManager = context.getSystemService(PowerManager::class.java)
        val screenOn = runCatching { powerManager?.isInteractive ?: false }.getOrDefault(false)

        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android device" }

        return DeviceStatus(
            device = device,
            battery = battery,
            screenOn = screenOn,
            // Shizuku support is its own task; report false until it is wired in.
            shizuku = false,
        )
    }
}
