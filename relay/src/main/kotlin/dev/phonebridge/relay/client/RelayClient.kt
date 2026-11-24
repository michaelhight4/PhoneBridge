package dev.phonebridge.relay.client

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.crypto.HelloAuth
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.EnvelopeCodec
import dev.phonebridge.core.model.RelayBotMessage
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayPeerEntry
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.RelayConfig
import dev.phonebridge.relay.crypto.Tls
import dev.phonebridge.relay.ws.WebSocketClient
import dev.phonebridge.relay.ws.WebSocketConnection
import dev.phonebridge.relay.ws.WebSocketException
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class ReconnectPolicy(
    val initialDelayMs: Long = 250,
    val maxDelayMs: Long = 10_000,
    val multiplier: Double = 2.0,
    val jitterMs: Long = 100
) {
    init {
        require(initialDelayMs > 0) { "initialDelayMs must be positive" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be at least initialDelayMs" }
        require(multiplier >= 1.0) { "multiplier must be at least 1.0" }
        require(jitterMs >= 0) { "jitterMs must not be negative" }
    }
}

enum class RelayClientState { IDLE, CONNECTING, CONNECTED, RECONNECTING, STOPPED }

data class RelayClientError(val code: String, val message: String?, val id: String? = null)

data class RelayMovedEvent(val newUrl: String, val expiresEpochSeconds: Long, val token: String?)

class RelayClientConfig(
    val url: String,
    /** Long-term identity of this device or bot; its role is sent in `hello`. */
    val identity: Identity,
    /** base64url SHA-256 certificate fingerprint; required to reach a self-signed relay over wss. */
    val tlsPin: String? = null,
    /** Development only: accept any TLS certificate. */
    val insecureTls: Boolean = false,
    /** Development only: allow plain `ws://` to a non-loopback host. */
    val allowInsecureWs: Boolean = false,
    val maxEnvelopeBytes: Int = 16 * 1024 * 1024,
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
    val handshakeTimeoutMs: Int = 20_000,
    val idleTimeoutMs: Int = 120_000,
    val outboxLimit: Int = 256,
    val log: (String) -> Unit = {}
) {
    fun validated(): RelayClientConfig {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "ws" || scheme == "wss") { "the relay URL must start with ws:// or wss://" }
        require(!uri.host.isNullOrBlank()) { "the relay URL needs a host" }
        if (scheme == "ws" && !allowInsecureWs && !RelayConfig.isLoopbackAddress(uri.host)) {
            throw IllegalArgumentException(
                "plain ws:// is only allowed to loopback; use wss:// (with tlsPin for a self-signed " +
                    "relay) or set allowInsecureWs for development"
            )
        }
        require(maxEnvelopeBytes > 0) { "maxEnvelopeBytes must be positive" }
        return this
    }
}

/**
 * Reconnecting relay client used by devices, bots and tests.
 *
 * It connects, authenticates with `hello` (proving key ownership against the relay challenge), keeps
 * the connection alive, reconnects with exponential backoff and follows `relay_moved` automatically:
 * the new one-time token is used for exactly one `hello`.
 *
 * Envelopes are the shared `:core` types; `payload` stays the opaque base64url end-to-end frame.
 */
class RelayClient(private val config: RelayClientConfig) {
    val peerId: String = config.identity.peerId
    private val publicKeyB64 = config.identity.publicKeyBase64

    private val lock = Any()
    private val connectedLatch = CountDownLatch(1)
    private val outbox = ArrayDeque<RelayEnvelope>()

    @Volatile
    private var connection: WebSocketConnection? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var currentUrl: String = config.url

    @Volatile
    private var moveToken: String? = null

    @Volatile
    private var pendingMove = false

    @Volatile
    var relayId: String? = null
        private set

    @Volatile
    var state: RelayClientState = RelayClientState.IDLE
        private set

    var onRoute: ((RelayEnvelope) -> Unit)? = null
    var onPairOffer: ((RelayEnvelope) -> Unit)? = null
    var onPeersResult: ((RelayEnvelope) -> Unit)? = null
    var onMsgList: ((RelayEnvelope) -> Unit)? = null
    var onMsgDeliver: ((RelayEnvelope) -> Unit)? = null
    var onRelayMoved: ((RelayMovedEvent) -> Unit)? = null
    var onError: ((RelayClientError) -> Unit)? = null
    var onStateChanged: ((RelayClientState, String?) -> Unit)? = null
    var onMessage: ((RelayEnvelope) -> Unit)? = null

