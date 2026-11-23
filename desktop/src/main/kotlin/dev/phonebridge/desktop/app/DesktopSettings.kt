package dev.phonebridge.desktop.app

import dev.phonebridge.core.Version
import dev.phonebridge.core.util.PbJsonPretty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Where the heart beats for this PC. */
@Serializable
enum class HeartHost {
    @SerialName("this_pc")
    THIS_PC,

    @SerialName("custom_url")
    CUSTOM_URL,
}

/** User choices that must survive a restart. */
@Serializable
data class DesktopSettings(
    val host: HeartHost = HeartHost.THIS_PC,
    /** Relay URL for [HeartHost.CUSTOM_URL]. */
    val url: String? = null,
    /** Pinned certificate fingerprint (base64url) for a self-signed relay. */
    @SerialName("tls_pin")
    val tlsPin: String? = null,
)

@Serializable
private data class DesktopSettingsFile(
    val v: Int = Version.PROTOCOL,
    val settings: DesktopSettings = DesktopSettings(),
)

/** Reads and writes [DesktopSettings] atomically (`desktop.v1.json` by convention). */
class DesktopSettingsStore(private val file: Path) {
    constructor(file: java.io.File) : this(file.toPath())

    fun load(): DesktopSettings {
        if (!Files.exists(file)) return DesktopSettings()
        return try {
            val text = String(Files.readAllBytes(file), Charsets.UTF_8)
            PbJsonPretty.decodeFromString(DesktopSettingsFile.serializer(), text).settings
        } catch (_: Exception) {
            DesktopSettings()
        }
    }

    fun save(settings: DesktopSettings): DesktopSettings {
        val parent = file.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(
            temp,
            PbJsonPretty.encodeToString(DesktopSettingsFile.serializer(), DesktopSettingsFile(settings = settings))
                .toByteArray(Charsets.UTF_8),
        )
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        return settings
    }

    val path: Path
        get() = file
}
