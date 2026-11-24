package dev.phonebridge.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import dev.phonebridge.executor.ShizukuState
import dev.phonebridge.executor.UiTreeSnapshot
import dev.phonebridge.ui.ControllerNotifications

/**
 * Home screen: whether the bridge is running and who is in control, the set-up checks (including
 * Shizuku for Full-level commands), and doors to pairing, the bot list, the activity log, the relay
 * screen and the Shizuku screen.
 */
class MainActivity : Activity() {

    private lateinit var bridgeState: TextView
    private lateinit var controllerState: TextView
    private lateinit var serviceState: TextView
    private lateinit var shizukuState: TextView
    private lateinit var testResult: TextView
    private lateinit var toggleBridge: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bridgeState = findViewById(R.id.bridge_state)
        controllerState = findViewById(R.id.controller_state)
        serviceState = findViewById(R.id.service_state)
        shizukuState = findViewById(R.id.shizuku_state)
        testResult = findViewById(R.id.test_result)
        toggleBridge = findViewById(R.id.toggle_bridge)

        toggleBridge.setOnClickListener {
            if (BridgeHub.isRunning) {
                BridgeService.stop(this)
            } else {
                BridgeService.start(this)
            }
            // The service flips state asynchronously; onResume refreshes it.
            bridgeState.postDelayed({ refresh() }, 300)
        }

        findViewById<Button>(R.id.open_accessibility_settings).setOnClickListener {
            SettingsLinks.openAccessibilitySettings(this)
        }
        findViewById<Button>(R.id.open_shizuku).setOnClickListener {
            startActivity(Intent(this, ShizukuPermissionActivity::class.java))
        }
        findViewById<Button>(R.id.open_onboarding).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<Button>(R.id.open_pairing).setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }
        findViewById<Button>(R.id.open_bots).setOnClickListener {
            startActivity(Intent(this, BotListActivity::class.java))
        }
        findViewById<Button>(R.id.open_log).setOnClickListener {
            startActivity(Intent(this, ActivityLogActivity::class.java))
        }
        findViewById<Button>(R.id.open_relay).setOnClickListener {
            startActivity(Intent(this, RelayActivity::class.java))
        }
        findViewById<Button>(R.id.test_screen_read).setOnClickListener {
            showScreenReadResult()
        }
        findViewById<Button>(R.id.open_app_info).setOnClickListener {
            SettingsLinks.openAppInfo(this)
        }
        findViewById<Button>(R.id.open_last_crash).setOnClickListener {
            startActivity(Intent(this, CrashActivity::class.java))
        }
        if (CrashReporter.takeUnseen(this)) startActivity(Intent(this, CrashActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        findViewById<Button>(R.id.open_last_crash).visibility =
            if (CrashReporter.lastCrash(this) != null) android.view.View.VISIBLE else android.view.View.GONE
        refresh()
        refreshShizukuState()
    }

    private fun refresh() {
        val connected = AccessibilityBridge.isConnected
        serviceState.setText(
            if (connected) R.string.main_service_enabled else R.string.main_service_disabled,
        )

        val running = BridgeHub.isRunning
        bridgeState.setText(if (running) R.string.main_bridge_running else R.string.main_bridge_stopped)
        toggleBridge.setText(if (running) R.string.main_stop_bridge else R.string.main_start_bridge)

        val content = BridgeHub.controller()?.bridgeState?.notificationContent()
        controllerState.text = content?.text ?: ControllerNotifications.text(null)
    }

    private fun refreshShizukuState() {
        val ready = ShizukuBridge.state == ShizukuState.READY
        shizukuState.setText(if (ready) R.string.main_shizuku_on else R.string.main_shizuku_off)
    }

    private fun showScreenReadResult() {
        if (!AccessibilityBridge.isConnected) {
            testResult.setText(R.string.main_test_result_disabled)
            return
        }
        // Diagnostic only: this runs on the main thread for the button press.
        val tree = AccessibilityBridge.service?.readTree()
        if (tree == null) {
            testResult.setText(R.string.main_test_result_disabled)
            return
        }
        val snapshot = UiTreeSnapshot.capture(tree)
        val lines = buildList {
            add("nodes: ${snapshot.size}")
            add("root: ${snapshot.root?.node?.className ?: "none"}")
            snapshot.nodes()
                .filter { it.node.clickable || it.node.editable }
                .take(MAX_LISTED_NODES)
                .forEach { indexed ->
                    val label = indexed.node.text ?: indexed.node.contentDescription ?: indexed.node.className
                    add("${indexed.id}  ${if (indexed.node.editable) "editable" else "clickable"}  $label")
                }
        }
        testResult.text = lines.joinToString("\n")
    }

    companion object {
        private const val MAX_LISTED_NODES = 12
    }
}
