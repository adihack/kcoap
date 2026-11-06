package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/**
 * The messages received recently, by peer and Message ID (RFC 7252 §4.5), and how each was answered.
 *
 * A duplicate Confirmable message is answered with the same ACK or RST as the original; a duplicate
 * Non-confirmable message is ignored. Entries live for EXCHANGE_LIFETIME (Confirmable) or NON_LIFETIME
 * (Non-confirmable) after arrival. Each kind has its own queue, ordered by arrival and so by expiry.
 *
 * **Bounds** (RFC 7252 §11.3). Any new message from any source address adds an entry, so at most [maxEntries]
 * are kept in all and [maxEntriesPerPeer] for one peer. [admit] finds a new message its place within what §4.5
 * allows:
 * - It forgets the oldest *repeatable* entry, of the same peer for that peer's bound, of any peer for the
 *   total: one whose message the endpoint may process again, because doing so ends the same way. That is a
 *   request with an idempotent method (GET, PUT and DELETE, RFC 7252 §5.1; FETCH and iPATCH, RFC 8132 §2, §3)
 *   once it no longer waits for a piggybacked answer, unless OSCORE protects it (RFC 8613 §7.4), any message
 *   answered with a Reset, and a request the server answered or dropped before anything acted on it
 *   ([markRepeatable]). §4.5 lets deduplication be relaxed for such requests, so a later duplicate of a
 *   forgotten one is processed as a new message.
 * - When nothing repeatable is left, it refuses the new message, which is then dropped without state or
 *   answer: every remembered message must still be processed at most once.
 * - A quarter of [maxEntries] is kept for repeatable entries: a message whose entry must be kept (a
 *   non-idempotent request or a response) is refused once three quarters of [maxEntries] hold entries that
 *   must be kept, so a flood of non-idempotent requests cannot crowd out idempotent ones.
 *
 * All operations take constant time, apart from [forget], which walks every entry once.
 */
