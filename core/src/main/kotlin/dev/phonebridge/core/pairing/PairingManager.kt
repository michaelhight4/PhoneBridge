package dev.phonebridge.core.pairing

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.PairingException
import dev.phonebridge.core.PairingFailure
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.Version
import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.identity.PeerIds
import dev.phonebridge.core.model.PairRequest
import dev.phonebridge.core.model.PairResult
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PairingReasons
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import java.security.SecureRandom

/**
 * A `pair_request` that decrypted correctly and is waiting for the human to tap Allow or Deny.
 *
 * The user sees [botName], [botFingerprint] and [requestedLevel]; the device may grant a different level
 * in [PairingManager.approve].
 */
class PendingApproval internal constructor(
    val offerCode: String,
    val request: PairRequest,
    val requestedLevel: PermissionLevel,
    val receivedAtMs: Long,
    internal val offer: PairingOffer,
) {
    val botName: String
        get() = request.botName

    val botId: String
        get() = request.computedBotId

    val botPublicKey: ByteArray
        get() = request.publicKey

    val botFingerprint: String
        get() = PeerIds.fingerprint(request.publicKey)

    internal val key: String
        get() = offerCode + ":" + botId

    override fun toString(): String = "PendingApproval($botName, $botFingerprint, $requestedLevel)"
}

/** The encrypted `pair_result` plus the plaintext copy, ready to send / show / log. */
class PairingAnswer(
    val result: PairResult,
    val sealed: ByteArray,
) {
    fun sealedBase64(): String = B64Url.encode(sealed)

    fun resultJson(): String = PbJson.encodeToString(PairResult.serializer(), result)
}

/**
 * Device-side pairing state machine.
 *
 * - [createOffer] makes a one-time offer: 32 random secret bytes, a fresh short code, expiry 10 minutes
 *   from now (injected [clock], so tests can move time).
 * - [accept] decrypts the bot's `pair_request`; it burns the offer on the first successful decrypt, so a
 *   second request with the same secret fails as `used`. An offer is also dead at `exp`.
 * - [approve] / [deny] answer the bot and (on approve) store the bot in the [BotRegistry].
 */
