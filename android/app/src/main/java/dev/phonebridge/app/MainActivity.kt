package dev.phonebridge.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import dev.phonebridge.executor.ShizukuState
import dev.phonebridge.executor.UiTreeSnapshot

/**
 * Minimal first screen: it shows whether device control is on and whether the Shizuku helper is
 * ready, takes the user to the accessibility settings page, and lets them check what a bot would
 * see. Pairing, the bot list and the activity log are later tasks.
 */
class MainActivity : Activity() {

    private lateinit var serviceState: TextView
    private lateinit var shizukuState: TextView
    private lateinit var testResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceState = findViewById(R.id.service_state)
        shizukuState = findViewById(R.id.shizuku_state)
        testResult = findViewById(R.id.test_result)

        findViewById<Button>(R.id.open_accessibility_settings).setOnClickListener {
            SettingsLinks.openAccessibilitySettings(this)
        }
        findViewById<Button>(R.id.open_shizuku).setOnClickListener {
            startActivity(Intent(this, ShizukuPermissionActivity::class.java))
        }
        findViewById<Button>(R.id.test_screen_read).setOnClickListener {
            showScreenReadResult()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
        refreshShizukuState()
    }

    private fun refreshServiceState() {
        val connected = AccessibilityBridge.isConnected
        serviceState.setText(
            if (connected) R.string.main_service_enabled else R.string.main_service_disabled,
        )
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
            testResult.text = getString(R.string.main_test_result_disabled)
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
