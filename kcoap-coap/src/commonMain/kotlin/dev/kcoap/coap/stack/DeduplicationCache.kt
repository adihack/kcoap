package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessageType
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/**
 * The messages received recently, by peer and Message ID (RFC 7252 §4.5), and how each was answered.
 *
 * A duplicate Confirmable message is answered with the same ACK or RST as the original; a duplicate
 * Non-confirmable message is ignored. Entries live for EXCHANGE_LIFETIME (Confirmable) or NON_LIFETIME
 * (Non-confirmable) after arrival. Each kind has its own queue, ordered by arrival and so by expiry.
 * At most [maxEntries] are kept; [hasRoom] tells whether a new message may add one.
 */
internal class DeduplicationCache(
    private val exchangeLifetime: Duration,
    private val nonLifetime: Duration,
    private val maxEntries: Int = Int.MAX_VALUE,
) {
    private val entries = HashMap<MessageKey, InboundEntry>()
    private val confirmableExpiries = ArrayDeque<InboundEntry>()
    private val nonConfirmableExpiries = ArrayDeque<InboundEntry>()

    /**
     * Confirmable messages delivered upwards and not answered yet; they get an Empty ACK at
     * [InboundEntry.ackDeadline]. In arrival order, which is deadline order, since every deadline is the
     * arrival plus the same delay.
     */
    val pending: MutableSet<InboundEntry> = LinkedHashSet()

    operator fun get(key: MessageKey): InboundEntry? = entries[key]

    fun add(key: MessageKey, type: CoapMessageType, now: Duration): InboundEntry {
        val confirmable = type == CoapMessageType.CON
        val entry = InboundEntry(key, type, now + if (confirmable) exchangeLifetime else nonLifetime)
        entries[key] = entry
        (if (confirmable) confirmableExpiries else nonConfirmableExpiries).addLast(entry)
        return entry
    }

    /** Whether a new entry fits, after dropping those expired by [now]. */
    fun hasRoom(now: Duration): Boolean {
        if (entries.size < maxEntries) return true
        prune(now)
        return entries.size < maxEntries
    }

    fun forget(peer: Endpoint) {
        entries.values.removeAll { it.key.peer == peer }
        pending.removeAll { it.key.peer == peer }
    }

    fun nextExpiry(): Duration? = listOfNotNull(confirmableExpiries.firstOrNull(), nonConfirmableExpiries.firstOrNull())
        .minOfOrNull { it.expiresAt }

    fun prune(now: Duration) {
        prune(confirmableExpiries, now)
        prune(nonConfirmableExpiries, now)
    }

    val size: Int get() = entries.size

    private fun prune(queue: ArrayDeque<InboundEntry>, now: Duration) {
        while (true) {
            val entry = queue.firstOrNull() ?: return
            if (entry.expiresAt > now) return
            queue.removeFirst()
            if (entries[entry.key] === entry) entries.remove(entry.key)
            pending.remove(entry)
        }
    }
}

/** A received Confirmable or Non-confirmable message, remembered for deduplication. */
internal class InboundEntry(val key: MessageKey, val type: CoapMessageType, val expiresAt: Duration) {
    var state: State = State.PENDING

    /** The ACK or RST sent for a Confirmable message, sent again for every duplicate. */
    var reply: ByteArray? = null

    /** When a still unanswered Confirmable request gets an Empty ACK, switching to a separate response. */
    var ackDeadline: Duration? = null

    enum class State {
        /** Delivered upwards; nothing sent yet. */
        PENDING,

        /** An Empty ACK was sent; any response follows separately (RFC 7252 §5.2.2). */
        ACKNOWLEDGED,

        /** Answered with a piggybacked response, or rejected with a Reset. */
        ANSWERED,
    }
}