class PairingManager(
    val identity: Identity,
    private val registry: BotRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = Primitives.defaultRandom(),
    private val offerTtlMs: Long = OFFER_TTL_MS,
) {
    private class OfferState(val offer: PairingOffer, val createdAtMs: Long) {
        var usedAtMs: Long? = null
        val isUsed: Boolean get() = usedAtMs != null
    }

    private val offersByCode = LinkedHashMap<String, OfferState>()
    private val offersBySecret = HashMap<String, OfferState>()
    private val pending = LinkedHashMap<String, PendingApproval>()
    private val lock = Any()

    init {
        if (identity.role != PeerRole.DEVICE) {
            throw IllegalArgumentException("PairingManager runs on the device; bot identities pair through PairingClient")
        }
        require(offerTtlMs > 0) { "offer lifetime must be positive" }
    }

    /** Creates and remembers a fresh offer for [relayUrl]. */
    fun createOffer(
        relayUrl: String,
        deviceName: String? = identity.name,
    ): PairingOffer = synchronized(lock) {
        prune()
        val now = clock()
        var code = ""
        var attempts = 0
        do {
            code = ShortCode.generateBare(random)
            attempts++
        } while (offersByCode.containsKey(code) && attempts < 100)
        if (offersByCode.containsKey(code)) throw ProtocolException("could not allocate a unique pairing code")

        val secret = Primitives.randomBytes(SECRET_BYTES, random)
        val offer = PairingOffer(
            v = Version.PROTOCOL,
            relay = relayUrl,
            deviceId = identity.peerId,
            devicePub = identity.publicKeyBase64,
            secret = B64Url.encode(secret),
            code = ShortCode.format(code),
            exp = (now + offerTtlMs) / 1000L,
            deviceName = deviceName,
        )
        offer.validate()
        val state = OfferState(offer, now)
        offersByCode[code] = state
        offersBySecret[offer.secret] = state
        offer
    }

    /** The live offer behind a short code, or null when unknown, used or expired. */
    fun offerByCode(code: String): PairingOffer? = synchronized(lock) {
        val normalised = ShortCode.normalise(code)
        val state = offersByCode[normalised] ?: return null
        if (state.isUsed || isExpired(state, clock())) return null
        state.offer
    }

    /** Offers still usable right now (UI: "waiting for a bot"). */
    fun liveOffers(): List<PairingOffer> = synchronized(lock) {
        val now = clock()
        offersByCode.values.filter { !it.isUsed && !isExpired(it, now) }.map { it.offer }
    }

    /** The user dismissed the QR/link; the code stops working immediately. */
    fun cancelOffer(code: String): Boolean = synchronized(lock) {
        val normalised = ShortCode.normalise(code)
        val state = offersByCode.remove(normalised) ?: return false
        offersBySecret.remove(state.offer.secret)
        state.usedAtMs = clock()
        true
    }

    /** Decrypts a bot's `pair_request` sent for [code]. */
    fun accept(code: String, sealedRequest: ByteArray): PendingApproval = synchronized(lock) {
        val normalised = ShortCode.normalise(code)
        if (!ShortCode.isValid(normalised)) {
            throw PairingException(PairingFailure.UNKNOWN_CODE, "malformed pairing code")
        }
        val state = offersByCode[normalised] ?: throw PairingException(PairingFailure.UNKNOWN_CODE)
        acceptState(state, sealedRequest)
    }

    /** Decrypts a bot's `pair_request` for an offer object obtained from [liveOffers] or a saved link. */
    fun accept(offer: PairingOffer, sealedRequest: ByteArray): PendingApproval = synchronized(lock) {
        offer.validate()
        val state = offersBySecret[offer.secret] ?: throw PairingException(PairingFailure.UNKNOWN_CODE)
        if (state.offer.code != offer.code) throw PairingException(PairingFailure.BAD_SECRET)
        acceptState(state, sealedRequest)
    }

    /** Requests that decrypted but have not been approved or denied yet. */
    fun pendingApprovals(): List<PendingApproval> = synchronized(lock) { pending.values.toList() }

    /** "Allow this bot?": stores the bot and returns the sealed `pair_result`. */
    fun approve(pendingApproval: PendingApproval, level: PermissionLevel = pendingApproval.requestedLevel): PairingAnswer =
        synchronized(lock) {
            val known = pending[pendingApproval.key]
                ?: throw PairingException(PairingFailure.BAD_REQUEST, "this approval is no longer pending")
            if (known !== pendingApproval && known.request.botPub != pendingApproval.request.botPub) {
                throw PairingException(PairingFailure.BAD_REQUEST, "approval does not match the pending request")
            }
            val now = clock()
            registry.put(
                BotRecord(
                    botId = pendingApproval.botId,
                    name = pendingApproval.botName,
                    publicKey = B64Url.encode(pendingApproval.botPublicKey),
                    level = level,
                    enabled = true,
                    pairedAt = now,
                    lastSeen = now,
                ),
            )
            pending.remove(pendingApproval.key)
            sealAnswer(pendingApproval, PairResult.granted(identity.name, identity.peerId, level))
        }

    /** "Deny": the offer stays burned, so the bot must ask for a brand-new offer. */
    fun deny(pendingApproval: PendingApproval, reason: String = PairingReasons.DENIED): PairingAnswer = synchronized(lock) {
        val known = pending[pendingApproval.key]
            ?: throw PairingException(PairingFailure.BAD_REQUEST, "this approval is no longer pending")
        if (known !== pendingApproval && known.request.botPub != pendingApproval.request.botPub) {
            throw PairingException(PairingFailure.BAD_REQUEST, "approval does not match the pending request")
        }
        pending.remove(pendingApproval.key)
        sealAnswer(pendingApproval, PairResult.denied(reason, identity.name, identity.peerId))
    }

    /** Unpairs one bot; the others are untouched. */
    fun revoke(botId: String): Boolean = registry.remove(botId)

    private fun acceptState(state: OfferState, sealedRequest: ByteArray): PendingApproval {
        val now = clock()
        if (state.isUsed) throw PairingException(PairingFailure.USED)
        if (isExpired(state, now)) {
            forget(state)
            throw PairingException(PairingFailure.EXPIRED)
        }
        val offer = state.offer
        val plaintext = try {
            PairingCrypto.open(
                secret = offer.secretBytes,
                associatedData = PairingCrypto.requestAad(offer.devicePublicKey),
                sealed = sealedRequest,
            )
        } catch (e: CryptoException) {
            throw PairingException(PairingFailure.BAD_SECRET, "pair_request did not decrypt", e)
        }
        val request = try {
            PbJson.decodeFromString(PairRequest.serializer(), String(plaintext, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw PairingException(PairingFailure.BAD_REQUEST, "malformed pair_request", e)
        }
        try {
            request.validate()
        } catch (e: ProtocolException) {
            throw PairingException(PairingFailure.BAD_REQUEST, e.message ?: "malformed pair_request", e)
        }
        // Burn on first use, before the human decides: a denied request cannot be replayed.
        state.usedAtMs = now
        val approval = PendingApproval(
            offerCode = ShortCode.normalise(offer.code),
            request = request,
            requestedLevel = request.requestedLevel,
            receivedAtMs = now,
            offer = offer,
        )
        pending[approval.key] = approval
        return approval
    }

    private fun sealAnswer(pendingApproval: PendingApproval, result: PairResult): PairingAnswer {
        val json = PbJson.encodeToString(PairResult.serializer(), result).toByteArray(Charsets.UTF_8)
        val sealed = PairingCrypto.seal(
            secret = pendingApproval.offer.secretBytes,
            associatedData = PairingCrypto.resultAad(pendingApproval.offer.devicePublicKey),
            plaintext = json,
            random = random,
        )
        return PairingAnswer(result, sealed)
    }

    private fun isExpired(state: OfferState, now: Long): Boolean {
        if (now >= state.offer.expiresAtMillis) return true
        return now - state.createdAtMs >= offerTtlMs
    }

    private fun forget(state: OfferState) {
        offersByCode.remove(ShortCode.normalise(state.offer.code))
        offersBySecret.remove(state.offer.secret)
    }

    private fun prune(now: Long = clock()) {
        val dead = offersByCode.values.filter { it.isUsed || isExpired(it, now) }
        for (state in dead) forget(state)
        val staleApprovalKeys = pending.values.filter { it.offerCode !in offersByCode }.map { it.key }
        for (key in staleApprovalKeys) pending.remove(key)
    }

    companion object {
        /** Offers live ten minutes, then the secret is dead whether or not it was used. */
        const val OFFER_TTL_MS = 10 * 60 * 1000L

        const val SECRET_BYTES = 32
    }
}
