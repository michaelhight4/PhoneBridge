package dev.phonebridge.ui

import dev.phonebridge.core.Version
import dev.phonebridge.core.util.PbJsonPretty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/** Where the heart (relay) beats. */
@Serializable
enum class RelayHost {
    @SerialName("this_phone")
    THIS_PHONE,

    @SerialName("my_pc")
    MY_PC,

    @SerialName("custom_url")
    CUSTOM_URL,
}

/** The user's relay choice, persisted as JSON so it survives a reboot. */
@Serializable
data class RelaySettings(
    val host: RelayHost = RelayHost.THIS_PHONE,
    /** Relay URL for [RelayHost.MY_PC] / [RelayHost.CUSTOM_URL]. */
    val url: String? = null,
    /** Pinned certificate fingerprint (base64url) for a self-signed relay. */
    @SerialName("tls_pin")
    val tlsPin: String? = null,
)

/** Reads and writes [RelaySettings] atomically (`relay.v1.json` by convention). */
class RelaySettingsStore(private val file: Path) {

    constructor(file: java.io.File) : this(file.toPath())

    fun load(): RelaySettings {
        if (!Files.exists(file)) return RelaySettings()
        return try {
            val text = Files.readString(file)
            PbJsonPretty.decodeFromString(RelaySettingsFile.serializer(), text).settings
        } catch (_: Exception) {
            // A corrupt settings file must not brick the app: fall back to the safe default.
            RelaySettings()
        }
    }

    fun save(settings: RelaySettings): RelaySettings {
        val parent = file.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(
            temp,
            PbJsonPretty.encodeToString(
                RelaySettingsFile.serializer(),
                RelaySettingsFile(settings = settings),
            ),
        )
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return settings
    }

    val path: Path get() = file
}

/** Versioned on-disk shape, so a future change can migrate it. */
@Serializable
data class RelaySettingsFile(
    val v: Int = Version.PROTOCOL,
    val settings: RelaySettings = RelaySettings(),
)

/** The parts of the embedded relay / relay client the UI has to drive. */
interface HeartControl {
    /** Start the embedded relay on this phone and return the URL peers should connect to. */
    fun startHeartHere(): String

    /** Stop the embedded relay (it is moving elsewhere or the user switched it off). */
    fun stopHeartHere()

    /** Point the outbound relay client at [url] (and pin its certificate when given). */
    fun useRelay(url: String, tlsPin: String?): Boolean
}

sealed interface RelayResult {
    data class Ok(val settings: RelaySettings, val url: String?) : RelayResult
    data class Error(val message: String) : RelayResult
}

/**
 * The Relay screen: choose where the heart beats (this phone / my PC / a custom URL) and "Move heart
 * here", which is the same operation a bot triggers with the Full-level `relay_move {to:"this"}`
 * command. URL rules mirror the relay client: `wss://` anywhere, plain `ws://` only on loopback.
 */
class RelayController(
    private val store: RelaySettingsStore,
    private val heart: HeartControl,
    private val log: ActivityLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cached: RelaySettings = store.load()
    private var heartUrl: String? = null

    fun settings(): RelaySettings = cached

    /** The relay URL a new pairing offer should advertise. Starts the embedded relay if needed. */
    fun offerUrl(): String? = when (cached.host) {
        RelayHost.THIS_PHONE -> startHeartIfNeeded()
        RelayHost.MY_PC, RelayHost.CUSTOM_URL -> cached.url
    }

    /** "Move heart here" / `relay_move {to:"this"}`. */
    fun moveHeartHere(actorId: String = DEVICE, actorName: String = DEVICE): RelayResult {
        val url = try {
            heart.startHeartHere()
        } catch (e: Exception) {
            return RelayResult.Error(e.message ?: "could not start the relay on this phone")
        }
        heartUrl = url
        cached = RelaySettings(host = RelayHost.THIS_PHONE, url = null, tlsPin = null)
        store.save(cached)
        log.record(actorId, actorName, "relay_move", true, detail = "Heart now beats on this phone ($url)")
        return RelayResult.Ok(cached, url)
    }

    /** Pick a different host. [url] is required for [RelayHost.MY_PC] and [RelayHost.CUSTOM_URL]. */
    fun setHost(
        host: RelayHost,
        url: String? = null,
        tlsPin: String? = null,
        actorId: String = DEVICE,
        actorName: String = DEVICE,
    ): RelayResult {
        if (host == RelayHost.THIS_PHONE) return moveHeartHere(actorId, actorName)

        val checked = validateRelayUrl(url)
        if (checked != null) return RelayResult.Error(checked)
        val cleanUrl = url!!.trim()
        if (!heart.useRelay(cleanUrl, tlsPin?.trim()?.takeIf { it.isNotEmpty() })) {
            return RelayResult.Error("could not connect to $cleanUrl")
        }
        heart.stopHeartHere()
        heartUrl = null
        cached = RelaySettings(host = host, url = cleanUrl, tlsPin = tlsPin?.trim()?.takeIf { it.isNotEmpty() })
        store.save(cached)
        log.record(actorId, actorName, "relay_host", true, detail = "Heart moved to $cleanUrl")
        return RelayResult.Ok(cached, cleanUrl)
    }

    /** `relay_move {to:"<url>"}`: hand routing over while the relay runs here. */
    fun handOverTo(newUrl: String, actorId: String = DEVICE, actorName: String = DEVICE): RelayResult {
        if (cached.host != RelayHost.THIS_PHONE) {
            return RelayResult.Error("this phone is not running the heart")
        }
        val checked = validateRelayUrl(newUrl, requireTls = true)
        if (checked != null) return RelayResult.Error(checked)
        if (!heart.useRelay(newUrl.trim(), null)) {
            return RelayResult.Error("could not reach ${newUrl.trim()}")
        }
        log.record(actorId, actorName, "relay_move", true, detail = "Handed the heart to ${newUrl.trim()}")
        return RelayResult.Ok(cached, newUrl.trim())
    }

    fun statusText(): String = when (cached.host) {
        RelayHost.THIS_PHONE -> heartUrl?.let { "Heart here: $it" } ?: "Heart: this phone (not started)"
        RelayHost.MY_PC -> "Heart on my PC: ${cached.url ?: "not set"}"
        RelayHost.CUSTOM_URL -> "Heart at ${cached.url ?: "not set"}"
    }

    private fun startHeartIfNeeded(): String? {
        heartUrl?.let { return it }
        return try {
            heart.startHeartHere().also { heartUrl = it }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEVICE = "device"

        /**
         * Returns a human-readable error, or null when [url] is usable. Plain `ws://` is only allowed
         * to loopback, matching the relay client's rule.
         */
        fun validateRelayUrl(url: String?, requireTls: Boolean = false): String? {
            if (url.isNullOrBlank()) return "Enter the relay URL (wss://host:port)"
            val uri = try {
                java.net.URI(url.trim())
            } catch (e: Exception) {
                return "That is not a valid URL"
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "ws" && scheme != "wss") return "The relay URL must start with ws:// or wss://"
            if (requireTls && scheme != "wss") return "Use wss:// for a relay on another device"
            val host = uri.host ?: return "The relay URL has no host"
            if (scheme == "ws" && !isLoopback(host)) {
                return "Plain ws:// is only allowed on this device; use wss:// (and the certificate pin)"
            }
            return null
        }

        fun isLoopback(host: String): Boolean {
            val normalised = host.trim().removePrefix("[").removeSuffix("]").lowercase()
            return normalised == "localhost" || normalised == "::1" || normalised == "0:0:0:0:0:0:0:1" ||
                normalised.startsWith("127.")
        }
    }
}
