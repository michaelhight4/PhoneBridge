package dev.phonebridge.ui

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.pairing.InMemoryBotRegistry
import dev.phonebridge.core.pairing.PairingManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingControllerTest {

    private var now = 1_700_000_000_000L
    private val manager = PairingManager(
        identity = Identity.generate(PeerRole.DEVICE, "phone"),
        registry = InMemoryBotRegistry(),
        clock = { now },
    )
    private val pairing = PairingController(
        newOffer = { manager.createOffer("ws://127.0.0.1:8443", deviceName = "phone") },
        cancelOffer = manager::cancelOffer,
        clock = { now },
    )

    @Test
    fun countsDownFromTenMinutes() {
        val view = pairing.generate()
        assertEquals(600L, view.secondsRemaining)
        assertFalse(view.expired)
        assertTrue(view.link.startsWith("phonebridge://pair?d="))
        assertTrue(view.fileContents.contains("\"code\""))
        assertEquals(view.link, view.qrPayload)
    }

    @Test
    fun roundsUpTheLastSecond() {
        pairing.generate()
        now += 599_000L
        assertEquals(1L, pairing.tick()!!.secondsRemaining)
    }

    @Test
    fun expiresAtTenMinutes() {
        val view = pairing.generate()
        now = view.expiresAtMs
        val expired = pairing.tick()!!
        assertTrue(expired.expired)
        assertEquals(0L, expired.secondsRemaining)
        assertFalse(expired.canShare)
    }

    @Test
    fun regenerateCancelsTheOldCodeAndMakesANewOne() {
        val first = pairing.generate()
        assertNotNull(manager.offerByCode(first.offer.code))
        now += 1_000L
        val second = pairing.regenerate()
        assertNotEquals(first.offer.code, second.offer.code)
        assertNull(manager.offerByCode(first.offer.code), "the first secret must be burned")
        assertNotNull(manager.offerByCode(second.offer.code))
    }

    @Test
    fun cancelBurnsTheCurrentCode() {
        val view = pairing.generate()
        assertTrue(pairing.cancel())
        assertNull(manager.offerByCode(view.offer.code))
        assertNull(pairing.current())
    }
}
