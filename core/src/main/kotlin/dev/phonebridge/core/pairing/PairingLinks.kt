package dev.phonebridge.core.pairing

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.PairingException
import dev.phonebridge.core.PairingFailure
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PairRequest
import dev.phonebridge.core.model.PairResult
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.core.util.PbJsonPretty
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * The three ways a pairing offer travels: a `phonebridge://pair?d=` link, a `.pbpair` JSON file and a QR
 * code that simply contains the link.
 */
object PairingLinks {
    const val SCHEME = "phonebridge"
    const val HOST = "pair"
    const val QUERY_PARAM = "d"
    const val LINK_PREFIX = "phonebridge://pair?d="
    const val FILE_EXTENSION = ".pbpair"

    fun link(offer: PairingOffer): String {
        offer.validate()
        val json = PbJson.encodeToString(PairingOffer.serializer(), offer)
        return LINK_PREFIX + B64Url.encode(json.toByteArray(Charsets.UTF_8))
    }

    fun parseLink(text: String): PairingOffer {
        val trimmed = text.trim()
        if (!trimmed.startsWith("$SCHEME:", ignoreCase = true)) {
            throw ProtocolException("not a PhoneBridge pairing link")
        }
        val queryStart = trimmed.indexOf('?')
        if (queryStart < 0) throw ProtocolException("pairing link has no payload")
        val query = trimmed.substring(queryStart + 1)
        val rawValue = query.split('&')
            .firstOrNull { it.substringBefore('=') == QUERY_PARAM }
            ?.substringAfter('=', "")
            .orEmpty()
        if (rawValue.isEmpty()) throw ProtocolException("pairing link has no $QUERY_PARAM= payload")
        val json = try {
            B64Url.decodeText(String(percentDecode(rawValue), Charsets.US_ASCII))
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("pairing link payload is not base64url", e)
        }
        return decodeOffer(json)
    }

    /** What goes into the QR code: exactly the link, nothing else. */
    fun qrPayload(offer: PairingOffer): String = link(offer)

    /** Contents of a `.pbpair` file: the offer JSON, indented so humans can inspect it. */
    fun fileContents(offer: PairingOffer): String {
        offer.validate()
        return PbJsonPretty.encodeToString(PairingOffer.serializer(), offer) + "\n"
    }

    fun parseFile(text: String): PairingOffer = decodeOffer(text)

    /** Accepts either a link or a `.pbpair` file body. */
    fun parse(text: String): PairingOffer {
        val trimmed = text.trim()
        return if (trimmed.startsWith("$SCHEME:", ignoreCase = true)) parseLink(trimmed) else parseFile(trimmed)
    }

    fun decodeOffer(json: String): PairingOffer {
        val cleaned = json.trim().removePrefix("\uFEFF")
        if (cleaned.isEmpty()) throw ProtocolException("empty pairing file")
        val offer = try {
            PbJson.decodeFromString(PairingOffer.serializer(), cleaned)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("malformed pairing offer", e)
        }
        offer.validate()
        return offer
    }

    private fun percentDecode(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            if (bytes[index] == '%'.code.toByte() && index + 2 < bytes.size) {
                val hex = String(byteArrayOf(bytes[index + 1], bytes[index + 2]), Charsets.US_ASCII)
                val value = hex.toIntOrNull(16) ?: throw ProtocolException("bad percent-encoding in pairing link")
                out.write(value)
                index += 3
            } else {
                out.write(bytes[index].toInt() and 0xFF)
                index++
            }
        }
        return out.toByteArray()
    }
}

/** The bot's sealed `pair_request` plus the plaintext copy it built it from. */
class SealedPairRequest(
    val request: PairRequest,
    val sealed: ByteArray,
) {
    fun sealedBase64(): String = B64Url.encode(sealed)

    fun requestJson(): String = PbJson.encodeToString(PairRequest.serializer(), request)
}

/**
 * The bot side of pairing: build the sealed request from an offer, then open the device's answer.
 * The device side is [PairingManager].
 */
object PairingClient {
    fun createRequest(
        offer: PairingOffer,
        botIdentity: Identity,
        requestedLevel: PermissionLevel = PermissionLevel.CONTROL,
        botName: String? = botIdentity.name,
        random: SecureRandom = dev.phonebridge.core.crypto.Primitives.defaultRandom(),
    ): SealedPairRequest {
        offer.validate()
        val request = PairRequest(
            botName = botName?.takeIf { it.isNotBlank() } ?: "bot",
            botPub = botIdentity.publicKeyBase64,
            requestedLevel = requestedLevel,
            botId = botIdentity.peerId,
        )
        val json = PbJson.encodeToString(PairRequest.serializer(), request).toByteArray(Charsets.UTF_8)
        val sealed = PairingCrypto.seal(
            secret = offer.secretBytes,
            associatedData = PairingCrypto.requestAad(offer.devicePublicKey),
            plaintext = json,
            random = random,
        )
        return SealedPairRequest(request, sealed)
    }

    /** Opens `pair_result`. Throws [PairingException] with `BAD_SECRET` when it was not for this offer. */
    fun openResult(offer: PairingOffer, sealed: ByteArray): PairResult {
        offer.validate()
        val plaintext = try {
            PairingCrypto.open(offer.secretBytes, PairingCrypto.resultAad(offer.devicePublicKey), sealed)
        } catch (e: CryptoException) {
            throw PairingException(PairingFailure.BAD_SECRET, "pair_result did not decrypt", e)
        }
        return try {
            PbJson.decodeFromString(PairResult.serializer(), String(plaintext, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw PairingException(PairingFailure.BAD_REQUEST, "malformed pair_result", e)
        }
    }
}
