package dev.phonebridge.ui

import dev.phonebridge.core.Version
import dev.phonebridge.core.util.AtomicFiles
import dev.phonebridge.core.util.PbJsonPretty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** One line in the per-bot activity log: which bot, which command, when, and how it ended. */
@Serializable
data class ActivityEntry(
    val id: String = "",
    @SerialName("bot_id")
    val botId: String,
    @SerialName("bot_name")
    val botName: String,
    val cmd: String,
    val ok: Boolean,
    /** Protocol error code (`permission_denied`, `not_controller`, ...) when [ok] is false. */
    val code: String? = null,
    val message: String? = null,
    /** Epoch millis. */
    val at: Long = 0,
    /** Short, human readable detail such as `tap (540,1200)`. */
    val detail: String? = null,
) {
    /** One-line form for the activity log list. */
    fun summary(): String {
        val outcome = if (ok) "ok" else "failed" + (code?.let { " ($it)" } ?: "")
        return "$cmd — $outcome" + (detail?.let { ": $it" } ?: "")
    }
}

/** Storage for the activity log. Newest entries come first from [entries]. */
interface ActivityLog {
    fun record(
        botId: String,
        botName: String,
        cmd: String,
        ok: Boolean,
        code: String? = null,
        message: String? = null,
        detail: String? = null,
    ): ActivityEntry

    fun record(entry: ActivityEntry): ActivityEntry

    fun entries(limit: Int = DEFAULT_LIMIT): List<ActivityEntry>

    fun entriesFor(botId: String, limit: Int = DEFAULT_LIMIT): List<ActivityEntry>

    fun clear()

    fun count(): Int

    /** Re-reads the backing file; a no-op for the in-memory log. */
    fun reload() = Unit

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}

/** Process-lifetime log, used by tests and by the service when no file is available yet. */
open class InMemoryActivityLog(
    protected val maxEntries: Int = 1000,
    protected val clock: () -> Long = System::currentTimeMillis,
) : ActivityLog {

    private val lock = Any()
    protected val entries = ArrayList<ActivityEntry>()

    override fun record(
        botId: String,
        botName: String,
        cmd: String,
        ok: Boolean,
        code: String?,
        message: String?,
        detail: String?,
    ): ActivityEntry = record(
        ActivityEntry(
            id = UUID.randomUUID().toString(),
            botId = botId,
            botName = botName,
            cmd = cmd,
            ok = ok,
            code = code,
            message = message,
            at = clock(),
            detail = detail,
        ),
    )

    override fun record(entry: ActivityEntry): ActivityEntry {
        val stored = if (entry.id.isBlank()) entry.copy(id = UUID.randomUUID().toString()) else entry
        synchronized(lock) {
            entries.add(stored)
            while (entries.size > maxEntries) entries.removeAt(0)
        }
        return stored
    }

    override fun entries(limit: Int): List<ActivityEntry> = synchronized(lock) {
        entries.asReversed().take(limit.coerceAtLeast(0))
    }

    override fun entriesFor(botId: String, limit: Int): List<ActivityEntry> = synchronized(lock) {
        entries.asReversed().filter { it.botId == botId }.take(limit.coerceAtLeast(0))
    }

    override fun clear() {
        synchronized(lock) { entries.clear() }
    }

    override fun count(): Int = synchronized(lock) { entries.size }

    /** Snapshot in oldest-first order, for the file writer. */
    protected fun snapshot(): List<ActivityEntry> = synchronized(lock) { entries.toList() }

    /** Replaces every entry (used when the file is re-read). */
    protected fun replaceAll(fresh: List<ActivityEntry>) {
        synchronized(lock) {
            entries.clear()
            entries.addAll(fresh.takeLast(maxEntries))
        }
    }
}

/** On-disk shape of the activity log; versioned so a future migration is possible. */
@Serializable
data class ActivityLogFile(
    val v: Int = Version.PROTOCOL,
    val entries: List<ActivityEntry> = emptyList(),
)

/**
 * Activity log persisted as a JSON file (`activity.v1.json` by convention). Every write rewrites it
 * atomically, so a crash cannot leave a half-written log. The file is capped at [maxEntries] lines
 * because a phone should not grow it forever.
 */
class JsonFileActivityLog(
    private val file: Path,
    maxEntries: Int = 1000,
    clock: () -> Long = System::currentTimeMillis,
) : InMemoryActivityLog(maxEntries, clock) {

    constructor(file: java.io.File, maxEntries: Int = 1000, clock: () -> Long = System::currentTimeMillis) :
        this(file.toPath(), maxEntries, clock)

    init {
        load()
    }

    val path: Path
        get() = file

    override fun record(entry: ActivityEntry): ActivityEntry {
        val stored = super.record(entry)
        save()
        return stored
    }

    override fun clear() {
        super.clear()
        save()
    }

    override fun reload() {
        replaceAll(emptyList())
        load()
    }

    private fun load() {
        if (!Files.exists(file)) return
        val text = try {
            // Files.readString is Java 11+ (Android 13); read bytes so this runs on API 26.
            String(Files.readAllBytes(file), Charsets.UTF_8)
        } catch (e: IOException) {
            throw IllegalStateException("cannot read the activity log at $file", e)
        }
        val parsed = try {
            PbJsonPretty.decodeFromString(ActivityLogFile.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("corrupt activity log at $file", e)
        }
        replaceAll(parsed.entries)
    }

    private fun save() {
        val json = PbJsonPretty.encodeToString(
            ActivityLogFile.serializer(),
            ActivityLogFile(entries = snapshot()),
        )
        val parent = file.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        // Files.writeString is Java 11+ (Android 13); write bytes so this runs on API 26.
        Files.write(temp, json.toByteArray(Charsets.UTF_8))
        AtomicFiles.replace(temp, file)
    }
}