    val isConnected: Boolean get() = connection?.isOpen == true

    val url: String get() = currentUrl

    fun start(): RelayClient {
        config.validated()
        synchronized(lock) {
            if (running) return this
            running = true
        }
        worker = Thread({ connectLoop() }, "pb-relay-client").apply {
            isDaemon = true
            start()
        }
        return this
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        connection?.close(WebSocketConnection.CLOSE_NORMAL, "client stopping")
        worker?.join(3000)
        connection = null
        updateState(RelayClientState.STOPPED, null)
    }

    fun awaitConnected(timeoutMs: Long): Boolean =
        connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Sends a route envelope to [to], passing the base64url end-to-end frame through unchanged. */
    fun sendRoute(to: String, payload: String, id: String = RelayEnvelope.newId()): String {
        sendJson(
            RelayEnvelope(
                type = RelayType.ROUTE,
                from = peerId,
                to = to,
                id = id,
                payload = payload
            )
        )
        return id
    }

    /** Sends a route envelope to [to] with a raw frame; the frame is base64url encoded for the wire. */
    fun sendRouteBytes(to: String, payload: ByteArray, id: String = RelayEnvelope.newId()): String {
        sendJson(RelayEnvelope.route(from = peerId, to = to, payload = payload, id = id))
        return id
    }

    fun ping(id: String = RelayEnvelope.newId()) {
        sendJson(RelayEnvelope.ping(from = peerId, id = id))
    }

    fun pairStore(code: String, offer: JsonObject) {
        sendJson(RelayEnvelope.pairStore(from = peerId, code = code, offer = offer))
    }

    fun pairLookup(code: String) {
        sendJson(RelayEnvelope.pairLookup(from = peerId, code = code))
    }

    /** Device -> relay: publish the paired bots and their messaging switches. */
    fun peersSync(
        deviceName: String,
        peers: List<RelayPeerEntry>,
        id: String = RelayEnvelope.newId(),
    ): String {
        sendJson(
            RelayEnvelope.peersSync(
                from = peerId,
                deviceName = deviceName,
                deviceId = peerId,
                peers = peers,
                id = id,
            ),
        )
        return id
    }

    /** Bot -> relay: ask which other bots this bot may message. */
    fun peersList(deviceId: String? = null, limit: Int? = null, id: String = RelayEnvelope.newId()): String {
        sendJson(RelayEnvelope.peersList(from = peerId, deviceId = deviceId, limit = limit, id = id))
        return id
    }

    /** Bot -> relay: store an encrypted message in the recipient's mailbox. */
    fun msgSend(
        message: RelayBotMessage,
        deviceId: String? = null,
        id: String = RelayEnvelope.newId(),
    ): String {
        sendJson(RelayEnvelope.msgSend(from = peerId, message = message, deviceId = deviceId, id = id))
        return id
    }

    /** Bot -> relay: list or consume the messages waiting for this bot. */
    fun msgInbox(
        limit: Int? = null,
        consume: Boolean? = null,
        ackIds: List<String>? = null,
        deviceId: String? = null,
        id: String = RelayEnvelope.newId(),
    ): String {
        sendJson(
            RelayEnvelope.msgInbox(
                from = peerId,
                limit = limit,
                consume = consume,
                ackIds = ackIds,
                deviceId = deviceId,
                id = id,
            ),
        )
        return id
    }

    /** Bot -> relay: acknowledge messages that were delivered out of band. */
    fun msgAck(ids: List<String>, deviceId: String? = null, id: String = RelayEnvelope.newId()): String {
        sendJson(RelayEnvelope.msgAck(from = peerId, ids = ids, deviceId = deviceId, id = id))
        return id
    }

    fun sendRaw(envelope: RelayEnvelope): Boolean = sendJson(envelope)

    private fun sendJson(envelope: RelayEnvelope): Boolean {
        val current = connection
        if (current != null && current.isOpen) {
            try {
                current.sendText(EnvelopeCodec.encode(envelope))
                return true
            } catch (_: Exception) {
                // Fall through and queue (e.g. the relay went away mid-send).
            }
        }
        synchronized(lock) {
            if (outbox.size >= config.outboxLimit) {
                outbox.removeFirst()
                config.log("relay client outbox full; dropping the oldest message")
            }
            outbox.addLast(envelope)
        }
        return false
    }

