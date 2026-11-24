package dev.phonebridge.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** "Last crash" screen: shows the saved report and lets the user copy it to send to whoever helps them. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.crash_title)
        val report = CrashReporter.lastCrash(this) ?: getString(R.string.crash_none)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_hint)
            textSize = 14f
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = getString(R.string.crash_copy)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("PhoneBridge crash", report))
                Toast.makeText(this@CrashActivity, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.crash_clear)
            setOnClickListener {
                CrashReporter.clear(this@CrashActivity)
                finish()
            }
        })
        root.addView(buttons)
        val scroll = ScrollView(this)
        scroll.addView(TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        })
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root, android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
}
