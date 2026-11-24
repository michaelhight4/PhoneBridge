package dev.phonebridge.core.crypto

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.identity.PeerIds
import dev.phonebridge.core.util.PbJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * The plaintext carried inside a store-and-forward bot message. `reply_to` is left off the wire when
 * there is no parent message; PbJson's `explicitNulls = false` handles that for us.
 */
@Serializable
data class BotMessagePlaintext(
    val v: Int = 1,
    val text: String,
    @SerialName("reply_to")
    val replyTo: String? = null,
)

/**
 * End-to-end encryption for bot-to-bot messages that may sit in a relay mailbox for days.
 *
 * Wire format:
 * ```
 * 0x01 | ephemeralPub (32) | senderPub (32) | nonce (12) | ChaCha20-Poly1305 ciphertext+tag
 * ```
 *
 * The symmetric key mixes the sender's static DH with the recipient's key (authenticating the
 * sender) and a fresh ephemeral DH (giving each ciphertext forward secrecy). The peer ids and message
 * id are bound into the AEAD associated data, so a message cannot be replayed under another id or
 * routed to another pair of bots.
 */
object BotMessageCrypto {
    const val VERSION: Int = 0x01
    const val KEY_BYTES = 32
    const val PUBLIC_KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val HEADER_BYTES = 1 + PUBLIC_KEY_BYTES + PUBLIC_KEY_BYTES + NONCE_BYTES

    private val HKDF_SALT = "PhoneBridge/1/bot-msg".toByteArray(Charsets.US_ASCII)
    private val HKDF_INFO = "aead-key".toByteArray(Charsets.US_ASCII)
    private val AAD_PREFIX = "PhoneBridge/1/bot-msg|v1|"

    fun seal(
        sender: X25519KeyPair,
        recipientPublicKey: ByteArray,
        fromPeerId: String,
        toPeerId: String,
        messageId: String,
        text: String,
        replyTo: String? = null,
        random: SecureRandom = Primitives.defaultRandom(),
    ): ByteArray {
        if (recipientPublicKey.size != X25519.DHLEN) {
            throw CryptoException("recipient public key must be ${X25519.DHLEN} bytes")
        }
        val ephemeral = X25519.generateKeyPair(random)
        val nonce = Primitives.randomBytes(NONCE_BYTES, random)
        val key = deriveKey(
            dh1 = X25519.ecdh(sender.privateKey, recipientPublicKey),
            dh2 = X25519.ecdh(ephemeral.privateKey, recipientPublicKey),
        )
        val plaintext = PbJson.encodeToString(
            BotMessagePlaintext.serializer(),
            BotMessagePlaintext(text = text, replyTo = replyTo),
        ).toByteArray(Charsets.UTF_8)
        val ciphertext = Primitives.aeadSeal(key, nonce, aad(fromPeerId, toPeerId, messageId), plaintext)

        val frame = ByteArray(HEADER_BYTES + ciphertext.size)
        frame[0] = VERSION.toByte()
        System.arraycopy(ephemeral.publicKey, 0, frame, 1, PUBLIC_KEY_BYTES)
        System.arraycopy(sender.publicKey, 0, frame, 1 + PUBLIC_KEY_BYTES, PUBLIC_KEY_BYTES)
        System.arraycopy(nonce, 0, frame, 1 + PUBLIC_KEY_BYTES + PUBLIC_KEY_BYTES, NONCE_BYTES)
        System.arraycopy(ciphertext, 0, frame, HEADER_BYTES, ciphertext.size)
        return frame
    }

    fun open(
        recipient: X25519KeyPair,
        fromPeerId: String,
        toPeerId: String,
        messageId: String,
        frame: ByteArray,
        expectedSenderPublicKey: ByteArray? = null,
    ): BotMessagePlaintext {
        if (frame.size <= HEADER_BYTES + Primitives.TAG_BYTES) {
            throw CryptoException("bot message frame is too short")
        }
        if (frame[0].toInt() and 0xFF != VERSION) {
            throw ProtocolException("unsupported bot message version: ${frame[0].toInt() and 0xFF}")
        }

        val ephemeralPub = frame.copyOfRange(1, 1 + PUBLIC_KEY_BYTES)
        val senderPub = frame.copyOfRange(1 + PUBLIC_KEY_BYTES, 1 + PUBLIC_KEY_BYTES + PUBLIC_KEY_BYTES)
        val nonce = frame.copyOfRange(
            1 + PUBLIC_KEY_BYTES + PUBLIC_KEY_BYTES,
            HEADER_BYTES,
        )
        if (PeerIds.peerId(senderPub) != fromPeerId) {
            throw CryptoException("bot message sender does not match from_peer_id")
        }
        if (expectedSenderPublicKey != null &&
            !Primitives.constantTimeEquals(expectedSenderPublicKey, senderPub)
        ) {
            throw CryptoException("bot message sender key is not the expected key")
        }

        val key = deriveKey(
            dh1 = X25519.ecdh(recipient.privateKey, senderPub),
            dh2 = X25519.ecdh(recipient.privateKey, ephemeralPub),
        )
        val plaintext = Primitives.aeadOpen(
            key,
            nonce,
            aad(fromPeerId, toPeerId, messageId),
            frame.copyOfRange(HEADER_BYTES, frame.size),
        )
        val decoded = try {
            PbJson.decodeFromString(BotMessagePlaintext.serializer(), String(plaintext, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("malformed bot message plaintext", e)
        }
        if (decoded.v != 1) throw ProtocolException("unsupported bot message plaintext version: ${decoded.v}")
        return decoded
    }

    private fun deriveKey(dh1: ByteArray, dh2: ByteArray): ByteArray =
        Primitives.hkdf(dh1 + dh2, HKDF_SALT, HKDF_INFO, KEY_BYTES)

    private fun aad(fromPeerId: String, toPeerId: String, messageId: String): ByteArray =
        "$AAD_PREFIX$fromPeerId|$toPeerId|$messageId".toByteArray(Charsets.UTF_8)
}
