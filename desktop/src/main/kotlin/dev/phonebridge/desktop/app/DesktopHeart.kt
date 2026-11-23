package dev.phonebridge.desktop.app

import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.identity.PeerIds
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.Relay
import dev.phonebridge.relay.RelayConfig
import dev.phonebridge.relay.RelayHandle
import dev.phonebridge.relay.TlsConfig
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * The embedded relay ("the heart") plus the outbound relay client, on this PC.
 *
 * - The heart listens on the LAN with a self-signed certificate; its fingerprint is the pin peers use.
 * - The PC's own client always dials the heart over loopback, so a local pairing code works even when
 *   the LAN address is not reachable from this machine, and a remote relay works the same way.
 * - `heart.handOver` tells every connected peer to reconnect elsewhere; the local client follows the
 *   relay's `relay_moved` on its own.
 */
class DesktopHeart(
    private val dataDir: Path,
    private val identity: Identity,
    private val onEnvelope: (RelayEnvelope) -> Unit,
    private val onStateChange: (url: String?, connected: Boolean) -> Unit,
    private val log: (String) -> Unit = {},
    private val relayStarter: (RelayConfig) -> RelayHandle = { Relay.start(it) },
    private val advertisedHost: String? = LocalNetwork.primaryIPv4(),
) : HeartControl {
    private val lock = Any()
    private var relay: RelayHandle? = null
    private var client: RelayClient? = null

    @Volatile
    override var currentUrl: String? = null
        private set

    /** Pin for the client connection in use (the heart's own fingerprint, or the chosen relay's pin). */
    @Volatile
    private var clientPin: String? = null

    override fun currentPin(): String? = synchronized(lock) { relay?.fingerprint ?: clientPin }

    /** Starts the heart here (preferring port 8443) and points this PC's client at it. */
    override fun startHere(): String {
        synchronized(lock) {
            relay?.let { running ->
                if (running.isRunning) {
                    pointClientAt("wss://127.0.0.1:${running.port}", running.fingerprint)
                    return running.url
                }
            }
            val handle = startRelay(PREFERRED_PORT) ?: startRelay(0)
                ?: throw IllegalStateException("the relay could not bind a port on this PC")
            relay = handle
            currentUrl = handle.url
            pointClientAt("wss://127.0.0.1:${handle.port}", handle.fingerprint)
            log("heart started here: ${handle.url} (pin ${handle.fingerprint})")
            return handle.url
        }
    }

    override fun stopHere() {
        synchronized(lock) {
            relay?.stop()
            relay = null
        }
    }

    override fun useRelay(url: String, tlsPin: String?): Boolean {
        synchronized(lock) {
            pointClientAt(url, tlsPin)
            val started = client?.awaitConnected(CONNECT_WAIT_MS) == true
            if (!started) log("could not connect to $url within ${CONNECT_WAIT_MS} ms")
            return started
        }
    }

    override fun handOver(newUrl: String): Int {
        val handle = synchronized(lock) { relay } ?: throw IllegalStateException("this PC is not running the heart")
        return handle.moveTo(newUrl)
    }

    /** Sends one opaque end-to-end frame to [to] through the relay (used by the session handler). */
    fun sendRoute(to: String, payload: ByteArray): Boolean {
        val current = synchronized(lock) { client } ?: return false
        if (!runCatching { current.isConnected }.getOrDefault(false)) return false
        return runCatching { current.sendRouteBytes(to, payload); true }.getOrDefault(false)
    }

    /** Publishes a pairing offer under its short code so a bot can look the code up. */
    fun pairStore(code: String, offer: kotlinx.serialization.json.JsonObject): Boolean {
        val current = synchronized(lock) { client } ?: return false
        return runCatching { current.pairStore(code, offer); true }.getOrDefault(false)
    }

    fun relayStats(): String {
        val handle = synchronized(lock) { relay } ?: return "Heart not running here"
        val stats = handle.stats()
        return "Heart ${handle.url} · ${stats.peers} peers · ${stats.envelopesRouted} routed"
    }

    fun relayPeers(): List<String> = synchronized(lock) { relay?.peers() ?: emptyList() }

    fun close() {
        stopHere()
        synchronized(lock) {
            client?.stop()
            client = null
        }
        onStateChange(null, false)
    }

    private fun startRelay(port: Int): RelayHandle? = try {
        relayStarter(
            RelayConfig(
                port = port,
                bindAddress = "0.0.0.0",
                advertisedHost = advertisedHost,
                tls = TlsConfig.selfSigned(DesktopPaths.relayDir(dataDir).toString()),
                dataDir = DesktopPaths.relayDir(dataDir).toString(),
                moveSecret = moveSecret(),
                log = log,
            ),
        )
    } catch (e: Exception) {
        if (port == PREFERRED_PORT) log("port $PREFERRED_PORT is not free (${e.message}); using an ephemeral port")
        null
    }

    private fun pointClientAt(url: String, tlsPin: String?) {
        client?.stop()
        clientPin = tlsPin
        val newClient = RelayClient(
            RelayClientConfig(
                url = url,
                identity = identity,
                tlsPin = tlsPin,
                log = log,
            ),
        )
        newClient.onRoute = { envelope -> onEnvelope(envelope) }
        newClient.onStateChanged = { _, _ -> onStateChange(url, newClient.isConnected) }
        newClient.start()
        client = newClient
        onStateChange(url, newClient.isConnected)
    }

    /** The secret shared with a relay this PC hands over to; stable across restarts. */
    private fun moveSecret(): String {
        val file = DesktopPaths.moveSecretFile(dataDir)
        if (Files.isRegularFile(file)) {
            val existing = runCatching { Files.readString(file).trim() }.getOrNull()
            if (!existing.isNullOrBlank()) return existing
        }
        val secret = B64Url.encode(Primitives.randomBytes(32))
        Files.createDirectories(file.toAbsolutePath().parent)
        Files.write(file, secret.toByteArray(Charsets.UTF_8))
        return secret
    }

    companion object {
        const val PREFERRED_PORT = 8443
        const val CONNECT_WAIT_MS = 5_000L

        /** The relay id this PC would present, for diagnostics. */
        fun relayIdOf(identity: Identity): String = PeerIds.peerId(identity.publicKey)
    }
}
