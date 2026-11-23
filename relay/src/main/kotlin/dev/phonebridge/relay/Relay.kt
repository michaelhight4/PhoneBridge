package dev.phonebridge.relay

import com.google.gson.JsonObject
import dev.phonebridge.relay.crypto.Base32
import dev.phonebridge.relay.crypto.Codec
import dev.phonebridge.relay.crypto.HelloProof
import dev.phonebridge.relay.crypto.PeerId
import dev.phonebridge.relay.crypto.PeerKeyPair
import dev.phonebridge.relay.crypto.Tls
import dev.phonebridge.relay.envelope.RelayEnvelope
import dev.phonebridge.relay.json.Json
import dev.phonebridge.relay.ws.WebSocketConnection
import dev.phonebridge.relay.ws.WebSocketException
import dev.phonebridge.relay.ws.WebSocketServer
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
 */
object Relay {
    fun start(config: RelayConfig = RelayConfig()): RelayHandle = RelayServer.create(config)
}

internal class RelayServer private constructor(
    private val config: RelayConfig,
    private val material: Tls.Material?,
    private val identity: PeerKeyPair,
    private val moveTokens: MoveTokenService
) : RelayHandle {

    private val peers = ConcurrentHashMap<String, Session>()
    private val mailbox = PairMailbox(config.pairMailboxTtlMs)
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

    override val relayId: String = identity.peerId
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
            Json.obj(
                "ok" to true,
                "relay_id" to relayId,
                "peers" to peers.size,
                "uptime_ms" to (System.currentTimeMillis() - startedAt)
            ).toString()
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
            val message = Json.obj(
                "v" to 1,
                "type" to "relay_moved",
                "to" to peer,
                "id" to RelayEnvelope.newId(),
                "new_url" to newUrl,
                "token" to moveTokens.issue(peer, ttlMs),
                "expires" to expiresAtSeconds
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
        val ephemeral = PeerKeyPair.generate()
        private val nonce = Codec.randomBytes(32)
        private val nonceB64 = Codec.b64url(nonce)
        private var limiter = RateLimiter(config.rateLimit)

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
                Json.obj(
                    "v" to 1,
                    "type" to "challenge",
                    "nonce" to nonceB64,
                    "relay_pub" to ephemeral.publicKeyB64(),
                    "relay_id" to relayId,
                    "proof" to "dh-hmac-sha256",
                    "expires" to (System.currentTimeMillis() / 1000 + config.helloTimeoutMs / 1000)
                )
            )
        }

        private fun handleMessage(text: String): Boolean {
            val obj = Json.parseObject(text)
            if (obj == null) {
                sendError("bad_envelope", "message is not a JSON object")
                return true
            }
            val type = Json.string(obj, "type")
            if (type == null) {
                sendError("bad_envelope", "message has no type")
                return true
            }

            if (!authenticated) {
                preAuthMessages++
                if (preAuthMessages > 10) {
                    sendError("unauthorized", "authenticate with hello first")
                    return false
                }
                return when (type) {
                    "hello" -> handleHello(obj)
                    "ping" -> {
                        sendPong(obj)
                        true
                    }
                    else -> {
                        sendError("unauthorized", "$type needs an authenticated hello")
                        false
                    }
                }
            }

            if (!limiter.tryAcquire()) {
                rateLimited.incrementAndGet()
                maybeSendRateLimited()
                return true
            }

            return when (type) {
                "hello" -> {
                    sendError("already_authenticated", "this connection is already authenticated")
                    true
                }
                "route" -> handleRoute(obj, text)
                "ping" -> {
                    sendPong(obj)
                    true
                }
                "pair_store" -> {
                    handlePairStore(obj)
                    true
                }
                "pair_lookup" -> {
                    handlePairLookup(obj)
                    true
                }
                else -> {
                    sendError("unknown_type", "unsupported message type: $type")
                    true
                }
            }
        }

        private fun handleHello(obj: JsonObject): Boolean {
            val from = Json.string(obj, "from")
                ?: return rejectHello("bad_envelope", "hello needs a from peer id")
            val publicKeyB64 = Json.string(obj, "pub")
                ?: return rejectHello("bad_envelope", "hello needs a public key")
            val publicKey = Codec.b64urlDecode(publicKeyB64)
            if (publicKey == null || publicKey.size != 32) {
                return rejectHello("bad_envelope", "hello public key must be 32 base64url bytes")
            }
            if (!PeerId.matches(from, publicKey)) {
                return rejectHello("unauthorized", "peer id does not match the public key")
            }
            val helloNonceB64 = Json.string(obj, "nonce")
            if (helloNonceB64 == null) {
                // docs/PROTOCOL.md has the relay challenge in the hello reply. A peer that sends
                // hello before reading the challenge gets a fresh challenge back, not a disconnect.
                if (challengeCount < 3) {
                    challengeCount++
                    sendChallenge()
                    return true
                }
                return rejectHello("unauthorized", "hello must carry the challenge nonce")
            }
            val helloNonce = Codec.b64urlDecode(helloNonceB64)
            if (helloNonce == null || !Codec.constantTimeEquals(helloNonce, nonce)) {
                return rejectHello("unauthorized", "hello nonce does not match the challenge")
            }
            val proofB64 = Json.string(obj, "sig")
                ?: return rejectHello("unauthorized", "hello must carry a key ownership proof")
            val proof = Codec.b64urlDecode(proofB64)
            if (proof == null || proof.size != 32) {
                return rejectHello("unauthorized", "hello proof is malformed")
            }
            val valid = HelloProof.verify(
                relayPrivateKey = ephemeral.privateKey,
                relayPublicKey = ephemeral.publicKey,
                relayId = relayId,
                peerId = from,
                peerPublicKey = publicKey,
                nonce = nonce,
                proof = proof
            )
            if (!valid) return rejectHello("unauthorized", "hello proof of key ownership failed")

            Json.string(obj, "move_token")?.let { token ->
                when (val result = moveTokens.verify(token, from)) {
                    is MoveTokenService.Verify.Invalid ->
                        return rejectHello("unauthorized", "move token rejected: ${result.reason}")
                    is MoveTokenService.Verify.Valid -> {
                        purgeUsedMoveTokens()
                        val key = "$from|${result.nonce}"
                        if (usedMoveTokens.putIfAbsent(key, result.expiresAt) != null) {
                            return rejectHello("token_used", "that move token has already been used")
                        }
                    }
                }
            }

            peerId = from
            role = Json.string(obj, "role") ?: "unknown"
            authenticated = true
            limiter = RateLimiter(config.rateLimit)
            conn.setReadTimeout(config.idleTimeoutMs)

            val previous = peers.put(from, this)
            if (previous != null && previous !== this) {
                config.log("peer $from reconnected; closing its previous connection")
                previous.conn.close(REPLACED_CLOSE_CODE, "replaced by a newer connection")
            }

            send(
                Json.obj(
                    "v" to 1,
                    "type" to "hello_ok",
                    "to" to from,
                    "relay_id" to relayId,
                    "peer" to from,
                    "server_time" to System.currentTimeMillis() / 1000,
                    "limits" to mapOf(
                        "max_envelope_bytes" to config.maxEnvelopeBytes,
                        "rate_messages" to config.rateLimit.maxMessages,
                        "rate_window_ms" to config.rateLimit.windowMs
                    )
                )
            )
            config.log("peer $from authenticated as $role")
            return true
        }

        private fun rejectHello(code: String, message: String): Boolean {
            envelopesDropped.incrementAndGet()
            sendError(code, message)
            return false
        }

        private fun handleRoute(obj: JsonObject, originalText: String): Boolean {
            val to = Json.string(obj, "to")
            val id = Json.string(obj, "id")
            if (to.isNullOrBlank()) {
                sendError("bad_envelope", "route needs a to peer id", id)
                return true
            }
            val claimedFrom = Json.string(obj, "from")
            if (claimedFrom != null && claimedFrom != peerId) {
                envelopesDropped.incrementAndGet()
                sendError("bad_envelope", "from does not match the authenticated peer", id)
                return true
            }
            if (to == peerId) {
                envelopesDropped.incrementAndGet()
                sendError("bad_envelope", "a peer cannot route to itself", id)
                return true
            }

            val target = peers[to]
            if (target == null) {
                peerOffline.incrementAndGet()
                sendError("peer_offline", "peer $to is not connected", id)
                return true
            }
            try {
                // Forwarded verbatim: the relay never parses or rewrites the encrypted payload.
                target.conn.sendText(originalText)
                envelopesRouted.incrementAndGet()
            } catch (_: Exception) {
                peers.remove(to, target)
                peerOffline.incrementAndGet()
                sendError("peer_offline", "peer $to is not reachable", id)
            }
            return true
        }

        private fun handlePairStore(obj: JsonObject) {
            val code = Json.string(obj, "code")?.let(Base32::canonicalCode)
            if (code == null) {
                sendError("bad_args", "pair_store needs a six symbol code")
                return
            }
            val offer = Json.objOrNull(obj, "offer")
            if (offer == null) {
                sendError("bad_args", "pair_store needs an offer object")
                return
            }
            try {
                val expiresAt = mailbox.store(code, offer)
                send(
                    Json.obj(
                        "v" to 1,
                        "type" to "pair_stored",
                        "to" to peerId,
                        "code" to code,
                        "expires" to expiresAt / 1000
                    )
                )
            } catch (e: PairMailbox.MailboxFullException) {
                sendError("mailbox_full", e.message ?: "the pairing mailbox is full")
            }
        }

        private fun handlePairLookup(obj: JsonObject) {
            val code = Json.string(obj, "code")?.let(Base32::canonicalCode)
            if (code == null) {
                sendError("bad_args", "pair_lookup needs a six symbol code")
                return
            }
            when (val result = mailbox.take(code)) {
                is PairMailbox.Take.Found -> send(
                    Json.obj(
                        "v" to 1,
                        "type" to "pair_offer",
                        "to" to peerId,
                        "code" to code,
                        "offer" to result.offer,
                        "expires" to result.expiresAt / 1000
                    )
                )
                PairMailbox.Take.Expired -> sendError("code_expired", "that pairing code has expired")
                PairMailbox.Take.Missing -> sendError("code_not_found", "no pairing offer waits for that code")
            }
        }

        private fun sendPong(request: JsonObject) {
            send(
                Json.obj(
                    "v" to 1,
                    "type" to "pong",
                    "to" to peerId,
                    "id" to Json.string(request, "id")
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

        fun send(obj: JsonObject): Boolean = try {
            conn.sendText(obj.toString())
            true
        } catch (_: Exception) {
            false
        }

        private fun sendError(code: String, message: String, id: String? = null) {
            send(
                Json.obj(
                    "v" to 1,
                    "type" to "error",
                    "to" to peerId,
                    "id" to id,
                    "error" to mapOf("code" to code, "message" to message)
                )
            )
        }

        private fun unregister() {
            val current = peerId ?: return
            peers.remove(current, this)
        }
    }

    companion object {
        private const val REPLACED_CLOSE_CODE = 4001
        private const val CLOSE_POLICY_VIOLATION = 1008

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
                Tls.loadOrCreateSelfSigned(
                    file = File(dir, Tls.DEFAULT_KEYSTORE_FILE),
                    password = password,
                    alias = tls.keyAlias,
                    subject = tls.subject
                )
            }
        }

        private fun loadOrCreateIdentity(config: RelayConfig): PeerKeyPair {
            val dir = config.dataDir?.let(::File) ?: return PeerKeyPair.generate()
            dir.mkdirs()
            val file = File(dir, "relay-identity.key")
            if (file.isFile) {
                val raw = Codec.b64urlDecode(file.readText().trim())
                if (raw != null && raw.size == 32) return PeerKeyPair.fromPrivateKey(raw)
                config.log("relay identity key is unreadable; generating a new one")
            }
            val pair = PeerKeyPair.generate()
            file.writeText(Codec.b64url(pair.privateKey))
            return pair
        }
    }
}
