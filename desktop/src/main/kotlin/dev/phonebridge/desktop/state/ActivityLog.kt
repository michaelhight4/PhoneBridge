package dev.phonebridge.desktop.state

import dev.phonebridge.core.Version
import dev.phonebridge.core.util.PbJsonPretty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** One line of the activity log: who ran what, when, and whether it worked. */
@Serializable
data class ActivityEntry(
    val time: Long,
    @SerialName("bot_id")
    val botId: String,
    @SerialName("bot_name")
    val botName: String,
    val cmd: String,
    val ok: Boolean,
    val code: String? = null,
    val detail: String? = null,
) {
    fun line(): String {
        val stamp = java.time.Instant.ofEpochMilli(time)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val status = if (ok) "ok" else "failed${code?.let { " ($it)" } ?: ""}"
        val extra = detail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        return "$stamp  ${botName.take(24)}  $cmd  $status$extra"
    }
}

/** Newest-first log with a hard entry cap. */
interface ActivityLog {
    fun record(entry: ActivityEntry)

    fun recent(limit: Int = 200, botId: String? = null): List<ActivityEntry>

    fun clear()

    fun size(): Int
}

class InMemoryActivityLog(private val cap: Int = DEFAULT_CAP) : ActivityLog {
    private val entries = ArrayDeque<ActivityEntry>()
    private val lock = Any()

    init {
        require(cap > 0) { "cap must be positive" }
    }

    override fun record(entry: ActivityEntry) {
        synchronized(lock) {
            entries.addFirst(entry)
            while (entries.size > cap) entries.removeLast()
        }
    }

    override fun recent(limit: Int, botId: String?): List<ActivityEntry> = synchronized(lock) {
        val filtered = if (botId == null) entries.asSequence() else entries.asSequence().filter { it.botId == botId }
        filtered.take(limit.coerceAtLeast(0)).toList()
    }

    override fun clear() {
        synchronized(lock) { entries.clear() }
    }

    override fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_CAP = 1000
    }
}

@Serializable
private data class ActivityFile(
    val v: Int = Version.PROTOCOL,
    val entries: List<ActivityEntry> = emptyList(),
)

/**
 * Activity log persisted as `activity.v1.json`, newest first, rewritten atomically so a crash cannot
 * leave a half-written file. A corrupt file is moved aside instead of blocking the app.
 */
class JsonFileActivityLog(
    private val file: Path,
    private val cap: Int = InMemoryActivityLog.DEFAULT_CAP,
) : ActivityLog {
    private val delegate = InMemoryActivityLog(cap)
    private val lock = Any()

    init {
        load()
    }

    constructor(file: java.io.File, cap: Int = InMemoryActivityLog.DEFAULT_CAP) : this(file.toPath(), cap)

    private fun load() {
        if (!Files.exists(file)) return
        val text = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull() ?: return
        val parsed = runCatching { PbJsonPretty.decodeFromString(ActivityFile.serializer(), text) }.getOrNull()
        if (parsed == null) {
            runCatching { Files.move(file, file.resolveSibling(file.fileName.toString() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING) }
            return
        }
        // The file is newest-first; the in-memory log wants the same order, so feed it backwards.
        for (entry in parsed.entries.asReversed()) delegate.record(entry)
    }

    override fun record(entry: ActivityEntry) {
        synchronized(lock) {
            delegate.record(entry)
            save()
        }
    }

    override fun recent(limit: Int, botId: String?): List<ActivityEntry> = delegate.recent(limit, botId)

    override fun clear() {
        synchronized(lock) {
            delegate.clear()
            save()
        }
    }

    override fun size(): Int = delegate.size()

    private fun save() {
        val entries = delegate.recent(cap)
        val json = PbJsonPretty.encodeToString(ActivityFile.serializer(), ActivityFile(entries = entries))
        val parent = file.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(temp, json.toByteArray(Charsets.UTF_8))
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * A short, payload-free description of a command for the activity log: how much was typed, which path
 * was touched, which keys were pressed. Typed text, shell command bodies and file contents are never
 * copied into the log.
 */
object ActivityEntryFactory {
    fun make(
        botId: String,
        botName: String,
        cmd: String,
        ok: Boolean,
        code: String? = null,
        detail: String? = null,
        clock: () -> Long = System::currentTimeMillis,
    ): ActivityEntry = ActivityEntry(clock(), botId, botName, cmd, ok, code, detail)
}

object ActivityDetails {
    fun describe(cmd: String, args: JsonObject): String? {
        fun text(name: String): String? = (args[name] as? JsonPrimitive)?.content
        fun number(name: String): String? = (args[name] as? JsonPrimitive)?.content
        return when (cmd) {
            "type" -> "text (${text("text")?.length ?: 0} chars)"
            "shell" -> "command (${text("command")?.length ?: 0} chars)"
            "files" -> {
                val op = text("op") ?: "?"
                val path = text("path") ?: "?"
                val bytes = text("data_b64")?.let { " · ${it.length * 3 / 4} bytes" } ?: ""
                "$op $path$bytes"
            }

            "key_combo" -> "keys (${(args["keys"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0})"
            "key" -> text("key")?.let { "key $it" }
            "tap" -> pair(number("x"), number("y"))?.let { "tap $it" }
            "long_press" -> pair(number("x"), number("y"))?.let { "long_press $it" }
            "swipe" -> pair(number("x1"), number("y1"))?.let { from ->
                pair(number("x2"), number("y2"))?.let { to -> "swipe $from -> $to" }
            }

            "drag" -> pair(number("x1"), number("y1"))?.let { from ->
                pair(number("x2"), number("y2"))?.let { to -> "drag $from -> $to" }
            }

            "move" -> pair(number("x"), number("y"))?.let { "move $it" }
            "scroll" -> "dx=${number("dx") ?: 0} dy=${number("dy") ?: 0}"
            "screenshot" -> "format=${text("format") ?: "png"}"
            "screen_stream" -> "frames=${number("frames") ?: "?"} fps=${number("fps") ?: "?"}"
            "install" -> text("id")?.let { "winget $it" }
                ?: text("path")?.let { "installer $it" }
                ?: text("url")?.let { url -> "download ${runCatching { java.net.URI(url).host }.getOrNull() ?: "url"}" }

            "relay_move" -> text("to")?.let { "to $it" }
            "open_app" -> text("package") ?: text("label")
            else -> null
        }
    }

    private fun pair(x: String?, y: String?): String? = if (x != null && y != null) "$x,$y" else null
}
