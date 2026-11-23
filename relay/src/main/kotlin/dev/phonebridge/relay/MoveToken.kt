package dev.phonebridge.relay

import dev.phonebridge.relay.crypto.Codec

/**
 * One-time admission tokens issued by a relay that is handing over to a new host.
 *
 * A token is bound to one peer id and expires, so it cannot be replayed by anyone else; the new relay
 * additionally remembers redeemed tokens so the same token is never accepted twice. Both relays share
 * a `moveSecret` out of band (the same way a user hands their new relay the old one's details).
 *
 * Wire format: `mv1.<expiresEpochMillis>.<nonce>.<hmac>`, all base64url where applicable.
 */
internal class MoveTokenService(
    private val secret: ByteArray?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    val enabled: Boolean get() = secret != null && secret.isNotEmpty()

    sealed class Verify {
        data class Valid(val expiresAt: Long, val nonce: String) : Verify()
        data class Invalid(val reason: String) : Verify()
    }

    fun issue(peerId: String, ttlMs: Long): String {
        require(enabled) { "relay_move requires a moveSecret" }
        val expiresAt = clock() + ttlMs
        val nonce = Codec.b64url(Codec.randomBytes(16))
        val mac = sign(peerId, expiresAt, nonce)
        return "mv1.$expiresAt.$nonce.$mac"
    }

    fun verify(token: String, peerId: String): Verify {
        if (!enabled) return Verify.Invalid("this relay was not configured to accept relay moves")
        val parts = token.split('.')
        if (parts.size != 4 || parts[0] != "mv1") return Verify.Invalid("malformed move token")
        val expiresAt = parts[1].toLongOrNull() ?: return Verify.Invalid("malformed move token expiry")
        if (expiresAt <= clock()) return Verify.Invalid("move token expired")
        val expected = sign(peerId, expiresAt, parts[2])
        if (!Codec.constantTimeEquals(expected.toByteArray(Charsets.UTF_8), parts[3].toByteArray(Charsets.UTF_8))) {
            return Verify.Invalid("move token does not match this peer")
        }
        return Verify.Valid(expiresAt, parts[2])
    }

    private fun sign(peerId: String, expiresAt: Long, nonce: String): String {
        val key = secret ?: throw IllegalStateException("relay_move requires a moveSecret")
        val message = "mv1|$expiresAt|$nonce|$peerId".toByteArray(Charsets.UTF_8)
        return Codec.b64url(Codec.hmacSha256(key, message))
    }

    companion object {
        fun fromSecret(secret: String?, clock: () -> Long = System::currentTimeMillis): MoveTokenService =
            MoveTokenService(secret?.takeIf { it.isNotBlank() }?.let(Codec::decodeSecret), clock)
    }
}
