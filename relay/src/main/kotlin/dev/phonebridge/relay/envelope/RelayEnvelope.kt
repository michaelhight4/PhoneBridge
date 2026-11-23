package dev.phonebridge.relay.envelope

import com.google.gson.JsonObject
import dev.phonebridge.relay.json.Json
import java.util.UUID

/**
 * Relay level envelope, per docs/PROTOCOL.md.
 *
 * :core (task 01) will own the real envelope type. Until it lands, the relay keeps this minimal
 * definition behind [RelayEnvelopeCodec] so swapping in the :core types is a one-line change. The
 * relay itself forwards the *raw* received text and never re-encodes it, so the encrypted payload is
 * byte for byte untouched.
 */
data class RelayEnvelope(
    val type: String,
    val from: String,
    val to: String? = null,
    val id: String? = null,
    val payload: String? = null,
    val v: Int = PROTOCOL_VERSION
) {
    fun toJson(): JsonObject = Json.obj(
        "v" to v,
        "type" to type,
        "from" to from,
        "to" to to,
        "id" to id,
        "payload" to payload
    )

    companion object {
        const val PROTOCOL_VERSION = 1

        fun newId(): String = UUID.randomUUID().toString()

        fun route(from: String, to: String, payload: String, id: String = newId()): RelayEnvelope =
            RelayEnvelope(type = "route", from = from, to = to, id = id, payload = payload)
    }
}

/** The seam: replace with :core's envelope codec once task 01 lands. */
interface RelayEnvelopeCodec {
    fun encode(envelope: RelayEnvelope): String

    /** Returns null when [text] is not a relay envelope. Never interprets `payload`. */
    fun decode(text: String): RelayEnvelope?
}

object GsonRelayEnvelopeCodec : RelayEnvelopeCodec {
    override fun encode(envelope: RelayEnvelope): String = envelope.toJson().toString()

    override fun decode(text: String): RelayEnvelope? {
        val obj = Json.parseObject(text) ?: return null
        val type = Json.string(obj, "type") ?: return null
        val from = Json.string(obj, "from") ?: return null
        val v = Json.long(obj, "v")?.toInt() ?: RelayEnvelope.PROTOCOL_VERSION
        return RelayEnvelope(
            type = type,
            from = from,
            to = Json.string(obj, "to"),
            id = Json.string(obj, "id"),
            payload = Json.string(obj, "payload"),
            v = v
        )
    }
}
