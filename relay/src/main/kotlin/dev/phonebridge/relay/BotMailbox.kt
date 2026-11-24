package dev.phonebridge.relay

import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.identity.PeerIds
import dev.phonebridge.core.model.RelayBotMessage
import dev.phonebridge.core.model.RelayPeerEntry
import dev.phonebridge.core.util.AtomicFiles
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.core.util.PbJsonPretty
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Relay-side state for bot-to-bot messaging: the per-device paired-bot ACLs a device publishes, and
 * the per-bot store-and-forward mailboxes.
 *
 * Both stores are written atomically under [dataDir] so a relay restart does not lose queued messages
 * or forget which bots the user allowed to message.
 */
internal class BotMailbox(
    private val dataDir: String? = null,
    private val maxBotMessageBytes: Int = 64 * 1024,
    private val maxMailboxMessagesPerBot: Int = 100,
    private val maxMailboxBytesPerBot: Int = 1024 * 1024,
    private val maxTotalMailboxMessages: Int = 5000,
    private val botMessageTtlMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class PeersResult {
        data class Allowed(val peers: List<RelayPeerEntry>) : PeersResult()
        data class Denied(val reason: String) : PeersResult()
    }

    class MailboxFullException(message: String) : Exception(message)

    private data class DevicePeers(
        val deviceId: String,
        val deviceName: String?,
        val bots: List<RelayPeerEntry>,
    )

    private val lock = Any()
    private val devices = LinkedHashMap<String, DevicePeers>()
    private val mailboxes = LinkedHashMap<String, MutableList<RelayBotMessage>>()

    private val peersFile: Path? get() = dataDir?.let { Paths.get(it, PEERS_FILE) }
    private val mailboxesFile: Path? get() = dataDir?.let { Paths.get(it, MAILBOXES_FILE) }

    init {
        load()
    }

    /** Replaces the peer list published by one device and persists it. */
    fun syncDevicePeers(deviceId: String, deviceName: String?, peers: List<RelayPeerEntry>): Int =
        synchronized(lock) {
            val normalised = peers
                .filter { it.peerId.isNotBlank() && it.publicKey.isNotBlank() }
                .map { entry ->
                    val key = runCatching { B64Url.decode(entry.publicKey) }.getOrNull()
                    val fingerprint = entry.fingerprint ?: key?.let { PeerIds.fingerprint(it) }
                    entry.copy(
                        fingerprint = fingerprint,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        online = null,
                    )
                }
                .distinctBy { it.peerId }
            devices[deviceId] = DevicePeers(deviceId, deviceName, normalised)
            persistPeers()
            normalised.size
        }

    /**
     * Lists the other bots [botId] is allowed to message. The asking bot must itself be paired, enabled
     * and messaging-enabled on at least one matching device, otherwise access is denied.
     */
    fun listPeersForBot(
        botId: String,
        deviceId: String? = null,
        isOnline: (String) -> Boolean = { false },
    ): PeersResult = synchronized(lock) {
        val allowedDevices = devices.values.filter { device ->
            (deviceId == null || device.deviceId == deviceId) && device.bots.any { canMessage(it, botId) }
        }
        if (allowedDevices.isEmpty()) {
            return@synchronized PeersResult.Denied("bot $botId is not paired with messaging enabled")
        }
        val peers = allowedDevices
            .flatMap { device ->
                device.bots
                    .filter { it.peerId != botId && it.enabled }
                    .map { entry ->
                        entry.copy(
                            fingerprint = entry.fingerprint,
                            deviceId = device.deviceId,
                            deviceName = device.deviceName,
                            online = isOnline(entry.peerId),
                        )
                    }
            }
            .distinctBy { it.peerId }
        PeersResult.Allowed(peers)
    }

    /** True when one synced device has paired, enabled and messaging-enabled both ends of a message. */
    fun authorizeSend(fromBotId: String, toBotId: String, deviceId: String? = null): Boolean = synchronized(lock) {
        if (fromBotId == toBotId) return@synchronized false
        devices.values.any { device ->
            (deviceId == null || device.deviceId == deviceId) &&
                device.bots.any { canMessage(it, fromBotId) } &&
                device.bots.any { canMessage(it, toBotId) }
        }
    }

    /** Stores a message in the recipient's mailbox, with caps and TTL applied. */
    fun enqueue(message: RelayBotMessage): RelayBotMessage = synchronized(lock) {
        purgeExpiredLocked()
        val payloadBytes = try {
            B64Url.decode(message.payload).size
        } catch (_: IllegalArgumentException) {
            throw ProtocolException("bot message payload is not base64url")
        }
        if (payloadBytes > maxBotMessageBytes) {
            throw MailboxFullException("bot message is larger than $maxBotMessageBytes bytes")
        }
        val now = clock()
        val stored = message.copy(
            createdAt = if (message.createdAt > 0L) message.createdAt else now,
            expiresAt = message.expiresAt ?: (now + botMessageTtlMs),
        )
        val box = mailboxes.getOrPut(stored.to) { mutableListOf() }
        val bytes = box.sumOf { runCatching { B64Url.decode(it.payload).size }.getOrDefault(it.payload.length) }
        if (box.size >= maxMailboxMessagesPerBot) {
            throw MailboxFullException("the mailbox for bot ${stored.to} is full")
        }
        if (bytes + payloadBytes > maxMailboxBytesPerBot) {
            throw MailboxFullException("the mailbox for bot ${stored.to} is out of space")
        }
        if (totalMessagesLocked() >= maxTotalMailboxMessages) {
            throw MailboxFullException("the bot message mailbox is full")
        }
        box += stored
        persistMailboxes()
        stored
    }

    /**
     * Returns up to [limit] waiting messages, oldest first. [ackIds] are removed first; with
     * [consume] the returned messages are removed as well.
     */
    fun readInbox(
        botId: String,
        limit: Int,
        consume: Boolean,
        ackIds: List<String> = emptyList(),
    ): List<RelayBotMessage> = synchronized(lock) {
        var changed = purgeExpiredLocked()
        val box = mailboxes[botId] ?: mutableListOf()
        if (ackIds.isNotEmpty()) {
            val before = box.size
            box.removeAll { it.id in ackIds }
            changed = changed || box.size != before
        }
        val selected = box.sortedBy { it.createdAt }.take(limit.coerceAtLeast(0))
        if (consume && selected.isNotEmpty()) {
            val ids = selected.map { it.id }.toSet()
            box.removeAll { it.id in ids }
            changed = true
        }
        if (box.isEmpty()) mailboxes.remove(botId)
        if (changed) persistMailboxes()
        selected
    }

    /** Removes acknowledged messages and returns the ids that were actually pending, in request order. */
    fun ack(botId: String, ids: List<String>): List<String> = synchronized(lock) {
        purgeExpiredLocked()
        val box = mailboxes[botId] ?: return@synchronized emptyList()
        val pending = box.map { it.id }.toSet()
        val removed = ids.filter { it in pending }.distinct()
        if (removed.isNotEmpty()) {
            val removedSet = removed.toSet()
            box.removeAll { it.id in removedSet }
            if (box.isEmpty()) mailboxes.remove(botId)
            persistMailboxes()
        }
        removed
    }

    fun totalMessages(): Int = synchronized(lock) {
        if (purgeExpiredLocked()) persistMailboxes()
        totalMessagesLocked()
    }

    fun clear() = synchronized(lock) {
        devices.clear()
        mailboxes.clear()
        persistPeers()
        persistMailboxes()
    }

    private fun canMessage(entry: RelayPeerEntry, botId: String): Boolean =
        entry.peerId == botId && entry.enabled && entry.canMessage

    private fun totalMessagesLocked(): Int = mailboxes.values.sumOf { it.size }

    /** Drops expired messages; true when something changed. Call only while holding [lock]. */
    private fun purgeExpiredLocked(): Boolean {
        val now = clock()
        var changed = false
        val iterator = mailboxes.values.iterator()
        while (iterator.hasNext()) {
            val box = iterator.next()
            val before = box.size
            box.removeAll { (it.expiresAt ?: Long.MAX_VALUE) <= now }
            if (box.isEmpty()) iterator.remove()
            changed = changed || box.size != before
        }
        return changed
    }

    private fun persistPeers() {
        val file = peersFile ?: return
        val json = PbJsonPretty.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("v", 1)
                putJsonArray("devices") {
                    for (device in devices.values) {
                        add(
                            buildJsonObject {
                                put("device_id", device.deviceId)
                                device.deviceName?.let { put("device_name", it) }
                                putJsonArray("bots") {
                                    for (bot in device.bots) {
                                        add(PbJson.encodeToJsonElement(RelayPeerEntry.serializer(), bot))
                                    }
                                }
                            },
                        )
                    }
                }
            },
        )
        replaceAtomically(file, json)
    }

    private fun persistMailboxes() {
        val file = mailboxesFile ?: return
        val json = PbJsonPretty.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("v", 1)
                putJsonArray("mailboxes") {
                    for ((botId, box) in mailboxes) {
                        if (box.isEmpty()) continue
                        add(
                            buildJsonObject {
                                put("bot_id", botId)
                                putJsonArray("messages") {
                                    for (message in box) {
                                        add(PbJson.encodeToJsonElement(RelayBotMessage.serializer(), message))
                                    }
                                }
                            },
                        )
                    }
                }
            },
        )
        replaceAtomically(file, json)
    }

    private fun replaceAtomically(file: Path, json: String) {
        val parent = file.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(temp, json.toByteArray(Charsets.UTF_8))
        AtomicFiles.replace(temp, file)
    }

    private fun load() {
        loadPeers()
        loadMailboxes()
        if (purgeExpiredLocked()) persistMailboxes()
    }

    private fun loadPeers() {
        val file = peersFile
        if (file == null || !Files.exists(file)) return
        val root = readObject(file, PEERS_FILE)
        val array: JsonArray = root["devices"]?.jsonArray ?: JsonArray(emptyList())
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val deviceId = obj["device_id"]?.jsonPrimitive?.content ?: continue
            val deviceName = obj["device_name"]?.jsonPrimitive?.content
            val bots: List<RelayPeerEntry> = (obj["bots"]?.jsonArray ?: JsonArray(emptyList()))
                .mapNotNull { decode(it, RelayPeerEntry.serializer()) }
            devices[deviceId] = DevicePeers(deviceId, deviceName, bots)
        }
    }

    private fun loadMailboxes() {
        val file = mailboxesFile
        if (file == null || !Files.exists(file)) return
        val root = readObject(file, MAILBOXES_FILE)
        val array: JsonArray = root["mailboxes"]?.jsonArray ?: JsonArray(emptyList())
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val botId = obj["bot_id"]?.jsonPrimitive?.content ?: continue
            val messages: MutableList<RelayBotMessage> =
                (obj["messages"]?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { decode(it, RelayBotMessage.serializer()) }
                    .toMutableList()
            if (messages.isNotEmpty()) mailboxes[botId] = messages
        }
    }

    private fun readObject(file: Path, what: String): JsonObject {
        val text = String(Files.readAllBytes(file), Charsets.UTF_8)
        return try {
            PbJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProtocolException("corrupt bot $what at $file", e)
        }
    }

    private fun <T> decode(element: JsonElement, serializer: KSerializer<T>): T? =
        runCatching { PbJson.decodeFromJsonElement(serializer, element) }.getOrNull()

    companion object {
        const val PEERS_FILE = "bot-peers.v1.json"
        const val MAILBOXES_FILE = "bot-mailboxes.v1.json"
    }
}
