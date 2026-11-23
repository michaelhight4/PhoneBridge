package dev.phonebridge.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import dev.phonebridge.executor.ShizukuState

/**
 * The permission and restart screen for Shizuku.
 *
 * Three things can be wrong and the user fixes each one differently: Shizuku is not installed, it is
 * installed but not running (the normal state after a reboot — Android kills it), or PhoneBridge has
 * not been allowed yet. The screen says which, offers the matching button, and updates itself when
 * the binder comes or goes.
 */
class ShizukuPermissionActivity : Activity() {

    private lateinit var stateText: TextView
    private lateinit var hintText: TextView
    private lateinit var actionButton: Button

    private val observer: (ShizukuState) -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizuku)

        stateText = findViewById(R.id.shizuku_state)
        hintText = findViewById(R.id.shizuku_hint)
        actionButton = findViewById(R.id.shizuku_action)

        actionButton.setOnClickListener { onAction() }
        findViewById<Button>(R.id.shizuku_open_app).setOnClickListener {
            ShizukuBridge.openShizukuOrDownload(this)
        }

        ShizukuBridge.addObserver(observer)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        ShizukuBridge.removeObserver(observer)
        super.onDestroy()
    }

    /** Some Shizuku versions surface the answer as a normal runtime-permission result. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun onAction() {
        when (ShizukuBridge.state) {
            ShizukuState.READY ->
                Toast.makeText(this, R.string.shizuku_already_ready, Toast.LENGTH_SHORT).show()

            ShizukuState.NO_PERMISSION -> ShizukuBridge.requestPermission()

            ShizukuState.NOT_INSTALLED,
            ShizukuState.NOT_RUNNING,
            ShizukuState.UNSUPPORTED,
            -> ShizukuBridge.openShizukuOrDownload(this)
        }
    }

    private fun refresh() {
        val state = ShizukuBridge.state
        stateText.setText(
            when (state) {
                ShizukuState.READY -> R.string.shizuku_state_ready
                ShizukuState.NOT_INSTALLED -> R.string.shizuku_state_not_installed
                ShizukuState.NOT_RUNNING -> R.string.shizuku_state_not_running
                ShizukuState.NO_PERMISSION -> R.string.shizuku_state_no_permission
                ShizukuState.UNSUPPORTED -> R.string.shizuku_state_unsupported
            },
        )
        hintText.text = state.message
        actionButton.setText(
            when (state) {
                ShizukuState.READY -> R.string.shizuku_action_ready
                ShizukuState.NO_PERMISSION -> R.string.shizuku_action_allow
                ShizukuState.NOT_INSTALLED -> R.string.shizuku_action_install
                ShizukuState.NOT_RUNNING,
                ShizukuState.UNSUPPORTED,
                -> R.string.shizuku_action_open
            },
        )
    }
}
