package dev.phonebridge.relay.crypto

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TlsTest {
    @Test
    fun `generates a self-signed certificate that parses back`() {
        val dir = Files.createTempDirectory("pb-tls").toFile()
        val material = Tls.loadOrCreateSelfSigned(File(dir, Tls.DEFAULT_KEYSTORE_FILE))

        val certificate = material.certificate
        assertTrue(certificate.subjectX500Principal.name.contains("CN=PhoneBridge Relay"))
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.notAfter.after(certificate.notBefore))
        assertEquals(43, material.fingerprint.length)
        assertTrue(material.fingerprintHex.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals(material.fingerprint, Tls.fingerprint(certificate))
    }

    @Test
    fun `keeps the certificate and fingerprint across restarts`() {
        val dir = Files.createTempDirectory("pb-tls-reload").toFile()
        val store = File(dir, Tls.DEFAULT_KEYSTORE_FILE)
        val first = Tls.loadOrCreateSelfSigned(store)
        val second = Tls.loadOrCreateSelfSigned(store)
        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(store.isFile)
    }

    @Test
    fun `different relays get different certificates`() {
        val a = Tls.loadOrCreateSelfSigned(File(Files.createTempDirectory("pb-tls-a").toFile(), Tls.DEFAULT_KEYSTORE_FILE))
        val b = Tls.loadOrCreateSelfSigned(File(Files.createTempDirectory("pb-tls-b").toFile(), Tls.DEFAULT_KEYSTORE_FILE))
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun `normalizes a pinned fingerprint`() {
        val pin = "abc-def_ghi"
        assertEquals("abc-def_ghi", Tls.normalizePin(" $pin "))
        assertEquals("abcd", Tls.normalizePin("abcd=="))
    }
}
