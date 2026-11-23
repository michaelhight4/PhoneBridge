package dev.phonebridge.app

import dev.phonebridge.core.crypto.E2ESession
import dev.phonebridge.core.crypto.Frame
import dev.phonebridge.core.crypto.NoiseRole
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.AppMessage
import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.pairing.BotRecord
import dev.phonebridge.core.pairing.BotRegistry
import dev.phonebridge.core.PairingException
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.pairing.PendingApproval
import dev.phonebridge.executor.BotSession
import dev.phonebridge.executor.ErrorCode as ExecutorErrorCode
import dev.phonebridge.executor.Request
import dev.phonebridge.executor.Response
import dev.phonebridge.ui.BridgeState
import dev.phonebridge.ui.ExecutorProtocol

/**
 * Turns relay envelopes into paired-bot sessions:
 *
 * - `pair_request` from an unknown peer is decrypted with the live pairing offers and handed to the
 *   "Allow this bot?" controller;
 * - a paired bot's Noise `XX` handshake completes here (the phone is the responder), with the bot's
 *   public key pinned to the one in the paired list;
 * - decrypted `AppRequest`s go to the command executor and the sealed response goes back through
 *   the same relay route;
 * - disabling, unpairing or "Kick everyone" sends a sealed `kicked` event and drops the session.
 *
 * The relay never sees plaintext: every frame here is either a Noise handshake message or Noise
 * transport ciphertext.
 */
class SessionHandler(
    private val identity: Identity,
    private val registry: BotRegistry,
    private val pairingManager: PairingManager,
    private val state: BridgeState,
    private val executor: (BotSession, Request) -> Response,
    /**
     * Sends a base64url payload to a peer through the relay. This is the raw envelope payload; the
     * caller base64url-encodes the Noise frame.
     */
    private val send: (to: String, payload: ByteArray) -> Unit,
    private val onPairing: (PendingApproval, expiresAtMs: Long) -> Unit,
    private val onSessionsChanged: () -> Unit,
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

    /** Closes the given sessions (optionally telling each bot why) and updates shared state. */
    fun closeSessions(botIds: List<String>, reason: String) {
        val kickedEvent = AppMessages.encodeEvent(AppMessages.kicked(reason))
        for (botId in botIds) {
            val session = synchronized(lock) { sessions.remove(botId) } ?: continue
            val channel = session.channel
            if (channel != null) {
                runCatching { send(botId, session.sealText(kickedEvent)) }
            }
            state.onBotDisconnected(botId)
        }
        onSessionsChanged()
    }

    /** Every live session, for the notification and the bot list. */
    fun liveBotIds(): List<String> = synchronized(lock) { sessions.keys.toList() }

    /** Sends `control_changed` to every connected bot when the controller actually moved. */
    fun broadcastControlChangedIfNeeded() {
        val current = state.controllerId()
        val changed = synchronized(lock) {
            if (lastControllerSent == current) {
                false
            } else {
                lastControllerSent = current
                true
            }
        }
        if (!changed) return
        val event = AppMessages.encodeEvent(AppMessages.controlChanged(current, state.controllerName()))
        val targets = synchronized(lock) { sessions.entries.map { it.key to it.value } }
        for ((botId, session) in targets) {
            if (session.channel == null) continue
            runCatching { send(botId, session.sealText(event)) }
        }
    }

    /** Forgets every session without sending anything (service stopping). */
    fun closeAll() {
        val ids = synchronized(lock) {
            val ids = sessions.keys.toList()
            sessions.clear()
            ids
        }
        for (id in ids) state.onBotDisconnected(id)
        onSessionsChanged()
    }

    // ------------------------------------------------------------------ internals

    private fun handleHandshake(from: String, record: BotRecord, session: E2ESession, payload: ByteArray) {
        try {
            session.readHandshakeFrame(payload)
            if (session.canWriteHandshake) {
                send(from, session.writeHandshakeFrame())
            }
            val channel = session.channel ?: return
            // Pin the key: a bot cannot take over a paired identity by guessing a peer id.
            channel.requirePeer(record.publicKeyBytes)
        } catch (_: Exception) {
            synchronized(lock) { sessions.remove(from) }
            state.onBotDisconnected(from)
            onSessionsChanged()
            return
        }

        state.onBotConnected(from)
        // A fresh handshake is a fresh session; send the current control state to it.
        synchronized(lock) { lastControllerSent = null }
        broadcastControlChangedIfNeeded()
        onSessionsChanged()
    }

    private fun handleTransport(from: String, record: BotRecord, session: E2ESession, payload: ByteArray) {
        if (session.channel == null) return
        val text = try {
            session.openText(payload)
        } catch (_: Exception) {
            // Tampered, replayed or out-of-order ciphertext: drop the session and require a new one.
            synchronized(lock) { sessions.remove(from) }
            state.onBotDisconnected(from)
            onSessionsChanged()
            return
        }
        val message = try {
            AppMessages.parse(text)
        } catch (_: Exception) {
            return
        }
        if (message is AppMessage.Request) {
            handleRequest(from, record, session, message.request)
        }
    }

    private fun handleRequest(
        from: String,
        record: BotRecord,
        session: E2ESession,
        wire: dev.phonebridge.core.model.AppRequest,
    ) {
        val request = ExecutorProtocol.toExecutor(wire)
        val botSession = BotSession(record.botId, record.name, record.level)
        val response = try {
            executor(botSession, request)
        } catch (_: Exception) {
            Response.failure(request.id, ExecutorErrorCode.INTERNAL, "the device could not run the command")
        }

        runCatching {
            val appResponse = ExecutorProtocol.toAppResponse(response)
            send(from, session.sealText(AppMessages.encodeResponse(appResponse)))
        }

        if (request.cmd == "request_control") {
            val granted = response.result?.get("granted") as? Boolean
            if (granted == false) {
                val position = (response.result?.get("position") as? Number)?.toInt() ?: 0
                runCatching {
                    send(
                        from,
                        session.sealText(AppMessages.encodeEvent(AppMessages.queuePosition(position))),
                    )
                }
            }
        }

        state.onCommand(
            botId = record.botId,
            botName = record.name,
            cmd = request.cmd,
            ok = response.ok,
            code = response.error?.code?.wire,
            message = response.error?.message,
            detail = ExecutorProtocol.detailFor(request),
        )
        // state.onCommand refreshed the arbiter; this pushes control_changed to every bot if it moved.
        broadcastControlChangedIfNeeded()
        onSessionsChanged()
    }

    private fun tryPairing(payload: ByteArray) {
        // The relay route carries the sealed pair_request. Try every live offer (a handful at most);
        // only the matching secret decrypts, and PairingManager burns it on first use.
        for (offer in pairingManager.liveOffers()) {
            val pending = try {
                pairingManager.accept(offer, payload)
            } catch (_: PairingException) {
                continue
            } catch (_: Exception) {
                continue
            }
            onPairing(pending, offer.expiresAtMillis)
            return
        }
    }
}
