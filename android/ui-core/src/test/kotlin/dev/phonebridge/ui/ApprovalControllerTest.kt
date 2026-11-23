package dev.phonebridge.ui

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PairResult
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.pairing.InMemoryBotRegistry
import dev.phonebridge.core.pairing.PairingClient
import dev.phonebridge.core.pairing.PairingManager
import dev.phonebridge.core.util.B64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ApprovalControllerTest {

    private var now = 1_700_000_000_000L
    private val registry = InMemoryBotRegistry(clock = { now })
    private val manager = PairingManager(
        identity = Identity.generate(PeerRole.DEVICE, "phone"),
        registry = registry,
        clock = { now },
    )
    private val log = InMemoryActivityLog(clock = { now })
    private val answers = mutableListOf<Pair<String, PairResult>>()

    private lateinit var offer: dev.phonebridge.core.model.PairingOffer
    private lateinit var bot: Identity

    private fun controller(): ApprovalController = ApprovalController(
        manager = manager,
        sendAnswer = { botId, sealed ->
            val result = PairingClient.openResult(offer, B64Url.decode(sealed))
            answers.add(botId to result)
        },
        log = log,
        clock = { now },
    )

    /** Creates an offer, a bot-side sealed request and the device's pending approval. */
    private fun pendingRequest(level: PermissionLevel = PermissionLevel.FULL): dev.phonebridge.core.pairing.PendingApproval {
        offer = manager.createOffer("ws://127.0.0.1:8443", deviceName = "phone")
        bot = Identity.generate(PeerRole.BOT, "Claude")
        val sealed = PairingClient.createRequest(offer, bot, requestedLevel = level)
        return manager.accept(offer, sealed.sealed)
    }

    @Test
    fun showsNameFingerprintAndRequestedLevel() {
        val approval = pendingRequest(PermissionLevel.FULL)
        val view = controller().onRequest(approval, offer.expiresAtMillis)

        assertEquals("Claude", view.botName)
        assertEquals(approval.botFingerprint, view.fingerprint)
        assertEquals(PermissionLevel.FULL, view.requestedLevel)
        assertEquals("Full", view.requestedLabel)
        assertEquals(600L, view.secondsRemaining(now))
    }

    @Test
    fun allowStoresTheBotAndAnswersIt() {
        val approval = pendingRequest(PermissionLevel.CONTROL)
        val controller = controller()
        val view = controller.onRequest(approval, offer.expiresAtMillis)

        val outcome = controller.approve(view.key)
        val approved = outcome as ApprovalOutcome.Approved
        assertEquals(PermissionLevel.CONTROL, approved.grantedLevel)

        val stored = registry.get(bot.peerId)
        assertNotNull(stored, "the allowed bot must appear in the bot list")
        assertEquals("Claude", stored.name)
        assertEquals(PermissionLevel.CONTROL, stored.level)

        assertEquals(1, answers.size)
        assertEquals(bot.peerId, answers.single().first)
        assertTrue(answers.single().second.ok)
        assertEquals(PermissionLevel.CONTROL, answers.single().second.grantedLevel)
        assertNull(controller.find(view.key))
        assertTrue(log.entriesFor(bot.peerId).any { it.cmd == "pairing_approved" })
    }

    @Test
    fun theUserMayLowerTheLevelButNotRaiseIt() {
        val approval = pendingRequest(PermissionLevel.VIEW)
        val controller = controller()
        val view = controller.onRequest(approval, offer.expiresAtMillis)

        val outcome = controller.approve(view.key, PermissionLevel.FULL) as ApprovalOutcome.Approved
        assertEquals(PermissionLevel.VIEW, outcome.grantedLevel)
        assertEquals(PermissionLevel.VIEW, registry.get(bot.peerId)!!.level)
    }

    @Test
    fun denyAnswersTheBotAndLeavesItUnpaired() {
        val approval = pendingRequest()
        val controller = controller()
        val view = controller.onRequest(approval, offer.expiresAtMillis)

        val outcome = controller.deny(view.key)
        assertTrue(outcome is ApprovalOutcome.Denied)
        assertNull(registry.get(bot.peerId), "a denied bot must not be stored")
        assertFalse(answers.single().second.ok)
        assertEquals("denied", answers.single().second.reason)
        assertTrue(log.entriesFor(bot.peerId).any { it.cmd == "pairing_denied" })
    }

    @Test
    fun approvingAfterTheOfferExpiresFails() {
        val approval = pendingRequest()
        val controller = controller()
        val view = controller.onRequest(approval, offer.expiresAtMillis)
        now = offer.expiresAtMillis + 1

        val outcome = controller.approve(view.key)
        assertTrue(outcome is ApprovalOutcome.Failed, "expired pairing code must not allow a bot")
        assertNull(registry.get(bot.peerId))
        assertTrue(answers.isEmpty())
    }

    @Test
    fun anUnknownKeyIsNotApproved() {
        val controller = controller()
        val outcome = controller.approve("nope")
        if (outcome !is ApprovalOutcome.Failed) fail("expected Failure, got $outcome")
        assertTrue(answers.isEmpty())
    }
}
