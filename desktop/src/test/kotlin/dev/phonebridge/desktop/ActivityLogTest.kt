package dev.phonebridge.desktop

import dev.phonebridge.desktop.state.ActivityDetails
import dev.phonebridge.desktop.state.ActivityEntry
import dev.phonebridge.desktop.state.InMemoryActivityLog
import dev.phonebridge.desktop.state.JsonFileActivityLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityLogTest {

    private fun entry(time: Long, bot: String = "bot1", cmd: String = "status", ok: Boolean = true) =
        ActivityEntry(time, bot, "Claude", cmd, ok)

    @Test
    fun keepsNewestFirstAndCapsTheLog() {
        val log = InMemoryActivityLog(cap = 3)
        for (index in 1..5) log.record(entry(index.toLong()))
        val recent = log.recent(10)
        assertEquals(listOf(5L, 4L, 3L), recent.map { it.time })
        assertEquals(3, log.size())
    }

    @Test
    fun filtersByBotAndLimit() {
        val log = InMemoryActivityLog()
        log.record(entry(1, bot = "a"))
        log.record(entry(2, bot = "b"))
        log.record(entry(3, bot = "a"))
        assertEquals(2, log.recent(10, "a").size)
        assertEquals(1, log.recent(1, "a").size)
        assertEquals(3, log.recent(10).size)
        assertEquals(0, log.recent(10, "missing").size)
    }

    @Test
    fun jsonFilePersistsAcrossInstances() {
        val file = Files.createTempDirectory("pb-log").resolve("activity.v1.json")
        val first = JsonFileActivityLog(file)
        first.record(entry(10, cmd = "tap"))
        first.record(entry(20, cmd = "screenshot", ok = false))

        val second = JsonFileActivityLog(file)
        val entries = second.recent(10)
        assertEquals(listOf(20L, 10L), entries.map { it.time })
        assertEquals("screenshot", entries.first().cmd)
        assertFalse(entries.first().ok)

        second.clear()
        assertTrue(JsonFileActivityLog(file).recent(10).isEmpty())
    }

    @Test
    fun aCorruptFileIsMovedAsideInsteadOfBlockingStartup() {
        val file = Files.createTempDirectory("pb-log").resolve("activity.v1.json")
        Files.writeString(file, "{not json")
        val log = JsonFileActivityLog(file)
        assertTrue(log.recent(10).isEmpty())
        assertTrue(Files.exists(file.resolveSibling("activity.v1.json.corrupt")))
    }

    @Test
    fun linesCarryTimeBotCommandAndOutcome() {
        val line = entry(1_700_000_000_000, cmd = "tap", ok = false).copy(code = "not_controller").line()
        assertTrue(line.contains("tap"))
        assertTrue(line.contains("Claude"))
        assertTrue(line.contains("not_controller"))
    }

    @Test
    fun detailsNeverCopyTypedTextOrCommandBodies() {
        val typed = ActivityDetails.describe("type", argsOf("text" to "my secret password"))
        assertEquals("text (18 chars)", typed)

        val shell = ActivityDetails.describe("shell", argsOf("command" to "reg export HKLM\\SAM sam.hiv"))
        assertEquals("command (27 chars)", shell)

        val files = ActivityDetails.describe(
            "files",
            argsOf("op" to "write", "path" to "C:\\x.txt", "data_b64" to "AAAA"),
        )
        assertTrue(files!!.contains("write C:\\x.txt"))
        assertFalse(files.contains("AAAA"), "file bytes must not be logged")

        assertEquals("keys (3)", ActivityDetails.describe("key_combo", keysArgs("ctrl", "shift", "s")))
        val install = ActivityDetails.describe("install", argsOf("url" to "https://example.test/app.exe"))
        assertEquals("download example.test", install)
        assertTrue(ActivityDetails.describe("relay_move", argsOf("to" to "this"))!!.contains("this"))
    }

    private fun argsOf(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) put(key, value)
    }

    private fun keysArgs(vararg keys: String): JsonObject = buildJsonObject {
        put("keys", kotlinx.serialization.json.JsonArray(keys.map { JsonPrimitive(it) }))
    }
}
