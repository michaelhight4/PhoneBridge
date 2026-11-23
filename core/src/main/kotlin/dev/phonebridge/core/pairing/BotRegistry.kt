package dev.phonebridge.core.pairing

import dev.phonebridge.core.PhoneBridgeException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.Version
import dev.phonebridge.core.identity.PeerIds
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJsonPretty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One paired bot, as shown in the device's Bluetooth-style list: name, key, last seen, on/off switch,
 * permission level. Unpairing one bot never touches the others.
 */
@Serializable
data class BotRecord(
    @SerialName("bot_id")
    val botId: String,
    val name: String,
    /** X25519 public key, base64url; also the identity pinned for the Noise handshake. */
    @SerialName("public_key")
    val publicKey: String,
    val level: PermissionLevel = PermissionLevel.CONTROL,
    /** The user's on/off switch for this bot; a disabled bot cannot start a session. */
    val enabled: Boolean = true,
    @SerialName("paired_at")
    val pairedAt: Long = 0,
    @SerialName("last_seen")
    val lastSeen: Long = 0,
) {
    val publicKeyBytes: ByteArray
        get() = B64Url.decode(publicKey)

    val fingerprint: String
        get() = PeerIds.fingerprint(publicKeyBytes)

    /** Throws when the record is internally inconsistent (used when loading a registry file). */
    fun validate() {
        if (publicKeyBytes.size != 32) throw ProtocolException("bot $botId has a malformed public key")
        if (PeerIds.peerId(publicKeyBytes) != botId) throw ProtocolException("bot $botId does not match its public key")
        if (name.isBlank()) throw ProtocolException("bot $botId has no name")
    }
}

/** Storage for paired bots. Implementations must be safe to call from any thread. */
interface BotRegistry {
    fun list(): List<BotRecord>

    fun get(botId: String): BotRecord?

    /** Inserts or replaces a bot. */
    fun put(record: BotRecord)

    /** Removes a bot; true when it existed. */
    fun remove(botId: String): Boolean

    fun setEnabled(botId: String, enabled: Boolean): Boolean =
        get(botId)?.let { put(it.copy(enabled = enabled)); true } ?: false

    fun setLevel(botId: String, level: PermissionLevel): Boolean =
        get(botId)?.let { put(it.copy(level = level)); true } ?: false

    fun setName(botId: String, name: String): Boolean =
        get(botId)?.let { put(it.copy(name = name)); true } ?: false

    /** Records that the bot was seen at [at] (epoch millis). */
    fun touch(botId: String, at: Long): Boolean =
        get(botId)?.let { put(it.copy(lastSeen = at)); true } ?: false

    /** True when the bot is paired and its on/off switch is on. */
    fun isEnabled(botId: String): Boolean = get(botId)?.enabled == true
}

/** Process-lifetime registry, used by tests and by the bot-side tools. */
class InMemoryBotRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
) : BotRegistry {
    private val bots = LinkedHashMap<String, BotRecord>()
    private val lock = Any()

    override fun list(): List<BotRecord> = synchronized(lock) {
        bots.values.sortedBy { it.pairedAt }.toList()
    }

    override fun get(botId: String): BotRecord? = synchronized(lock) { bots[botId] }

    override fun put(record: BotRecord) {
        record.validate()
        synchronized(lock) { bots[record.botId] = record }
    }

    override fun remove(botId: String): Boolean = synchronized(lock) { bots.remove(botId) != null }

    fun count(): Int = synchronized(lock) { bots.size }

    fun lastSeen(botId: String): Long? = synchronized(lock) { bots[botId]?.lastSeen }

    fun now(): Long = clock()
}

/** On-disk shape of the registry; a version field so future migrations are possible. */
@Serializable
data class BotRegistryFile(
    val v: Int = Version.PROTOCOL,
    val bots: List<BotRecord> = emptyList(),
)

/**
 * Registry persisted as a JSON file (`bots.v1.json` by convention). Every mutation rewrites the file
 * atomically (temp file + rename) so a crash or a full disk cannot leave a half-written bot list.
 */
class JsonFileBotRegistry(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) : BotRegistry {
    private val lock = Any()
    private val bots = LinkedHashMap<String, BotRecord>()

    init {
        load()
    }

    constructor(file: java.io.File, clock: () -> Long = System::currentTimeMillis) : this(file.toPath(), clock)

    override fun list(): List<BotRecord> = synchronized(lock) {
        bots.values.sortedBy { it.pairedAt }.toList()
    }

    override fun get(botId: String): BotRecord? = synchronized(lock) { bots[botId] }

    override fun put(record: BotRecord) {
        record.validate()
        synchronized(lock) {
            bots[record.botId] = record
            save()
        }
    }

    override fun remove(botId: String): Boolean = synchronized(lock) {
        val existed = bots.remove(botId) != null
        if (existed) save()
        existed
    }

    /** Re-reads the file, e.g. after another process (or the desktop app) wrote it. */
    fun reload() = synchronized(lock) {
        bots.clear()
        load()
    }

    val path: Path
        get() = file

    private fun load() {
        if (!Files.exists(file)) return
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            throw PhoneBridgeException("cannot read bot registry at $file", e)
        }
        val parsed = try {
            PbJsonPretty.decodeFromString(BotRegistryFile.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw PhoneBridgeException("corrupt bot registry at $file", e)
        }
        for (record in parsed.bots) {
            try {
                record.validate()
            } catch (e: ProtocolException) {
                throw PhoneBridgeException("corrupt bot registry at $file", e)
            }
            bots[record.botId] = record
        }
    }

    private fun save() {
        val json = PbJsonPretty.encodeToString(
            BotRegistryFile.serializer(),
            BotRegistryFile(bots = bots.values.toList()),
        )
        val parent = file.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, json)
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
