package dev.phonebridge.core.crypto

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.util.B64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimitivesTest {
    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Primitives.toHex(Primitives.sha256("abc".toByteArray())),
        )
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Primitives.hkdf(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Primitives.toHex(okm),
        )
    }

    @Test
    fun noiseHkdfProducesThreeIndependentKeys() {
        val chainingKey = ByteArray(32) { 1 }
        val outputs = Primitives.noiseHkdf(chainingKey, ByteArray(32) { 2 }, 3)
        assertEquals(3, outputs.size)
        assertTrue(outputs.all { it.size == 32 })
        assertEquals(3, outputs.map { Primitives.toHex(it) }.toSet().size)
        // First output is HMAC(temp_key, 0x01) with temp_key = HMAC(chaining_key, ikm).
        val tempKey = Primitives.hmacSha256(chainingKey, ByteArray(32) { 2 })
        assertContentEquals(Primitives.hmacSha256(tempKey, byteArrayOf(1)), outputs[0])
    }

    @Test
    fun aeAdRoundTripAndFailureModes() {
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(12) { 3 }
        val aad = "hello".toByteArray()
        val plaintext = "the quick brown fox".toByteArray()
        val sealed = Primitives.aeadSeal(key, nonce, aad, plaintext)
        assertEquals(plaintext.size + 16, sealed.size)
        assertContentEquals(plaintext, Primitives.aeadOpen(key, nonce, aad, sealed))

        assertFailsWith<CryptoException>("wrong associated data must fail") {
            Primitives.aeadOpen(key, nonce, "other".toByteArray(), sealed)
        }
        assertFailsWith<CryptoException>("wrong key must fail") {
            Primitives.aeadOpen(ByteArray(32) { 8 }, nonce, aad, sealed)
        }
        assertFailsWith<CryptoException>("tampered tag must fail") {
            val tampered = sealed.copyOf()
            tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
            Primitives.aeadOpen(key, nonce, aad, tampered)
        }
        assertFailsWith<CryptoException>("truncated ciphertext must fail") {
            Primitives.aeadOpen(key, nonce, aad, sealed.copyOf(5))
        }
        assertFailsWith<CryptoException>("bad nonce length must fail") {
            Primitives.aeadSeal(key, ByteArray(11), aad, plaintext)
        }
    }

    @Test
    fun noiseNonceIsLittleEndianCounterPlusZeroPadding() {
        assertContentEquals(ByteArray(12), Primitives.noiseNonce(0))
        assertContentEquals(
            byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            Primitives.noiseNonce(1),
        )
        assertContentEquals(
            byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            Primitives.noiseNonce(256),
        )
    }

    @Test
    fun x25519AgreesOnBothSides() {
        val alice = X25519.generateKeyPair()
        val bob = X25519.generateKeyPair()
        assertContentEquals(
            X25519.sharedSecret(alice.privateKey, bob.publicKey),
            X25519.sharedSecret(bob.privateKey, alice.publicKey),
        )
        assertFailsWith<CryptoException>("low-order public key must be refused") {
            X25519.sharedSecret(alice.privateKey, ByteArray(32))
        }
    }

    @Test
    fun identityKeyPairsAreStableAcrossSerialization() {
        val keys = X25519.generateKeyPair()
        val restored = X25519.fromPrivateKey(keys.privateKey)
        assertContentEquals(keys.publicKey, restored.publicKey)
        assertEquals(keys, restored)
        assertContentEquals(keys.publicKey, B64Url.decode(B64Url.encode(keys.publicKey)))
    }

    @Test
    fun constantTimeEqualsWorks() {
        assertTrue(Primitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(Primitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(Primitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
    }
}
