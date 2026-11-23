package dev.phonebridge.desktop

import dev.phonebridge.desktop.app.DesktopSettingsStore
import dev.phonebridge.desktop.app.HeartControl
import dev.phonebridge.desktop.app.HeartController
import dev.phonebridge.desktop.app.HeartHost
import dev.phonebridge.desktop.app.RelayResult
import dev.phonebridge.desktop.state.InMemoryActivityLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartControllerTest {
    private class FakeHeart : HeartControl {
        var starts = 0
        var stops = 0
        var usedUrl: String? = null
        var usedPin: String? = null
        var handOverTarget: String? = null
        var notified = 2
        var failStart = false
        var acceptRelay = true
        override var currentUrl: String? = "wss://fake:1"
        var startedUrl = "wss://192.168.1.5:8443"

        override fun startHere(): String {
            if (failStart) throw IllegalStateException("port in use")
            starts++
            return startedUrl
        }

        override fun stopHere() {
            stops++
        }

        override fun useRelay(url: String, tlsPin: String?): Boolean {
            usedUrl = url
            usedPin = tlsPin
            currentUrl = url
            return acceptRelay
        }

        override fun handOver(newUrl: String): Int {
            handOverTarget = newUrl
            return notified
        }
    }

    private val log = InMemoryActivityLog()
    private val heart = FakeHeart()
    private val store = DesktopSettingsStore(Files.createTempDirectory("pb-heart").resolve("desktop.v1.json"))
    private val controller = HeartController(store, heart, log)

    @Test
    fun heartHereStartsOnceAndIsRemembered() {
        val offerUrl = controller.offerUrl()
        assertEquals("wss://192.168.1.5:8443", offerUrl)
        assertEquals(offerUrl, controller.offerUrl(), "the heart must not be started twice")
        assertEquals(1, heart.starts)
    }

    @Test
    fun moveHeartHereSavesAndLogs() {
        val result = controller.moveHeartHere()
        assertTrue(result is RelayResult.Ok)
        assertEquals("wss://192.168.1.5:8443", result.url)
        assertEquals(HeartHost.THIS_PC, controller.settings().host)
        assertEquals(HeartHost.THIS_PC, DesktopSettingsStore(store.path).load().host)
        assertTrue(log.recent().any { it.cmd == "relay_move" })
        assertEquals("Heart here: wss://192.168.1.5:8443", controller.statusText())
    }

    @Test
    fun aFailedStartIsReportedNotThrown() {
        heart.failStart = true
        val result = controller.moveHeartHere()
        assertTrue(result is RelayResult.Error)
        assertTrue(result.message.contains("port in use"))
    }

    @Test
    fun aCustomRelayIsValidatedPinnedAndSaved() {
        val result = controller.setCustomRelay("wss://relay.example:8443", " pin-value ")
        assertTrue(result is RelayResult.Ok)
        assertEquals("wss://relay.example:8443", heart.usedUrl)
        assertEquals("pin-value", heart.usedPin)
        assertEquals(1, heart.stops, "the local heart stops when the relay moves elsewhere")
        val saved = DesktopSettingsStore(store.path).load()
        assertEquals(HeartHost.CUSTOM_URL, saved.host)
        assertEquals("wss://relay.example:8443", saved.url)
        assertEquals("pin-value", saved.tlsPin)
    }

    @Test
    fun plainWsOffThisMachineIsRefused() {
        val result = controller.setCustomRelay("ws://relay.example:8443", null)
        assertTrue(result is RelayResult.Error)
        assertNull(heart.usedUrl)
        assertEquals(0, heart.stops)
    }

    @Test
    fun anUnreachableRelayIsAnError() {
        heart.acceptRelay = false
        val result = controller.setCustomRelay("wss://relay.example:8443", null)
        assertTrue(result is RelayResult.Error)
        assertEquals(HeartHost.THIS_PC, controller.settings().host, "the old choice survives a failed switch")
    }

    @Test
    fun handOverNeedsTlsAndReportsPeers() {
        controller.moveHeartHere()
        val moved = controller.handOver("wss://other.example:8443")
        assertTrue(moved is RelayResult.Ok)
        assertEquals("wss://other.example:8443", heart.handOverTarget)
        assertTrue(log.recent().any { it.detail?.contains("2 peers") == true })

        assertTrue(controller.handOver("ws://other.example:8443") is RelayResult.Error)
    }

    @Test
    fun handOverRefusesWhenThisPcIsNotTheHeart() {
        controller.setCustomRelay("wss://relay.example:8443", null)
        assertTrue(controller.handOver("wss://other.example:8443") is RelayResult.Error)
    }

    @Test
    fun validatesRelayUrls() {
        assertNull(HeartController.validateRelayUrl("wss://relay.example:8443"))
        assertNull(HeartController.validateRelayUrl("ws://127.0.0.1:8443"))
        assertTrue(HeartController.validateRelayUrl(null) != null)
        assertTrue(HeartController.validateRelayUrl("http://relay.example") != null)
        assertTrue(HeartController.validateRelayUrl("ws://relay.example") != null)
        assertTrue(HeartController.validateRelayUrl("wss://relay.example", requireTls = false) == null)
        assertTrue(HeartController.validateRelayUrl("wss://") != null)
        assertTrue(HeartController.isLoopback("localhost"))
        assertTrue(HeartController.isLoopback("[::1]"))
        assertTrue(!HeartController.isLoopback("example.test"))
    }
}
