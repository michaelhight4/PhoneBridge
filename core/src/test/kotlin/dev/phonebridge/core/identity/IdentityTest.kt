package dev.phonebridge.core.identity

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.model.PeerRole
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityTest {
    /** Fixed 32-byte key 0x00..0x1f; vector computed independently of this library. */
    private val vectorKey = ByteArray(32) { it.toByte() }
    private val vectorPeerId = "cc6wtab6rgspd48jah4bqcjv9w"
    private val vectorFingerprint = "CC6W-TAB6"

    @Test
    fun peerIdAndFingerprintMatchTheProtocolVector() {
        assertEquals(vectorPeerId, PeerIds.peerId(vectorKey))
        assertEquals(26, vectorPeerId.length)
        assertEquals(vectorFingerprint, PeerIds.fingerprint(vectorKey))
        assertEquals(vectorFingerprint, PeerIds.fingerprintOf(vectorPeerId))
        assertTrue(PeerIds.isValidPeerId(vectorPeerId))
        assertFalse(PeerIds.isValidPeerId(vectorPeerId.uppercase()))
        assertFalse(PeerIds.isValidPeerId(vectorPeerId.dropLast(1)))
        assertTrue(PeerIds.isValidFingerprint("cc6w-tab6"))
        assertTrue(PeerIds.isValidFingerprint("CC6WTAB6"))
        assertFalse(PeerIds.isValidFingerprint("CC6W-TAB"))
    }

    @Test
    fun generatedIdentityIsSelfConsistent() {
        val identity = Identity.generate(PeerRole.DEVICE, "Pixel")
        assertEquals(PeerRole.DEVICE, identity.role)
        assertEquals(32, identity.publicKey.size)
        assertEquals(PeerIds.peerId(identity.publicKey), identity.peerId)
        assertEquals(PeerIds.fingerprint(identity.publicKey), identity.fingerprint)
        assertTrue(identity.fingerprint.matches(Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}")))
        assertEquals("Pixel", identity.name)
    }

    @Test
    fun storedIdentitySurvivesJsonAndRejectsTampering() {
        val identity = Identity.generate(PeerRole.BOT, "laptop")
        val json = identity.toJson()
        val restored = Identity.fromJson(json)
        assertEquals(identity.peerId, restored.peerId)
        assertEquals(identity.name, restored.name)
        assertEquals(identity.role, restored.role)
        assertContentEquals(identity.publicKey, restored.publicKey)
        assertContentEquals(identity.privateKey, restored.privateKey)

        val tampered = json.replace(identity.publicKeyBase64, B64UrlDummy)
        assertFailsWith<CryptoException> { Identity.fromJson(tampered) }
    }

    private val B64UrlDummy = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun privateKeyBase64ReloadKeepsTheSamePeerId() {
        val identity = Identity.generate(PeerRole.DEVICE)
        val reloaded = Identity.fromPrivateKeyBase64(PeerRole.DEVICE, identity.exportPrivateKeyBase64())
        assertEquals(identity.peerId, reloaded.peerId)
        assertEquals(identity.fingerprint, reloaded.fingerprint)
    }

    @Test
    fun renamingKeepsTheKeys() {
        val identity = Identity.generate(PeerRole.BOT, "old")
        val renamed = identity.withName("new")
        assertEquals("new", renamed.name)
        assertEquals(identity.peerId, renamed.peerId)
        assertContentEquals(identity.publicKey, renamed.publicKey)
    }
}
