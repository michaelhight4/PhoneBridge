package dev.phonebridge.core.model

import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.Version
import dev.phonebridge.core.crypto.HelloAuth
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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

    /** Relay -> peer: the authentication challenge (nonce + fresh ephemeral key). */
    const val CHALLENGE = "challenge"

    /** Relay -> peer: authentication accepted, with limits. */
    const val HELLO_OK = "hello_ok"

    /** Device -> relay: store a pairing offer under its short code. */
    const val PAIR_STORE = "pair_store"

    /** Relay -> device: the offer is stored until `expires`. */
    const val PAIR_STORED = "pair_stored"

    /** Bot -> relay: "what offer is behind this short code?". */
    const val PAIR_LOOKUP = "pair_lookup"

    /** Relay -> bot: the offer that was stored, released once. */
    const val PAIR_OFFER = "pair_offer"

    /** Device -> relay: publish the paired bots that may use bot-to-bot messaging. */
    const val PEERS_SYNC = "peers_sync"

    /** Relay -> device: the peer list was stored. */
    const val PEERS_SYNCED = "peers_synced"

    /** Bot -> relay: ask which other paired bots it may message. */
    const val PEERS_LIST = "peers_list"

    /** Relay -> bot: the bots this bot may message. */
    const val PEERS_RESULT = "peers_result"

    /** Bot -> relay: store an end-to-end encrypted message in the recipient's mailbox. */
    const val MSG_SEND = "msg_send"

    /** Relay -> bot: the message was stored. */
    const val MSG_SENT = "msg_sent"

    /** Bot -> relay: read (and optionally consume) the bot's mailbox. */
    const val MSG_INBOX = "msg_inbox"

    /** Relay -> bot: the messages in the bot's mailbox. */
    const val MSG_LIST = "msg_list"

    /** Relay -> bot: a newly arrived message, pushed while the bot is online. */
    const val MSG_DELIVER = "msg_deliver"

    /** Bot -> relay: acknowledge delivered message ids. */
    const val MSG_ACK = "msg_ack"

    /** Relay -> bot: the acknowledgement was applied. */
    const val MSG_ACKED = "msg_acked"

    val ALL: Set<String> = setOf(
        HELLO, ROUTE, PING, PONG, ERROR, RELAY_MOVED, CHALLENGE, HELLO_OK, PAIR_STORE, PAIR_STORED, PAIR_LOOKUP, PAIR_OFFER,
        PEERS_SYNC, PEERS_SYNCED, PEERS_LIST, PEERS_RESULT, MSG_SEND, MSG_SENT, MSG_INBOX, MSG_LIST, MSG_DELIVER, MSG_ACK,
        MSG_ACKED,
    )
}

/** One paired bot as published by a device, for relay-side bot-to-bot ACLs. */
@Serializable
data class RelayPeerEntry(
    @SerialName("peer_id")
    val peerId: String,
    val name: String,
    @SerialName("public_key")
    val publicKey: String,
    val fingerprint: String? = null,
    @SerialName("device_id")
    val deviceId: String? = null,
    @SerialName("device_name")
    val deviceName: String? = null,
    val enabled: Boolean = true,
    @SerialName("can_message")
    val canMessage: Boolean = true,
    val online: Boolean? = null,
)

/** One stored bot message. [payload] is a base64url [dev.phonebridge.core.crypto.BotMessageCrypto] frame. */
@Serializable
data class RelayBotMessage(
    val id: String,
    val from: String,
    @SerialName("from_name")
    val fromName: String? = null,
    val to: String,
    @SerialName("device_id")
    val deviceId: String? = null,
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("expires_at")
    val expiresAt: Long? = null,
    val payload: String,
)

/** The rate/size limits a relay reports in `hello_ok`. */
@Serializable
data class RelayLimits(
    @SerialName("max_envelope_bytes")
    val maxEnvelopeBytes: Long = EnvelopeCodec.MAX_BYTES.toLong(),
    @SerialName("rate_messages")
    val rateMessages: Int? = null,
    @SerialName("rate_window_ms")
    val rateWindowMs: Int? = null,
)

