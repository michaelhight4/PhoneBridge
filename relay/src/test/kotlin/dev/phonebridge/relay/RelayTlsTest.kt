package dev.phonebridge.relay

import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import dev.phonebridge.relay.client.ReconnectPolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelayTlsTest {
    private fun tempDir(): String = Files.createTempDirectory("pb-tls-relay").toFile().absolutePath

    private fun client(
        relay: RelayHandle,
        tlsPin: String?,
        insecureTls: Boolean = false
    ): RelayClient = RelayClient(
        RelayClientConfig(
            url = relay.url,
            identity = Identity.generate(PeerRole.BOT),
            tlsPin = tlsPin,
            insecureTls = insecureTls,
            reconnect = ReconnectPolicy(initialDelayMs = 50, maxDelayMs = 150, multiplier = 1.5, jitterMs = 0)
        )
    )

    @Test
    fun `a self-signed relay accepts peers that pin its fingerprint`() {
        val dir = tempDir()
        val relay = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        val device = TestSupport.client(relay, Identity.generate(PeerRole.DEVICE))
        val bot = TestSupport.client(relay)
        val atDevice = TestSupport.Collector<RelayEnvelope>()
        device.onRoute = { atDevice.add(it) }
        try {
            assertTrue(relay.url.startsWith("wss://"), "expected wss, got ${relay.url}")
            assertNotNull(relay.fingerprint)
            assertNotNull(relay.fingerprintHex)

            device.start()
            bot.start()
            assertTrue(device.awaitConnected(8000))
            assertTrue(bot.awaitConnected(8000))

            bot.sendRoute(device.peerId, B64Url.encode(Primitives.randomBytes(64)), id = "tls-1")
            assertEquals("tls-1", assertNotNull(atDevice.await()).id)
        } finally {
            device.stop()
            bot.stop()
            relay.stop()
        }
    }

    @Test
    fun `a wrong pinned fingerprint is rejected`() {
        val dir = tempDir()
        val relay = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        val client = client(relay, tlsPin = B64Url.encode(Primitives.randomBytes(32)))
        try {
            client.start()
            assertFalse(client.awaitConnected(2500), "a peer with the wrong pin must not connect")
            assertFalse(client.isConnected)
        } finally {
            client.stop()
            relay.stop()
        }
    }

    @Test
    fun `development mode can skip certificate checking`() {
        val dir = tempDir()
        val relay = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        val client = client(relay, tlsPin = null, insecureTls = true)
        try {
            client.start()
            assertTrue(client.awaitConnected(8000), "an insecure dev client should reach the relay")
        } finally {
            client.stop()
            relay.stop()
        }
    }

    @Test
    fun `hostname verification is enforced unless the certificate is pinned`() {
        val dir = tempDir()
        val relay = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        try {
            val material = dev.phonebridge.relay.crypto.Tls.fromKeyStore(
                java.io.File(dir, dev.phonebridge.relay.crypto.Tls.DEFAULT_KEYSTORE_FILE),
                dev.phonebridge.relay.crypto.Tls.DEFAULT_PASSWORD
            )
            val trusting = trustOnly(material.certificate)

            // A CA-style client (no pin) must check that the certificate matches the host name. The
            // generated certificate has no SAN, so connecting to 127.0.0.1 by name must fail.
            val mismatch = runCatching {
                dev.phonebridge.relay.ws.WebSocketClient.connect(
                    uri = java.net.URI(relay.url),
                    sslContext = trusting,
                    timeoutMs = 5000,
                    maxMessageBytes = 64 * 1024,
                    verifyHostname = true
                )
            }.exceptionOrNull()
            assertTrue(mismatch is java.io.IOException, "expected a host name verification failure, got $mismatch")

            // With verification off (pinned or development mode) the same certificate is accepted.
            dev.phonebridge.relay.ws.WebSocketClient.connect(
                uri = java.net.URI(relay.url),
                sslContext = trusting,
                timeoutMs = 5000,
                maxMessageBytes = 64 * 1024,
                verifyHostname = false
            ).closeQuietly(1000, "test done")
        } finally {
            relay.stop()
        }
    }

    private fun trustOnly(certificate: java.security.cert.X509Certificate): javax.net.ssl.SSLContext {
        val store = java.security.KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setCertificateEntry("relay", certificate)
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(store)
        return javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, factory.trustManagers, java.security.SecureRandom())
        }
    }

    @Test
    fun `the certificate fingerprint is stable across restarts`() {
        val dir = tempDir()
        val first = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        val firstPin = assertNotNull(first.fingerprint)
        val firstId = first.relayId
        first.stop()

        val second = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        try {
            assertEquals(firstPin, second.fingerprint)
            assertEquals(firstId, second.relayId, "the persisted relay identity should survive a restart")
            assertNotNull(second.fingerprintHex)
        } finally {
            second.stop()
        }
    }

    @Test
    fun `plain ws on a public interface needs an explicit opt in`() {
        val failure = runCatching {
            RelayConfig(bindAddress = "0.0.0.0", tls = null).validated()
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a rejection, got $failure")

        RelayConfig(bindAddress = "0.0.0.0", tls = null, insecureDev = true).validated()
        RelayConfig(bindAddress = "127.0.0.1", tls = null).validated()
    }

    @Test
    fun `a client refuses plain ws to a non loopback host`() {
        val failure = runCatching {
            RelayClientConfig(url = "ws://192.0.2.10:8443", identity = Identity.generate(PeerRole.BOT)).validated()
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a rejection, got $failure")

        RelayClientConfig(
            url = "ws://192.0.2.10:8443",
            identity = Identity.generate(PeerRole.BOT),
            allowInsecureWs = true
        ).validated()
    }
}
