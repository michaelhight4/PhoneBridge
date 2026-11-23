package dev.phonebridge.relay.crypto

import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.util.B64Url
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS material for the relay.
 *
 * A relay can either load an existing PKCS#12 keystore (the normal standalone case) or generate a
 * self-signed P-256 certificate on first run and persist it, so the certificate fingerprint stays
 * stable across restarts and can be pinned in pairing offers.
 *
 * The X.509 certificate is DER encoded by hand: the JDK's certificate builder is internal API and is
 * not available on Android, while a self-signed v1 certificate is a small, well-defined structure.
 */
object Tls {
    const val DEFAULT_ALIAS = "phonebridge-relay"
    const val DEFAULT_KEYSTORE_FILE = "relay-cert.p12"
    val DEFAULT_PASSWORD: CharArray = "phonebridge".toCharArray()

    data class Material(
        val sslContext: SSLContext,
        val certificate: X509Certificate,
        /** base64url SHA-256 of the DER certificate: the value pairing offers pin. */
        val fingerprint: String,
        /** Colon separated uppercase hex of the same digest, for humans. */
        val fingerprintHex: String,
        /**
         * False when the certificate could not be written to disk (for example a read-only app
         * directory on a phone). TLS still works, but the fingerprint changes when the relay restarts,
         * so peers have to re-pin it.
         */
        val persisted: Boolean = true
    )

    fun fingerprint(certificate: X509Certificate): String =
        B64Url.encode(Primitives.sha256(certificate.encoded))

    fun fingerprintHex(certificate: X509Certificate): String =
        Primitives.toHex(Primitives.sha256(certificate.encoded)).uppercase().chunked(2).joinToString(":")

    /**
     * Loads [file] when it exists and parses, otherwise generates a new self-signed key pair and
     * certificate, stores it in a PKCS#12 keystore at [file] and returns the server context.
     *
     * A corrupt keystore is replaced, and a location that cannot be written (some Android app
     * directories, a read-only host) still yields working in-memory material with
     * [Material.persisted] false, so the relay can serve instead of refusing to start.
     */
    fun loadOrCreateSelfSigned(
        file: File,
        password: CharArray = DEFAULT_PASSWORD,
        alias: String = DEFAULT_ALIAS,
        subject: String = "CN=PhoneBridge Relay"
    ): Material {
        if (file.exists()) {
            try {
                return fromKeyStore(file, password, alias)
            } catch (_: Exception) {
                // Unreadable or corrupt: fall through and generate a fresh certificate.
            }
        }
        try {
            file.parentFile?.mkdirs()
        } catch (_: Exception) {
        }
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        val now = Instant.now()
        val certificateDer = buildSelfSignedCertificate(
            keyPair = keyPair,
            subject = subject,
            notBefore = now.minus(Duration.ofDays(1)),
            notAfter = now.plus(Duration.ofDays(3650))
        )
        val certificate = parseCertificate(certificateDer)

        val store = KeyStore.getInstance("PKCS12")
        store.load(null, password)
        store.setKeyEntry(alias, keyPair.private, password, arrayOf(certificate))
        val persisted = try {
            file.outputStream().use { store.store(it, password) }
            restrictPermissions(file)
            true
        } catch (_: Exception) {
            false
        }

        return material(certificate, aliasKeyManager(store, password), persisted)
    }

    fun fromKeyStore(file: File, password: CharArray, alias: String = DEFAULT_ALIAS): Material {
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, password) }
        val certificate = store.getCertificate(alias) as? X509Certificate
            ?: throw IllegalStateException("keystore ${file.name} has no certificate under alias '$alias'")
        return material(certificate, aliasKeyManager(store, password))
    }

    private fun material(
        certificate: X509Certificate,
        keyManagers: Array<javax.net.ssl.KeyManager>,
        persisted: Boolean = true
    ): Material {
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, null, SecureRandom())
        return Material(
            sslContext = context,
            certificate = certificate,
            fingerprint = fingerprint(certificate),
            fingerprintHex = fingerprintHex(certificate),
            persisted = persisted
        )
    }

    private fun aliasKeyManager(store: KeyStore, password: CharArray): Array<javax.net.ssl.KeyManager> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, password)
        return kmf.keyManagers
    }

    /**
     * Client context for `wss://`.
     *
     * [pin] is a base64url SHA-256 certificate fingerprint; when set, only a certificate with that
     * fingerprint is accepted (this is how self-signed relays are reached without a CA).
     * [insecure] accepts any certificate and exists for development only.
     */
    fun clientContext(pin: String? = null, insecure: Boolean = false): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(PinnedTrustManager(pin?.let(::normalizePin), insecure)), SecureRandom())
        return context
    }

    fun normalizePin(pin: String): String = pin.filterNot { it.isWhitespace() }.trimEnd('=')

    private class PinnedTrustManager(
        private val pin: String?,
        private val insecure: Boolean
    ) : X509TrustManager {
        private val system: X509TrustManager? by lazy { defaultTrustManager() }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("the relay does not accept client certificates")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("server presented no certificate")
            when {
                pin != null -> {
                    val actual = fingerprint(leaf)
                    if (!java.security.MessageDigest.isEqual(
                            actual.toByteArray(Charsets.UTF_8),
                            pin.toByteArray(Charsets.UTF_8)
                        )
                    ) {
                        throw CertificateException("relay certificate fingerprint does not match the pinned value")
                    }
                }
                insecure -> Unit
                else -> system?.checkServerTrusted(chain, authType)
                    ?: throw CertificateException("no trust manager available")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun defaultTrustManager(): X509TrustManager? {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }

    private fun restrictPermissions(file: File) {
        try {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        } catch (_: Exception) {
            // Best effort: Windows ACLs and Android storage may refuse POSIX style changes.
        }
    }

    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate

    internal fun buildSelfSignedCertificate(
        keyPair: java.security.KeyPair,
        subject: String,
        notBefore: Instant,
        notAfter: Instant
    ): ByteArray {
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val signatureAlgorithm = Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256))
        val commonName = Der.sequence(
            Der.set(
                Der.sequence(
                    Der.oid(OID_COMMON_NAME),
                    Der.tlv(0x0C, subject.removePrefix("CN=").toByteArray(Charsets.UTF_8))
                )
            )
        )
        val tbs = Der.sequence(
            Der.integer(serial),
            signatureAlgorithm,
            commonName,
            Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter)),
            commonName,
            keyPair.public.encoded
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        return Der.sequence(tbs, signatureAlgorithm, Der.bitString(signature))
    }

    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)
    private val OID_ECDSA_WITH_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
}

/** Minimal ASN.1 DER writer, enough for a self-signed X.509 v1 certificate. */
internal object Der {
    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    fun length(length: Int): ByteArray {
        require(length >= 0)
        if (length < 0x80) return byteArrayOf(length.toByte())
        var value = length
        var count = 0
        while (value > 0) {
            count++
            value = value ushr 8
        }
        val out = ByteArray(count + 1)
        out[0] = (0x80 or count).toByte()
        value = length
        for (i in count downTo 1) {
            out[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return out
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray =
        concat(byteArrayOf(tag.toByte()), length(content.size), content)

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(*parts))

    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(*parts))

    fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    fun oid(encoded: ByteArray): ByteArray = tlv(0x06, encoded)

    private val UTC_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

    fun utcTime(instant: Instant): ByteArray =
        tlv(0x17, UTC_FORMAT.format(instant).toByteArray(Charsets.US_ASCII))

    fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, concat(byteArrayOf(0), bytes))
}
