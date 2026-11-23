package dev.phonebridge.app

import android.content.Context
import android.os.Build
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.pairing.JsonFileBotRegistry
import dev.phonebridge.ui.JsonFileActivityLog
import dev.phonebridge.ui.RelaySettingsStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk locations for the app's small JSON stores. Everything lives in the app's private files
 * directory: no shared storage, no cloud backup of keys.
 *
 * - `identity.v1.json` — this phone's X25519 key pair (devices never share it).
 * - `bots.v1.json` — paired bots (`:core` `JsonFileBotRegistry`).
 * - `activity.v1.json` — the per-bot command log.
 * - `relay.v1.json` — where the heart beats.
 * - `relay/` — the embedded relay's certificate and identity.
 */
object AppStores {

    fun identityFile(context: Context): File = File(context.filesDir, "identity.v1.json")

    fun registryFile(context: Context): File = File(context.filesDir, "bots.v1.json")

    fun activityFile(context: Context): File = File(context.filesDir, "activity.v1.json")

    fun relaySettingsFile(context: Context): File = File(context.filesDir, "relay.v1.json")

    fun relayDataDir(context: Context): File = File(context.filesDir, "relay").apply { mkdirs() }

    /** A friendly device name shown to bots, e.g. `Pixel 8`. */
    fun deviceName(): String = (Build.MODEL ?: "Android").trim().ifBlank { "Android" }

    /** Loads the device identity, generating one on first run. */
    fun identity(context: Context): Identity {
        val file = identityFile(context)
        if (file.exists()) {
            try {
                return Identity.fromJson(file.readText())
            } catch (_: Exception) {
                // The key material is unusable. Keep the corrupt copy for inspection, then start over;
                // every bot has to be paired again, which is the safe outcome.
                val broken = File(file.parentFile, "identity.corrupt.${System.currentTimeMillis()}.json")
                runCatching { Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        val identity = Identity.generate(PeerRole.DEVICE, deviceName())
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(identity.toJson())
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return identity
    }

    fun registry(context: Context): JsonFileBotRegistry = JsonFileBotRegistry(registryFile(context))

    fun activityLog(context: Context): JsonFileActivityLog = JsonFileActivityLog(activityFile(context))

    fun relaySettings(context: Context): RelaySettingsStore = RelaySettingsStore(relaySettingsFile(context))
}
