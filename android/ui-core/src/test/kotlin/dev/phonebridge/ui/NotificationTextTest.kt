package dev.phonebridge.ui

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.pairing.BotRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationTextTest {

    @Test
    fun namesTheController() {
        assertEquals("Alpha is in control", ControllerNotifications.text("Alpha"))
        assertEquals("PhoneBridge", ControllerNotifications.content("Alpha").title)
    }

    @Test
    fun saysPlainlyWhenNobodyIsInControl() {
        assertEquals("No bot in control", ControllerNotifications.text(null))
        assertEquals("No bot in control", ControllerNotifications.text("  "))
    }

    @Test
    fun appendsTheQueueWithoutHidingTheController() {
        assertEquals("Alpha is in control · 2 waiting", ControllerNotifications.text("Alpha", 2))
        assertEquals("No bot in control · 1 waiting", ControllerNotifications.text(null, 1))
    }
}

/** Small builders shared by the JVM tests. */
internal object TestBots {
    fun record(
        name: String,
        level: PermissionLevel = PermissionLevel.CONTROL,
        enabled: Boolean = true,
        lastSeen: Long = 0L,
    ): BotRecord {
        val identity = Identity.generate(PeerRole.BOT, name)
        return BotRecord(
            botId = identity.peerId,
            name = name,
            publicKey = identity.publicKeyBase64,
            level = level,
            enabled = enabled,
            pairedAt = 1L,
            lastSeen = lastSeen,
        )
    }

    fun isControllerRow(rows: List<BotRow>, botId: String): Boolean =
        rows.first { it.botId == botId }.inControl

    fun assertBotListConsistent(rows: List<BotRow>) {
        assertTrue(rows.map { it.botId }.toSet().size == rows.size, "duplicate bot rows")
        rows.forEach { bot ->
            assertFalse(bot.name.isBlank())
            assertTrue(dev.phonebridge.core.identity.PeerIds.isValidFingerprint(bot.fingerprint), bot.fingerprint)
        }
    }
}
