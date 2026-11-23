package dev.phonebridge.core.crypto

import dev.phonebridge.core.CryptoException
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/**
 * The symmetric primitives PhoneBridge uses, all from Bouncy Castle's lightweight API so they run
 * unchanged on the JVM and on Android API 26+ (no `javax.crypto` provider needed).
 */
object Primitives {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    const val HASH = "SHA-256"
    const val CIPHER = "ChaCha20-Poly1305"
    const val DH = "X25519"

    private val sharedRandom: SecureRandom by lazy { SecureRandom() }

    /** Process-wide CSPRNG. Tests may pass their own [SecureRandom] to make keys deterministic. */
    fun defaultRandom(): SecureRandom = sharedRandom

    fun randomBytes(size: Int, random: SecureRandom = defaultRandom()): ByteArray {
        val out = ByteArray(size)
        random.nextBytes(out)
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = SHA256Digest()
        for (part in parts) digest.update(part, 0, part.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    /** RFC 5869 HKDF-SHA256 (extract + expand). Used outside the Noise handshake. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * KEY_BYTES)) { "bad HKDF length: $length" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(KEY_BYTES) else salt
        val prk = hmacSha256(effectiveSalt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            block = hmacSha256(prk, block + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, length - written)
            System.arraycopy(block, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    /**
     * The Noise `HKDF(chaining_key, input_key_material, num_outputs)` of the spec: HMAC-expand with
     * one-byte counters and no info, returning [numOutputs] 32-byte values.
     */
    fun noiseHkdf(chainingKey: ByteArray, inputKeyMaterial: ByteArray, numOutputs: Int): List<ByteArray> {
        require(numOutputs in 1..3) { "bad HKDF output count: $numOutputs" }
        val tempKey = hmacSha256(chainingKey, inputKeyMaterial)
        val outputs = ArrayList<ByteArray>(numOutputs)
        var previous = ByteArray(0)
        for (counter in 1..numOutputs) {
            previous = hmacSha256(tempKey, previous + byteArrayOf(counter.toByte()))
            outputs.add(previous)
        }
        return outputs
    }

    /** ChaCha20-Poly1305 seal: returns ciphertext with the 16-byte tag appended. */
    fun aeadSeal(key: ByteArray, nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        requireKey(key, "aead key")
        requireNonce(nonce)
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, associatedData))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val written = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        return try {
            val final = cipher.doFinal(out, written)
            out.copyOf(written + final)
        } catch (e: InvalidCipherTextException) {
            throw CryptoException("encryption failed", e)
        }
    }

    /** ChaCha20-Poly1305 open: throws [CryptoException] when the tag does not verify. */
    fun aeadOpen(key: ByteArray, nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray {
        requireKey(key, "aead key")
        requireNonce(nonce)
        if (ciphertext.size < TAG_BYTES) throw CryptoException("ciphertext too short")
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, associatedData))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val written = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        return try {
            val final = cipher.doFinal(out, written)
            out.copyOf(written + final)
        } catch (e: InvalidCipherTextException) {
            throw CryptoException("authentication failed", e)
        }
    }

    /**
     * The Noise nonce: the 64-bit counter little-endian, padded with four zero bytes to 12 bytes so it
     * fits ChaCha20-Poly1305's 96-bit nonce.
     */
    fun noiseNonce(counter: Long): ByteArray {
        require(counter >= 0) { "nonce counter must not be negative" }
        val nonce = ByteArray(NONCE_BYTES)
        for (i in 0 until 8) nonce[i] = ((counter ushr (8 * i)) and 0xFF).toByte()
        return nonce
    }

    /** Length-independent comparison for tags and MACs. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun toHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return out.toString()
    }

    private fun requireKey(key: ByteArray, what: String) {
        if (key.size != KEY_BYTES) throw CryptoException("$what must be $KEY_BYTES bytes, got ${key.size}")
    }

    private fun requireNonce(nonce: ByteArray) {
        if (nonce.size != NONCE_BYTES) throw CryptoException("nonce must be $NONCE_BYTES bytes, got ${nonce.size}")
    }
}
