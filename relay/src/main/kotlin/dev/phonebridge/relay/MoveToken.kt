package dev.phonebridge.relay

import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.crypto.Secrets

/**
 * One-time admission tokens issued by a relay that is handing over to a new host.
 *
 * A token is bound to one peer id and expires, so it cannot be replayed by anyone else; the new relay
 * additionally remembers redeemed tokens so the same token is never accepted twice. Both relays share
 * a `moveSecret` out of band (the same way a user hands their new relay the old one's details).
 *
 * Wire format: `mv1.<expiresEpochMillis>.<nonce>.<hmac>`, base64url for the nonce and the HMAC.
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
        val nonce = B64Url.encode(Primitives.randomBytes(16))
        return "mv1.$expiresAt.$nonce.${B64Url.encode(sign(peerId, expiresAt, nonce))}"
    }

    fun verify(token: String, peerId: String): Verify {
        if (!enabled) return Verify.Invalid("this relay was not configured to accept relay moves")
        val parts = token.split('.')
        if (parts.size != 4 || parts[0] != "mv1") return Verify.Invalid("malformed move token")
        val expiresAt = parts[1].toLongOrNull() ?: return Verify.Invalid("malformed move token expiry")
        if (expiresAt <= clock()) return Verify.Invalid("move token expired")
        val presented = try {
            B64Url.decode(parts[3])
        } catch (_: IllegalArgumentException) {
            return Verify.Invalid("malformed move token signature")
        }
        if (!Primitives.constantTimeEquals(sign(peerId, expiresAt, parts[2]), presented)) {
            return Verify.Invalid("move token does not match this peer")
        }
        return Verify.Valid(expiresAt, parts[2])
    }

    private fun sign(peerId: String, expiresAt: Long, nonce: String): ByteArray {
        val key = secret ?: throw IllegalStateException("relay_move requires a moveSecret")
        val message = "mv1|$expiresAt|$nonce|$peerId".toByteArray(Charsets.UTF_8)
        return Primitives.hmacSha256(key, message)
    }

    companion object {
        fun fromSecret(secret: String?, clock: () -> Long = System::currentTimeMillis): MoveTokenService =
            MoveTokenService(secret?.takeIf { it.isNotBlank() }?.let(Secrets::decode), clock)
    }
}
