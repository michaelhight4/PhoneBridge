package dev.phonebridge.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityLogTest {

    private var now = 1_700_000_000_000L

    @Test
    fun newestEntriesComeFirstAndPerBotFilteringWorks() {
        val log = InMemoryActivityLog(clock = { now })
        log.record("bot-a", "Alpha", "tap", true)
        now += 1_000L
        log.record("bot-b", "Beta", "screenshot", true)
        now += 1_000L
        log.record("bot-a", "Alpha", "swipe", false, code = "bad_args")

        val all = log.entries()
        assertEquals(listOf("swipe", "screenshot", "tap"), all.map { it.cmd })
        assertEquals(listOf("swipe", "tap"), log.entriesFor("bot-a").map { it.cmd })
        assertEquals("swipe — failed (bad_args)", all.first().summary())
    }

    @Test
    fun theInMemoryLogIsCapped() {
        val log = InMemoryActivityLog(maxEntries = 3, clock = { now })
        repeat(5) { index ->
            now += 1_000L
            log.record("bot", "Bot", "cmd$index", true)
        }
        assertEquals(3, log.count())
        assertEquals(listOf("cmd4", "cmd3", "cmd2"), log.entries().map { it.cmd })
    }

    @Test
    fun theJsonFileLogSurvivesARestart() {
        val dir = Files.createTempDirectory("pb-log-test")
        try {
            val file = dir.resolve("activity.v1.json")
            val first = JsonFileActivityLog(file, clock = { now })
            first.record("bot-a", "Alpha", "tap", true, detail = "(1,2)")
            now += 5_000L
            first.record("bot-b", "Beta", "open_app", false, code = "bad_args", message = "no such app")

            val second = JsonFileActivityLog(file, clock = { now })
            assertEquals(2, second.count())
            assertEquals(listOf("open_app", "tap"), second.entries().map { it.cmd })
            assertEquals("(1,2)", second.entries().last().detail)
            assertEquals("bad_args", second.entries().first().code)

            second.clear()
            assertTrue(JsonFileActivityLog(file).entries().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
