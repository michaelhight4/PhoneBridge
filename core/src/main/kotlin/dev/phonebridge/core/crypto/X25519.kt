package dev.phonebridge.core.crypto

import dev.phonebridge.core.CryptoException
import org.bouncycastle.math.ec.rfc7748.X25519 as BcX25519
import java.security.SecureRandom

/**
 * An X25519 key pair. The arrays are owned by this object; treat them as read-only and copy before
 * storing them somewhere that might mutate them.
 */
class X25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    init {
        if (privateKey.size != X25519.DHLEN) throw CryptoException("private key must be ${X25519.DHLEN} bytes")
        if (publicKey.size != X25519.DHLEN) throw CryptoException("public key must be ${X25519.DHLEN} bytes")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is X25519KeyPair) return false
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * privateKey.contentHashCode() + publicKey.contentHashCode()

    override fun toString(): String = "X25519KeyPair(pub=${publicKey.size}B)"
}

/** X25519 Diffie-Hellman using Bouncy Castle's RFC 7748 implementation (pure Java, Android safe). */
object X25519 {
    const val DHLEN = 32

    fun generateKeyPair(random: SecureRandom = Primitives.defaultRandom()): X25519KeyPair {
        val privateKey = ByteArray(DHLEN)
        BcX25519.generatePrivateKey(random, privateKey)
        return fromPrivateKey(privateKey)
    }

    /** Rebuilds a key pair from a stored private key (the public key is always derived, never trusted). */
    fun fromPrivateKey(privateKey: ByteArray): X25519KeyPair {
        if (privateKey.size != DHLEN) throw CryptoException("x25519 private key must be $DHLEN bytes")
        val publicKey = ByteArray(DHLEN)
        BcX25519.generatePublicKey(privateKey, 0, publicKey, 0)
        return X25519KeyPair(privateKey.copyOf(), publicKey)
    }

    fun publicKeyOf(privateKey: ByteArray): ByteArray = fromPrivateKey(privateKey).publicKey

    /**
     * X25519(our private, their public). Rejects low-order public keys: RFC 7748 says the output of an
     * all-zero shared secret must be rejected.
     */
    fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (privateKey.size != DHLEN) throw CryptoException("x25519 private key must be $DHLEN bytes")
        if (publicKey.size != DHLEN) throw CryptoException("x25519 public key must be $DHLEN bytes")
        val shared = ByteArray(DHLEN)
        BcX25519.scalarMult(privateKey, 0, publicKey, 0, shared, 0)
        // RFC 7748: an all-zero shared secret means a low-order public key; refuse it.
        var accumulator = 0
        for (byte in shared) accumulator = accumulator or byte.toInt()
        if (accumulator == 0) throw CryptoException("invalid x25519 public key (low order point)")
        return shared
    }
}
