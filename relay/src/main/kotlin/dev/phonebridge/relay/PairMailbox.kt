package dev.phonebridge.relay

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-code mailbox for pairing offers.
 *
 * A device stores its pairing offer under a short code (`7K4-Q9B`); a bot looks the code up once and
 * receives the offer. The entry is released by the first successful lookup, expires after [ttlMs] and
 * is never handed out twice.
 */
internal class PairMailbox(
    private val ttlMs: Long,
    private val maxEntries: Int = 512,
    private val maxOfferBytes: Int = 16 * 1024,
    private val clock: () -> Long = System::currentTimeMillis
) {
    sealed class Take {
        data class Found(val offer: JsonObject, val expiresAt: Long) : Take()
        data object Expired : Take()
        data object Missing : Take()
    }

    class MailboxFullException(message: String) : Exception(message)

    private data class Entry(val offer: JsonObject, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    val size: Int get() = entries.size

    /** Stores [offer] under the canonical [codeKey] and returns the expiry in epoch millis. */
    fun store(codeKey: String, offer: JsonObject): Long {
        purgeExpired()
        if (entries.size >= maxEntries) throw MailboxFullException("the pairing mailbox is full")
        if (offer.toString().length > maxOfferBytes) {
            throw MailboxFullException("pairing offer is larger than $maxOfferBytes bytes")
        }
        val expiresAt = clock() + ttlMs
        entries[codeKey] = Entry(offer, expiresAt)
        return expiresAt
    }

    /** Removes and returns the offer for [codeKey]; a code can only ever be released once. */
    fun take(codeKey: String): Take {
        val entry = entries.remove(codeKey) ?: return Take.Missing
        return if (entry.expiresAt <= clock()) Take.Expired else Take.Found(entry.offer, entry.expiresAt)
    }

    fun purgeExpired() {
        val now = clock()
        entries.entries.removeIf { it.value.expiresAt <= now }
    }

    fun clear() = entries.clear()
}
