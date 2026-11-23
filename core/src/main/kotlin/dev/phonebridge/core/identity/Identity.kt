package dev.phonebridge.core.identity

import dev.phonebridge.core.CryptoException
import dev.phonebridge.core.Version
import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.crypto.X25519
import dev.phonebridge.core.crypto.X25519KeyPair
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * A long-term identity on disk: an X25519 key pair plus the friendly name. The private key is stored
 * base64url; on Android the app should wrap this file with the Keystore (not this library's job).
 */
@Serializable
data class StoredIdentity(
    val v: Int = Version.PROTOCOL,
    val role: PeerRole,
    @SerialName("private_key")
    val privateKey: String,
    @SerialName("public_key")
    val publicKey: String,
    val name: String? = null,
    @SerialName("created_at")
    val createdAt: Long = 0,
)

/**
 * A device or bot identity: one X25519 key pair, its [peerId] and the printable [fingerprint] users see.
 */
class Identity private constructor(
    val role: PeerRole,
    val keys: X25519KeyPair,
    val name: String? = null,
) {
    init {
        if (keys.privateKey.size != X25519.DHLEN) throw CryptoException("identity key must be ${X25519.DHLEN} bytes")
    }

    val publicKey: ByteArray
        get() = keys.publicKey.copyOf()

    val publicKeyBase64: String
        get() = B64Url.encode(keys.publicKey)

    /** The private key is needed by the Noise handshake; copy it if you must store it. */
    val privateKey: ByteArray
        get() = keys.privateKey.copyOf()

    val peerId: String
        get() = PeerIds.peerId(keys.publicKey)

    /** `ABCD-EFGH` style fingerprint of this identity. */
    val fingerprint: String
        get() = PeerIds.fingerprint(keys.publicKey)

    fun withName(newName: String?): Identity = Identity(role, keys, newName)

    fun exportPrivateKeyBase64(): String = B64Url.encode(keys.privateKey)

    fun export(createdAt: Long = 0L): StoredIdentity = StoredIdentity(
        role = role,
        privateKey = exportPrivateKeyBase64(),
        publicKey = publicKeyBase64,
        name = name,
        createdAt = createdAt,
    )

    fun toJson(): String = PbJson.encodeToString(StoredIdentity.serializer(), export())

    override fun toString(): String = "Identity(${role.wire}, $peerId, $fingerprint)"

    companion object {
        fun generate(
            role: PeerRole,
            name: String? = null,
            random: SecureRandom = Primitives.defaultRandom(),
        ): Identity = Identity(role, X25519.generateKeyPair(random), name)

        fun fromPrivateKey(role: PeerRole, privateKey: ByteArray, name: String? = null): Identity =
            Identity(role, X25519.fromPrivateKey(privateKey), name)

        fun fromPrivateKeyBase64(role: PeerRole, privateKeyB64: String, name: String? = null): Identity =
            fromPrivateKey(role, B64Url.decode(privateKeyB64), name)

        /** Rebuilds an identity and refuses a stored public key that does not match the private key. */
        fun fromStored(stored: StoredIdentity): Identity {
            val identity = fromPrivateKey(stored.role, B64Url.decode(stored.privateKey), stored.name)
            if (identity.publicKeyBase64 != stored.publicKey) {
                throw CryptoException("stored identity public key does not match its private key")
            }
            return identity
        }

        fun fromJson(json: String): Identity = try {
            fromStored(PbJson.decodeFromString(StoredIdentity.serializer(), json))
        } catch (e: IllegalArgumentException) {
            throw CryptoException("malformed identity file", e)
        }
    }
}