    private fun flushOutbox() {
        while (true) {
            val next = synchronized(lock) { outbox.removeFirstOrNull() } ?: return
            val current = connection ?: return
            try {
                current.sendText(EnvelopeCodec.encode(next))
            } catch (_: Exception) {
                synchronized(lock) { outbox.addFirst(next) }
                return
            }
        }
    }

    private fun connectLoop() {
        var delay = config.reconnect.initialDelayMs
        while (running) {
            val url = currentUrl
            val authenticated = try {
                connectAndAuthenticate(url)
            } catch (e: Exception) {
                config.log("relay client: ${e.message}")
                null
            }

            if (authenticated == null) {
                if (!running) break
                if (pendingMove) {
                    pendingMove = false
                    delay = config.reconnect.initialDelayMs
                    continue
                }
                updateState(RelayClientState.RECONNECTING, "connect failed")
                sleep(delay)
                delay = nextDelay(delay)
                continue
            }

            synchronized(lock) { connection = authenticated }
            delay = config.reconnect.initialDelayMs
            connectedLatch.countDown()
            updateState(RelayClientState.CONNECTED, null)
            flushOutbox()

            try {
                readLoop(authenticated)
            } catch (e: WebSocketException) {
                if (e.closeCode == WebSocketConnection.CLOSE_TOO_BIG) {
                    onError?.invoke(RelayClientError("too_large", e.message))
                }
            } catch (_: Exception) {
                // Connection reset: reconnect below.
            } finally {
                synchronized(lock) {
                    if (connection === authenticated) connection = null
                }
                authenticated.closeQuietly(WebSocketConnection.CLOSE_ABNORMAL, "reconnecting")
            }

            if (!running) break
            if (pendingMove) {
                pendingMove = false
                delay = config.reconnect.initialDelayMs
                continue
            }
            updateState(RelayClientState.RECONNECTING, "connection lost")
            sleep(delay)
            delay = nextDelay(delay)
        }
        updateState(RelayClientState.STOPPED, null)
    }

    private fun nextDelay(current: Long): Long {
        val grown = (current * config.reconnect.multiplier).toLong()
        return grown.coerceAtLeast(current).coerceAtMost(config.reconnect.maxDelayMs)
    }

