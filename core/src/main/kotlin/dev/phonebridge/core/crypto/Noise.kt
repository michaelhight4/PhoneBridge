package dev.phonebridge.core.crypto

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.ProtocolException
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

enum class NoiseRole {
    INITIATOR,
    RESPONDER,
    ;

    val other: NoiseRole
        get() = if (this == INITIATOR) RESPONDER else INITIATOR
}

/**
 * The Noise `CipherState`: one 32-byte key plus a 64-bit message counter. Each successful encrypt or
 * decrypt advances the counter, so a message can only be opened at the exact counter position it was
 * sealed at. Replays and reordering therefore fail authentication instead of being silently accepted.
 */
class NoiseCipherState internal constructor(
    private var key: ByteArray?,
    private var nonce: Long = 0L,
) {
    val hasKey: Boolean
        get() = key != null

    /** Messages processed so far, i.e. the next counter value. */
    val counter: Long
        get() = nonce

    fun encryptWithAd(associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val currentKey = key ?: return plaintext
        if (nonce == Long.MAX_VALUE) throw CryptoException("nonce exhausted, session must be replaced")
        val ciphertext = Primitives.aeadSeal(currentKey, Primitives.noiseNonce(nonce), associatedData, plaintext)
        nonce++
        return ciphertext
    }

    fun decryptWithAd(associatedData: ByteArray, ciphertext: ByteArray): ByteArray {
        val currentKey = key ?: return ciphertext
        if (nonce == Long.MAX_VALUE) throw CryptoException("nonce exhausted, session must be replaced")
        val plaintext = Primitives.aeadOpen(currentKey, Primitives.noiseNonce(nonce), associatedData, ciphertext)
        // Only advance after a successful authentication check.
        nonce++
        return plaintext
    }
}

/** The Noise `SymmetricState`: chaining key, handshake hash, and the current encryption key. */
private class SymmetricState(protocolName: String) {
    var chainingKey: ByteArray
        private set
    var handshakeHash: ByteArray
        private set
    private var cipher = NoiseCipherState(null)

