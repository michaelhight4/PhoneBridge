package dev.phonebridge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import dev.phonebridge.ui.OnboardingStatus

/**
 * The three Android consents PhoneBridge needs, with their live state:
 * accessibility service, notifications, and the battery-optimisation exemption.
 */
class OnboardingActivity : Activity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 51
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        findViewById<Button>(R.id.onboarding_accessibility_button).setOnClickListener {
            SettingsLinks.openAccessibilitySettings(this)
        }
        findViewById<Button>(R.id.onboarding_notifications_button).setOnClickListener {
            requestNotifications()
        }
        findViewById<Button>(R.id.onboarding_battery_button).setOnClickListener {
            requestBatteryExemption()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) refresh()
    }

    private fun refresh() {
        val status = OnboardingStatus(
            accessibilityEnabled = SettingsLinks.isAccessibilityServiceEnabled(this),
            notificationsGranted = notificationsGranted(),
            batteryOptimisationExempt = batteryExempt(),
        )
        setRow(
            R.id.onboarding_accessibility_status,
            status.accessibilityEnabled,
            R.id.onboarding_accessibility_button,
        )
        setRow(
            R.id.onboarding_notifications_status,
            status.notificationsGranted,
            R.id.onboarding_notifications_button,
        )
        setRow(
            R.id.onboarding_battery_status,
            status.batteryOptimisationExempt,
            R.id.onboarding_battery_button,
        )
    }

    private fun setRow(statusId: Int, done: Boolean, buttonId: Int) {
        findViewById<TextView>(statusId).setText(
            if (done) R.string.onboarding_done else R.string.onboarding_missing,
        )
        findViewById<Button>(buttonId).isEnabled = !done
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        return manager?.areNotificationsEnabled() ?: false
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            SettingsLinks.openAppDetails(this)
        }
    }

    private fun batteryExempt(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (batteryExempt()) return
        // This opens Android's own "ignore battery optimisations" dialog for the app.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        val opened = runCatching { startActivity(direct) }.isSuccess
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
