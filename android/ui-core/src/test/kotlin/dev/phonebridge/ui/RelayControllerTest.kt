package dev.phonebridge.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayControllerTest {

    private var now = 1_700_000_000_000L
    private val log = InMemoryActivityLog(clock = { now })

    private class FakeHeart : HeartControl {
        var startedHere = false
        var stopped = false
        var usedUrl: String? = null
        var usedPin: String? = null
        var nextUrl = "wss://192.168.1.20:8443"

        override fun startHeartHere(): String {
            startedHere = true
            return nextUrl
        }

        override fun stopHeartHere() {
            stopped = true
        }

        override fun useRelay(url: String, tlsPin: String?): Boolean {
            usedUrl = url
            usedPin = tlsPin
            return true
        }
    }

    private fun controller(heart: HeartControl): RelayController {
        val dir = Files.createTempDirectory("pb-relay-test")
        return RelayController(RelaySettingsStore(dir.resolve("relay.v1.json")), heart, log, clock = { now })
    }

    @Test
    fun moveHeartHereStartsTheEmbeddedRelayAndIsOfferedToBots() {
        val heart = FakeHeart()
        val relay = controller(heart)
        val result = relay.moveHeartHere()

        assertTrue(result is RelayResult.Ok)
        assertTrue(heart.startedHere)
        assertEquals("wss://192.168.1.20:8443", relay.offerUrl())
        assertEquals(RelayHost.THIS_PHONE, relay.settings().host)
        assertTrue(log.entries().any { it.cmd == "relay_move" })
    }

    @Test
    fun theHeartCanLiveOnMyPc() {
        val heart = FakeHeart()
        val relay = controller(heart)
        val result = relay.setHost(RelayHost.MY_PC, "wss://pc.example:8443", "PIN123")

        assertTrue(result is RelayResult.Ok)
        assertEquals("wss://pc.example:8443", heart.usedUrl)
        assertEquals("PIN123", heart.usedPin)
        assertTrue(heart.stopped)
        assertEquals("wss://pc.example:8443", relay.offerUrl())
        assertEquals(RelayHost.MY_PC, relay.settings().host)
    }

    @Test
    fun plainWsIsOnlyAllowedOnThisDevice() {
        assertNull(RelayController.validateRelayUrl("ws://127.0.0.1:8443"))
        assertNull(RelayController.validateRelayUrl("ws://localhost:8443"))
        assertNull(RelayController.validateRelayUrl("wss://relay.example.com:8443"))
        assertTrue(RelayController.validateRelayUrl("ws://relay.example.com:8443") != null)
        assertTrue(RelayController.validateRelayUrl("http://relay.example.com") != null)
        assertTrue(RelayController.validateRelayUrl("") != null)

        val relay = controller(FakeHeart())
        val rejected = relay.setHost(RelayHost.CUSTOM_URL, "ws://relay.example.com:8443")
        assertTrue(rejected is RelayResult.Error)
    }

    @Test
    fun settingsArePersisted() {
        val dir = Files.createTempDirectory("pb-relay-persist")
        try {
            val file = dir.resolve("relay.v1.json")
            val store = RelaySettingsStore(file)
            store.save(RelaySettings(host = RelayHost.CUSTOM_URL, url = "wss://relay.example.com:9000"))
            val loaded = RelaySettingsStore(file).load()
            assertEquals(RelayHost.CUSTOM_URL, loaded.host)
            assertEquals("wss://relay.example.com:9000", loaded.url)

            // A corrupt file falls back to the safe default instead of crashing the app.
            Files.writeString(file, "{not json")
            assertEquals(RelayHost.THIS_PHONE, RelaySettingsStore(file).load().host)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun statusTextNamesTheChosenHost() {
        val relay = controller(FakeHeart())
        assertTrue(relay.statusText().contains("this phone"))
        val moved = relay.setHost(RelayHost.MY_PC, "wss://pc.example:8443")
        assertTrue(moved is RelayResult.Ok)
        assertFalse(relay.statusText().isEmpty())
        assertTrue(relay.statusText().contains("pc.example"))
    }
}
