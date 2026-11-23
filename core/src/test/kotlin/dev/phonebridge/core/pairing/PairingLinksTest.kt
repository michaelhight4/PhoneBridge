package dev.phonebridge.core.pairing

import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.util.B64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingLinksTest {
    private val device = Identity.generate(PeerRole.DEVICE, "Pixel 9")
    private val secret = ByteArray(32) { (it * 7).toByte() }

    private fun offer(
        deviceIdentity: Identity = device,
        relay: String = "wss://relay.example:8443",
        code: String = "7K4-Q9B",
        secretBytes: ByteArray = secret,
        exp: Long = 1_700_000_600L,
    ): PairingOffer = PairingOffer(
        relay = relay,
        deviceId = deviceIdentity.peerId,
        devicePub = deviceIdentity.publicKeyBase64,
        secret = B64Url.encode(secretBytes),
        code = code,
        exp = exp,
        deviceName = deviceIdentity.name,
    ).also { it.validate() }

    @Test
    fun linkRoundTripKeepsEveryField() {
        val original = offer()
        val link = PairingLinks.link(original)
        assertTrue(link.startsWith("phonebridge://pair?d="))
        assertEquals(original, PairingLinks.parseLink(link))
        assertEquals(original, PairingLinks.parse(link))
    }

    @Test
    fun qrPayloadIsExactlyTheLink() {
        val original = offer()
        assertEquals(PairingLinks.link(original), PairingLinks.qrPayload(original))
    }

    @Test
    fun fileRoundTripKeepsEveryField() {
        val original = offer()
        val contents = PairingLinks.fileContents(original)
        assertTrue(contents.trimStart().startsWith("{"))
        assertEquals(original, PairingLinks.parseFile(contents))
        assertEquals(original, PairingLinks.parse(contents))
        assertTrue(PairingLinks.FILE_EXTENSION == ".pbpair")
    }

    @Test
    fun extraQueryParametersAndPercentEscapesAreTolerated() {
        val original = offer()
        val link = PairingLinks.link(original)
        assertEquals(original, PairingLinks.parseLink("$link&ts=1"))

        // Percent-encode the first byte of the payload: the link must still decode.
        val prefix = link.substringBefore("d=") + "d="
        val payload = link.removePrefix(prefix)
        val percent = "%" + payload[0].code.toString(16).padStart(2, '0')
        assertEquals(original, PairingLinks.parseLink(prefix + percent + payload.substring(1)))
    }

    @Test
    fun aBomOrWhitespaceAroundAFileIsIgnored() {
        val original = offer()
        val contents = "\uFEFF  " + PairingLinks.fileContents(original) + "  \n"
        assertEquals(original, PairingLinks.parseFile(contents))
    }

    @Test
    fun malformedLinksAreRejected() {
        assertFailsWith<ProtocolException> { PairingLinks.parseLink("https://example.com/pair?d=abc") }
        assertFailsWith<ProtocolException> { PairingLinks.parseLink("phonebridge://pair") }
        assertFailsWith<ProtocolException> { PairingLinks.parseLink("phonebridge://pair?d=") }
        assertFailsWith<ProtocolException> { PairingLinks.parseLink("phonebridge://pair?d=!!!") }
        assertFailsWith<ProtocolException> { PairingLinks.parseFile("not json") }
        assertFailsWith<ProtocolException> { PairingLinks.parseFile("{}") }
    }

    @Test
    fun inconsistentOffersAreRejectedBeforeUse() {
        assertFailsWith<ProtocolException>("secret must be 32 bytes") {
            offer(secretBytes = ByteArray(16))
        }
        assertFailsWith<ProtocolException>("device id must match the key") {
            PairingOffer(
                relay = "wss://relay.example",
                deviceId = "aaaaaaaaaaaaaaaaaaaaaaaaaa",
                devicePub = device.publicKeyBase64,
                secret = B64Url.encode(secret),
                code = "7K4-Q9B",
                exp = 1_700_000_600L,
            ).validate()
        }
        assertFailsWith<ProtocolException>("code must carry a valid check symbol") {
            offer(code = "7K4-Q9M")
        }
        assertFailsWith<ProtocolException>("relay must be ws or wss") {
            offer(relay = "http://relay.example")
        }
        assertFailsWith<ProtocolException>("expiry is required") {
            offer(exp = 0)
        }
    }
}
