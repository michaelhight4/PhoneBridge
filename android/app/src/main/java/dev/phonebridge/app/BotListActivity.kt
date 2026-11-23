package dev.phonebridge.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.ui.BotListController
import dev.phonebridge.ui.BotRow

/**
 * The Bluetooth-style bot list: name, fingerprint, last seen, on/off switch, permission level and
 * unpair, plus "Kick everyone". Works even when the bridge service is stopped (the registry is on
 * disk); live connection state is greyed out then.
 */
class BotListActivity : Activity() {

    private lateinit var controller: BotListController
    private lateinit var list: ListView
    private lateinit var adapter: BotAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bot_list)

        controller = BridgeHub.botList(this)
        list = findViewById(R.id.bot_list)
        adapter = BotAdapter()
        list.adapter = adapter
        list.emptyView = findViewById(R.id.bot_empty)

        findViewById<Button>(R.id.bot_kick_everyone).setOnClickListener { confirmKickEveryone() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(controller.rows())
    }

    private fun chooseLevel(row: BotRow) {
        val levels = PermissionLevel.entries
        val labels = levels.map { shortName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bots_level_title, row.name))
            .setSingleChoiceItems(labels, levels.indexOf(row.level)) { dialog, which ->
                controller.setLevel(row.botId, levels[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun shortName(level: PermissionLevel): String = when (level) {
        PermissionLevel.VIEW -> "View"
        PermissionLevel.CONTROL -> "Control"
        PermissionLevel.FULL -> "Full"
    }

    private fun confirmUnpair(row: BotRow) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bots_unpair_title, row.name))
            .setMessage(R.string.bots_unpair_message)
            .setPositiveButton(R.string.action_unpair) { _, _ ->
                controller.unpair(row.botId)
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmKickEveryone() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bots_kick_title)
            .setMessage(R.string.bots_kick_message)
            .setPositiveButton(R.string.action_kick_everyone) { _, _ ->
                val bridge = BridgeHub.controller()
                if (bridge == null) {
                    Toast.makeText(this, R.string.pairing_not_running, Toast.LENGTH_LONG).show()
                } else {
                    val kicked = bridge.kickEveryone()
                    Toast.makeText(
                        this,
                        getString(R.string.bots_kicked, kicked.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun PermissionLevel.label(): String = when (this) {
        PermissionLevel.VIEW -> getString(R.string.level_view)
        PermissionLevel.CONTROL -> getString(R.string.level_control)
        PermissionLevel.FULL -> getString(R.string.level_full)
    }

    private inner class BotAdapter : BaseAdapter() {
        private var rows: List<BotRow> = emptyList()

        fun submit(fresh: List<BotRow>) {
            rows = fresh
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): BotRow = rows[position]
        override fun getItemId(position: Int): Long = rows[position].botId.hashCode().toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = rows[position]
            val view = recycled ?: LayoutInflater.from(this@BotListActivity)
                .inflate(R.layout.item_bot, parent, false)

            view.findViewById<TextView>(R.id.bot_name).text = row.name
            view.findViewById<TextView>(R.id.bot_fingerprint).text = row.fingerprint

            val state = when {
                !row.enabled -> getString(R.string.bots_state_switched_off)
                row.inControl -> getString(R.string.bots_state_in_control)
                row.queuePosition != null -> getString(R.string.bots_state_waiting, row.queuePosition)
                row.connected -> getString(R.string.bots_state_connected)
                else -> getString(R.string.bots_state_offline)
            }
            view.findViewById<TextView>(R.id.bot_meta).text =
                getString(R.string.bots_meta_format, row.lastSeenText, "${levelShort(row.level)} · $state")

            val toggle = view.findViewById<Switch>(R.id.bot_enabled)
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = row.enabled
            toggle.setOnCheckedChangeListener { _, checked ->
                controller.setEnabled(row.botId, checked)
                refresh()
            }

            view.findViewById<Button>(R.id.bot_level).text =
                getString(R.string.bots_change_level) + ": " + levelShort(row.level)
            view.findViewById<Button>(R.id.bot_level).setOnClickListener { chooseLevel(row) }
            view.findViewById<Button>(R.id.bot_unpair).setOnClickListener { confirmUnpair(row) }
            return view
        }

        private fun levelShort(level: PermissionLevel): String = when (level) {
            PermissionLevel.VIEW -> "View"
            PermissionLevel.CONTROL -> "Control"
            PermissionLevel.FULL -> "Full"
        }
    }
}