/**
 * The plaintext frame the relay sees and routes. It carries no application data: `payload` is a
 * base64url end-to-end frame ([dev.phonebridge.core.crypto.Frame]) for `route`.
 *
 * All fields beyond `v`/`type` are optional because each message type uses a different subset; the
 * shapes are listed in docs/PROTOCOL.md and relay/PROTOCOL-RELAY.md.
 */
@Serializable
data class RelayEnvelope(
    val v: Int = Version.PROTOCOL,
    val type: String,
    val from: String? = null,
    val to: String? = null,
    val id: String? = null,
    /** base64url end-to-end frame for `route`. */
    val payload: String? = null,
    // hello / challenge / hello_ok
    val pub: String? = null,
    val sig: String? = null,
    val role: PeerRole? = null,
    val nonce: String? = null,
    @SerialName("relay_pub")
    val relayPub: String? = null,
    @SerialName("relay_id")
    val relayId: String? = null,
    /** Challenge: which key-ownership proof the relay expects (`dh-hmac-sha256`). */
    val proof: String? = null,
    /** Challenge / relay_moved / pair messages: unix seconds. */
    val expires: Long? = null,
    @SerialName("move_token")
    val moveToken: String? = null,
    /** hello_ok: the authenticated peer id. */
    val peer: String? = null,
    @SerialName("server_time")
    val serverTime: Long? = null,
    val limits: RelayLimits? = null,
    // error
    val error: ErrorBody? = null,
    // short-code mailbox
    val code: String? = null,
    val offer: JsonObject? = null,
    // relay_moved
    @SerialName("new_url")
    val newUrl: String? = null,
    val token: String? = null,
    // bot-to-bot messaging
    @SerialName("device_id")
    val deviceId: String? = null,
    @SerialName("device_name")
    val deviceName: String? = null,
    val peers: List<RelayPeerEntry>? = null,
    val count: Int? = null,
    val limit: Int? = null,
    val consume: Boolean? = null,
    val ids: List<String>? = null,
    @SerialName("ack_ids")
    val ackIds: List<String>? = null,
    @SerialName("created_at")
    val createdAt: Long? = null,
    val message: RelayBotMessage? = null,
    val messages: List<RelayBotMessage>? = null,
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

    /** Decodes the `offer` object carried by `pair_offer`/`pair_store`. */
    fun offerAsPairingOffer(): PairingOffer {
        val element = offer ?: throw ProtocolException("envelope has no offer")
        return try {
            PbJson.decodeFromJsonElement(PairingOffer.serializer(), element)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("malformed pairing offer in envelope", e)
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** Relay -> peer: the authentication challenge. */
        fun challenge(
            nonceB64: String,
            relayPublicKeyB64: String,
            relayId: String,
            expires: Long? = null,
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.CHALLENGE,
            nonce = nonceB64,
            relayPub = relayPublicKeyB64,
            relayId = relayId,
            proof = HelloAuth.PROOF_METHOD,
            expires = expires,
        )

        /** Peer -> relay: announce the key, answer the challenge. */
        fun hello(
            from: String,
            publicKeyB64: String,
            role: PeerRole,
            proofB64: String,
            nonceB64: String,
            moveToken: String? = null,
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.HELLO,
            from = from,
            pub = publicKeyB64,
            role = role,
            sig = proofB64,
            nonce = nonceB64,
            moveToken = moveToken,
        )

        /** Relay -> peer: authentication accepted. */
        fun helloOk(
            to: String,
            relayId: String,
            peer: String = to,
            serverTime: Long,
            limits: RelayLimits = RelayLimits(),
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.HELLO_OK,
            to = to,
            relayId = relayId,
            peer = peer,
            serverTime = serverTime,
            limits = limits,
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

        fun pong(to: String, id: String): RelayEnvelope =
            RelayEnvelope(type = RelayType.PONG, to = to, id = id)

        /** Relay -> peer error, with the offending envelope's id echoed when there is one. */
        fun error(code: String, message: String? = null, to: String? = null, id: String? = null): RelayEnvelope =
            RelayEnvelope(type = RelayType.ERROR, to = to, id = id, error = ErrorBody.of(code, message))

        fun relayMoved(newUrl: String, token: String? = null, expires: Long? = null): RelayEnvelope =
            RelayEnvelope(type = RelayType.RELAY_MOVED, newUrl = newUrl, token = token, expires = expires)

        /** Device -> relay: hold an offer under its short code for at most ten minutes. */
        fun pairStore(from: String, code: String, offer: JsonObject, id: String = newId()): RelayEnvelope =
            RelayEnvelope(type = RelayType.PAIR_STORE, from = from, code = code, offer = offer, id = id)

        /** Relay -> device: the offer is now in the mailbox. */
        fun pairStored(to: String, code: String, expires: Long, id: String? = null): RelayEnvelope =
            RelayEnvelope(type = RelayType.PAIR_STORED, to = to, code = code, expires = expires, id = id)

        /** Bot -> relay: ask for the offer behind a short code (`7K4-Q9B`). */
        fun pairLookup(from: String, code: String, id: String = newId()): RelayEnvelope =
            RelayEnvelope(type = RelayType.PAIR_LOOKUP, from = from, code = code, id = id)

        /** Relay -> bot: the stored offer, released once. */
        fun pairOffer(
            to: String,
            code: String,
            offer: JsonObject,
            expires: Long,
            id: String? = null,
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.PAIR_OFFER,
            to = to,
            code = code,
            offer = offer,
            expires = expires,
            id = id,
        )

        fun peersSync(
            from: String,
            peers: List<RelayPeerEntry>,
            deviceName: String? = null,
            deviceId: String? = null,
            id: String = newId(),
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.PEERS_SYNC,
            from = from,
            deviceId = deviceId,
            deviceName = deviceName,
            peers = peers,
            id = id,
        )

        fun peersSynced(to: String, count: Int, id: String = newId()): RelayEnvelope = RelayEnvelope(
            type = RelayType.PEERS_SYNCED,
            to = to,
            count = count,
            id = id,
        )

        fun peersList(
            from: String,
            deviceId: String? = null,
            limit: Int? = null,
            id: String = newId(),
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.PEERS_LIST,
            from = from,
            deviceId = deviceId,
            limit = limit,
            id = id,
        )

        fun peersResult(
            to: String,
            peers: List<RelayPeerEntry>,
            deviceId: String? = null,
            id: String? = null,
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.PEERS_RESULT,
            to = to,
            deviceId = deviceId,
            peers = peers,
            count = peers.size,
            id = id,
        )

        fun msgSend(
            from: String,
            message: RelayBotMessage,
            deviceId: String? = null,
            id: String = newId(),
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.MSG_SEND,
            from = from,
            deviceId = deviceId,
            message = message,
            id = id,
        )

        fun msgSent(to: String, message: RelayBotMessage, id: String = newId()): RelayEnvelope = RelayEnvelope(
            type = RelayType.MSG_SENT,
            to = to,
            message = message,
            id = id,
        )

        fun msgInbox(
            from: String,
            limit: Int? = null,
            consume: Boolean? = null,
            ackIds: List<String>? = null,
            deviceId: String? = null,
            id: String = newId(),
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.MSG_INBOX,
            from = from,
            deviceId = deviceId,
            limit = limit,
            consume = consume,
            ackIds = ackIds,
            id = id,
        )

        fun msgList(
            to: String,
            messages: List<RelayBotMessage>,
            count: Int = messages.size,
            id: String = newId(),
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.MSG_LIST,
            to = to,
            messages = messages,
            count = count,
            id = id,
        )

        fun msgDeliver(to: String, message: RelayBotMessage, id: String = newId()): RelayEnvelope = RelayEnvelope(
            type = RelayType.MSG_DELIVER,
            to = to,
            message = message,
            id = id,
        )

        fun msgAck(
            from: String,
            ids: List<String>,
            deviceId: String? = null,
            id: String = newId(),
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.MSG_ACK,
            from = from,
            deviceId = deviceId,
            ids = ids,
            id = id,
        )

        fun msgAcked(
            to: String,
            ackIds: List<String>,
            count: Int = ackIds.size,
            id: String = newId(),
        ): RelayEnvelope = RelayEnvelope(
            type = RelayType.MSG_ACKED,
            to = to,
            ackIds = ackIds,
            count = count,
            id = id,
        )
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
