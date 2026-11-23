package dev.phonebridge.relay

import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Standalone relay for a VPS or a desktop.
 *
 * ```
 * gradlew.bat :relay:run --args="--port 8443"
 * java -jar phonebridge-relay-all.jar --port 8443 --data-dir /var/lib/phonebridge
 * ```
 */
fun main(args: Array<String>) {
    val options = try {
        CliOptions.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(64)
    }
    if (options.help) {
        println(CliOptions.HELP)
        return
    }

    val log: (String) -> Unit = if (options.quiet) {
        { _ -> }
    } else {
        { message -> println("[relay] $message") }
    }

    val config = RelayConfig(
        port = options.port,
        bindAddress = options.bindAddress,
        advertisedHost = options.advertisedHost,
        tls = if (options.noTls) null else TlsConfig(
            keystorePath = options.keystorePath,
            keystorePassword = options.keystorePassword,
            keyAlias = options.keystoreAlias,
            certDir = options.certDir
        ),
        insecureDev = options.insecureDev,
        maxEnvelopeBytes = options.maxEnvelopeBytes,
        rateLimit = RateLimitConfig(maxMessages = options.rateLimit, windowMs = options.rateWindowMs, burst = options.rateLimit),
        moveSecret = options.moveSecret,
        dataDir = options.dataDir,
        log = log
    )

    val relay = try {
        Relay.start(config)
    } catch (e: Exception) {
        System.err.println("relay failed to start: ${e.message}")
        exitProcess(1)
    }

    if (!options.quiet) {
        val httpUrl = relay.url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
        println()
        println("PhoneBridge relay")
        println("  url             ${relay.url}")
        println("  relay id        ${relay.relayId}")
        relay.fingerprint?.let { println("  cert pin        $it") }
        relay.fingerprintHex?.let { println("  cert sha256     $it") }
        println("  health          $httpUrl/healthz")
        println()
    }

    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            relay.stop()
            stopped.countDown()
        }, "pb-relay-shutdown")
    )
    stopped.await()
}

internal class CliOptions(
    val port: Int = 8443,
    val bindAddress: String = "0.0.0.0",
    val advertisedHost: String? = null,
    val noTls: Boolean = false,
    val insecureDev: Boolean = false,
    val keystorePath: String? = null,
    val keystorePassword: String? = null,
    val keystoreAlias: String = "phonebridge-relay",
    val certDir: String? = null,
    val dataDir: String? = null,
    val moveSecret: String? = null,
    val maxEnvelopeBytes: Int = 16 * 1024 * 1024,
    val rateLimit: Int = 240,
    val rateWindowMs: Long = 1000,
    val quiet: Boolean = false,
    val help: Boolean = false
) {
    companion object {
        val HELP = """
            PhoneBridge relay ("the heart")

            Usage: relay [options]

              --port N                 Listen port (default 8443, 0 picks a free port)
              --bind ADDR              Bind address (default 0.0.0.0)
              --advertised-host HOST   Host name printed in the relay URL
              --no-tls                 Plain ws:// (loopback only, or with --insecure-dev)
              --insecure-dev           Allow plain ws:// on a non-loopback address (development only)
              --keystore PATH          PKCS#12 keystore with the TLS key and certificate
              --keystore-password P    Keystore password (or PHONEBRIDGE_KEYSTORE_PASSWORD)
              --keystore-alias ALIAS   Key alias inside the keystore (default phonebridge-relay)
              --cert-dir DIR           Where the self-signed certificate is generated and kept
              --data-dir DIR           Relay identity and certificate directory (default ~/.phonebridge)
              --move-secret SECRET     Shared secret for relay_move (or PHONEBRIDGE_MOVE_SECRET)
              --max-envelope-bytes N   Largest envelope accepted (default 16 MiB)
              --rate-limit N           Messages per peer per window (default 240)
              --rate-window-ms N       Rate limit window in milliseconds (default 1000)
              --quiet                  Only print errors
              --help                   Show this help

            Without --no-tls and without --keystore, a self-signed certificate is generated on first run
            and its fingerprint is printed; pin that fingerprint in pairing offers.
        """.trimIndent()

        fun parse(args: Array<String>): CliOptions {
            var port = 8443
            var bind = "0.0.0.0"
            var advertisedHost: String? = null
            var noTls = false
            var insecureDev = false
            var keystorePath: String? = null
            var keystorePassword: String? = System.getenv("PHONEBRIDGE_KEYSTORE_PASSWORD")
            var keystoreAlias = "phonebridge-relay"
            var certDir: String? = null
            var dataDir: String? = null
            var moveSecret: String? = System.getenv("PHONEBRIDGE_MOVE_SECRET")
            var maxEnvelopeBytes = 16 * 1024 * 1024
            var rateLimit = 240
            var rateWindowMs = 1000L
            var quiet = false
            var help = false

            var index = 0
            while (index < args.size) {
                val arg = args[index]
                fun value(): String {
                    if (index + 1 >= args.size) throw IllegalArgumentException("$arg needs a value")
                    index++
                    return args[index]
                }
                when (arg) {
                    "--port" -> port = value().toIntOrNull()
                        ?: throw IllegalArgumentException("--port needs a number")
                    "--bind" -> bind = value()
                    "--advertised-host" -> advertisedHost = value()
                    "--no-tls" -> noTls = true
                    "--insecure-dev" -> insecureDev = true
                    "--keystore" -> keystorePath = value()
                    "--keystore-password" -> keystorePassword = value()
                    "--keystore-alias" -> keystoreAlias = value()
                    "--cert-dir" -> certDir = value()
                    "--data-dir" -> dataDir = value()
                    "--move-secret" -> moveSecret = value()
                    "--max-envelope-bytes" -> maxEnvelopeBytes = value().toIntOrNull()
                        ?: throw IllegalArgumentException("--max-envelope-bytes needs a number")
                    "--rate-limit" -> rateLimit = value().toIntOrNull()
                        ?: throw IllegalArgumentException("--rate-limit needs a number")
                    "--rate-window-ms" -> rateWindowMs = value().toLongOrNull()
                        ?: throw IllegalArgumentException("--rate-window-ms needs a number")
                    "--quiet" -> quiet = true
                    "--help", "-h" -> help = true
                    else -> throw IllegalArgumentException("unknown option: $arg (try --help)")
                }
                index++
            }
            if (port !in 0..65535) throw IllegalArgumentException("--port must be between 0 and 65535")
            if (rateLimit <= 0) throw IllegalArgumentException("--rate-limit must be positive")
            if (rateWindowMs <= 0) throw IllegalArgumentException("--rate-window-ms must be positive")

            return CliOptions(
                port = port,
                bindAddress = bind,
                advertisedHost = advertisedHost,
                noTls = noTls,
                insecureDev = insecureDev,
                keystorePath = keystorePath,
                keystorePassword = keystorePassword,
                keystoreAlias = keystoreAlias,
                certDir = certDir,
                dataDir = dataDir,
                moveSecret = moveSecret,
                maxEnvelopeBytes = maxEnvelopeBytes,
                rateLimit = rateLimit,
                rateWindowMs = rateWindowMs,
                quiet = quiet,
                help = help
            )
        }
    }
}