    val hasKey: Boolean
        get() = cipher.hasKey

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        handshakeHash = if (name.size <= NoiseXX.HASHLEN) {
            name.copyOf(NoiseXX.HASHLEN)
        } else {
            Primitives.sha256(name)
        }
        chainingKey = handshakeHash.copyOf()
    }

    fun mixHash(data: ByteArray) {
        handshakeHash = Primitives.sha256(handshakeHash, data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val outputs = Primitives.noiseHkdf(chainingKey, inputKeyMaterial, 2)
        chainingKey = outputs[0]
        cipher = NoiseCipherState(outputs[1])
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(handshakeHash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decryptWithAd(handshakeHash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    fun split(): Pair<ByteArray, ByteArray> {
        val outputs = Primitives.noiseHkdf(chainingKey, ByteArray(0), 2)
        return outputs[0] to outputs[1]
    }
}

internal enum class Token(val text: String) {
    E("e"),
    S("s"),
    EE("ee"),
    ES("es"),
    SE("se"),
    SS("ss"),
}

/**
 * The Noise `XX` handshake: `-> e`, `<- e, ee, s, es`, `-> s, se`.
 *
 * Both static public keys travel encrypted and are only exchanged after an ephemeral-ephemeral DH, so
 * a passive relay learns nothing, and each side learns the other's static key. The application must
 * still check that key against its paired peer before trusting the session (see [E2ESession]).
 */
class NoiseXXHandshake(
    val role: NoiseRole,
    private val localStatic: X25519KeyPair,
    prologue: ByteArray = NoiseXX.prologue(),
    private val random: SecureRandom = Primitives.defaultRandom(),
) {
    private val symmetric = SymmetricState(NoiseXX.PROTOCOL_NAME)
    private var localEphemeral: X25519KeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteStatic: ByteArray? = null

    /** Number of handshake messages already written or read, 0..3. */
    var messageIndex: Int = 0
        private set

    val isComplete: Boolean
        get() = messageIndex >= NoiseXX.PATTERN.size

    val peerStaticKey: ByteArray?
        get() = remoteStatic?.copyOf()

    val localStaticPublicKey: ByteArray
        get() = localStatic.publicKey

    /** True when it is this side's turn to send a handshake message. */
    val canWrite: Boolean
        get() = !isComplete && isSender(messageIndex)

    /** True when it is this side's turn to receive a handshake message. */
    val canRead: Boolean
        get() = !isComplete && !isSender(messageIndex)

    init {
        symmetric.mixHash(prologue)
    }

    private fun isSender(index: Int): Boolean = (index % 2 == 0) == (role == NoiseRole.INITIATOR)

    fun writeMessage(payload: ByteArray = EMPTY): ByteArray {
        if (!canWrite) {
            throw ProtocolException(
                "handshake message $messageIndex is not ours to write (role $role, ${if (isComplete) "complete" else "peer's turn"})",
            )
        }
        val out = ByteArrayOutputStream()
        for (token in NoiseXX.PATTERN[messageIndex]) {
            when (token) {
                Token.E -> {
                    if (localEphemeral != null) throw ProtocolException("ephemeral key already used")
                    val ephemeral = X25519.generateKeyPair(random)
                    localEphemeral = ephemeral
                    out.write(ephemeral.publicKey)
                    symmetric.mixHash(ephemeral.publicKey)
                }

                Token.S -> out.write(symmetric.encryptAndHash(localStatic.publicKey))

                Token.EE -> symmetric.mixKey(dh(localEphemeral, remoteEphemeral))

                Token.ES -> symmetric.mixKey(
                    if (role == NoiseRole.INITIATOR) {
                        dh(localEphemeral, remoteStatic)
                    } else {
                        dh(localStatic, remoteEphemeral)
                    },
                )

                Token.SE -> symmetric.mixKey(
                    if (role == NoiseRole.INITIATOR) {
                        dh(localStatic, remoteEphemeral)
                    } else {
                        dh(localEphemeral, remoteStatic)
                    },
                )

                Token.SS -> symmetric.mixKey(dh(localStatic, remoteStatic))
            }
        }
        out.write(symmetric.encryptAndHash(payload))
        val message = out.toByteArray()
        if (message.size > NoiseXX.MAX_HANDSHAKE_MESSAGE) {
            throw ProtocolException("handshake message too large: ${message.size}")
        }
        messageIndex++
        return message
    }

    fun readMessage(message: ByteArray): ByteArray {
        if (!canRead) {
            throw ProtocolException(
                "handshake message $messageIndex is not ours to read (role $role, ${if (isComplete) "complete" else "our turn to send"})",
            )
        }
        if (message.size > NoiseXX.MAX_HANDSHAKE_MESSAGE) {
            throw ProtocolException("handshake message too large: ${message.size}")
        }
        var offset = 0
        for (token in NoiseXX.PATTERN[messageIndex]) {
            when (token) {
                Token.E -> {
                    if (remoteEphemeral != null) throw ProtocolException("peer sent two ephemeral keys")
                    remoteEphemeral = slice(message, offset, NoiseXX.DHLEN)
                    offset += NoiseXX.DHLEN
                    symmetric.mixHash(remoteEphemeral!!)
                }

                Token.S -> {
                    val length = NoiseXX.DHLEN + if (symmetric.hasKey) Primitives.TAG_BYTES else 0
                    val plaintext = symmetric.decryptAndHash(slice(message, offset, length))
                    offset += length
                    if (plaintext.size != NoiseXX.DHLEN) {
                        throw CryptoException("peer static key has wrong length ${plaintext.size}")
                    }
                    if (remoteStatic != null) throw ProtocolException("peer sent two static keys")
                    remoteStatic = plaintext
                }

                Token.EE -> symmetric.mixKey(dh(localEphemeral, remoteEphemeral))

                Token.ES -> symmetric.mixKey(
                    if (role == NoiseRole.INITIATOR) {
                        dh(localEphemeral, remoteStatic)
                    } else {
                        dh(localStatic, remoteEphemeral)
                    },
                )

                Token.SE -> symmetric.mixKey(
                    if (role == NoiseRole.INITIATOR) {
                        dh(localStatic, remoteEphemeral)
                    } else {
                        dh(localEphemeral, remoteStatic)
                    },
                )

                Token.SS -> symmetric.mixKey(dh(localStatic, remoteStatic))
            }
            if (offset > message.size) throw CryptoException("truncated handshake message")
        }
        val payload = symmetric.decryptAndHash(slice(message, offset, message.size - offset))
        messageIndex++
        return payload
    }

    private fun slice(message: ByteArray, offset: Int, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > message.size) {
            throw CryptoException("truncated handshake message")
        }
        return message.copyOfRange(offset, offset + length)
    }

    /** Call once [isComplete]; returns the two-direction transport for the rest of the session. */
    fun split(): NoiseTransport {
        if (!isComplete) throw ProtocolException("handshake is not complete")
        val peer = remoteStatic ?: throw CryptoException("handshake finished without a peer static key")
        val (initiatorToResponder, responderToInitiator) = symmetric.split()
        val sendFirst = role == NoiseRole.INITIATOR
        return NoiseTransport(
            sendCipher = NoiseCipherState(if (sendFirst) initiatorToResponder else responderToInitiator),
            receiveCipher = NoiseCipherState(if (sendFirst) responderToInitiator else initiatorToResponder),
            peerStaticKey = peer.copyOf(),
            handshakeHash = symmetric.handshakeHash.copyOf(),
            role = role,
        )
    }

    /** The `h` value after the handshake, for channel binding. */
    val handshakeHash: ByteArray
        get() = symmetric.handshakeHash.copyOf()

    private fun dh(local: X25519KeyPair?, remote: ByteArray?): ByteArray {
        val keyPair = local ?: throw CryptoException("missing local key for DH")
        val publicKey = remote ?: throw CryptoException("missing remote key for DH")
        return X25519.sharedSecret(keyPair.privateKey, publicKey)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Post-handshake transport: two independent keys (one per direction) with strictly increasing message
 * counters. Both sides must agree on [peerStaticKey]; callers compare it to the paired peer's key.
 */
class NoiseTransport internal constructor(
    private val sendCipher: NoiseCipherState,
    private val receiveCipher: NoiseCipherState,
    val peerStaticKey: ByteArray,
    val handshakeHash: ByteArray,
    val role: NoiseRole,
) {
    /** Messages sealed on this transport. */
    val sentMessages: Long
        @Synchronized get() = sendCipher.counter

    /** Messages opened on this transport. */
    val receivedMessages: Long
        @Synchronized get() = receiveCipher.counter

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        if (plaintext.size > MAX_PLAINTEXT_BYTES) {
            throw ProtocolException("plaintext of ${plaintext.size} bytes exceeds $MAX_PLAINTEXT_BYTES; chunk it")
        }
        return sendCipher.encryptWithAd(EMPTY, plaintext)
    }

    @Synchronized
    fun open(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size > MAX_PLAINTEXT_BYTES + Primitives.TAG_BYTES) {
            throw ProtocolException("ciphertext of ${ciphertext.size} bytes exceeds the transport limit")
        }
        return receiveCipher.decryptWithAd(EMPTY, ciphertext)
    }

    companion object {
        /**
         * Transport messages are capped well below the 16 MiB relay envelope cap even after base64
         * wrapping; larger application payloads (screenshots, big UI trees) go through `Chunker`.
         */
        const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024

        private val EMPTY = ByteArray(0)
    }
}

object NoiseXX {
    const val PATTERN_NAME = "XX"
    const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    const val HASHLEN = 32
    const val DHLEN = 32
    const val MAX_HANDSHAKE_MESSAGE = 65535

    /** Prologue both peers hash before the first message; changing it breaks every old build. */
    const val PROLOGUE_TEXT = "PhoneBridge/1/noise-xx"

    fun prologue(): ByteArray = PROLOGUE_TEXT.toByteArray(Charsets.US_ASCII)

    internal val PATTERN: List<List<Token>> = listOf(
        listOf(Token.E),
        listOf(Token.E, Token.EE, Token.S, Token.ES),
        listOf(Token.S, Token.SE),
    )
}
