package dev.phonebridge.core

import dev.phonebridge.core.model.PermissionLevel

/** Base class for every error this library raises. */
open class PhoneBridgeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A message, link, file or envelope did not match the protocol. */
class ProtocolException(message: String, cause: Throwable? = null) : PhoneBridgeException(message, cause)

/** Arguments for a command were missing, malformed or contradictory. */
class BadArgsException(message: String, cause: Throwable? = null) : PhoneBridgeException(message, cause)

/** A command name is not part of the protocol. */
class UnknownCommandException(val cmd: String) : PhoneBridgeException("unknown command: $cmd")

/** A bot tried something above its permission level. */
class PermissionDeniedException(
    val required: PermissionLevel,
    val granted: PermissionLevel,
) : PhoneBridgeException("needs ${required.name.lowercase()}, bot has ${granted.name.lowercase()}")

/** Crypto failed: bad key, bad tag, replayed nonce, tampered input. */
class CryptoException(message: String, cause: Throwable? = null) : PhoneBridgeException(message, cause)

/** Why a pairing attempt was refused. */
enum class PairingFailure {
    /** The offer is past its 10-minute lifetime. */
    EXPIRED,

    /** The offer was already used once. */
    USED,

    /** No live offer matches that short code. */
    UNKNOWN_CODE,

    /** The pairing secret does not decrypt the request, or the offer does not match. */
    BAD_SECRET,

    /** The request itself is malformed or asks for something impossible. */
    BAD_REQUEST,

    /** The user tapped "Deny". */
    DENIED,

    /** This bot key is already paired and the device refused a duplicate. */
    ALREADY_PAIRED,

    /** The caller asked about a bot that is not in the registry. */
    UNKNOWN_BOT,
}

/** Pairing failed; [reason] is machine readable for the `pair_result` message. */
class PairingException(
    val reason: PairingFailure,
    message: String = reason.name.lowercase(),
    cause: Throwable? = null,
) : PhoneBridgeException(message, cause)
