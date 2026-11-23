package dev.phonebridge.ui

import dev.phonebridge.core.PhoneBridgeException
import dev.phonebridge.core.model.PairResult
import dev.phonebridge.core.model.PairingReasons
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.pairing.PendingApproval

/** `View` / `Control` / `Full`, for buttons and rows. */
fun PermissionLevel.displayLabel(): String = when (this) {
    PermissionLevel.VIEW -> "View"
    PermissionLevel.CONTROL -> "Control"
    PermissionLevel.FULL -> "Full"
}

/** What the "Allow this bot?" screen shows: name, fingerprint, requested level and time left. */
data class ApprovalView(
    val key: String,
    val botId: String,
    val botName: String,
    val fingerprint: String,
    val requestedLevel: PermissionLevel,
    val receivedAtMs: Long,
    val expiresAtMs: Long,
) {
    fun secondsRemaining(now: Long): Long =
        (((expiresAtMs - now).coerceAtLeast(0L)) + 999L) / 1000L

    fun isExpired(now: Long): Boolean = now >= expiresAtMs

    val requestedLabel: String get() = requestedLevel.displayLabel()

    /** The user may lower the level, never raise it above what the bot asked for. */
    fun grantable(level: PermissionLevel): PermissionLevel = PermissionLevel.min(level, requestedLevel)
}

/** Outcome of Allow / Deny, so the screen can show a message and close. */
sealed interface ApprovalOutcome {
    data class Approved(val view: ApprovalView, val grantedLevel: PermissionLevel, val result: PairResult) : ApprovalOutcome
    data class Denied(val view: ApprovalView, val result: PairResult) : ApprovalOutcome
    data class Failed(val message: String) : ApprovalOutcome
}

/**
 * The device side of "Allow this bot?".
 *
 * The service decrypts a bot's `pair_request` through `:core`'s [PairingManager] and hands the
 * [PendingApproval] here. The user then taps Allow (optionally with a lower level) or Deny; the
 * sealed `pair_result` goes back through [sendAnswer] on the bot's relay route, and the bot is stored
 * in the paired list only on Allow.
 *
 * Requests are dropped once the pairing offer's ten minutes are over, because the bot can no longer
 * decrypt an answer sealed with that offer.
 */
class ApprovalController(
    private val manager: PairingManager,
    private val sendAnswer: (botId: String, sealedBase64: String) -> Unit,
    private val log: ActivityLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (() -> Unit)? = null,
) {
    private data class Pending(val approval: PendingApproval, val view: ApprovalView)

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()

    /** A decrypted request from the relay. Replaces a duplicate for the same bot and offer. */
    fun onRequest(approval: PendingApproval, expiresAtMs: Long): ApprovalView {
        val view = ApprovalView(
            key = keyOf(approval),
            botId = approval.botId,
            botName = approval.botName,
            fingerprint = approval.botFingerprint,
            requestedLevel = approval.requestedLevel,
            receivedAtMs = approval.receivedAtMs,
            expiresAtMs = expiresAtMs,
        )
        synchronized(lock) { pending[view.key] = Pending(approval, view) }
        onChange?.invoke()
        return view
    }

    /** Pending requests that have not expired yet, oldest first. */
    fun views(): List<ApprovalView> {
        val now = clock()
        synchronized(lock) {
            pending.entries.removeAll { (_, value) -> value.view.isExpired(now) }
            return pending.values.map { it.view }.toList()
        }
    }

    fun find(key: String): ApprovalView? = synchronized(lock) { pending[key]?.view }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** "Allow": stores the bot at [level] (never above what it requested) and answers the bot. */
    fun approve(key: String, level: PermissionLevel? = null): ApprovalOutcome {
        val entry = synchronized(lock) { pending[key] } ?: return ApprovalOutcome.Failed(noLongerPending)
        val granted = entry.view.grantable(level ?: entry.view.requestedLevel)
        if (entry.view.isExpired(clock())) {
            remove(key)
            return ApprovalOutcome.Failed(expiredMessage)
        }
        return try {
            val answer = manager.approve(entry.approval, granted)
            sendAnswer(entry.view.botId, answer.sealedBase64())
            log.record(
                entry.view.botId,
                entry.view.botName,
                "pairing_approved",
                true,
                message = "Allowed at ${granted.wire} (fingerprint ${entry.view.fingerprint})",
            )
            remove(key)
            ApprovalOutcome.Approved(entry.view, granted, answer.result)
        } catch (e: PhoneBridgeException) {
            ApprovalOutcome.Failed(e.message ?: "could not approve this bot")
        }
    }

    /** "Deny": burns the request, tells the bot and leaves it unpaired. */
    fun deny(key: String, reason: String = PairingReasons.DENIED): ApprovalOutcome {
        val entry = synchronized(lock) { pending[key] } ?: return ApprovalOutcome.Failed(noLongerPending)
        return try {
            val answer = manager.deny(entry.approval, reason)
            sendAnswer(entry.view.botId, answer.sealedBase64())
            log.record(
                entry.view.botId,
                entry.view.botName,
                "pairing_denied",
                true,
                message = "Denied on this phone",
            )
            remove(key)
            ApprovalOutcome.Denied(entry.view, answer.result)
        } catch (e: PhoneBridgeException) {
            ApprovalOutcome.Failed(e.message ?: "could not deny this bot")
        }
    }

    /** Drops expired requests (notification tap after ten minutes). */
    fun expire() {
        val now = clock()
        val changed = synchronized(lock) {
            val before = pending.size
            pending.entries.removeAll { (_, value) -> value.view.isExpired(now) }
            before != pending.size
        }
        if (changed) onChange?.invoke()
    }

    private fun remove(key: String) {
        synchronized(lock) { pending.remove(key) }
        onChange?.invoke()
    }

    private fun keyOf(approval: PendingApproval): String = approval.offerCode + ":" + approval.botId

    private companion object {
        const val noLongerPending = "This pairing request is no longer pending"
        const val expiredMessage = "This pairing code expired; ask the bot for a new one"
    }
}
