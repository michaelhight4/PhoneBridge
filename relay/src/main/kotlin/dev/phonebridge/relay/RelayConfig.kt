package dev.phonebridge.relay

import dev.phonebridge.relay.crypto.Tls
import java.io.File

/**
 * TLS settings for a relay.
 *
 * - [keystorePath] set: load a PKCS#12 keystore (the usual standalone/VPS case).
 * - [keystorePath] null: generate a self-signed P-256 certificate on first run under [certDir] and
 *   reuse it afterwards, so the fingerprint pinned in pairing offers stays stable.
 */
data class TlsConfig(
    val keystorePath: String? = null,
    val keystorePassword: String? = null,
    val keyAlias: String = Tls.DEFAULT_ALIAS,
    val certDir: String? = null,
    val subject: String = "CN=PhoneBridge Relay"
) {
    companion object {
        /** Self-signed certificate, stored in [dir] (defaults to the relay data directory). */
        fun selfSigned(dir: String? = null): TlsConfig = TlsConfig(certDir = dir)

        fun keyStore(path: String, password: String, alias: String = Tls.DEFAULT_ALIAS): TlsConfig =
            TlsConfig(keystorePath = path, keystorePassword = password, keyAlias = alias)
    }
}

data class RelayConfig(
    val port: Int = 8443,
    /**
     * Interface to listen on. A wildcard address (`0.0.0.0`, `::`) means "reachable from the LAN", so
     * it requires TLS unless [insecureDev] is set explicitly.
     */
    val bindAddress: String = "0.0.0.0",
    /** Host name advertised in [RelayHandle.url] and in pairing offers (defaults to the bind address). */
    val advertisedHost: String? = null,
    val tls: TlsConfig? = null,
    /** Explicit opt-in for plain `ws://` on a non-loopback address. Development only. */
    val insecureDev: Boolean = false,
    /** docs/PROTOCOL.md: relays must drop envelopes larger than 16 MiB. */
    val maxEnvelopeBytes: Int = 16 * 1024 * 1024,
    val rateLimit: RateLimitConfig = RateLimitConfig(),
    /** Short-code mailbox lifetime (10 minutes per docs/PROTOCOL.md). */
    val pairMailboxTtlMs: Long = 10 * 60 * 1000,
    /** Largest raw bot message frame (base64url-decoded) the relay will store. */
    val maxBotMessageBytes: Int = 64 * 1024,
    /** Mailbox limits, per bot and for the whole relay. */
    val maxMailboxMessagesPerBot: Int = 100,
    val maxMailboxBytesPerBot: Int = 1024 * 1024,
    val maxTotalMailboxMessages: Int = 5000,
    /** How long an undelivered bot message is kept before it expires. */
    val botMessageTtlMs: Long = 7L * 24 * 60 * 60 * 1000,
    /** Separate token bucket for `msg_send`, so chatty bots cannot starve normal routing. */
    val botMessageRateLimit: RateLimitConfig = RateLimitConfig(
        maxMessages = 60,
        windowMs = 60_000,
        burst = 20,
    ),
    /** How long a connection may stay unauthenticated before the relay closes it. */
    val helloTimeoutMs: Int = 30_000,
    /** Idle read timeout after which the relay sends a WebSocket ping. */
    val idleTimeoutMs: Int = 120_000,
    /** Maximum number of open WebSocket connections. */
    val maxConnections: Int = 512,
    /**
     * Secret shared with the next relay for `relay_move`. Hex or base64url (or any text, hashed as
     * given). Without it the relay refuses to hand over, and refuses incoming move tokens.
     */
    val moveSecret: String? = null,
    /** Directory for the relay identity key and the generated certificate. */
    val dataDir: String? = null,
    val log: (String) -> Unit = {}
) {
    /** Throws [IllegalArgumentException] for an unsafe combination of settings. */
    fun validated(): RelayConfig {
        require(port in 0..65535) { "port must be between 0 and 65535" }
        require(maxEnvelopeBytes in 1024..(64 * 1024 * 1024)) {
            "maxEnvelopeBytes must be between 1 KiB and 64 MiB"
        }
        require(pairMailboxTtlMs > 0) { "pairMailboxTtlMs must be positive" }
        require(maxBotMessageBytes in 1..(64 * 1024 * 1024)) {
            "maxBotMessageBytes must be between 1 and 64 MiB"
        }
        require(maxMailboxMessagesPerBot > 0) { "maxMailboxMessagesPerBot must be positive" }
        require(maxMailboxBytesPerBot > 0) { "maxMailboxBytesPerBot must be positive" }
        require(maxTotalMailboxMessages > 0) { "maxTotalMailboxMessages must be positive" }
        require(botMessageTtlMs > 0) { "botMessageTtlMs must be positive" }
        require(helloTimeoutMs > 0) { "helloTimeoutMs must be positive" }
        require(maxConnections > 0) { "maxConnections must be positive" }
        if (tls == null && !insecureDev && !isLoopbackAddress(bindAddress)) {
            throw IllegalArgumentException(
                "plain ws:// is only allowed on loopback; enable TLS or pass insecureDev = true " +
                    "(the standalone jar's --insecure-dev flag) for a throwaway development relay"
            )
        }
        return this
    }

    internal fun dataDirectory(): File =
        File(dataDir ?: File(System.getProperty("user.home"), ".phonebridge").path)

    companion object {
        fun isLoopbackAddress(host: String): Boolean = try {
            java.net.InetAddress.getByName(host).isLoopbackAddress
        } catch (_: Exception) {
            host.equals("localhost", ignoreCase = true)
        }
    }
}
