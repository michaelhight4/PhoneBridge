package dev.phonebridge.relay

import dev.phonebridge.relay.client.RelayClient
import dev.phonebridge.relay.client.RelayClientConfig
import dev.phonebridge.relay.client.ReconnectPolicy
import dev.phonebridge.relay.crypto.Codec
import dev.phonebridge.relay.crypto.PeerKeyPair
import dev.phonebridge.relay.envelope.RelayEnvelope
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
            identity = PeerKeyPair.generate(),
            role = "bot",
            tlsPin = tlsPin,
            insecureTls = insecureTls,
            reconnect = ReconnectPolicy(initialDelayMs = 50, maxDelayMs = 150, multiplier = 1.5, jitterMs = 0)
        )
    )

    @Test
    fun `a self-signed relay accepts peers that pin its fingerprint`() {
        val dir = tempDir()
        val relay = TestSupport.testRelay(tls = TlsConfig.selfSigned(dir), dataDir = dir)
        val device = TestSupport.client(relay, role = "device")
        val bot = TestSupport.client(relay, role = "bot")
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

            bot.sendRoute(device.peerId, Codec.b64url(Codec.randomBytes(64)), id = "tls-1")
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
        val client = client(relay, tlsPin = Codec.b64url(Codec.randomBytes(32)))
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
            assertNotEquals(null, second.fingerprintHex)
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
            RelayClientConfig(url = "ws://192.0.2.10:8443", identity = PeerKeyPair.generate()).validated()
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a rejection, got $failure")

        RelayClientConfig(
            url = "ws://192.0.2.10:8443",
            identity = PeerKeyPair.generate(),
            allowInsecureWs = true
        ).validated()
    }
}
