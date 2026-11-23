package dev.phonebridge.core.model

import dev.phonebridge.core.PairingFailure
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.Version
import dev.phonebridge.core.identity.PeerIds
import dev.phonebridge.core.pairing.ShortCode
import dev.phonebridge.core.util.B64Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire strings for `pair_result.reason`, matching [PairingFailure]. */
object PairingReasons {
    const val EXPIRED = "expired"
    const val USED = "used"
    const val UNKNOWN_CODE = "unknown_code"
    const val BAD_SECRET = "bad_secret"
    const val BAD_REQUEST = "bad_request"
    const val DENIED = "denied"
    const val ALREADY_PAIRED = "already_paired"
    const val UNKNOWN_BOT = "unknown_bot"

    fun of(failure: PairingFailure): String = failure.name.lowercase()
}

/**
 * The one-time pairing offer created on the device. It travels as a QR code, a link or a `.pbpair`
 * file, and the relay may hold it for the short code for at most 10 minutes.
 */
@Serializable
data class PairingOffer(
    val v: Int = Version.PROTOCOL,
    /** Relay the bot should connect to, `wss://host:port`. */
    val relay: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("device_pub")
    val devicePub: String,
    /** 32 random bytes, base64url, single use, burned after the first accepted request. */
    val secret: String,
    /** Display form `7K4-Q9M`; the last symbol is a check symbol. */
    val code: String,
    /** Unix seconds; creation + 10 minutes. */
    val exp: Long,
    @SerialName("device_name")
    val deviceName: String? = null,
) {
    val secretBytes: ByteArray
        get() = B64Url.decode(secret)

    val devicePublicKey: ByteArray
        get() = B64Url.decode(devicePub)

    val expiresAtMillis: Long
        get() = exp * 1000L

    val normalisedCode: String
        get() = ShortCode.normalise(code)

    fun isExpiredAt(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    /**
     * Checks every invariant a receiver relies on. Throws [ProtocolException] with a specific message so
     * a bad QR/link/file shows the user something useful instead of failing inside crypto later.
     */
    fun validate() {
        if (v != Version.PROTOCOL) throw ProtocolException("unsupported pairing version: $v")
        if (!relay.startsWith("wss://") && !relay.startsWith("ws://")) {
            throw ProtocolException("relay must be a ws:// or wss:// URL")
        }
        if (secretBytes.size != 32) throw ProtocolException("pairing secret must be 32 bytes")
        val deviceKey = devicePublicKey
        if (deviceKey.size != 32) throw ProtocolException("device_pub must be 32 bytes")
        if (PeerIds.peerId(deviceKey) != deviceId) throw ProtocolException("device_id does not match device_pub")
        if (!ShortCode.isValid(code)) throw ProtocolException("invalid pairing code: $code")
        if (exp <= 0) throw ProtocolException("pairing offer has no expiry")
    }
}

/** Bot -> device, encrypted with a key derived from the offer secret. */
@Serializable
data class PairRequest(
    val v: Int = Version.PROTOCOL,
    @SerialName("bot_name")
    val botName: String,
    @SerialName("bot_pub")
    val botPub: String,
    @SerialName("requested_level")
    val requestedLevel: PermissionLevel = PermissionLevel.CONTROL,
    /** Convenience copy of `peer_id(bot_pub)`; ignored when it disagrees. */
    @SerialName("bot_id")
    val botId: String? = null,
) {
    val publicKey: ByteArray
        get() = B64Url.decode(botPub)

    val computedBotId: String
        get() = PeerIds.peerId(publicKey)

    fun validate() {
        if (botName.isBlank()) throw ProtocolException("bot_name must not be blank")
        if (publicKey.size != 32) throw ProtocolException("bot_pub must be 32 bytes")
        val derived = computedBotId
        if (botId != null && botId != derived) throw ProtocolException("bot_id does not match bot_pub")
    }
}

/** Device -> bot answer to a pair request. */
@Serializable
data class PairResult(
    val v: Int = Version.PROTOCOL,
    val ok: Boolean,
    @SerialName("granted_level")
    val grantedLevel: PermissionLevel? = null,
    @SerialName("device_name")
    val deviceName: String? = null,
    @SerialName("device_id")
    val deviceId: String? = null,
    /** Machine readable reason from [PairingReasons]; only present when `ok` is false. */
    val reason: String? = null,
) {
    companion object {
        fun granted(
            deviceName: String?,
            deviceId: String?,
            level: PermissionLevel,
        ): PairResult = PairResult(
            ok = true,
            grantedLevel = level,
            deviceName = deviceName,
            deviceId = deviceId,
        )

        fun denied(
            reason: String = PairingReasons.DENIED,
            deviceName: String? = null,
            deviceId: String? = null,
        ): PairResult = PairResult(ok = false, deviceName = deviceName, deviceId = deviceId, reason = reason)
    }
}
