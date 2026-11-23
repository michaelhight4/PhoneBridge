package dev.phonebridge.relay

import com.google.gson.JsonObject
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import dev.phonebridge.relay.crypto.Codec
import dev.phonebridge.relay.crypto.HelloProof
import dev.phonebridge.relay.crypto.PeerKeyPair
import dev.phonebridge.relay.crypto.Tls
import dev.phonebridge.relay.json.Json
import dev.phonebridge.relay.ws.WebSocketClient
import dev.phonebridge.relay.ws.WebSocketConnection
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Test helpers: relays on loopback, clients with fresh identities and a raw protocol peer. */
object TestSupport {
    fun testRelay(
        tls: TlsConfig? = null,
        insecureDev: Boolean = false,
        maxEnvelopeBytes: Int = 16 * 1024 * 1024,
        rateLimit: RateLimitConfig = RateLimitConfig(),
        pairMailboxTtlMs: Long = 10 * 60 * 1000,
        moveSecret: String? = null,
        dataDir: String? = null,
        helloTimeoutMs: Int = 30_000,
        log: (String) -> Unit = {}
    ): RelayHandle = Relay.start(
        RelayConfig(
            port = 0,
            bindAddress = "127.0.0.1",
            tls = tls,
            insecureDev = insecureDev,
            maxEnvelopeBytes = maxEnvelopeBytes,
            rateLimit = rateLimit,
            pairMailboxTtlMs = pairMailboxTtlMs,
            moveSecret = moveSecret,
            dataDir = dataDir,
            helloTimeoutMs = helloTimeoutMs,
            log = log
        )
    )

    fun client(
        relay: RelayHandle,
        identity: PeerKeyPair = PeerKeyPair.generate(),
        role: String = "bot",
        maxEnvelopeBytes: Int = 16 * 1024 * 1024
    ): RelayClient = RelayClient(
        RelayClientConfig(
            url = relay.url,
            identity = identity,
            role = role,
            tlsPin = relay.fingerprint,
            maxEnvelopeBytes = maxEnvelopeBytes,
            reconnect = dev.phonebridge.relay.client.ReconnectPolicy(
                initialDelayMs = 50,
                maxDelayMs = 500,
                multiplier = 1.5,
                jitterMs = 0
            )
        )
    )

    fun waitUntil(timeoutMs: Long, description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        if (!condition()) {
            throw AssertionError("timed out after $timeoutMs ms waiting for: $description")
        }
        return true
    }

    class Collector<T> {
        private val queue = LinkedBlockingQueue<T>()

        fun add(value: T) {
            queue.add(value)
        }

        fun await(timeoutMs: Long = 5000): T? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

        fun awaitWhere(timeoutMs: Long = 5000, predicate: (T) -> Boolean): T? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                val value = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
                if (predicate(value)) return value
            }
        }

        fun poll(): T? = queue.poll()

        fun size(): Int = queue.size
    }
}

/**
 * A hand driven protocol peer: connect, read the challenge, send a hello. Used for the negative tests
 * (forged proof, identity mismatch, unauthorised route, token replay) where a real RelayClient would
 * refuse to misbehave.
 */
class RawPeer(
    val identity: PeerKeyPair,
    private val url: String,
    private val tlsPin: String? = null,
    private val maxMessageBytes: Int = 16 * 1024 * 1024
) : AutoCloseable {
    lateinit var connection: WebSocketConnection
        private set

    private lateinit var challenge: JsonObject

    fun open(readChallenge: Boolean = true): RawPeer {
        val uri = URI(url)
        val context = if (uri.scheme.equals("wss", ignoreCase = true)) {
            Tls.clientContext(tlsPin, false)
        } else {
            null
        }
        connection = WebSocketClient.connect(
            uri = uri,
            sslContext = context,
            timeoutMs = 5000,
            maxMessageBytes = maxMessageBytes,
            verifyHostname = tlsPin == null
        )
        connection.setReadTimeout(5000)
        if (readChallenge) {
            challenge = readJson() ?: throw IllegalStateException("relay sent no challenge")
        }
        return this
    }

    /** Uses a challenge that was read out of band, e.g. after sending hello before reading it. */
    fun adoptChallenge(obj: JsonObject) {
        challenge = obj
    }

    fun challengeRelayId(): String = Json.string(challenge, "relay_id") ?: ""

    fun challengeNonce(): String = Json.string(challenge, "nonce") ?: ""

    fun send(json: JsonObject) {
        connection.sendText(json.toString())
    }

    fun readJson(): JsonObject? {
        val bytes = connection.readMessage() ?: return null
        return Json.parseObject(String(bytes, Charsets.UTF_8))
    }

    fun helloSigned(
        moveToken: String? = null,
        fromOverride: String? = null,
        publicKeyOverride: String? = null,
        proofOverride: String? = null,
        nonceOverride: String? = null,
        role: String = "bot"
    ): JsonObject {
        val relayPublic = Codec.b64urlDecode(Json.string(challenge, "relay_pub")!!)!!
        val relayId = challengeRelayId()
        val nonceB64 = nonceOverride ?: challengeNonce()
        val nonce = Codec.b64urlDecode(nonceB64) ?: ByteArray(32)
        val from = fromOverride ?: identity.peerId
        val proof = proofOverride ?: Codec.b64url(
            HelloProof.compute(
                peerPrivateKey = identity.privateKey,
                relayPublicKey = relayPublic,
                relayId = relayId,
                peerId = from,
                nonce = nonce
            )
        )
        val hello = Json.obj(
            "v" to 1,
            "type" to "hello",
            "from" to from,
            "pub" to (publicKeyOverride ?: Codec.b64url(identity.publicKey)),
            "sig" to proof,
            "nonce" to nonceB64,
            "role" to role
        )
        moveToken?.let { hello.addProperty("move_token", it) }
        send(hello)
        return hello
    }

    /** Opens, authenticates and returns the `hello_ok`. */
    fun authenticate(): JsonObject {
        open()
        helloSigned()
        val reply = readJson() ?: throw IllegalStateException("relay closed during hello")
        check(Json.string(reply, "type") == "hello_ok") {
            "authentication failed: $reply"
        }
        return reply
    }

    override fun close() {
        if (::connection.isInitialized) connection.closeQuietly(1000, "test done")
    }
}
