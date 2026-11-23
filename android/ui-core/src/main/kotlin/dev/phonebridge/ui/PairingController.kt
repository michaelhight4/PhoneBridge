package dev.phonebridge.ui

import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.pairing.PairingLinks

/**
 * The pairing screen state: one live offer at a time, its QR/link/file payloads, and the countdown
 * to the ten-minute expiry. Time is injected so the countdown and expiry can be unit tested without
 * sleeping for ten minutes.
 */
class PairingController(
    private val newOffer: () -> PairingOffer,
    private val cancelOffer: (String) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Everything the pairing screen shows. [expired] is true from the expiry instant onwards. */
    data class View(
        val offer: PairingOffer,
        val link: String,
        val fileContents: String,
        val expiresAtMs: Long,
        val secondsRemaining: Long,
        val expired: Boolean,
    ) {
        /** What the QR code encodes: exactly the pairing link. */
        val qrPayload: String get() = link

        /** True when Android should stop offering the QR but keep the (expired) screen readable. */
        val canShare: Boolean get() = !expired
    }

    private var currentOffer: PairingOffer? = null

    @Synchronized
    fun current(): View? = currentOffer?.let { viewOf(it) }

    /** Creates a fresh offer, cancelling any previous one so only one secret is ever live. */
    @Synchronized
    fun generate(): View {
        currentOffer?.let { cancelOffer(it.code) }
        val offer = newOffer()
        currentOffer = offer
        return viewOf(offer)
    }

    /** Alias for the "Regenerate" button. */
    @Synchronized
    fun regenerate(): View = generate()

    /** Recomputes the countdown; call once a second while the screen is visible. */
    @Synchronized
    fun tick(): View? = current()

    /** The user left the screen or turned pairing off: the code stops working immediately. */
    @Synchronized
    fun cancel(): Boolean {
        val offer = currentOffer ?: return false
        currentOffer = null
        return cancelOffer(offer.code)
    }

    /** Drops an expired offer so the next [generate] starts clean. */
    @Synchronized
    fun expireIfNeeded(): Boolean {
        val offer = currentOffer ?: return false
        if (!offer.isExpiredAt(clock())) return false
        cancelOffer(offer.code)
        return true
    }

    private fun viewOf(offer: PairingOffer): View {
        val now = clock()
        val expiresAt = offer.expiresAtMillis
        val remainingMs = (expiresAt - now).coerceAtLeast(0L)
        // Round up so "1" is shown during the last second instead of "0" while still valid.
        val seconds = (remainingMs + 999L) / 1000L
        return View(
            offer = offer,
            link = PairingLinks.link(offer),
            fileContents = PairingLinks.fileContents(offer),
            expiresAtMs = expiresAt,
            secondsRemaining = seconds,
            expired = now >= expiresAt,
        )
    }
}
