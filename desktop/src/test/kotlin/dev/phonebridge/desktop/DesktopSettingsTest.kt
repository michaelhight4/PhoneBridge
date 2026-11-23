package dev.phonebridge.desktop

import dev.phonebridge.desktop.app.DesktopSettings
import dev.phonebridge.desktop.app.DesktopSettingsStore
import dev.phonebridge.desktop.app.HeartHost
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSettingsTest {

    @Test
    fun roundTripsAChoice() {
        val file = Files.createTempDirectory("pb-settings").resolve("desktop.v1.json")
        val store = DesktopSettingsStore(file)
        assertEquals(DesktopSettings(), store.load())

        val settings = DesktopSettings(
            host = HeartHost.CUSTOM_URL,
            url = "wss://relay.example:8443",
            tlsPin = "abc",
        )
        store.save(settings)
        assertEquals(settings, DesktopSettingsStore(file).load())
        assertTrue(Files.exists(file))
    }

    @Test
    fun aCorruptFileFallsBackToTheSafeDefault() {
        val file = Files.createTempDirectory("pb-settings").resolve("desktop.v1.json")
        Files.writeString(file, "{ broken")
        assertEquals(DesktopSettings(), DesktopSettingsStore(file).load())
    }

    @Test
    fun theDefaultHostIsThisPc() {
        assertEquals(HeartHost.THIS_PC, DesktopSettings().host)
    }
}
