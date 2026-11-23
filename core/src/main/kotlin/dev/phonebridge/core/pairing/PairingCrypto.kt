package dev.phonebridge.core.pairing

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.crypto.Primitives
import java.security.SecureRandom

/**
 * Pairing payload crypto: a one-time secret is stretched into an AEAD key and the request/result JSON
 * is sealed with ChaCha20-Poly1305. The AEAD associated data binds the direction and the device key, so
 * a captured `pair_request` can never be replayed as a `pair_result` or re-targeted at another device.
 *
 * Sealed bytes are `nonce(12) || ciphertext || tag(16)`, nonce drawn from the CSPRNG per message.
 */
object PairingCrypto {
    const val SALT_TEXT = "PhoneBridge/1/pairing"
    const val KEY_INFO_TEXT = "pair-frame"
    const val REQUEST_LABEL = "PhoneBridge/1/pair|request|"
    const val RESULT_LABEL = "PhoneBridge/1/pair|result|"

    /** HKDF-SHA256(secret, salt = "PhoneBridge/1/pairing", info = "pair-frame") -> 32-byte AEAD key. */
    fun deriveKey(secret: ByteArray): ByteArray {
        if (secret.size != 32) throw CryptoException("pairing secret must be 32 bytes")
        return Primitives.hkdf(
            ikm = secret,
            salt = SALT_TEXT.toByteArray(Charsets.US_ASCII),
            info = KEY_INFO_TEXT.toByteArray(Charsets.US_ASCII),
            length = Primitives.KEY_BYTES,
        )
    }

    fun requestAad(devicePublicKey: ByteArray): ByteArray =
        REQUEST_LABEL.toByteArray(Charsets.US_ASCII) + devicePublicKey

    fun resultAad(devicePublicKey: ByteArray): ByteArray =
        RESULT_LABEL.toByteArray(Charsets.US_ASCII) + devicePublicKey

    fun seal(
        secret: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
        random: SecureRandom = Primitives.defaultRandom(),
    ): ByteArray {
        val key = deriveKey(secret)
        val nonce = Primitives.randomBytes(Primitives.NONCE_BYTES, random)
        return nonce + Primitives.aeadSeal(key, nonce, associatedData, plaintext)
    }

    fun open(secret: ByteArray, associatedData: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < Primitives.NONCE_BYTES + Primitives.TAG_BYTES) {
            throw CryptoException("pairing payload too short")
        }
        val key = deriveKey(secret)
        val nonce = sealed.copyOfRange(0, Primitives.NONCE_BYTES)
        val ciphertext = sealed.copyOfRange(Primitives.NONCE_BYTES, sealed.size)
        return Primitives.aeadOpen(key, nonce, associatedData, ciphertext)
    }
}
