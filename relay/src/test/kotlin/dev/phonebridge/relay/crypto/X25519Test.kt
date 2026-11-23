package dev.phonebridge.relay.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class X25519Test {
    // RFC 7748 section 5.2
    private val scalar1 = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"
    private val u1 = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"
    private val output1 = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"

    private val scalar2 = "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"
    private val u2 = "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"
    private val output2 = "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"

    // RFC 7748 section 6.1 (Diffie-Hellman)
    private val alicePrivate = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
    private val alicePublic = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    private val bobPrivate = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
    private val bobPublic = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    private val sharedSecret = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

    @Test
    fun `matches the first RFC 7748 vector`() {
        assertContentEquals(
            Codec.unhex(output1),
            X25519.sharedSecret(Codec.unhex(scalar1), Codec.unhex(u1))
        )
    }

    @Test
    fun `matches the second RFC 7748 vector`() {
        assertContentEquals(
            Codec.unhex(output2),
            X25519.sharedSecret(Codec.unhex(scalar2), Codec.unhex(u2))
        )
    }

    @Test
    fun `matches the RFC 7748 iteration vector`() {
        var k = Codec.unhex("0900000000000000000000000000000000000000000000000000000000000000")
        var u = k.copyOf()
        repeat(1) {
            val next = X25519.sharedSecret(k, u)
            u = k
            k = next
        }
        assertContentEquals(
            Codec.unhex("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079"),
            k
        )
    }

    @Test
    fun `diffie-hellman matches the RFC 7748 section 6_1 vector`() {
        val alice = Codec.unhex(alicePrivate)
        val bob = Codec.unhex(bobPrivate)
        assertContentEquals(Codec.unhex(alicePublic), X25519.publicKey(alice))
        assertContentEquals(Codec.unhex(bobPublic), X25519.publicKey(bob))
        assertContentEquals(Codec.unhex(sharedSecret), X25519.sharedSecret(alice, Codec.unhex(bobPublic)))
        assertContentEquals(Codec.unhex(sharedSecret), X25519.sharedSecret(bob, Codec.unhex(alicePublic)))
    }

    @Test
    fun `random key pairs agree on the same shared secret`() {
        val a = PeerKeyPair.generate()
        val b = PeerKeyPair.generate()
        val fromA = X25519.sharedSecret(a.privateKey, b.publicKey)
        val fromB = X25519.sharedSecret(b.privateKey, a.publicKey)
        assertContentEquals(fromA, fromB)
        assertFalse(X25519.isAllZero(fromA))
        assertEquals(32, fromA.size)
    }

    @Test
    fun `a low order point produces an all zero secret`() {
        val zero = ByteArray(32)
        assertTrue(X25519.isAllZero(X25519.sharedSecret(PeerKeyPair.generate().privateKey, zero)))
    }
}
