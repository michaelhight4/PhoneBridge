package dev.phonebridge.desktop.session

import dev.phonebridge.core.PairingException
import dev.phonebridge.core.crypto.E2ESession
import dev.phonebridge.core.crypto.Frame
import dev.phonebridge.core.crypto.NoiseRole
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.AppMessage
import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.AppRequest
import dev.phonebridge.core.model.AppResponse
import dev.phonebridge.core.model.ErrorCode
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.pairing.BotRecord
import dev.phonebridge.core.pairing.BotRegistry
import dev.phonebridge.core.pairing.PairingAnswer
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.pairing.PendingApproval
import dev.phonebridge.desktop.executor.BotSession
import dev.phonebridge.desktop.state.ActivityDetails
import dev.phonebridge.desktop.state.ActivityEntryFactory
import dev.phonebridge.desktop.state.ActivityLog
import dev.phonebridge.desktop.state.DesktopState

/**
 * Turns relay envelopes into paired-bot sessions on the desktop:
 *
 * - a `pair_request` from an unknown peer is decrypted against the live offers and shown as
 *   "Allow this bot?";
 * - a paired bot's Noise `XX` handshake completes here (the PC is the responder) with the bot's pinned
 *   key, then decrypted requests go to the [dev.phonebridge.desktop.executor.DesktopExecutor];
 * - disabling, unpairing or "Kick everyone" sends a sealed `kicked` event and drops the session.
 *
 * The relay only forwards ciphertext; every frame here is a Noise handshake message or transport
 * ciphertext.
 */
