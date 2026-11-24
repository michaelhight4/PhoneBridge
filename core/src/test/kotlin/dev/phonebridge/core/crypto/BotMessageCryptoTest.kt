package dev.phonebridge.core.crypto

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.identity.PeerIds
import dev.phonebridge.core.util.PbJson
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class BotMessageCryptoTest {
    private class FixedRandom(private val value: Byte) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (value.toInt() + i).toByte()
        }
    }

    private fun peers(): Pair<X25519KeyPair, X25519KeyPair> =
        X25519.generateKeyPair() to X25519.generateKeyPair()

    @Test
    fun `a sealed message opens with the recipient key`() {
        val (alice, bob) = peers()
        val from = PeerIds.peerId(alice.publicKey)
        val to = PeerIds.peerId(bob.publicKey)
        val frame = BotMessageCrypto.seal(
            sender = alice,
            recipientPublicKey = bob.publicKey,
            fromPeerId = from,
            toPeerId = to,
            messageId = "msg-1",
            text = "héllo ✓ 日本語 🚀",
            replyTo = "msg-0",
            random = FixedRandom(7),
        )

        assertEquals(0x01, frame[0].toInt() and 0xFF)
        assertEquals(BotMessageCrypto.VERSION.toByte(), frame[0])
        val plain = BotMessageCrypto.open(
            recipient = bob,
            fromPeerId = from,
            toPeerId = to,
            messageId = "msg-1",
            frame = frame,
            expectedSenderPublicKey = alice.publicKey,
        )
        assertEquals("héllo ✓ 日本語 🚀", plain.text)
        assertEquals("msg-0", plain.replyTo)
        assertEquals(1, plain.v)
    }

    @Test
    fun `reply_to is omitted when there is no parent`() {
        val (alice, bob) = peers()
        val from = PeerIds.peerId(alice.publicKey)
        val to = PeerIds.peerId(bob.publicKey)
        val frame = BotMessageCrypto.seal(alice, bob.publicKey, from, to, "msg-2", "no parent")
        val plain = BotMessageCrypto.open(bob, from, to, "msg-2", frame)
        assertEquals(null, plain.replyTo)
        assertFalse(PbJson.encodeToString(BotMessagePlaintext.serializer(), plain).contains("reply_to"))
    }

    @Test
    fun `each message uses a fresh ephemeral key and nonce`() {
        val (alice, bob) = peers()
        val from = PeerIds.peerId(alice.publicKey)
        val to = PeerIds.peerId(bob.publicKey)
        val first = BotMessageCrypto.seal(alice, bob.publicKey, from, to, "msg-3", "same text")
        val second = BotMessageCrypto.seal(alice, bob.publicKey, from, to, "msg-3", "same text")
        assertNotEquals(first.asList(), second.asList())
        assertEquals("same text", BotMessageCrypto.open(bob, from, to, "msg-3", first).text)
        assertEquals("same text", BotMessageCrypto.open(bob, from, to, "msg-3", second).text)
    }

    @Test
    fun `peer ids message id and ciphertext are all bound into the aad and tag`() {
        val (alice, bob) = peers()
        val from = PeerIds.peerId(alice.publicKey)
        val to = PeerIds.peerId(bob.publicKey)
        val frame = BotMessageCrypto.seal(alice, bob.publicKey, from, to, "msg-4", "do not tamper")

        assertFailsWith<CryptoException> {
            BotMessageCrypto.open(bob, PeerIds.peerId(bob.publicKey), to, "msg-4", frame)
        }
        assertFailsWith<CryptoException> { BotMessageCrypto.open(bob, from, from, "msg-4", frame) }
        assertFailsWith<CryptoException> { BotMessageCrypto.open(bob, from, to, "msg-5", frame) }
        assertFailsWith<CryptoException> {
            BotMessageCrypto.open(bob, from, to, "msg-4", frame, expectedSenderPublicKey = bob.publicKey)
        }

        val tampered = frame.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
        assertFailsWith<CryptoException> { BotMessageCrypto.open(bob, from, to, "msg-4", tampered) }
    }

    @Test
    fun `malformed frames are rejected before decryption`() {
        val (alice, bob) = peers()
        val from = PeerIds.peerId(alice.publicKey)
        val to = PeerIds.peerId(bob.publicKey)
        val frame = BotMessageCrypto.seal(alice, bob.publicKey, from, to, "msg-6", "hello")

        assertFailsWith<CryptoException> { BotMessageCrypto.open(bob, from, to, "msg-6", ByteArray(0)) }
        assertFailsWith<CryptoException> {
            BotMessageCrypto.open(bob, from, to, "msg-6", frame.copyOfRange(0, BotMessageCrypto.HEADER_BYTES))
        }
        val wrongVersion = frame.copyOf()
        wrongVersion[0] = 0x02
        assertFailsWith<ProtocolException> { BotMessageCrypto.open(bob, from, to, "msg-6", wrongVersion) }

        val forgedSender = frame.copyOf()
        val senderStart = 1 + BotMessageCrypto.PUBLIC_KEY_BYTES
        forgedSender[senderStart] = (forgedSender[senderStart].toInt() xor 0x01).toByte()
        assertFailsWith<CryptoException> { BotMessageCrypto.open(bob, from, to, "msg-6", forgedSender) }
    }
}
