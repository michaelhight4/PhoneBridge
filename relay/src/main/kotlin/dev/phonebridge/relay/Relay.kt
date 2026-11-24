package dev.phonebridge.relay

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.crypto.HelloAuth
import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.crypto.X25519
import dev.phonebridge.core.crypto.X25519KeyPair
import dev.phonebridge.core.identity.PeerIds
import dev.phonebridge.core.model.EnvelopeCodec
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayLimits
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.pairing.ShortCode
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.crypto.Tls
import dev.phonebridge.relay.ws.WebSocketConnection
import dev.phonebridge.relay.ws.WebSocketException
import dev.phonebridge.relay.ws.WebSocketServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Counters and health information for a running relay. */
data class RelayStats(
    val uptimeMs: Long,
    val peers: Int,
    val envelopesRouted: Long,
    val envelopesDropped: Long,
    val peerOffline: Long,
    val rateLimited: Long,
    val mailboxEntries: Int
)

/**
 * A running relay. Embedded callers keep one of these for the lifetime of their app and call [stop]
 * when the user turns the relay off.
 */
interface RelayHandle : AutoCloseable {
    /** The URL peers connect to, e.g. `wss://192.168.1.20:8443`. */
    val url: String
    val port: Int
    /** This relay's own peer id (docs/PROTOCOL.md identity form). */
    val relayId: String
    /** base64url SHA-256 of the TLS certificate; pin this in pairing offers. Null for plain `ws://`. */
    val fingerprint: String?
    /** The same fingerprint as colon separated hex, for humans. */
    val fingerprintHex: String?
    val isRunning: Boolean

    fun stop()

    /**
     * Hands routing over to a new relay: every connected peer receives `relay_moved` with a fresh
     * one-time token for [newUrl] and reconnects there. Returns the number of peers notified.
     */
    fun moveTo(newUrl: String, ttlMs: Long = 60_000): Int

    fun stats(): RelayStats

    /** Currently connected, authenticated peer ids. */
    fun peers(): List<String>

    override fun close() = stop()
}

/**
 * The heart, embedded.
 *
 * ```kotlin
 * val relay = Relay.start(RelayConfig(port = 8443, tls = TlsConfig.selfSigned()))
 * println("pin this: ${relay.fingerprint}")
 * relay.stop()
 * ```
 *
 * The relay speaks the `:core` protocol types (`RelayEnvelope`, `EnvelopeCodec`, `HelloAuth`,
 * `PeerIds`) and forwards the raw received text, so an envelope's encrypted payload is never decoded
 * or rewritten.
 */
object Relay {
    fun start(config: RelayConfig = RelayConfig()): RelayHandle = RelayServer.create(config)
}

