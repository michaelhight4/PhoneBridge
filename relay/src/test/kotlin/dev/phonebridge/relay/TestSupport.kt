package dev.phonebridge.relay

import dev.phonebridge.core.crypto.HelloAuth
import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.EnvelopeCodec
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.model.RelayType
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import dev.phonebridge.relay.client.ReconnectPolicy
import dev.phonebridge.relay.crypto.Tls
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
        identity: Identity = Identity.generate(PeerRole.BOT),
        maxEnvelopeBytes: Int = 16 * 1024 * 1024
    ): RelayClient = RelayClient(
        RelayClientConfig(
            url = relay.url,
            identity = identity,
            tlsPin = relay.fingerprint,
            maxEnvelopeBytes = maxEnvelopeBytes,
            reconnect = ReconnectPolicy(
                initialDelayMs = 50,
                maxDelayMs = 500,
                multiplier = 1.5,
                jitterMs = 0
            )
        )
    )

    fun errorCode(envelope: RelayEnvelope?): String? = envelope?.error?.code

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
    val identity: Identity,
    private val url: String,
    private val tlsPin: String? = null,
    private val maxMessageBytes: Int = 16 * 1024 * 1024
) : AutoCloseable {
    lateinit var connection: WebSocketConnection
        private set

    private lateinit var challenge: RelayEnvelope

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
            challenge = readEnvelope() ?: throw IllegalStateException("relay sent no challenge")
        }
        return this
    }

    /** Uses a challenge that was read out of band, e.g. after sending hello before reading it. */
    fun adoptChallenge(envelope: RelayEnvelope) {
        challenge = envelope
    }

    fun challengeRelayId(): String = challenge.relayId ?: ""

    fun challengeNonce(): String = challenge.nonce ?: ""

    fun send(envelope: RelayEnvelope) {
        connection.sendText(EnvelopeCodec.encode(envelope))
    }

    /** Sends a frame that is deliberately outside the envelope model (oversize tests). */
    fun sendRawText(text: String) {
        connection.sendText(text)
    }

    fun readEnvelope(): RelayEnvelope? {
        val bytes = connection.readMessage() ?: return null
        return try {
            EnvelopeCodec.decode(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    fun helloSigned(
        moveToken: String? = null,
        fromOverride: String? = null,
        publicKeyOverride: String? = null,
        proofOverride: String? = null,
        nonceOverride: String? = null,
        role: PeerRole = PeerRole.BOT
    ): RelayEnvelope {
        val relayPublic = B64Url.decode(challenge.relayPub ?: error("the challenge has no relay_pub"))
        val relayId = challengeRelayId()
        val nonceB64 = nonceOverride ?: challengeNonce()
        val nonce = runCatching { B64Url.decode(nonceB64) }.getOrDefault(ByteArray(32))
        val from = fromOverride ?: identity.peerId
        val proof = proofOverride ?: B64Url.encode(
            HelloAuth.proof(
                peerPrivateKey = identity.privateKey,
                peerPublicKey = identity.publicKey,
                relayPublicKey = relayPublic,
                relayId = relayId,
                peerId = from,
                nonce = nonce
            )
        )
        val hello = RelayEnvelope(
            type = RelayType.HELLO,
            from = from,
            pub = publicKeyOverride ?: identity.publicKeyBase64,
            sig = proof,
            nonce = nonceB64,
            role = role,
            moveToken = moveToken
        )
        send(hello)
        return hello
    }

    /** Opens, authenticates and returns the `hello_ok`. */
    fun authenticate(): RelayEnvelope {
        open()
        helloSigned()
        val reply = readEnvelope() ?: throw IllegalStateException("relay closed during hello")
        check(reply.type == RelayType.HELLO_OK) { "authentication failed: $reply" }
        return reply
    }

    override fun close() {
        if (::connection.isInitialized) connection.closeQuietly(1000, "test done")
    }

    companion object {
        fun randomProof(): String = B64Url.encode(Primitives.randomBytes(32))
    }
}
