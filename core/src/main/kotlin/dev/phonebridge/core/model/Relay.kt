package dev.phonebridge.core.model

import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.Version
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Who is on the other end of an envelope. */
@Serializable
enum class PeerRole(val wire: String) {
    @SerialName("device")
    DEVICE("device"),

    @SerialName("bot")
    BOT("bot"),
    ;

    companion object {
        fun parse(text: String): PeerRole? = entries.firstOrNull { it.wire == text.trim().lowercase() }

        fun parseOrThrow(text: String): PeerRole =
            parse(text) ?: throw ProtocolException("unknown role: $text")
    }
}

/** Envelope type names, kept as constants so relays can route without knowing the app layer. */
object RelayType {
    const val HELLO = "hello"
    const val ROUTE = "route"
    const val PING = "ping"
    const val PONG = "pong"
    const val ERROR = "error"
    const val RELAY_MOVED = "relay_moved"

    /** Bot -> relay: "what offer is behind this short code?". */
    const val PAIR_LOOKUP = "pair_lookup"

    /** Relay -> bot: the offer JSON, base64url in `payload`. */
    const val PAIR_OFFER = "pair_offer"

    val ALL: Set<String> = setOf(
        HELLO, ROUTE, PING, PONG, ERROR, RELAY_MOVED, PAIR_LOOKUP, PAIR_OFFER,
    )
}

/**
 * The plaintext frame the relay sees and routes. It carries no application data: `payload` is a
 * base64url end-to-end ciphertext ([dev.phonebridge.core.crypto.Frame]).
 *
 * All fields beyond `v`/`type` are optional because each message type uses a different subset; the
 * shapes are listed in docs/PROTOCOL.md.
 */
@Serializable
data class RelayEnvelope(
    val v: Int = Version.PROTOCOL,
    val type: String,
    val from: String? = null,
    val to: String? = null,
    val id: String? = null,
    val payload: String? = null,
    // hello (peer -> relay, and the relay's challenge)
    val pub: String? = null,
    val sig: String? = null,
    val role: PeerRole? = null,
    val nonce: String? = null,
    @SerialName("relay_pub")
    val relayPub: String? = null,
    // error
    val code: String? = null,
    val message: String? = null,
    val pos: Int? = null,
    // relay_moved
    @SerialName("new_url")
    val newUrl: String? = null,
    val token: String? = null,
    val expires: Long? = null,
) {
    /** True for a type this protocol version understands; unknown types must be ignored, not fatal. */
    val isKnownType: Boolean
        get() = type in RelayType.ALL

    /** Throws when the envelope uses a type this version cannot handle. */
    fun requireKnownType() {
        if (!isKnownType) throw ProtocolException("unknown envelope type: $type")
    }

    fun payloadBytes(): ByteArray =
        payload?.let { B64Url.decode(it) } ?: throw ProtocolException("envelope has no payload")

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** A peer announcing itself to the relay, answering the relay's challenge. */
        fun hello(
            from: String,
            publicKeyB64: String,
            role: PeerRole,
            proofB64: String? = null,
            nonceB64: String? = null,
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.HELLO,
            from = from,
            pub = publicKeyB64,
            role = role,
            sig = proofB64,
            nonce = nonceB64,
        )

        /** The relay's challenge: a fresh nonce plus a fresh X25519 public key for the proof. */
        fun helloChallenge(nonceB64: String, relayPublicKeyB64: String): RelayEnvelope = RelayEnvelope(
            type = RelayType.HELLO,
            nonce = nonceB64,
            relayPub = relayPublicKeyB64,
        )

        /** A routed end-to-end frame. */
        fun route(from: String, to: String, payload: ByteArray, id: String = newId()): RelayEnvelope =
            RelayEnvelope(
                type = RelayType.ROUTE,
                from = from,
                to = to,
                id = id,
                payload = B64Url.encode(payload),
            )

        fun ping(from: String, id: String = newId()): RelayEnvelope =
            RelayEnvelope(type = RelayType.PING, from = from, id = id)

        fun pong(from: String, id: String): RelayEnvelope =
            RelayEnvelope(type = RelayType.PONG, from = from, id = id)

        fun error(code: String, message: String? = null, pos: Int? = null, id: String? = null): RelayEnvelope =
            RelayEnvelope(type = RelayType.ERROR, id = id, code = code, message = message, pos = pos)

        fun relayMoved(newUrl: String, token: String? = null, expires: Long? = null): RelayEnvelope =
            RelayEnvelope(type = RelayType.RELAY_MOVED, newUrl = newUrl, token = token, expires = expires)

        /** Bot -> relay: ask for the offer behind a short code (`7K4-Q9B`). */
        fun pairLookup(from: String, code: String, id: String = newId()): RelayEnvelope =
            RelayEnvelope(type = RelayType.PAIR_LOOKUP, from = from, code = code, id = id)

        /** Relay -> bot: the offer JSON (bytes of the same JSON a `.pbpair` file holds). */
        fun pairOffer(offerJson: ByteArray, id: String? = null): RelayEnvelope =
            RelayEnvelope(type = RelayType.PAIR_OFFER, id = id, payload = B64Url.encode(offerJson))
    }
}

/** Envelope JSON codec, including the 16 MiB relay cap. */
object EnvelopeCodec {
    /** Relays MUST drop envelopes larger than this. */
    const val MAX_BYTES = 16 * 1024 * 1024

    fun encode(envelope: RelayEnvelope): String {
        val json = PbJson.encodeToString(RelayEnvelope.serializer(), envelope)
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw ProtocolException("envelope exceeds $MAX_BYTES bytes, chunk the payload")
        }
        return json
    }

    fun decode(json: String): RelayEnvelope {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw ProtocolException("envelope exceeds $MAX_BYTES bytes")
        }
        return try {
            PbJson.decodeFromString(RelayEnvelope.serializer(), json)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("malformed envelope", e)
        }
    }
}
