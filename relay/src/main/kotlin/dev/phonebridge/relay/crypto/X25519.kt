package dev.phonebridge.relay.crypto

import java.math.BigInteger

/**
 * X25519 (RFC 7748) in plain Kotlin, using `BigInteger` for field arithmetic.
 *
 * The relay only needs X25519 to verify the `hello` proof of key ownership, so portability and
 * obvious correctness win over raw speed here: every primitive is available on Android API 26 and
 * on a plain JVM. A full scalar multiplication takes a few milliseconds.
 *
 * Note: this implementation is *not* constant time. The relay uses it on its own ephemeral keys and
 * on protocol nonces, never as a long-term secret. :core will supply the constant-time
 * implementation used for end-to-end sessions; see HANDOFF.md.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val TWO = BigInteger.TWO

    val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** X25519(privateKey, 9): the public key for a 32 byte private key. */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        return scalarMult(privateKey, BASE_POINT)
    }

    /**
     * X25519(privateKey, peerPublic). The result is all zero for low order points, which callers
     * must check (see [isAllZero]).
     */
    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(peerPublic.size == 32) { "X25519 public key must be 32 bytes" }
        return scalarMult(privateKey, peerPublic)
    }

    fun isAllZero(data: ByteArray): Boolean = Codec.isAllZero(data)

    private fun scalarMult(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()

        val u = uCoordinate.copyOf()
        u[31] = (u[31].toInt() and 127).toByte()

        val scalarInt = littleEndianToBigInteger(k)
        val x1 = mod(littleEndianToBigInteger(u))

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = if (scalarInt.testBit(t)) 1 else 0
            swap = swap xor bit
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit

            val a = mod(x2.add(z2))
            val aa = mod(a.multiply(a))
            val b = mod(x2.subtract(z2))
            val bb = mod(b.multiply(b))
            val e = mod(aa.subtract(bb))
            val c = mod(x3.add(z3))
            val d = mod(x3.subtract(z3))
            val da = mod(d.multiply(a))
            val cb = mod(c.multiply(b))
            val daPlusCb = mod(da.add(cb))
            val daMinusCb = mod(da.subtract(cb))
            x3 = mod(daPlusCb.multiply(daPlusCb))
            z3 = mod(x1.multiply(mod(daMinusCb.multiply(daMinusCb))))
            x2 = mod(aa.multiply(bb))
            z2 = mod(e.multiply(mod(aa.add(A24.multiply(e)))))
        }

        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        val result = if (z2.signum() == 0) {
            BigInteger.ZERO
        } else {
            mod(x2.multiply(z2.modPow(P.subtract(TWO), P)))
        }
        return bigIntegerToLittleEndian(result)
    }

    private fun mod(value: BigInteger): BigInteger = value.mod(P)

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        val bigEndian = ByteArray(bytes.size)
        for (i in bytes.indices) bigEndian[i] = bytes[bytes.size - 1 - i]
        return BigInteger(1, bigEndian)
    }

    private fun bigIntegerToLittleEndian(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(32)
        val start = bigEndian.size - 32
        if (start >= 0) {
            System.arraycopy(bigEndian, start, out, 0, 32)
        } else {
            System.arraycopy(bigEndian, 0, out, -start, bigEndian.size)
        }
        out.reverse()
        return out
    }
}
