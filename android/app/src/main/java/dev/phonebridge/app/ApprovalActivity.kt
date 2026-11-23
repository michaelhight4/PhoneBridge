package dev.phonebridge.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.ui.ApprovalOutcome
import dev.phonebridge.ui.ApprovalView

/**
 * Full-screen "Allow this bot?" screen. It is opened by the high-priority notification's full-screen
 * intent (so it also appears over the lock screen) and shows the bot's self-reported name, its key
 * fingerprint, the level it asked for, and a level picker that can only lower that request.
 */
class ApprovalActivity : Activity() {

    companion object {
        private const val EXTRA_KEY = "approval_key"

        fun intent(context: Context, key: String): Intent =
            Intent(context, ApprovalActivity::class.java)
                .putExtra(EXTRA_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private lateinit var name: TextView
    private lateinit var fingerprint: TextView
    private lateinit var requested: TextView
    private lateinit var countdown: TextView
    private lateinit var levels: RadioGroup

    private var view: ApprovalView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val current = view ?: return
            val seconds = current.secondsRemaining(System.currentTimeMillis())
            if (seconds <= 0L) {
                Toast.makeText(this@ApprovalActivity, R.string.approval_expired, Toast.LENGTH_LONG).show()
                BridgeNotifications.dismissApproval(this@ApprovalActivity)
                finish()
                return
            }
            countdown.text = getString(
                R.string.approval_countdown_format,
                seconds / 60,
                (seconds % 60).toInt(),
            )
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_approval)

        name = findViewById(R.id.approval_name)
        fingerprint = findViewById(R.id.approval_fingerprint)
        requested = findViewById(R.id.approval_requested)
        countdown = findViewById(R.id.approval_countdown)
        levels = findViewById(R.id.approval_levels)

        val key = intent.getStringExtra(EXTRA_KEY)
        val controller = BridgeHub.controller()?.approvalController
        val pending = if (key != null) controller?.find(key) else null
        if (key == null || controller == null || pending == null) {
            Toast.makeText(this, R.string.approval_gone, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        view = pending

        name.text = pending.botName
        fingerprint.text = getString(R.string.approval_fingerprint_format, pending.fingerprint)
        requested.text = getString(R.string.approval_requested_format, pending.requestedLabel)

        // The user can grant the requested level or lower it, never raise it.
        checkLevel(R.id.level_view, PermissionLevel.VIEW, pending)
        checkLevel(R.id.level_control, PermissionLevel.CONTROL, pending)
        checkLevel(R.id.level_full, PermissionLevel.FULL, pending)
        levels.check(levelButtonId(pending.requestedLevel))

        findViewById<Button>(R.id.approval_allow).setOnClickListener {
            val chosen = levelOf(levels.checkedRadioButtonId) ?: pending.requestedLevel
            when (val outcome = controller.approve(pending.key, chosen)) {
                is ApprovalOutcome.Approved -> Toast.makeText(
                    this,
                    getString(R.string.approval_allowed, outcome.grantedLevel.displayName()),
                    Toast.LENGTH_LONG,
                ).show()
                is ApprovalOutcome.Failed -> Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                is ApprovalOutcome.Denied -> Unit
            }
            BridgeNotifications.dismissApproval(this)
            finish()
        }
        findViewById<Button>(R.id.approval_deny).setOnClickListener {
            controller.deny(pending.key)
            Toast.makeText(this, R.string.approval_denied, Toast.LENGTH_LONG).show()
            BridgeNotifications.dismissApproval(this)
            finish()
        }

        handler.postDelayed(ticker, 0L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun checkLevel(id: Int, level: PermissionLevel, pending: ApprovalView) {
        val button = findViewById<RadioButton>(id)
        button.isEnabled = pending.requestedLevel.allows(level)
    }

    private fun levelButtonId(level: PermissionLevel): Int = when (level) {
        PermissionLevel.VIEW -> R.id.level_view
        PermissionLevel.CONTROL -> R.id.level_control
        PermissionLevel.FULL -> R.id.level_full
    }

    private fun levelOf(id: Int): PermissionLevel? = when (id) {
        R.id.level_view -> PermissionLevel.VIEW
        R.id.level_control -> PermissionLevel.CONTROL
        R.id.level_full -> PermissionLevel.FULL
        else -> null
    }

    private fun PermissionLevel.displayName(): String = when (this) {
        PermissionLevel.VIEW -> "View"
        PermissionLevel.CONTROL -> "Control"
        PermissionLevel.FULL -> "Full"
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
