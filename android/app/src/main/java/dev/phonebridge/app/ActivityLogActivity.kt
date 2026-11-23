package dev.phonebridge.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import dev.phonebridge.ui.ActivityEntry
import dev.phonebridge.ui.ActivityLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-bot activity log: every command with its time and result, newest first, filterable per bot.
 * Typed text and shell commands are not copied into the log (see ui-core `ExecutorProtocol`).
 */
class ActivityLogActivity : Activity() {

    private lateinit var log: ActivityLog
    private lateinit var list: ListView
    private lateinit var filter: Spinner
    private lateinit var adapter: ArrayAdapter<String>

    private val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    private var selectedBotId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        log = BridgeHub.controller()?.activityLog ?: AppStores.activityLog(this)
        list = findViewById(R.id.log_list)
        filter = findViewById(R.id.log_filter)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.log_empty)

        findViewById<Button>(R.id.log_clear).setOnClickListener { confirmClear() }
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedBotId = if (position == 0) null else bots.getOrNull(position - 1)?.second
                refresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private var bots: List<Pair<String, String>> = emptyList()

    override fun onResume() {
        super.onResume()
        refreshFilters()
        refresh()
    }

    private fun refreshFilters() {
        val registry = BridgeHub.controller()?.registry ?: AppStores.registry(this)
        bots = registry.list().map { it.name to it.botId }
        @Suppress("UNCHECKED_CAST")
        val entries = (listOf(getString(R.string.log_filter_all)) + bots.map { it.first })
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries)
        filter.adapter = spinnerAdapter
        // Keep the previous selection when possible.
        val index = bots.indexOfFirst { it.second == selectedBotId }
        filter.setSelection(if (index >= 0) index + 1 else 0)
    }

    private fun refresh() {
        val entries: List<ActivityEntry> = selectedBotId?.let { log.entriesFor(it) } ?: log.entries()
        adapter.clear()
        adapter.addAll(entries.map { format(it) })
        adapter.notifyDataSetChanged()
    }

    private fun format(entry: ActivityEntry): String {
        val time = timeFormat.format(Date(entry.at))
        val line = "$time  ${entry.botName}: ${entry.summary()}"
        val message = entry.message
        return if (message.isNullOrBlank()) line else "$line\n    $message"
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_clear_title)
            .setMessage(R.string.log_clear_message)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                log.clear()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
