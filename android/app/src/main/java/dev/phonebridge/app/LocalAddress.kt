package dev.phonebridge.app

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort LAN address for the embedded relay's advertised URL. A bot on another device needs an
 * address it can reach, so 127.0.0.1 is only the fallback when no interface is up.
 */
object LocalAddress {

    fun ipv4(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
                ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
