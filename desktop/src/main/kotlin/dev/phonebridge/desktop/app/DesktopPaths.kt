package dev.phonebridge.desktop.app

import java.nio.file.Files
import java.nio.file.Path

/** Where the desktop agent keeps its identity, bot list, settings and log. */
object DesktopPaths {
    /** `%APPDATA%\PhoneBridge` on Windows, `~/.phonebridge` elsewhere. */
    fun defaultDataDir(): Path {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
        val base = if (appData != null) Path.of(appData) else Path.of(System.getProperty("user.home", "."), ".phonebridge")
        return base.resolve("PhoneBridge")
    }

    fun prepare(dir: Path): Path {
        Files.createDirectories(dir)
        return dir
    }

    fun identityFile(dir: Path): Path = dir.resolve("identity.v1.json")

    fun botsFile(dir: Path): Path = dir.resolve("bots.v1.json")

    fun settingsFile(dir: Path): Path = dir.resolve("desktop.v1.json")

    fun activityFile(dir: Path): Path = dir.resolve("activity.v1.json")

    fun relayDir(dir: Path): Path = dir.resolve("relay")

    fun moveSecretFile(dir: Path): Path = dir.resolve("move-secret.txt")
}

/** Best-effort LAN address to advertise in pairing offers, so other devices can reach the heart. */
object LocalNetwork {
    fun primaryIPv4(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .firstOrNull()
    } catch (_: Exception) {
        null
    }
}
