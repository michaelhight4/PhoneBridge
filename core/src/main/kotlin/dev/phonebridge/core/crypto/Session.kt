package dev.phonebridge.core.crypto

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.identity.PeerIds

/**
 * One-byte tag in front of every end-to-end frame, so a peer can tell handshake traffic from transport
 * traffic even when both arrive as relay envelopes. The tag itself is never encrypted.
 *
 *   0x01 || Noise handshake message
 *   0x02 || Noise transport ciphertext
 */
object Frame {
    const val HANDSHAKE: Byte = 0x01
    const val TRANSPORT: Byte = 0x02

    fun handshake(body: ByteArray): ByteArray = wrap(HANDSHAKE, body)

    fun transport(body: ByteArray): ByteArray = wrap(TRANSPORT, body)

    fun type(frame: ByteArray): Byte {
        if (frame.isEmpty()) throw CryptoException("empty frame")
        return frame[0]
    }

    fun body(frame: ByteArray, expected: Byte): ByteArray {
        val actual = type(frame)
        if (actual != expected) throw CryptoException("expected frame type $expected, got $actual")
        return frame.copyOfRange(1, frame.size)
    }

    private fun wrap(tag: Byte, body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 1)
        out[0] = tag
        System.arraycopy(body, 0, out, 1, body.size)
        return out
    }
}

/** A finished end-to-end session: one key per direction, counters, replay rejection. */
class SecureChannel internal constructor(private val transport: NoiseTransport) {
    val peerStaticKey: ByteArray
        get() = transport.peerStaticKey.copyOf()

    val peerId: String
        get() = PeerIds.peerId(transport.peerStaticKey)

    val peerFingerprint: String
        get() = PeerIds.fingerprint(transport.peerStaticKey)

    val handshakeHash: ByteArray
        get() = transport.handshakeHash.copyOf()

    val sentMessages: Long
        get() = transport.sentMessages

    val receivedMessages: Long
        get() = transport.receivedMessages

    fun seal(plaintext: ByteArray): ByteArray = transport.seal(plaintext)

    fun open(ciphertext: ByteArray): ByteArray = transport.open(ciphertext)

    fun sealText(text: String): ByteArray = seal(text.toByteArray(Charsets.UTF_8))

    fun openText(ciphertext: ByteArray): String = String(open(ciphertext), Charsets.UTF_8)

    /** True when the handshake produced the key we expected for this peer (paired key pinning). */
    fun verifyPeer(expectedPublicKey: ByteArray): Boolean =
        Primitives.constantTimeEquals(transport.peerStaticKey, expectedPublicKey)

    /** Throws unless the handshake produced [expectedPublicKey]; call this before trusting the channel. */
    fun requirePeer(expectedPublicKey: ByteArray) {
        if (!verifyPeer(expectedPublicKey)) throw CryptoException("handshake peer key does not match the paired peer")
    }
}

/**
 * Drives the Noise `XX` handshake over relay frames and exposes the encrypted channel once it
 * completes. Callers exchange [Frame]s between peers (base64url for the relay envelope).
 */
class E2ESession(
    val role: NoiseRole,
    localStatic: X25519KeyPair,
    prologue: ByteArray = NoiseXX.prologue(),
    random: java.security.SecureRandom = Primitives.defaultRandom(),
) {
    private val handshake = NoiseXXHandshake(role, localStatic, prologue, random)

    /** Non-null once the third handshake message has been processed. */
    var channel: SecureChannel? = null
        private set

    val isEstablished: Boolean
        get() = channel != null

    val canWriteHandshake: Boolean
        get() = handshake.canWrite

    val canReadHandshake: Boolean
        get() = handshake.canRead

    /** The peer's static key once the handshake reached its third message. */
    val peerStaticKey: ByteArray?
        get() = handshake.peerStaticKey

    /** True when the peer's static key must match [expectedPublicKey] (pin the paired key). */
    fun writeHandshakeFrame(payload: ByteArray = ByteArray(0)): ByteArray {
        val frame = Frame.handshake(handshake.writeMessage(payload))
        // The initiator completes the pattern when it writes its third message.
        establishIfComplete()
        return frame
    }

    fun readHandshakeFrame(frame: ByteArray): ByteArray {
        val payload = handshake.readMessage(Frame.body(frame, Frame.HANDSHAKE))
        establishIfComplete()
        return payload
    }

    private fun establishIfComplete() {
        if (handshake.isComplete && channel == null) {
            channel = SecureChannel(handshake.split())
        }
    }

    fun seal(plaintext: ByteArray): ByteArray = requireChannel().let { Frame.transport(it.seal(plaintext)) }

    fun open(frame: ByteArray): ByteArray = requireChannel().open(Frame.body(frame, Frame.TRANSPORT))

    fun sealText(text: String): ByteArray = seal(text.toByteArray(Charsets.UTF_8))

    fun openText(frame: ByteArray): String = String(open(frame), Charsets.UTF_8)

    private fun requireChannel(): SecureChannel =
        channel ?: throw CryptoException("handshake is not complete yet")
}
