package dev.phonebridge.relay.client

import com.google.gson.JsonObject
import dev.phonebridge.relay.RelayConfig
import dev.phonebridge.relay.crypto.Codec
import dev.phonebridge.relay.crypto.HelloProof
import dev.phonebridge.relay.crypto.PeerKeyPair
import dev.phonebridge.relay.crypto.Tls
import dev.phonebridge.relay.envelope.GsonRelayEnvelopeCodec
import dev.phonebridge.relay.envelope.RelayEnvelope
import dev.phonebridge.relay.json.Json
import dev.phonebridge.relay.ws.WebSocketClient
import dev.phonebridge.relay.ws.WebSocketConnection
import dev.phonebridge.relay.ws.WebSocketException
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
    val identity: PeerKeyPair,
    val role: String = "bot",
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
 */
class RelayClient(private val config: RelayClientConfig) {
    val peerId: String = config.identity.peerId
    private val publicKeyB64 = Codec.b64url(config.identity.publicKey)

    private val lock = Any()
    private val connectedLatch = CountDownLatch(1)
    private val outbox = ArrayDeque<JsonObject>()

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
    var onPairOffer: ((JsonObject) -> Unit)? = null
    var onRelayMoved: ((RelayMovedEvent) -> Unit)? = null
    var onError: ((RelayClientError) -> Unit)? = null
    var onStateChanged: ((RelayClientState, String?) -> Unit)? = null
    var onMessage: ((JsonObject) -> Unit)? = null

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

    /** Sends a route envelope to [to]; queues it if the connection is momentarily down. */
    fun sendRoute(to: String, payload: String, id: String = RelayEnvelope.newId()): String {
        sendJson(RelayEnvelope.route(peerId, to, payload, id).toJson())
        return id
    }

    fun ping(id: String = RelayEnvelope.newId()) {
        sendJson(
            Json.obj(
                "v" to 1,
                "type" to "ping",
                "from" to peerId,
                "id" to id
            )
        )
    }

    fun pairStore(code: String, offer: JsonObject) {
        sendJson(
            Json.obj(
                "v" to 1,
                "type" to "pair_store",
                "from" to peerId,
                "code" to code,
                "offer" to offer
            )
        )
    }

    fun pairLookup(code: String) {
        sendJson(
            Json.obj(
                "v" to 1,
                "type" to "pair_lookup",
                "from" to peerId,
                "code" to code
            )
        )
    }

    fun sendRaw(obj: JsonObject): Boolean = sendJson(obj)

    private fun sendJson(obj: JsonObject): Boolean {
        val current = connection
        if (current != null && current.isOpen) {
            try {
                current.sendText(obj.toString())
                return true
            } catch (_: Exception) {
                // Fall through and queue.
            }
        }
        val encoded = obj.toString()
        synchronized(lock) {
            if (outbox.size >= config.outboxLimit) {
                outbox.removeFirst()
                config.log("relay client outbox full; dropping the oldest message")
            }
            outbox.addLast(Json.parseObject(encoded) ?: return false)
        }
        return false
    }

    private fun flushOutbox() {
        while (true) {
            val next = synchronized(lock) { outbox.removeFirstOrNull() } ?: return
            val current = connection ?: return
            try {
                current.sendText(next.toString())
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
            val challenge = Json.parseObject(String(challengeBytes, Charsets.UTF_8))
                ?: throw IOException("relay sent a malformed challenge")
            if (Json.string(challenge, "type") != "challenge") {
                throw IOException("expected a challenge, got ${Json.string(challenge, "type")}")
            }
            val nonceB64 = Json.string(challenge, "nonce") ?: throw IOException("challenge has no nonce")
            val relayPublicB64 = Json.string(challenge, "relay_pub")
                ?: throw IOException("challenge has no relay public key")
            val nonce = Codec.b64urlDecode(nonceB64) ?: throw IOException("challenge nonce is not base64url")
            val relayPublicKey = Codec.b64urlDecode(relayPublicB64)
                ?: throw IOException("challenge relay key is not base64url")
            val challengeRelayId = Json.string(challenge, "relay_id") ?: ""

            val proof = HelloProof.compute(
                peerPrivateKey = config.identity.privateKey,
                relayPublicKey = relayPublicKey,
                relayId = challengeRelayId,
                peerId = peerId,
                nonce = nonce
            )
            val hello = Json.obj(
                "v" to 1,
                "type" to "hello",
                "from" to peerId,
                "pub" to publicKeyB64,
                "sig" to Codec.b64url(proof),
                "nonce" to nonceB64,
                "role" to config.role
            )
            moveToken?.let { hello.addProperty("move_token", it) }
            conn.sendText(hello.toString())

            while (true) {
                val bytes = conn.readMessage()
                    ?: throw IOException("relay closed the connection during authentication")
                val obj = Json.parseObject(String(bytes, Charsets.UTF_8)) ?: continue
                when (Json.string(obj, "type")) {
                    "hello_ok" -> {
                        relayId = Json.string(obj, "relay_id")
                        // A move token is single use: never send it again on later reconnects.
                        moveToken = null
                        conn.setReadTimeout(config.idleTimeoutMs)
                        config.log("relay client $peerId connected to $url (relay ${relayId})")
                        return conn
                    }
                    "error" -> {
                        val error = parseError(obj)
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
            val obj = Json.parseObject(text) ?: continue
            when (Json.string(obj, "type")) {
                "route" -> {
                    val envelope = GsonRelayEnvelopeCodec.decode(text) ?: continue
                    onRoute?.invoke(envelope)
                    onMessage?.invoke(obj)
                }
                "relay_moved" -> {
                    val newUrl = Json.string(obj, "new_url")
                    if (newUrl == null) {
                        onError?.invoke(RelayClientError("bad_envelope", "relay_moved without new_url"))
                        continue
                    }
                    val expires = Json.long(obj, "expires") ?: 0L
                    val token = Json.string(obj, "token")
                    currentUrl = newUrl
                    moveToken = token
                    pendingMove = true
                    onRelayMoved?.invoke(RelayMovedEvent(newUrl, expires, token))
                    config.log("relay moved to $newUrl; following")
                    conn.close(WebSocketConnection.CLOSE_NORMAL, "relay moved")
                    return
                }
                "error" -> onError?.invoke(parseError(obj))
                "pair_offer" -> {
                    onPairOffer?.invoke(obj)
                    onMessage?.invoke(obj)
                }
                else -> onMessage?.invoke(obj)
            }
        }
    }

    private fun parseError(obj: JsonObject): RelayClientError {
        val error = Json.objOrNull(obj, "error")
        val code = error?.let { Json.string(it, "code") } ?: "unknown"
        val message = error?.let { Json.string(it, "message") }
        return RelayClientError(code, message, Json.string(obj, "id"))
    }

    private fun updateState(next: RelayClientState, detail: String?) {
        if (state == next) return
        state = next
        onStateChanged?.invoke(next, detail)
    }
}
