package dev.phonebridge.core.crypto

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoiseSessionTest {
    private val alice = Identity.generate(PeerRole.BOT, "alice")
    private val bob = Identity.generate(PeerRole.DEVICE, "bob")

    private fun handshake(
        initiatorPrologue: ByteArray = NoiseXX.prologue(),
        responderPrologue: ByteArray = NoiseXX.prologue(),
    ): Pair<E2ESession, E2ESession> {
        val opener = E2ESession(NoiseRole.INITIATOR, alice.keys, initiatorPrologue)
        val responder = E2ESession(NoiseRole.RESPONDER, bob.keys, responderPrologue)
        responder.readHandshakeFrame(opener.writeHandshakeFrame())
        opener.readHandshakeFrame(responder.writeHandshakeFrame())
        responder.readHandshakeFrame(opener.writeHandshakeFrame())
        return opener to responder
    }

    @Test
    fun xxHandshakeSizesMatchThePattern() {
        val opener = E2ESession(NoiseRole.INITIATOR, alice.keys)
        val responder = E2ESession(NoiseRole.RESPONDER, bob.keys)

        val first = opener.writeHandshakeFrame()
        // e (32) + plaintext payload (0) + frame tag (1)
        assertEquals(33, first.size)
        responder.readHandshakeFrame(first)

        val second = responder.writeHandshakeFrame()
        // e (32) + encrypted s (32+16) + encrypted payload (0+16) + frame tag (1)
        assertEquals(97, second.size)
        opener.readHandshakeFrame(second)

        val third = opener.writeHandshakeFrame()
        // encrypted s (32+16) + encrypted payload (0+16) + frame tag (1)
        assertEquals(65, third.size)
        responder.readHandshakeFrame(third)

        assertTrue(opener.isEstablished)
        assertTrue(responder.isEstablished)
    }

    @Test
    fun bothSidesLearnTheOtherStaticKey() {
        val (opener, responder) = handshake()
        assertContentEquals(bob.publicKey, opener.channel!!.peerStaticKey)
        assertContentEquals(alice.publicKey, responder.channel!!.peerStaticKey)
        assertEquals(bob.peerId, opener.channel!!.peerId)
        assertEquals(alice.fingerprint, responder.channel!!.peerFingerprint)
        assertTrue(opener.channel!!.verifyPeer(bob.publicKey))
        assertFalse(opener.channel!!.verifyPeer(alice.publicKey))
        opener.channel!!.requirePeer(bob.publicKey)
        assertFailsWith<CryptoException> { opener.channel!!.requirePeer(alice.publicKey) }
    }

    @Test
    fun transportEncryptsBothDirections() {
        val (opener, responder) = handshake()
        val botToDevice = opener.sealText("""{"cmd":"status"}""")
        assertEquals("""{"cmd":"status"}""", responder.openText(botToDevice))
        val deviceToBot = responder.sealText("""{"ok":true}""")
        assertEquals("""{"ok":true}""", opener.openText(deviceToBot))

        assertEquals(1, opener.channel!!.sentMessages)
        assertEquals(1, opener.channel!!.receivedMessages)
        assertEquals(1, responder.channel!!.sentMessages)
        assertEquals(1, responder.channel!!.receivedMessages)
    }

    @Test
    fun countersAreIndependentPerDirection() {
        val (opener, responder) = handshake()
        repeat(4) { responder.openText(opener.sealText("to-device-$it")) }
        repeat(2) { opener.openText(responder.sealText("to-bot-$it")) }
        assertEquals(4, opener.channel!!.sentMessages)
        assertEquals(2, opener.channel!!.receivedMessages)
        assertEquals(2, responder.channel!!.sentMessages)
        assertEquals(4, responder.channel!!.receivedMessages)
    }

    @Test
    fun replayedCiphertextIsRejected() {
        val (opener, responder) = handshake()
        val frame = opener.sealText("once")
        assertEquals("once", responder.openText(frame))
        assertFailsWith<CryptoException>("the same ciphertext must not open twice") {
            responder.openText(frame)
        }
        // The failed replay must not have consumed the next counter value.
        assertEquals("next", responder.openText(opener.sealText("next")))
    }

    @Test
    fun outOfOrderMessagesFailWithoutAdvancingTheCounter() {
        val (opener, responder) = handshake()
        val first = opener.sealText("first")
        val second = opener.sealText("second")
        assertFailsWith<CryptoException> { responder.openText(second) }
        assertEquals("first", responder.openText(first))
        assertEquals("second", responder.openText(second))
    }

    @Test
    fun tamperedTransportFramesAreRejected() {
        val (opener, responder) = handshake()
        val frame = opener.sealText("payload")
        val tampered = frame.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x40).toByte()
        assertFailsWith<CryptoException> { responder.open(tampered) }

        // Truncating must fail as well, not return partial plaintext.
        assertFailsWith<CryptoException> { responder.open(frame.copyOf(frame.size - 4)) }
    }

    @Test
    fun frameTypesCannotBeConfused() {
        val (opener, responder) = handshake()
        val transport = opener.sealText("x")
        assertFailsWith<CryptoException> { responder.readHandshakeFrame(transport) }
        assertFailsWith<CryptoException> { responder.open(byteArrayOf(Frame.HANDSHAKE)) }
        assertFailsWith<CryptoException> { responder.open(ByteArray(0)) }
    }

    @Test
    fun handshakeMessagesCannotBeSentOutOfTurn() {
        val opener = E2ESession(NoiseRole.INITIATOR, alice.keys)
        val responder = E2ESession(NoiseRole.RESPONDER, bob.keys)
        assertFailsWith<ProtocolException> { responder.writeHandshakeFrame() }
        assertFailsWith<CryptoException> { opener.readHandshakeFrame(ByteArray(33)) }

        val first = opener.writeHandshakeFrame()
        assertFailsWith<ProtocolException> { opener.writeHandshakeFrame() }
        responder.readHandshakeFrame(first)
        assertFailsWith<ProtocolException> { responder.readHandshakeFrame(first) }
    }

    @Test
    fun transportCannotBeUsedBeforeTheHandshakeFinishes() {
        val opener = E2ESession(NoiseRole.INITIATOR, alice.keys)
        assertNull(opener.channel)
        assertFailsWith<CryptoException> { opener.sealText("too early") }
        assertFailsWith<CryptoException> { opener.openText(ByteArray(5)) }
    }

    @Test
    fun aCorruptedHandshakeMessageFails() {
        val opener = E2ESession(NoiseRole.INITIATOR, alice.keys)
        val responder = E2ESession(NoiseRole.RESPONDER, bob.keys)
        responder.readHandshakeFrame(opener.writeHandshakeFrame())
        val second = responder.writeHandshakeFrame()
        val tampered = second.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<CryptoException> { opener.readHandshakeFrame(tampered) }
        assertFalse(opener.isEstablished)
    }

    @Test
    fun mismatchedProloguesFailTheHandshake() {
        val opener = E2ESession(NoiseRole.INITIATOR, alice.keys, "PhoneBridge/1/other".toByteArray())
        val responder = E2ESession(NoiseRole.RESPONDER, bob.keys)
        responder.readHandshakeFrame(opener.writeHandshakeFrame())
        assertFailsWith<CryptoException> {
            opener.readHandshakeFrame(responder.writeHandshakeFrame())
        }
    }

    @Test
    fun aManInTheMiddleCannotImpersonateThePairedPeer() {
        val opener = E2ESession(NoiseRole.INITIATOR, alice.keys)
        val mallory = Identity.generate(PeerRole.DEVICE, "mallory")
        val responder = E2ESession(NoiseRole.RESPONDER, mallory.keys)
        responder.readHandshakeFrame(opener.writeHandshakeFrame())
        opener.readHandshakeFrame(responder.writeHandshakeFrame())
        responder.readHandshakeFrame(opener.writeHandshakeFrame())

        val channel = opener.channel!!
        // The bot pinned bob's key when pairing, so the session must be refused.
        assertFalse(channel.verifyPeer(bob.publicKey))
        assertTrue(channel.verifyPeer(mallory.publicKey))
        assertFailsWith<CryptoException> { channel.requirePeer(bob.publicKey) }
    }

    @Test
    fun oversizedTransportPayloadsAreRefused() {
        val (opener, _) = handshake()
        val tooBig = ByteArray(NoiseTransport.MAX_PLAINTEXT_BYTES + 1)
        assertFailsWith<ProtocolException> { opener.seal(tooBig) }
    }
}
