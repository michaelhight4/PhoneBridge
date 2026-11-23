package dev.phonebridge.core.pairing

import dev.phonebridge.core.FakeClock
import dev.phonebridge.core.PhoneBridgeException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotRegistryTest {
    private fun record(
        name: String,
        pairedAt: Long = 1_700_000_000_000L,
        level: PermissionLevel = PermissionLevel.CONTROL,
        enabled: Boolean = true,
    ): BotRecord {
        val identity = Identity.generate(PeerRole.BOT, name)
        return BotRecord(
            botId = identity.peerId,
            name = name,
            publicKey = identity.publicKeyBase64,
            level = level,
            enabled = enabled,
            pairedAt = pairedAt,
            lastSeen = pairedAt,
        )
    }

    @Test
    fun inMemoryRegistryBehavesLikeTheBluetoothList() {
        val registry = InMemoryBotRegistry()
        val older = record("older", pairedAt = 100)
        val newer = record("newer", pairedAt = 200)

        registry.put(newer)
        registry.put(older)
        assertEquals(listOf("older", "newer"), registry.list().map { it.name })
        assertEquals(older, registry.get(older.botId))
        assertEquals(2, registry.list().size)

        assertTrue(registry.setEnabled(older.botId, false))
        assertFalse(registry.isEnabled(older.botId))
        assertTrue(registry.isEnabled(newer.botId))
        assertTrue(registry.setLevel(older.botId, PermissionLevel.FULL))
        assertEquals(PermissionLevel.FULL, registry.get(older.botId)?.level)
        assertTrue(registry.setName(older.botId, "renamed"))
        assertEquals("renamed", registry.get(older.botId)?.name)
        assertTrue(registry.touch(older.botId, 999))
        assertEquals(999, registry.get(older.botId)?.lastSeen)
        assertFalse(registry.touch("nope", 1))

        assertTrue(registry.remove(older.botId))
        assertFalse(registry.remove(older.botId))
        assertNull(registry.get(older.botId))
        assertEquals(1, registry.list().size)
    }

    @Test
    fun inconsistentRecordsAreRejected() {
        val good = record("bot")
        assertFailsWith<ProtocolException> { good.copy(botId = "aaaaaaaaaaaaaaaaaaaaaaaaaa").validate() }
        assertFailsWith<ProtocolException> { good.copy(publicKey = "AAAA").validate() }
        assertFailsWith<ProtocolException> { good.copy(name = "  ").validate() }
        assertFailsWith<ProtocolException> {
            InMemoryBotRegistry().put(good.copy(botId = "aaaaaaaaaaaaaaaaaaaaaaaaaa"))
        }
    }

    @Test
    fun jsonFileRegistryPersistsAcrossInstances() {
        val dir = Files.createTempDirectory("pb-registry")
        try {
            val file = dir.resolve("bots.v1.json")
            val clock = FakeClock()
            val registry = JsonFileBotRegistry(file, clock)
            val first = record("first", pairedAt = 100)
            val second = record("second", pairedAt = 200)
            registry.put(first)
            registry.put(second)
            registry.setEnabled(second.botId, false)

            assertTrue(Files.exists(file))
            assertTrue(file.readText().contains("\"bots\""))

            val reloaded = JsonFileBotRegistry(file, clock)
            assertEquals(registry.list(), reloaded.list())
            assertFalse(reloaded.isEnabled(second.botId))

            assertTrue(reloaded.remove(first.botId))
            val third = JsonFileBotRegistry(file, clock)
            assertEquals(listOf(second.copy(enabled = false)), third.list())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun replacingABotDoesNotDuplicateIt() {
        val dir = Files.createTempDirectory("pb-registry")
        try {
            val file: Path = dir.resolve("bots.json")
            val registry = JsonFileBotRegistry(file)
            val original = record("bot")
            registry.put(original)
            registry.put(original.copy(name = "renamed", level = PermissionLevel.FULL))
            assertEquals(1, registry.list().size)
            assertEquals("renamed", registry.list().single().name)
            assertEquals(PermissionLevel.FULL, registry.list().single().level)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptRegistryFilesAreReportedNotWiped() {
        val dir = Files.createTempDirectory("pb-registry")
        try {
            val file = dir.resolve("bots.json")
            Files.writeString(file, "{ this is not json")
            assertFailsWith<PhoneBridgeException> { JsonFileBotRegistry(file) }
            assertTrue(file.readText().contains("not json"))

            val mismatched = dir.resolve("mismatched.json")
            val bogus = record("bot").copy(botId = "aaaaaaaaaaaaaaaaaaaaaaaaaa")
            Files.writeString(
                mismatched,
                """{"v":1,"bots":[${dev.phonebridge.core.util.PbJson.encodeToString(BotRecord.serializer(), bogus)}]}""",
            )
            assertFailsWith<PhoneBridgeException> { JsonFileBotRegistry(mismatched) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fingerprintsComeFromTheStoredKey() {
        val identity = Identity.generate(PeerRole.BOT, "bot")
        val record = BotRecord(identity.peerId, "bot", identity.publicKeyBase64)
        assertEquals(identity.fingerprint, record.fingerprint)
        assertEquals(32, record.publicKeyBytes.size)
    }
}
