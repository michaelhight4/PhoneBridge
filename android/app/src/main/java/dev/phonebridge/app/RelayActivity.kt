package dev.phonebridge.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import dev.phonebridge.ui.RelayController
import dev.phonebridge.ui.RelayHost
import dev.phonebridge.ui.RelayResult

/**
 * Where the heart beats: this phone, my PC, or a custom relay URL. "Move heart here" is the same
 * operation a Full-level bot triggers with `relay_move {to:"this"}`.
 */
class RelayActivity : Activity() {

    private lateinit var controller: RelayController
    private lateinit var hostGroup: RadioGroup
    private lateinit var url: EditText
    private lateinit var pin: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay)

        controller = BridgeHub.relay(this)
        hostGroup = findViewById(R.id.relay_host)
        url = findViewById(R.id.relay_url)
        pin = findViewById(R.id.relay_pin)
        status = findViewById(R.id.relay_status)

        val settings = controller.settings()
        hostGroup.check(
            when (settings.host) {
                RelayHost.THIS_PHONE -> R.id.relay_this_phone
                RelayHost.MY_PC -> R.id.relay_my_pc
                RelayHost.CUSTOM_URL -> R.id.relay_custom
            },
        )
        url.setText(settings.url.orEmpty())
        pin.setText(settings.tlsPin.orEmpty())

        findViewById<Button>(R.id.relay_save).setOnClickListener { save() }
        findViewById<Button>(R.id.relay_move_here).setOnClickListener { moveHeartHere() }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun save() {
        val host = when (hostGroup.checkedRadioButtonId) {
            R.id.relay_this_phone -> RelayHost.THIS_PHONE
            R.id.relay_my_pc -> RelayHost.MY_PC
            else -> RelayHost.CUSTOM_URL
        }
        if (host == RelayHost.THIS_PHONE) {
            moveHeartHere()
            return
        }
        val result = controller.setHost(host, url.text.toString(), pin.text.toString())
        showResult(result)
    }

    private fun moveHeartHere() {
        val result = controller.moveHeartHere()
        showResult(result)
        if (result is RelayResult.Ok) hostGroup.check(R.id.relay_this_phone)
    }

    private fun showResult(result: RelayResult) {
        when (result) {
            is RelayResult.Error -> {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                status.text = result.message
            }
            is RelayResult.Ok -> {
                Toast.makeText(this, R.string.relay_saved, Toast.LENGTH_SHORT).show()
                result.settings.url?.let { url.setText(it) }
                result.settings.tlsPin?.let { pin.setText(it) }
                updateStatus()
            }
        }
    }

    private fun updateStatus() {
        val running = if (BridgeHub.isRunning) "" else "\n" + getString(R.string.relay_not_running)
        status.text = controller.statusText() + running
    }
}
