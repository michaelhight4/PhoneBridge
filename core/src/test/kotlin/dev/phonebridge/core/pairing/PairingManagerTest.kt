package dev.phonebridge.core.pairing

import dev.phonebridge.core.FakeClock
import dev.phonebridge.core.PairingException
import dev.phonebridge.core.PairingFailure
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PairingReasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingManagerTest {
    private val clock = FakeClock()
    private val device = Identity.generate(PeerRole.DEVICE, "Pixel 9")
    private val registry = InMemoryBotRegistry { clock.now }
    private val manager = PairingManager(device, registry, clock = { clock.now })
    private val bot = Identity.generate(PeerRole.BOT, "helper")

    private fun newOffer() = manager.createOffer("wss://relay.example:8443")

    @Test
    fun createOfferHasTheDocumentedShape() {
        val offer = newOffer()
        assertEquals(device.peerId, offer.deviceId)
        assertEquals(device.publicKeyBase64, offer.devicePub)
        assertEquals("Pixel 9", offer.deviceName)
        assertEquals(32, offer.secretBytes.size)
        assertTrue(ShortCode.isValid(offer.code))
        assertEquals(ShortCode.format(offer.code), offer.code)
        assertEquals((clock.now + PairingManager.OFFER_TTL_MS) / 1000, offer.exp)
        assertEquals(offer, manager.offerByCode(offer.code))
        assertEquals(offer, manager.offerByCode(ShortCode.normalise(offer.code)))
        assertEquals(listOf(offer), manager.liveOffers())
    }

    @Test
    fun acceptApproveStoresTheBotAndAnswersTheBot() {
        val offer = newOffer()
        val sealed = PairingClient.createRequest(offer, bot, PermissionLevel.CONTROL)
        val pending = manager.accept(offer.code, sealed.sealed)

        assertEquals("helper", pending.botName)
        assertEquals(bot.peerId, pending.botId)
        assertEquals(bot.fingerprint, pending.botFingerprint)
        assertEquals(PermissionLevel.CONTROL, pending.requestedLevel)
        assertEquals(1, manager.pendingApprovals().size)

        val answer = manager.approve(pending)
        assertTrue(answer.result.ok)
        assertEquals(PermissionLevel.CONTROL, answer.result.grantedLevel)
        assertEquals("Pixel 9", answer.result.deviceName)
        assertEquals(device.peerId, answer.result.deviceId)
        assertTrue(manager.pendingApprovals().isEmpty())

        val record = assertNotNull(registry.get(bot.peerId))
        assertEquals("helper", record.name)
        assertEquals(bot.publicKeyBase64, record.publicKey)
        assertEquals(PermissionLevel.CONTROL, record.level)
        assertTrue(record.enabled)
        assertEquals(clock.now, record.pairedAt)

        // The bot can open the sealed answer with the offer secret.
        val botSide = PairingClient.openResult(offer, answer.sealed)
        assertTrue(botSide.ok)
        assertEquals(PermissionLevel.CONTROL, botSide.grantedLevel)
        assertEquals("Pixel 9", botSide.deviceName)
    }

    @Test
    fun theOfferIsSingleUse() {
        val offer = newOffer()
        val first = PairingClient.createRequest(offer, bot)
        manager.accept(offer.code, first.sealed)

        val second = PairingClient.createRequest(offer, bot)
        val failure = assertFailsWith<PairingException> { manager.accept(offer.code, second.sealed) }
        assertEquals(PairingFailure.USED, failure.reason)

        // An exact replay of the first ciphertext is refused too.
        val replay = assertFailsWith<PairingException> { manager.accept(offer.code, first.sealed) }
        assertEquals(PairingFailure.USED, replay.reason)
    }

    @Test
    fun theOfferExpiresAfterTenMinutes() {
        val early = newOffer()
        clock.advance(599_000)
        manager.accept(early.code, PairingClient.createRequest(early, bot).sealed)

        val late = newOffer()
        clock.advance(600_001)
        val failure = assertFailsWith<PairingException> {
            manager.accept(late.code, PairingClient.createRequest(late, bot).sealed)
        }
        assertEquals(PairingFailure.EXPIRED, failure.reason)
        assertNull(manager.offerByCode(late.code))
        assertTrue(manager.liveOffers().none { it.code == late.code })
    }

    @Test
    fun denyAnswersWithoutPairingAndBurnsTheOffer() {
        val offer = newOffer()
        val sealed = PairingClient.createRequest(offer, bot)
        val pending = manager.accept(offer.code, sealed.sealed)
        val answer = manager.deny(pending)

        assertFalse(answer.result.ok)
        assertEquals(PairingReasons.DENIED, answer.result.reason)
        assertTrue(registry.list().isEmpty())

        val botSide = PairingClient.openResult(offer, answer.sealed)
        assertFalse(botSide.ok)
        assertEquals(PairingReasons.DENIED, botSide.reason)

        val failure = assertFailsWith<PairingException> {
            manager.accept(offer.code, PairingClient.createRequest(offer, bot).sealed)
        }
        assertEquals(PairingFailure.USED, failure.reason)
    }

    @Test
    fun theUserMayGrantALowerLevelThanRequested() {
        val offer = newOffer()
        val sealed = PairingClient.createRequest(offer, bot, PermissionLevel.FULL)
        val pending = manager.accept(offer.code, sealed.sealed)
        assertEquals(PermissionLevel.FULL, pending.requestedLevel)
        manager.approve(pending, PermissionLevel.VIEW)
        assertEquals(PermissionLevel.VIEW, registry.get(bot.peerId)?.level)
    }

    @Test
    fun wrongOrTamperedRequestsAreRejected() {
        val offer = newOffer()
        val unknown = assertFailsWith<PairingException> {
            manager.accept("ZZZ-ZZQ", ByteArray(64))
        }
        assertEquals(PairingFailure.UNKNOWN_CODE, unknown.reason)

        val sealed = PairingClient.createRequest(offer, bot).sealed
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        val badSecret = assertFailsWith<PairingException> { manager.accept(offer.code, tampered) }
        assertEquals(PairingFailure.BAD_SECRET, badSecret.reason)
        assertTrue(manager.pendingApprovals().isEmpty())

        // The offer was not burned by a failed decrypt: the real request still works.
        manager.accept(offer.code, sealed)
    }

    @Test
    fun aRequestForAnotherDeviceIsRejected() {
        val offer = newOffer()
        val foreign = PairingManager(
            Identity.generate(PeerRole.DEVICE, "Other"),
            InMemoryBotRegistry(),
            clock = { clock.now },
        ).createOffer("wss://relay.example")
        // Whose secret does not match this device's offer.
        val sealed = PairingClient.createRequest(foreign, bot)
        val failure = assertFailsWith<PairingException> { manager.accept(offer.code, sealed.sealed) }
        assertEquals(PairingFailure.BAD_SECRET, failure.reason)
    }

    @Test
    fun cancelledOffersStopWorking() {
        val offer = newOffer()
        assertTrue(manager.cancelOffer(offer.code))
        assertNull(manager.offerByCode(offer.code))
        val failure = assertFailsWith<PairingException> {
            manager.accept(offer.code, PairingClient.createRequest(offer, bot).sealed)
        }
        assertEquals(PairingFailure.UNKNOWN_CODE, failure.reason)
    }

    @Test
    fun acceptByOfferObjectAndUnknownOffers() {
        val offer = newOffer()
        val sealed = PairingClient.createRequest(offer, bot)
        val pending = manager.accept(offer, sealed.sealed)
        manager.approve(pending)
        assertEquals(bot.peerId, registry.list().single().botId)

        val otherDevice = Identity.generate(PeerRole.DEVICE)
        val unknownOffer = PairingManager(
            otherDevice,
            InMemoryBotRegistry(),
            clock = { clock.now },
        ).createOffer("wss://relay.example")
        val failure = assertFailsWith<PairingException> {
            manager.accept(unknownOffer, PairingClient.createRequest(unknownOffer, bot).sealed)
        }
        assertEquals(PairingFailure.UNKNOWN_CODE, failure.reason)
    }

    @Test
    fun pairingSeveralBotsKeepsTheirRecordsIndependent() {
        val second = Identity.generate(PeerRole.BOT, "second")

        val offerA = newOffer()
        manager.approve(manager.accept(offerA.code, PairingClient.createRequest(offerA, bot).sealed))
        val offerB = newOffer()
        manager.approve(manager.accept(offerB.code, PairingClient.createRequest(offerB, second).sealed), PermissionLevel.FULL)

        assertEquals(2, registry.list().size)
        assertEquals(PermissionLevel.FULL, registry.get(second.peerId)?.level)

        assertTrue(manager.revoke(bot.peerId))
        assertNull(registry.get(bot.peerId))
        assertNotNull(registry.get(second.peerId))
        assertFalse(manager.revoke(bot.peerId))
    }

    @Test
    fun approvalsOnlyWorkOnce() {
        val offer = newOffer()
        val pending = manager.accept(offer.code, PairingClient.createRequest(offer, bot).sealed)
        manager.approve(pending)
        val failure = assertFailsWith<PairingException> { manager.approve(pending) }
        assertEquals(PairingFailure.BAD_REQUEST, failure.reason)
    }

    @Test
    fun aPendingApprovalSurvivesANewOffer() {
        val first = newOffer()
        val pending = manager.accept(first.code, PairingClient.createRequest(first, bot).sealed)
        // The user shows a second QR code while the first "Allow this bot?" prompt is still open.
        val second = newOffer()
        assertEquals(1, manager.pendingApprovals().size)
        assertEquals(second.code, manager.liveOffers().single().code)
        manager.approve(pending)
        assertEquals(bot.peerId, registry.list().single().botId)
    }

    @Test
    fun unansweredPromptsExpireAfterTenMinutes() {
        val offer = newOffer()
        val pending = manager.accept(offer.code, PairingClient.createRequest(offer, bot).sealed)
        clock.advance(600_001)
        assertTrue(manager.pendingApprovals().isEmpty())
        val failure = assertFailsWith<PairingException> { manager.approve(pending) }
        assertEquals(PairingFailure.BAD_REQUEST, failure.reason)
        assertTrue(registry.list().isEmpty())
    }
}