class DesktopSessionHandler(
    private val identity: Identity,
    private val registry: BotRegistry,
    private val pairingManager: PairingManager,
    private val state: DesktopState,
    private val activity: ActivityLog,
    private val executor: (BotSession, AppRequest) -> AppResponse,
    private val send: (to: String, payload: ByteArray) -> Unit,
    private val onPairing: (PendingApproval) -> Unit,
    private val onChange: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val sessions = LinkedHashMap<String, E2ESession>()
    private var lastControllerSent: String? = null

    fun onRoute(envelope: RelayEnvelope) {
        if (envelope.type != RelayType.ROUTE) return
        val from = envelope.from ?: return
        val payload = try {
            envelope.payloadBytes()
        } catch (_: Exception) {
            return
        }
        val record = registry.get(from)
        if (record == null) {
            tryPairing(payload)
            return
        }
        if (!record.enabled) return

        val session = synchronized(lock) {
            sessions.getOrPut(from) { E2ESession(NoiseRole.RESPONDER, identity.keys) }
        }
        when (payload.firstOrNull()) {
            Frame.HANDSHAKE -> handleHandshake(from, record, session, payload)
            Frame.TRANSPORT -> handleTransport(from, record, session, payload)
            else -> Unit
        }
    }

    /** Approves a pending request at [level], stores the bot and answers the sealed `pair_result`. */
    fun approve(pending: PendingApproval, level: PermissionLevel = pending.requestedLevel): PairingAnswer? {
        val granted = PermissionLevel.min(level, pending.requestedLevel)
        return try {
            val answer = pairingManager.approve(pending, granted)
            send(pending.botId, answer.sealed)
            activity.record(
                ActivityEntryFactory.make(
                    pending.botId,
                    pending.botName,
                    "pair_approve",
                    true,
                    detail = "approved at ${granted.wire}",
                    clock = clock,
                ),
            )
            onChange()
            answer
        } catch (e: Exception) {
            activity.record(
                ActivityEntryFactory.make(
                    pending.botId,
                    pending.botName,
                    "pair_approve",
                    false,
                    code = "pairing_failed",
                    detail = e.message,
                    clock = clock,
                ),
            )
            onChange()
            null
        }
    }

    /** Denies a pending request; the offer stays burned. */
    fun deny(pending: PendingApproval): Boolean = try {
        val answer = pairingManager.deny(pending)
        send(pending.botId, answer.sealed)
        activity.record(
            ActivityEntryFactory.make(pending.botId, pending.botName, "pair_deny", true, detail = "denied by the user", clock = clock),
        )
        onChange()
        true
    } catch (e: Exception) {
        onChange()
        false
    }

    /** Closes the given sessions, telling each bot why, and updates shared state. */
    fun closeSessions(botIds: List<String>, reason: String) {
        val kicked = AppMessages.encodeEvent(AppMessages.kicked(reason))
        for (botId in botIds) {
            val session = synchronized(lock) { sessions.remove(botId) } ?: continue
            if (session.channel != null) runCatching { send(botId, session.sealText(kicked)) }
            state.onBotDisconnected(botId)
        }
        onChange()
    }

    fun liveBotIds(): List<String> = synchronized(lock) { sessions.keys.toList() }

    /** Sends `control_changed` to every connected bot when the controller actually moved. */
    fun broadcastControlChangedIfNeeded() {
        val current = state.controller()
        val changed = synchronized(lock) {
            if (lastControllerSent == current) {
                false
            } else {
                lastControllerSent = current
                true
            }
        }
        if (!changed) return
        val text = AppMessages.encodeEvent(AppMessages.controlChanged(current, state.controllerName()))
        val targets = synchronized(lock) { sessions.entries.map { it.key to it.value } }
        for ((botId, session) in targets) {
            if (session.channel == null) continue
            runCatching { send(botId, session.sealText(text)) }
        }
    }

    /** Sends `relay_moved` to every connected bot so they can follow a heart that just moved here. */
    fun broadcastRelayMoved(newUrl: String, token: String? = null, expires: Long? = null) {
        val text = AppMessages.encodeEvent(AppMessages.relayMoved(newUrl, token, expires))
        val targets = synchronized(lock) { sessions.entries.map { it.key to it.value } }
        for ((botId, session) in targets) {
            if (session.channel == null) continue
            runCatching { send(botId, session.sealText(text)) }
        }
    }

    /** Seals one unsolicited event for [botId] (screenshot chunks, stream frames). */
    fun sendEvent(botId: String, event: dev.phonebridge.core.model.AppEvent): Boolean {
        val session = synchronized(lock) { sessions[botId] } ?: return false
        if (session.channel == null) return false
        return runCatching { send(botId, session.sealEvent(event)); true }.getOrDefault(false)
    }

    /** Forgets every session without sending anything (the app is quitting). */
    fun closeAll() {
        val ids = synchronized(lock) {
            val current = sessions.keys.toList()
            sessions.clear()
            current
        }
        for (id in ids) state.onBotDisconnected(id)
        onChange()
    }

    // ------------------------------------------------------------------ internals

    private fun handleHandshake(from: String, record: BotRecord, session: E2ESession, payload: ByteArray) {
        try {
            session.readHandshakeFrame(payload)
            if (session.canWriteHandshake) send(from, session.writeHandshakeFrame())
            val channel = session.channel ?: return
            channel.requirePeer(record.publicKeyBytes)
        } catch (_: Exception) {
            synchronized(lock) { sessions.remove(from) }
            state.onBotDisconnected(from)
            onChange()
            return
        }
        registry.touch(from, clock())
        state.onBotConnected(from)
        synchronized(lock) { lastControllerSent = null }
        broadcastControlChangedIfNeeded()
        onChange()
    }

    private fun handleTransport(from: String, record: BotRecord, session: E2ESession, payload: ByteArray) {
        if (session.channel == null) return
        val text = try {
            session.openText(payload)
        } catch (_: Exception) {
            // Tampered, replayed or out-of-order ciphertext: drop the session and require a new one.
            synchronized(lock) { sessions.remove(from) }
            state.onBotDisconnected(from)
            onChange()
            return
        }
        val message = try {
            AppMessages.parse(text)
        } catch (_: Exception) {
            return
        }
        if (message is AppMessage.Request) handleRequest(from, record, session, message.request)
    }

    private fun handleRequest(from: String, record: BotRecord, session: E2ESession, request: AppRequest) {
        val botSession = BotSession(record.botId, record.name, record.level)
        val response = try {
            executor(botSession, request)
        } catch (e: Exception) {
            AppMessages.errorResponse(request.id, ErrorCode.INTERNAL, e.message ?: "the PC could not run the command")
        }
        runCatching { send(from, session.sealText(AppMessages.encodeResponse(response))) }

        if (request.cmd == "request_control") {
            val granted = response.result?.get("granted") as? kotlinx.serialization.json.JsonPrimitive
            if (granted?.content == "false") {
                val position = (response.result?.get("position") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toIntOrNull()
                if (position != null) {
                    runCatching {
                        send(from, session.sealText(AppMessages.encodeEvent(AppMessages.queuePosition(position))))
                    }
                }
            }
        }

        activity.record(
            ActivityEntryFactory.make(
                botId = record.botId,
                botName = record.name,
                cmd = request.cmd,
                ok = response.ok,
                code = response.error?.code,
                detail = describe(request),
                clock = clock,
            ),
        )
        registry.touch(record.botId, clock())
        broadcastControlChangedIfNeeded()
        onChange()
    }

    private fun describe(request: AppRequest): String? = try {
        ActivityDetails.describe(request.cmd, request.args)
    } catch (_: Exception) {
        null
    }

    private fun tryPairing(payload: ByteArray) {
        // Try each live offer; only the matching secret decrypts and the manager burns it on first use.
        for (offer in pairingManager.liveOffers()) {
            val pending = try {
                pairingManager.accept(offer, payload)
            } catch (_: PairingException) {
                continue
            } catch (_: Exception) {
                continue
            }
            onPairing(pending)
            onChange()
            return
        }
    }
}
