package dev.phonebridge.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import dev.phonebridge.executor.SettingsDeepLink

/**
 * Sends the user to the right Settings page. Android does not let an app switch its own
 * accessibility service on, so the best we can do is take the user straight there and explain why.
 */
object SettingsLinks {

    /**
     * Opens Settings > Accessibility (where the PhoneBridge service is listed). Falls back to the
     * top-level Settings app on builds that do not expose the accessibility page intent.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(SettingsDeepLink.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Many Settings apps scroll straight to our service with this fragment hint; others ignore
        // the extras, which is harmless.
        intent.putExtra(SettingsDeepLink.EXTRA_FRAGMENT_ARG_KEY, SettingsDeepLink.FRAGMENT_ARG_SERVICE)

        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(fallback)
            } catch (e: ActivityNotFoundException) {
                // Nothing else we can do; the caller shows the hint text.
            }
        }
    }

    /** Per-app page, used later for notification permission and "app info". */
    fun openAppDetails(context: Context) {
        val intent = Intent(SettingsDeepLink.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse(SettingsDeepLink.packageUri(context.packageName)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * True when the PhoneBridge accessibility service is listed as enabled in Settings. This can be
     * true a moment before [AccessibilityBridge.isConnected] becomes true, so the UI checks both.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val expected = ComponentName(context, BridgeAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> ComponentName.unflattenFromString(info.id) == expected }
    }

    /** App info, where Android hides "Allow restricted settings" (three-dot menu) for sideloaded apps. */
    fun openAppInfo(context: android.content.Context) {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