    private fun sleep(millis: Long) {
        val jitter = if (config.reconnect.jitterMs > 0) Random.nextLong(config.reconnect.jitterMs + 1) else 0
        val until = System.currentTimeMillis() + millis + jitter
        while (running) {
            val remaining = until - System.currentTimeMillis()
            if (remaining <= 0) return
            try {
                Thread.sleep(minOf(remaining, 100))
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun connectAndAuthenticate(url: String): WebSocketConnection {
        val uri = URI(url)
        updateState(RelayClientState.CONNECTING, url)
        val context = if (uri.scheme.equals("wss", ignoreCase = true)) {
            Tls.clientContext(config.tlsPin, config.insecureTls)
        } else {
            null
        }
        val conn = WebSocketClient.connect(
            uri = uri,
            sslContext = context,
            timeoutMs = config.handshakeTimeoutMs,
            maxMessageBytes = config.maxEnvelopeBytes,
            idleTimeoutMs = config.idleTimeoutMs,
            verifyHostname = config.tlsPin == null && !config.insecureTls,
            log = config.log
        )

        try {
            conn.setReadTimeout(config.handshakeTimeoutMs)
            val challengeBytes = conn.readMessage()
                ?: throw IOException("relay closed the connection before its challenge")
            val challenge = decode(String(challengeBytes, Charsets.UTF_8), "challenge")
            if (challenge.type != RelayType.CHALLENGE) {
                throw IOException("expected a challenge, got ${challenge.type}")
            }
            val nonceB64 = challenge.nonce ?: throw IOException("challenge has no nonce")
            val relayPublicB64 = challenge.relayPub ?: throw IOException("challenge has no relay public key")
            val nonce = try {
                B64Url.decode(nonceB64)
            } catch (_: IllegalArgumentException) {
                throw IOException("challenge nonce is not base64url")
            }
            val relayPublicKey = try {
                B64Url.decode(relayPublicB64)
            } catch (_: IllegalArgumentException) {
                throw IOException("challenge relay key is not base64url")
            }
            val challengeRelayId = challenge.relayId ?: ""

            val proof = try {
                HelloAuth.proof(
                    peerPrivateKey = config.identity.privateKey,
                    peerPublicKey = config.identity.publicKey,
                    relayPublicKey = relayPublicKey,
                    relayId = challengeRelayId,
                    peerId = peerId,
                    nonce = nonce
                )
            } catch (e: CryptoException) {
                throw IOException("relay challenge key is unusable: ${e.message}")
            }

            val hello = RelayEnvelope.hello(
                from = peerId,
                publicKeyB64 = publicKeyB64,
                role = config.identity.role,
                proofB64 = B64Url.encode(proof),
                nonceB64 = nonceB64,
                moveToken = moveToken
            )
            conn.sendText(EnvelopeCodec.encode(hello))

            while (true) {
                val bytes = conn.readMessage()
                    ?: throw IOException("relay closed the connection during authentication")
                val envelope = decode(String(bytes, Charsets.UTF_8), "authentication reply")
                when (envelope.type) {
                    RelayType.HELLO_OK -> {
                        relayId = envelope.relayId
                        // A move token is single use: never send it again on later reconnects.
                        moveToken = null
                        conn.setReadTimeout(config.idleTimeoutMs)
                        config.log("relay client $peerId connected to $url (relay ${relayId})")
                        return conn
                    }
                    RelayType.ERROR -> {
                        val error = parseError(envelope)
                        onError?.invoke(error)
                        moveToken = null
                        throw IOException("relay rejected authentication: ${error.code} ${error.message ?: ""}")
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            conn.closeQuietly(WebSocketConnection.CLOSE_ABNORMAL, "authentication failed")
            throw e
        }
    }

    private fun readLoop(conn: WebSocketConnection) {
        while (running && conn.isOpen) {
            val bytes = conn.readMessage() ?: throw IOException("relay closed the connection")
            val text = String(bytes, Charsets.UTF_8)
            val envelope = decode(text, "relay message")
            when (envelope.type) {
                RelayType.ROUTE -> {
                    onRoute?.invoke(envelope)
                    onMessage?.invoke(envelope)
                }
                RelayType.RELAY_MOVED -> {
                    val newUrl = envelope.newUrl
                    if (newUrl == null) {
                        onError?.invoke(RelayClientError("bad_envelope", "relay_moved without new_url"))
                        continue
                    }
                    val expires = envelope.expires ?: 0L
                    val token = envelope.token
                    currentUrl = newUrl
                    moveToken = token
                    pendingMove = true
                    onRelayMoved?.invoke(RelayMovedEvent(newUrl, expires, token))
                    config.log("relay moved to $newUrl; following")
                    conn.close(WebSocketConnection.CLOSE_NORMAL, "relay moved")
                    return
                }
                RelayType.ERROR -> onError?.invoke(parseError(envelope))
                RelayType.PAIR_OFFER -> {
                    onPairOffer?.invoke(envelope)
                    onMessage?.invoke(envelope)
                }
                RelayType.PEERS_RESULT -> {
                    onPeersResult?.invoke(envelope)
                    onMessage?.invoke(envelope)
                }
                RelayType.MSG_LIST -> {
                    onMsgList?.invoke(envelope)
                    onMessage?.invoke(envelope)
                }
                RelayType.MSG_DELIVER -> {
                    onMsgDeliver?.invoke(envelope)
                    onMessage?.invoke(envelope)
                }
                else -> onMessage?.invoke(envelope)
            }
        }
    }

    private fun decode(text: String, what: String): RelayEnvelope = try {
        EnvelopeCodec.decode(text)
    } catch (e: ProtocolException) {
        throw IOException("relay sent a malformed $what: ${e.message}")
    }

    private fun parseError(envelope: RelayEnvelope): RelayClientError {
        val error = envelope.error
        return RelayClientError(error?.code ?: "unknown", error?.message, envelope.id)
    }

    private fun updateState(next: RelayClientState, detail: String?) {
        if (state == next) return
        state = next
        onStateChanged?.invoke(next, detail)
    }
}