internal class RelayServer private constructor(
    private val config: RelayConfig,
    private val material: Tls.Material?,
    private val identity: X25519KeyPair,
    private val moveTokens: MoveTokenService
) : RelayHandle {

    private val peers = ConcurrentHashMap<String, Session>()
    private val mailbox = PairMailbox(config.pairMailboxTtlMs)
    private val botMailbox = BotMailbox(
        dataDir = config.dataDir,
        maxBotMessageBytes = config.maxBotMessageBytes,
        maxMailboxMessagesPerBot = config.maxMailboxMessagesPerBot,
        maxMailboxBytesPerBot = config.maxMailboxBytesPerBot,
        maxTotalMailboxMessages = config.maxTotalMailboxMessages,
        botMessageTtlMs = config.botMessageTtlMs,
    )
    private val usedMoveTokens = ConcurrentHashMap<String, Long>()

    private val envelopesRouted = AtomicLong()
    private val envelopesDropped = AtomicLong()
    private val peerOffline = AtomicLong()
    private val rateLimited = AtomicLong()

    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var running = false

    private val server = WebSocketServer(
        bindAddress = config.bindAddress,
        requestedPort = config.port,
        sslContext = material?.sslContext,
        maxMessageBytes = config.maxEnvelopeBytes,
        idleTimeoutMs = config.idleTimeoutMs,
        maxConnections = config.maxConnections,
        log = config.log
    )

    override val relayId: String = PeerIds.peerId(identity.publicKey)
    override val fingerprint: String? = material?.fingerprint
    override val fingerprintHex: String? = material?.fingerprintHex
    override val port: Int get() = server.port
    override val url: String
        get() = (if (material != null) "wss" else "ws") + "://" + advertisedHost() + ":" + port

    override val isRunning: Boolean get() = running

    private fun advertisedHost(): String {
        config.advertisedHost?.let { return it }
        val bind = config.bindAddress
        val wildcard = bind == "0.0.0.0" || bind == "::" || bind == "[::]"
        return if (wildcard) "127.0.0.1" else bind
    }

    private fun startRelay(): RelayHandle {
        server.healthProvider = {
            buildJsonObject {
                put("ok", true)
                put("relay_id", relayId)
                put("peers", peers.size)
                put("uptime_ms", System.currentTimeMillis() - startedAt)
            }.toString()
        }
        server.start { connection -> Session(connection).run() }
        running = true
        config.log("relay listening on $url (relay id $relayId)")
        fingerprint?.let { config.log("certificate fingerprint (pin in pairing offers): $it") }
        return this
    }

    override fun stop() {
        if (!running) return
        running = false
        mailbox.clear()
        for (session in peers.values) {
            session.conn.close(WebSocketConnection.CLOSE_GOING_AWAY, "relay stopping")
        }
        peers.clear()
        server.stop()
        config.log("relay stopped")
    }

    override fun moveTo(newUrl: String, ttlMs: Long): Int {
        require(newUrl.startsWith("ws://") || newUrl.startsWith("wss://")) {
            "the new relay URL must start with ws:// or wss://"
        }
        check(running) { "relay is not running" }
        check(moveTokens.enabled) {
            "relay_move needs moveSecret to be shared with the new relay"
        }
        require(ttlMs > 0) { "ttlMs must be positive" }
        val expiresAtSeconds = (System.currentTimeMillis() + ttlMs) / 1000
        var notified = 0
        for (session in peers.values) {
            val peer = session.peerId ?: continue
            val message = RelayEnvelope(
                type = RelayType.RELAY_MOVED,
                to = peer,
                id = RelayEnvelope.newId(),
                newUrl = newUrl,
                token = moveTokens.issue(peer, ttlMs),
                expires = expiresAtSeconds
            )
            if (session.send(message)) notified++
        }
        config.log("relay_move: told $notified peer(s) to reconnect to $newUrl")
        return notified
    }

    override fun stats(): RelayStats = RelayStats(
        uptimeMs = System.currentTimeMillis() - startedAt,
        peers = peers.size,
        envelopesRouted = envelopesRouted.get(),
        envelopesDropped = envelopesDropped.get(),
        peerOffline = peerOffline.get(),
        rateLimited = rateLimited.get(),
        mailboxEntries = mailbox.size
    )

    override fun peers(): List<String> = peers.keys().toList()

    /** One connection from one peer. Everything after `hello_ok` is forwarding, never inspection. */
    private inner class Session(val conn: WebSocketConnection) {
        val ephemeral: X25519KeyPair = X25519.generateKeyPair()
        private val nonce = Primitives.randomBytes(32)
        private val nonceB64 = B64Url.encode(nonce)
        private var limiter = RateLimiter(config.rateLimit)
        private val botLimiter = RateLimiter(config.botMessageRateLimit)

        @Volatile
        var peerId: String? = null
            private set

        @Volatile
        var role: String = "unknown"
            private set

        @Volatile
        private var authenticated = false

        private var preAuthMessages = 0
        private var challengeCount = 1
        private var rateViolations = 0
        private var lastRateErrorAt = 0L

        fun run() {
            try {
                conn.setReadTimeout(config.helloTimeoutMs)
                sendChallenge()
                while (true) {
                    val frame = conn.readMessage() ?: break
                    val text = String(frame, Charsets.UTF_8)
                    if (!handleMessage(text)) break
                }
            } catch (e: WebSocketException) {
                conn.close(e.closeCode, e.message ?: "protocol error")
            } catch (_: Exception) {
                // Socket closed, connection reset, TLS failure: nothing left to do.
            } finally {
                unregister()
                conn.closeQuietly(WebSocketConnection.CLOSE_NORMAL)
            }
        }

        private fun sendChallenge() {
            send(
                RelayEnvelope.challenge(
                    nonceB64 = nonceB64,
                    relayPublicKeyB64 = B64Url.encode(ephemeral.publicKey),
                    relayId = relayId,
                    expires = System.currentTimeMillis() / 1000 + config.helloTimeoutMs / 1000
                )
            )
        }

        private fun handleMessage(text: String): Boolean {
            val envelope = try {
                EnvelopeCodec.decode(text)
            } catch (e: ProtocolException) {
                sendError("bad_envelope", e.message ?: "malformed envelope")
                return !authenticated
            }

            if (!authenticated) {
                preAuthMessages++
                if (preAuthMessages > 10) {
                    sendError("unauthorized", "authenticate with hello first")
                    return false
                }
                return when (envelope.type) {
                    RelayType.HELLO -> handleHello(envelope)
                    RelayType.PING -> {
                        sendPong(envelope)
                        true
                    }
                    else -> {
                        sendError("unauthorized", "${envelope.type} needs an authenticated hello")
                        false
                    }
                }
            }

            if (!limiter.tryAcquire()) {
                rateLimited.incrementAndGet()
                maybeSendRateLimited()
                return true
            }

            return when (envelope.type) {
                RelayType.HELLO -> {
                    sendError("already_authenticated", "this connection is already authenticated")
                    true
                }
                RelayType.ROUTE -> handleRoute(envelope, text)
                RelayType.PING -> {
                    sendPong(envelope)
                    true
                }
                RelayType.PAIR_STORE -> {
                    handlePairStore(envelope)
                    true
                }
                RelayType.PAIR_LOOKUP -> {
                    handlePairLookup(envelope)
                    true
                }
                RelayType.PEERS_SYNC -> {
                    handlePeersSync(envelope)
                    true
                }
                RelayType.PEERS_LIST -> {
                    handlePeersList(envelope)
                    true
                }
                RelayType.MSG_SEND -> {
                    if (!botLimiter.tryAcquire()) {
                        rateLimited.incrementAndGet()
                        maybeSendRateLimited()
                    } else {
                        handleMsgSend(envelope)
                    }
                    true
                }
                RelayType.MSG_INBOX -> {
                    handleMsgInbox(envelope)
                    true
                }
                RelayType.MSG_ACK -> {
                    handleMsgAck(envelope)
                    true
                }
                else -> {
                    sendError("unknown_type", "unsupported message type: ${envelope.type}")
                    true
                }
            }
        }

        private fun handleHello(envelope: RelayEnvelope): Boolean {
            val claimedId = envelope.from
                ?: return rejectHello("bad_envelope", "hello needs a from peer id")
            val publicKeyB64 = envelope.pub
                ?: return rejectHello("bad_envelope", "hello needs a public key")
            val publicKey = try {
                B64Url.decode(publicKeyB64)
            } catch (_: IllegalArgumentException) {
                return rejectHello("bad_envelope", "hello public key is not base64url")
            }
            if (publicKey.size != X25519.DHLEN) {
                return rejectHello("bad_envelope", "hello public key must be ${X25519.DHLEN} bytes")
            }
            val canonicalId = PeerIds.peerId(publicKey)
            if (!claimedId.equals(canonicalId, ignoreCase = true)) {
                return rejectHello("unauthorized", "peer id does not match the public key")
            }

            val helloNonceB64 = envelope.nonce
            if (helloNonceB64.isNullOrEmpty()) {
                // docs/PROTOCOL.md has the relay challenge in the hello reply. A peer that sends
                // hello before reading the challenge gets a fresh challenge back, not a disconnect.
                if (challengeCount < 3) {
                    challengeCount++
                    sendChallenge()
                    return true
                }
                return rejectHello("unauthorized", "hello must carry the challenge nonce")
            }
            val helloNonce = try {
                B64Url.decode(helloNonceB64)
            } catch (_: IllegalArgumentException) {
                return rejectHello("unauthorized", "hello nonce is not base64url")
            }
            if (!Primitives.constantTimeEquals(helloNonce, nonce)) {
                return rejectHello("unauthorized", "hello nonce does not match the challenge")
            }

            val proofB64 = envelope.sig
                ?: return rejectHello("unauthorized", "hello must carry a key ownership proof")
            val proof = try {
                B64Url.decode(proofB64)
            } catch (_: IllegalArgumentException) {
                return rejectHello("unauthorized", "hello proof is malformed")
            }
            if (proof.size != 32) return rejectHello("unauthorized", "hello proof is malformed")

            val valid = try {
                HelloAuth.verify(
                    relayPrivateKey = ephemeral.privateKey,
                    relayPublicKey = ephemeral.publicKey,
                    relayId = relayId,
                    peerId = canonicalId,
                    peerPublicKey = publicKey,
                    nonce = nonce,
                    proof = proof
                )
            } catch (_: CryptoException) {
                false
            }
            if (!valid) return rejectHello("unauthorized", "hello proof of key ownership failed")

            envelope.moveToken?.let { token ->
                when (val result = moveTokens.verify(token, canonicalId)) {
                    is MoveTokenService.Verify.Invalid ->
                        return rejectHello("unauthorized", "move token rejected: ${result.reason}")
                    is MoveTokenService.Verify.Valid -> {
                        purgeUsedMoveTokens()
                        val key = "$canonicalId|${result.nonce}"
                        if (usedMoveTokens.putIfAbsent(key, result.expiresAt) != null) {
                            return rejectHello("token_used", "that move token has already been used")
                        }
                    }
                }
            }

            peerId = canonicalId
            role = envelope.role?.wire ?: "unknown"
            authenticated = true
            limiter = RateLimiter(config.rateLimit)
            conn.setReadTimeout(config.idleTimeoutMs)

            val previous = peers.put(canonicalId, this)
            if (previous != null && previous !== this) {
                config.log("peer $canonicalId reconnected; closing its previous connection")
                previous.conn.close(REPLACED_CLOSE_CODE, "replaced by a newer connection")
            }

            send(
                RelayEnvelope.helloOk(
                    to = canonicalId,
                    relayId = relayId,
                    serverTime = System.currentTimeMillis() / 1000,
                    limits = RelayLimits(
                        maxEnvelopeBytes = config.maxEnvelopeBytes.toLong(),
                        rateMessages = config.rateLimit.maxMessages,
                        rateWindowMs = config.rateLimit.windowMs.toInt()
                    )
                )
            )
            config.log("peer $canonicalId authenticated as $role")
            return true
        }

        private fun rejectHello(code: String, message: String): Boolean {
            envelopesDropped.incrementAndGet()
            sendError(code, message)
            return false
        }

        private fun handleRoute(envelope: RelayEnvelope, originalText: String): Boolean {
            val to = envelope.to
            if (to.isNullOrBlank()) {
                sendError("bad_envelope", "route needs a to peer id", envelope.id)
                return true
            }
            if (envelope.from != null && envelope.from != peerId) {
                envelopesDropped.incrementAndGet()
                sendError("bad_envelope", "from does not match the authenticated peer", envelope.id)
                return true
            }
            if (to == peerId) {
                envelopesDropped.incrementAndGet()
                sendError("bad_envelope", "a peer cannot route to itself", envelope.id)
                return true
            }

            val target = peers[to]
            if (target == null) {
                peerOffline.incrementAndGet()
                sendError("peer_offline", "peer $to is not connected", envelope.id)
                return true
            }
            try {
                // Forwarded verbatim: the relay never parses or rewrites the encrypted payload.
                target.conn.sendText(originalText)
                envelopesRouted.incrementAndGet()
            } catch (_: Exception) {
                peers.remove(to, target)
                peerOffline.incrementAndGet()
                sendError("peer_offline", "peer $to is not reachable", envelope.id)
            }
            return true
        }

        private fun handlePairStore(envelope: RelayEnvelope) {
            val code = envelope.code?.let(::canonicalCode)
            if (code == null) {
                sendError("bad_args", "pair_store needs a six symbol code")
                return
            }
            val offer = envelope.offer
            if (offer == null) {
                sendError("bad_args", "pair_store needs an offer object")
                return
            }
            try {
                val expiresAt = mailbox.store(code, offer)
                send(RelayEnvelope.pairStored(to = peerId ?: "", code = code, expires = expiresAt / 1000))
            } catch (e: PairMailbox.MailboxFullException) {
                sendError("mailbox_full", e.message ?: "the pairing mailbox is full")
            }
        }

        private fun handlePairLookup(envelope: RelayEnvelope) {
            val code = envelope.code?.let(::canonicalCode)
            if (code == null) {
                sendError("bad_args", "pair_lookup needs a six symbol code")
                return
            }
            when (val result = mailbox.take(code)) {
                is PairMailbox.Take.Found -> send(
                    RelayEnvelope.pairOffer(
                        to = peerId ?: "",
                        code = code,
                        offer = result.offer,
                        expires = result.expiresAt / 1000
                    )
                )
                PairMailbox.Take.Expired -> sendError("code_expired", "that pairing code has expired")
                PairMailbox.Take.Missing -> sendError("code_not_found", "no pairing offer waits for that code")
            }
        }

        private fun handlePeersSync(envelope: RelayEnvelope) {
            if (role != PeerRole.DEVICE.wire) {
                sendError("unauthorized", "only devices may sync bot peers", envelope.id)
                return
            }
            val sender = peerId ?: return
            val count = botMailbox.syncDevicePeers(
                deviceId = sender,
                deviceName = envelope.deviceName,
                peers = envelope.peers.orEmpty(),
            )
            send(RelayEnvelope.peersSynced(to = sender, count = count, id = envelope.id ?: RelayEnvelope.newId()))
        }

        private fun handlePeersList(envelope: RelayEnvelope) {
            val sender = peerId ?: return
            when (val result = botMailbox.listPeersForBot(
                botId = sender,
                deviceId = envelope.deviceId,
                isOnline = ::isBotOnline,
            )) {
                is BotMailbox.PeersResult.Allowed -> send(
                    RelayEnvelope.peersResult(
                        to = sender,
                        peers = result.peers,
                        deviceId = envelope.deviceId,
                        id = envelope.id ?: RelayEnvelope.newId(),
                    ),
                )

                is BotMailbox.PeersResult.Denied ->
                    sendError("permission_denied", result.reason, envelope.id)
            }
        }

        private fun handleMsgSend(envelope: RelayEnvelope) {
            val sender = peerId ?: return
            val incoming = envelope.message
            if (incoming == null) {
                sendError("bad_args", "msg_send needs a message", envelope.id)
                return
            }
            if (incoming.id.isBlank()) {
                sendError("bad_args", "msg_send needs a message id", envelope.id)
                return
            }
            if (incoming.from != sender || (envelope.from != null && envelope.from != sender)) {
                sendError("unauthorized", "msg_send must come from the authenticated bot", envelope.id)
                return
            }
            val payloadBytes = try {
                B64Url.decode(incoming.payload).size
            } catch (_: IllegalArgumentException) {
                sendError("bad_args", "bot message payload is not base64url", envelope.id)
                return
            }
            if (payloadBytes > config.maxBotMessageBytes) {
                sendError("too_large", "bot message exceeds ${config.maxBotMessageBytes} bytes", envelope.id)
                return
            }
            if (!botMailbox.authorizeSend(sender, incoming.to, envelope.deviceId)) {
                sendError("permission_denied", "these bots may not message each other", envelope.id)
                return
            }
            val stored = try {
                botMailbox.enqueue(
                    incoming.copy(from = sender, deviceId = envelope.deviceId ?: incoming.deviceId),
                )
            } catch (e: BotMailbox.MailboxFullException) {
                sendError("mailbox_full", e.message ?: "the bot mailbox is full", envelope.id)
                return
            }
            send(
                RelayEnvelope.msgSent(
                    to = sender,
                    message = stored,
                    id = envelope.id ?: RelayEnvelope.newId(),
                ),
            )
            // Deliver immediately when the recipient is connected; otherwise it reads the mailbox later.
            peers[stored.to]?.send(
                RelayEnvelope.msgDeliver(to = stored.to, message = stored),
            )
        }

        private fun handleMsgInbox(envelope: RelayEnvelope) {
            val sender = peerId ?: return
            val limit = (envelope.limit ?: DEFAULT_INBOX_LIMIT).coerceIn(1, config.maxMailboxMessagesPerBot)
            val messages = botMailbox.readInbox(
                botId = sender,
                limit = limit,
                consume = envelope.consume ?: false,
                ackIds = envelope.ackIds.orEmpty(),
            )
            send(
                RelayEnvelope.msgList(
                    to = sender,
                    messages = messages,
                    id = envelope.id ?: RelayEnvelope.newId(),
                ),
            )
        }

        private fun handleMsgAck(envelope: RelayEnvelope) {
            val sender = peerId ?: return
            val ackedIds = botMailbox.ack(sender, envelope.ids.orEmpty())
            send(
                RelayEnvelope.msgAcked(
                    to = sender,
                    ackIds = ackedIds,
                    count = ackedIds.size,
                    id = envelope.id ?: RelayEnvelope.newId(),
                ),
            )
        }

        private fun isBotOnline(botId: String): Boolean = peers.containsKey(botId)

        private fun sendPong(request: RelayEnvelope) {
            send(
                RelayEnvelope(
                    type = RelayType.PONG,
                    to = peerId,
                    id = request.id ?: RelayEnvelope.newId()
                )
            )
        }

        private fun maybeSendRateLimited() {
            val now = System.currentTimeMillis()
            if (now - lastRateErrorAt > 1000) {
                lastRateErrorAt = now
                sendError("rate_limited", "too many messages; slow down")
            }
            if (++rateViolations > 500) {
                conn.close(CLOSE_POLICY_VIOLATION, "rate limit")
            }
        }

        private fun purgeUsedMoveTokens() {
            if (usedMoveTokens.size < 1024) return
            val now = System.currentTimeMillis()
            usedMoveTokens.entries.removeIf { it.value <= now }
        }

        fun send(envelope: RelayEnvelope): Boolean = try {
            conn.sendText(EnvelopeCodec.encode(envelope))
            true
        } catch (_: Exception) {
            false
        }

        private fun sendError(code: String, message: String, id: String? = null) {
            send(RelayEnvelope.error(code = code, message = message, to = peerId, id = id))
        }

        private fun unregister() {
            val current = peerId ?: return
            peers.remove(current, this)
        }
    }

    companion object {
        private const val REPLACED_CLOSE_CODE = 4001
        private const val CLOSE_POLICY_VIOLATION = 1008
        private const val DEFAULT_INBOX_LIMIT = 50

        /** Canonical mailbox key for a short code, or null when it is not six Crockford symbols. */
        internal fun canonicalCode(raw: String): String? {
            val normalised = ShortCode.normalise(raw)
            if (normalised.length != ShortCode.SYMBOLS) return null
            if (normalised.any { ShortCode.valueOf(it) < 0 }) return null
            return ShortCode.format(normalised)
        }

        fun create(config: RelayConfig): RelayHandle {
            config.validated()
            val material = loadTls(config)
            val identity = loadOrCreateIdentity(config)
            val moveTokens = MoveTokenService.fromSecret(config.moveSecret)
            return RelayServer(config, material, identity, moveTokens).startRelay()
        }

        private fun loadTls(config: RelayConfig): Tls.Material? {
            val tls = config.tls ?: return null
            val password = tls.keystorePassword?.toCharArray() ?: Tls.DEFAULT_PASSWORD
            val keystorePath = tls.keystorePath
            return if (keystorePath != null) {
                val file = File(keystorePath)
                check(file.isFile) { "TLS keystore not found: $keystorePath" }
                Tls.fromKeyStore(file, password, tls.keyAlias)
            } else {
                val dir = File(tls.certDir ?: config.dataDirectory().path)
                val material = Tls.loadOrCreateSelfSigned(
                    file = File(dir, Tls.DEFAULT_KEYSTORE_FILE),
                    password = password,
                    alias = tls.keyAlias,
                    subject = tls.subject
                )
                if (!material.persisted) {
                    config.log(
                        "warning: the TLS certificate could not be written to $dir; " +
                            "peers must re-pin the printed fingerprint after every restart"
                    )
                }
                material
            }
        }

        private fun loadOrCreateIdentity(config: RelayConfig): X25519KeyPair {
            val dir = config.dataDir?.let(::File) ?: return X25519.generateKeyPair()
            dir.mkdirs()
            val file = File(dir, "relay-identity.key")
            if (file.isFile) {
                val raw = try {
                    B64Url.decode(file.readText().trim())
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (raw != null && raw.size == X25519.DHLEN) return X25519.fromPrivateKey(raw)
                config.log("relay identity key is unreadable; generating a new one")
            }
            val pair = X25519.generateKeyPair()
            file.writeText(B64Url.encode(pair.privateKey))
            return pair
        }
    }
}
