package dev.phonebridge.desktop.app

import dev.phonebridge.desktop.state.ActivityEntryFactory
import dev.phonebridge.desktop.state.ActivityLog

/** The embedded relay and the outbound relay client, as the UI and `relay_move` need them. */
interface HeartControl {
    /** Start the embedded relay on this PC and return the URL peers should use. */
    fun startHere(): String

    /** Stop the embedded relay (the heart is moving, or the user turned it off). */
    fun stopHere()

    /** Point the outbound relay client at [url], pinning the certificate when given. */
    fun useRelay(url: String, tlsPin: String?): Boolean

    /** Hand every connected peer over to [newUrl] and return how many were told. */
    fun handOver(newUrl: String): Int

    /** URL the outbound client is on, or null while it is (re)connecting. */
    val currentUrl: String?
}

sealed interface RelayResult {
    data class Ok(val settings: DesktopSettings, val url: String?) : RelayResult

    data class Error(val message: String) : RelayResult
}

/**
 * The Relay screen logic: "heart on this PC" or a custom relay URL, plus the two directions of
 * `relay_move` (a bot asks this PC to host, or this PC hands the heart to another URL).
 *
 * The embedded relay itself is behind [HeartControl], so every rule here is unit tested with a fake.
 */
class HeartController(
    private val store: DesktopSettingsStore,
    private val heart: HeartControl,
    private val log: ActivityLog,
    private val actorId: String = ACTOR,
    private val actorName: String = ACTOR,
) {
    private var cached: DesktopSettings = store.load()
    private var heartUrl: String? = null

    fun settings(): DesktopSettings = cached

    /** Relay URL a new pairing offer should advertise; starts the embedded relay when this PC hosts. */
    fun offerUrl(): String? = when (cached.host) {
        HeartHost.THIS_PC -> startHeartIfNeeded()
        HeartHost.CUSTOM_URL -> cached.url
    }

    /** "Move heart here" and `relay_move {to:"this"}`. */
    fun moveHeartHere(): RelayResult {
        val url = try {
            heart.startHere()
        } catch (e: Exception) {
            return RelayResult.Error(e.message ?: "could not start the relay on this PC")
        }
        heartUrl = url
        cached = DesktopSettings(host = HeartHost.THIS_PC)
        store.save(cached)
        log.record(
            ActivityEntryFactory.make(actorId, actorName, "relay_move", true, detail = "Heart now beats on this PC ($url)"),
        )
        return RelayResult.Ok(cached, url)
    }

    /** Use a relay that runs on another device or a VPS. */
    fun setCustomRelay(url: String?, tlsPin: String?): RelayResult {
        val checked = validateRelayUrl(url)
        if (checked != null) return RelayResult.Error(checked)
        val cleanUrl = url!!.trim()
        val pin = tlsPin?.trim()?.takeIf { it.isNotEmpty() }
        if (!heart.useRelay(cleanUrl, pin)) return RelayResult.Error("could not connect to $cleanUrl")
        heart.stopHere()
        heartUrl = null
        cached = DesktopSettings(host = HeartHost.CUSTOM_URL, url = cleanUrl, tlsPin = pin)
        store.save(cached)
        log.record(ActivityEntryFactory.make(actorId, actorName, "relay_host", true, detail = "Heart moved to $cleanUrl"))
        return RelayResult.Ok(cached, cleanUrl)
    }

    /** `relay_move {to:"<url>"}` while this PC hosts: tell every peer to reconnect to [newUrl]. */
    fun handOver(newUrl: String): RelayResult {
        if (cached.host != HeartHost.THIS_PC) return RelayResult.Error("this PC is not running the heart")
        val checked = validateRelayUrl(newUrl, requireTls = true)
        if (checked != null) return RelayResult.Error(checked)
        val cleanUrl = newUrl.trim()
        val notified = try {
            heart.handOver(cleanUrl)
        } catch (e: Exception) {
            return RelayResult.Error(e.message ?: "could not hand the heart over")
        }
        log.record(
            ActivityEntryFactory.make(
                actorId,
                actorName,
                "relay_move",
                true,
                detail = "Handed the heart to $cleanUrl ($notified peers told)",
            ),
        )
        return RelayResult.Ok(cached, cleanUrl)
    }

    fun statusText(): String = when (cached.host) {
        HeartHost.THIS_PC -> heartUrl?.let { "Heart here: $it" } ?: "Heart: this PC (not started)"
        HeartHost.CUSTOM_URL -> "Heart at ${cached.url ?: "not set"}"
    }

    fun heartUrlOrNull(): String? = heartUrl

    private fun startHeartIfNeeded(): String? {
        heartUrl?.let { return it }
        return try {
            heart.startHere().also { heartUrl = it }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ACTOR = "device"

        /** Returns a human-readable error, or null when [url] is usable. */
        fun validateRelayUrl(url: String?, requireTls: Boolean = false): String? {
            if (url.isNullOrBlank()) return "Enter the relay URL (wss://host:port)"
            val uri = try {
                java.net.URI(url.trim())
            } catch (_: Exception) {
                return "That is not a valid URL"
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "ws" && scheme != "wss") return "The relay URL must start with ws:// or wss://"
            if (requireTls && scheme != "wss") return "Use wss:// for a relay on another device"
            val host = uri.host ?: return "The relay URL has no host"
            if (scheme == "ws" && !isLoopback(host)) {
                return "Plain ws:// is only allowed on this PC; use wss:// (with the certificate pin)"
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
