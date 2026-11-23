package dev.phonebridge.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * One-time screen-capture consent. The result (or denial) is forwarded to [BridgeRuntime] so the
 * next `screenshot` command can use MediaProjection.
 */
class MediaProjectionConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val intent = manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                BridgeRuntime.setMediaProjectionResult(resultCode, data)
            } else {
                BridgeRuntime.clearMediaProjection()
            }
        }
        finish()
    }

    companion object {
        private const val REQ_CAPTURE = 1001

        fun start(context: Context) {
            val intent = Intent(context, MediaProjectionConsentActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
