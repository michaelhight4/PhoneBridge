package dev.phonebridge.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import dev.phonebridge.ui.PairingController

/**
 * Shows one live pairing offer: QR code (the `phonebridge://pair?...` link, ZXing), the short code,
 * Share link, Export `.pbpair` (system share sheet), Regenerate, and the countdown to the ten-minute
 * expiry.
 *
 * The offer itself lives in the foreground service, so rotating the screen does not burn the code and
 * closing the screen does not silently mint another one.
 */
class PairingActivity : Activity() {

    private var controller: PairingController? = null
    private var current: PairingController.View? = null

    private lateinit var qr: ImageView
    private lateinit var code: TextView
    private lateinit var countdown: TextView
    private lateinit var relay: TextView
    private lateinit var shareLink: Button
    private lateinit var exportFile: Button

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render(controller?.tick())
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        val pairing = BridgeHub.controller()?.pairingController
        if (pairing == null) {
            Toast.makeText(this, R.string.pairing_not_running, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        controller = pairing

        qr = findViewById(R.id.pairing_qr)
        code = findViewById(R.id.pairing_code)
        countdown = findViewById(R.id.pairing_countdown)
        relay = findViewById(R.id.pairing_relay)
        shareLink = findViewById(R.id.pairing_share_link)
        exportFile = findViewById(R.id.pairing_export_file)

        current = pairing.current() ?: runCatching { pairing.generate() }.getOrNull()
        if (current == null) {
            Toast.makeText(this, R.string.pairing_not_running, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        render(current)

        shareLink.setOnClickListener { shareLink() }
        exportFile.setOnClickListener { sharePairingFile() }
        findViewById<Button>(R.id.pairing_regenerate).setOnClickListener {
            current = runCatching { controller?.regenerate() }.getOrNull()
            if (current == null) {
                Toast.makeText(this, R.string.pairing_not_running, Toast.LENGTH_LONG).show()
                finish()
            } else {
                render(current)
            }
        }
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun render(view: PairingController.View?) {
        if (view == null) return
        current = view
        code.text = view.offer.code
        relay.text = getString(R.string.pairing_relay_format, view.offer.relay)

        if (view.expired) {
            qr.alpha = 0.15f
            countdown.setText(R.string.pairing_expired)
            shareLink.isEnabled = false
            exportFile.isEnabled = false
        } else {
            qr.alpha = 1f
            qr.setImageBitmap(QrCodes.bitmap(view.qrPayload, 720))
            val minutes = view.secondsRemaining / 60
            val seconds = view.secondsRemaining % 60
            countdown.text = getString(R.string.pairing_countdown_format, minutes, seconds)
            shareLink.isEnabled = true
            exportFile.isEnabled = true
        }
    }

    private fun shareLink() {
        val view = current ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.pairing_share_title))
            putExtra(Intent.EXTRA_TEXT, view.link)
        }
        startActivity(Intent.createChooser(send, getString(R.string.pairing_share_link)))
    }

    /** Puts the .pbpair file in the system share sheet (chat, email, Drive, Files, ...). */
    private fun sharePairingFile() {
        val view = current ?: return
        if (view.expired) return
        try {
            val directory = PairingFileProvider.directory(this)
            val file = java.io.File(directory, getString(R.string.pairing_export_name, view.offer.code))
            file.writeText(view.fileContents)
            val uri = PairingFileProvider.uriFor(file.name)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.pairing_share_title))
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("pairing file", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.pairing_export_file)))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Could not prepare the file", Toast.LENGTH_LONG).show()
        }
    }
}