internal class DeduplicationCache(
    private val exchangeLifetime: Duration,
    private val nonLifetime: Duration,
    private val maxEntries: Int = Int.MAX_VALUE,
    private val maxEntriesPerPeer: Int = Int.MAX_VALUE,
) {
    private val entries = HashMap<MessageKey, InboundEntry>()
    private val confirmableExpiries = LinkedChain<InboundEntry>()
    private val nonConfirmableExpiries = LinkedChain<InboundEntry>()

    /** The repeatable entries of every peer, in the order they became repeatable. */
    private val repeatable = LinkedChain<InboundEntry>()
    private val peers = HashMap<Endpoint, PeerEntries>()

    /** The most entries that must be kept: [maxEntries] less the quarter kept for repeatable ones. */
    private val maxKept = maxEntries - maxEntries / 4

    /**
     * Confirmable requests delivered upwards and not answered yet; they get an Empty ACK at
     * [InboundEntry.ackDeadline]. In arrival order, which is deadline order, since every deadline is the
     * arrival plus the same delay. Responses are never here: the upper layer always answers them itself.
     */
    val pending: MutableSet<InboundEntry> = LinkedHashSet()

    /** One peer's share: how many entries it has, and which of them are repeatable, oldest first. */
    private class PeerEntries {
        var count = 0
        val repeatable = LinkedChain<InboundEntry>()
    }

    operator fun get(key: MessageKey): InboundEntry? = entries[key]

    /**
     * Makes room for a new message from [peer], after dropping the entries expired by [now], as the class
     * describes; [mustBeKept] tells whether the message's entry will have to be kept, that is, whether it will
     * not be repeatable. Returns null when the message may be [add]ed, or the bound that refuses it.
     */
    fun admit(peer: Endpoint, now: Duration, mustBeKept: Boolean): Limit? {
        prune(now)
        if (mustBeKept && entries.size - repeatable.size >= maxKept) return Limit.DEDUPLICATION_ENTRIES
        val own = peers[peer]
        if (own != null && own.count >= maxEntriesPerPeer) {
            remove(own.repeatable.first() ?: return Limit.DEDUPLICATION_ENTRIES_PER_PEER)
        }
        if (entries.size >= maxEntries) remove(repeatable.first() ?: return Limit.DEDUPLICATION_ENTRIES)
        return null
    }

    /**
     * Remembers the message with [key] that arrived at [now] as [type]. A Non-confirmable request with an
     * [idempotent] method is repeatable at once; a Confirmable one once it is answered or acknowledged.
     */
    fun add(key: MessageKey, type: CoapMessageType, now: Duration, idempotent: Boolean): InboundEntry {
        val confirmable = type == CoapMessageType.CON
        val entry = InboundEntry(key, type, now + if (confirmable) exchangeLifetime else nonLifetime, idempotent)
        entries.remove(key)?.let(::unlink)
        entries[key] = entry
        entry.expiryNode = (if (confirmable) confirmableExpiries else nonConfirmableExpiries).addLast(entry)
        peers.getOrPut(key.peer) { PeerEntries() }.count++
        if (!confirmable && idempotent) markRepeatable(entry)
        return entry
    }

    /**
     * [entry] may now be forgotten for room, since processing its message again ends the same way (RFC 7252
     * §4.5): it was answered with a Reset, an idempotent request was answered or acknowledged, or the server
     * answered the request before anything acted on it. Idempotent.
     */
    fun markRepeatable(entry: InboundEntry) {
        if (entry.repeatableNode != null || entries[entry.key] !== entry) return
        entry.repeatableNode = repeatable.addLast(entry)
        entry.peerRepeatableNode = peers[entry.key.peer]?.repeatable?.addLast(entry)
    }

    /**
     * Forgets the message with [key], as if it had never arrived, so the next one with its Message ID is new.
     */
    fun remove(key: MessageKey) {
        entries[key]?.let(::remove)
    }

    /** Forgets every message of [peer], whose endpoint is never seen again (an ended DTLS session). */
    fun forget(peer: Endpoint) {
        entries.values.filter { it.key.peer == peer }.forEach(::remove)
    }

    fun nextExpiry(): Duration? = listOfNotNull(confirmableExpiries.first(), nonConfirmableExpiries.first())
        .minOfOrNull { it.expiresAt }

    fun prune(now: Duration) {
        prune(confirmableExpiries, now)
        prune(nonConfirmableExpiries, now)
    }

    val size: Int get() = entries.size

    /** Repeatable entries, for tests. */
    val repeatableCount: Int get() = repeatable.size

    /** Peers with entries, for tests: the per-peer bookkeeping goes with their last entry. */
    val peerCount: Int get() = peers.size

    private fun prune(queue: LinkedChain<InboundEntry>, now: Duration) {
        while (true) {
            val entry = queue.first() ?: return
            if (entry.expiresAt > now) return
            remove(entry)
        }
    }

    private fun remove(entry: InboundEntry) {
        if (entries[entry.key] === entry) entries.remove(entry.key)
        unlink(entry)
    }

    /** Takes [entry] out of every queue and its peer's count. */
    private fun unlink(entry: InboundEntry) {
        entry.expiryNode?.let { (if (entry.type == CoapMessageType.CON) confirmableExpiries else nonConfirmableExpiries).remove(it) }
        entry.expiryNode = null
        entry.repeatableNode?.let(repeatable::remove)
        entry.repeatableNode = null
        val share = peers[entry.key.peer]
        entry.peerRepeatableNode?.let { node -> share?.repeatable?.remove(node) }
        entry.peerRepeatableNode = null
        pending.remove(entry)
        if (share != null && --share.count <= 0) peers.remove(entry.key.peer)
    }
}

/** A received Confirmable or Non-confirmable message, remembered for deduplication. */
internal class InboundEntry(
    val key: MessageKey,
    val type: CoapMessageType,
    val expiresAt: Duration,
    /** Whether the message is a request with an idempotent method (RFC 7252 §5.1, RFC 8132 §2, §3). */
    val isIdempotent: Boolean = false,
) {
    var state: State = State.PENDING

    /** The ACK or RST sent for a Confirmable message, sent again for every duplicate. */
    var reply: ByteArray? = null

    /** When a still unanswered Confirmable request gets an Empty ACK, switching to a separate response. */
    var ackDeadline: Duration? = null

    /** Its place in its expiry queue; null once it left the cache. */
    internal var expiryNode: LinkedChain.Node<InboundEntry>? = null

    /** Its place among the repeatable entries of all peers, and of its peer; null while it must be kept. */
    internal var repeatableNode: LinkedChain.Node<InboundEntry>? = null
    internal var peerRepeatableNode: LinkedChain.Node<InboundEntry>? = null

    enum class State {
        /** Delivered upwards; nothing sent yet. */
        PENDING,

        /** An Empty ACK was sent; any response follows separately (RFC 7252 §5.2.2). */
        ACKNOWLEDGED,

        /** Answered with a piggybacked response, or rejected with a Reset. */
        ANSWERED,
    }
}
