package dev.phonebridge.e2e

import dev.phonebridge.relay.Relay
import dev.phonebridge.relay.RelayConfig
import dev.phonebridge.relay.RelayHandle
import dev.phonebridge.relay.TlsConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Small helpers shared by the harness, the tests and the manual fake-phone server. */
object E2e {

    const val DEFAULT_TIMEOUT_MS = 15_000L

    fun tempDir(prefix: String = "pb-e2e"): Path = Files.createTempDirectory(prefix)

    /**
     * Starts the standalone relay ("the heart") on a free loopback port.
     *
     * [tls] turns on the real self-signed TLS listener, so the e2e run also proves the certificate pin
     * that travels in the pairing offer. Without it the relay speaks plain `ws://` on loopback, which
     * the relay allows for development only.
     */
    fun startRelay(
        tls: Boolean = false,
        moveSecret: String? = null,
        dataDir: Path = tempDir("pb-relay"),
    ): RelayHandle = Relay.start(
        RelayConfig(
            port = 0,
            bindAddress = "127.0.0.1",
            tls = if (tls) TlsConfig.selfSigned(dataDir.resolve("cert").toString()) else null,
            moveSecret = moveSecret,
            dataDir = dataDir.toString(),
            log = {},
        ),
    )

    /** Spins until [condition] holds, then returns; throws an [AssertionError] with [what] on timeout. */
    fun waitUntil(timeoutMs: Long = DEFAULT_TIMEOUT_MS, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        if (!condition()) throw AssertionError("timed out after ${timeoutMs}ms waiting for $what")
    }

    /** Takes the next value from [queue] or fails with [what]. */
    fun <T> await(queue: LinkedBlockingQueue<T>, what: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): T =
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("timed out after ${timeoutMs}ms waiting for $what")
}
