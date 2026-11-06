package dev.kcoap.coap.stack

import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/**
 * Hands out Message IDs so that none is reused towards the same peer within its lifetime
 * (RFC 7252 §4.4). One counter serves all peers, as the §4.4 implementation note suggests, starting at a
 * random value to make off-path attacks harder; IDs still reserved for a peer are skipped.
 *
 * **Bounds** (RFC 7252 §11.3). Every message the endpoint sends with a new ID reserves it, and a peer can make
 * the endpoint send, so at most [maxReserved] IDs are reserved in all and [maxReservedPerPeer] towards one
 * peer, which the ID space bounds at 65 536 anyway. A reservation is never given up early, since §4.4 forbids
 * reusing the ID, so beyond a bound [refusal] refuses the new message instead.
 *
 * A quarter of each bound is kept from *droppable* messages: Non-confirmable responses to Non-confirmable
 * requests, which any source address, forged or not, makes the endpoint send one for each request, and whose
 * loss the client already copes with (§5.2.3). They are refused once three quarters are reserved, so such a
 * flood leaves IDs for what the endpoint must send: its own requests, notifications, separate responses to
 * Confirmable requests and Q-Block bodies of notifications.
 */
internal class MessageIdAllocator(
    initial: Int,
    private val maxReserved: Int = Int.MAX_VALUE,
    private val maxReservedPerPeer: Int = MESSAGE_ID_SPACE,
) {
    private var next = initial and 0xFFFF
    private val reservations = HashMap<MessageKey, Reservation>()

    /** Every reservation made, in order of expiry; one replaced by an extension stays until its own expiry. */
    private val expiries = ArrayDeque<Reservation>()
    private val perPeer = HashMap<Endpoint, Int>()

    private class Reservation(val key: MessageKey, val until: Duration)

    /**
     * The bound that keeps [peer] from getting a new ID at [now], after dropping the reservations expired by then,
     * or null when it may have one. A [droppable] message may take three quarters of each bound only.
     */
    fun refusal(peer: Endpoint, now: Duration, droppable: Boolean = false): Limit? {
        prune(now)
        return when {
            (perPeer[peer] ?: 0) >= maxReservedPerPeer.forDroppable(droppable) -> Limit.MESSAGE_IDS_PER_PEER
            reservations.size >= maxReserved.forDroppable(droppable) -> Limit.MESSAGE_IDS
            else -> null
        }
    }

    /** This bound less the quarter kept from droppable messages, for a [droppable] one. */
    private fun Int.forDroppable(droppable: Boolean): Int = if (droppable) this - this / 4 else this

    /**
     * Reserves and returns a free ID for [peer] until [until]; the caller checked [refusal] first.
     *
     * @throws CoapException with [CoapException.Reason.IDENTIFIERS_EXHAUSTED] when every ID towards [peer] is
     *   reserved.
     */
    fun allocate(peer: Endpoint, now: Duration, until: Duration): Int {
        repeat(MESSAGE_ID_SPACE) {
            val candidate = next
            next = (next + 1) and 0xFFFF
            val key = MessageKey(peer, candidate)
            val reserved = reservations[key]
            if (reserved == null || reserved.until <= now) {
                reserve(key, until)
                return candidate
            }
        }
        throw CoapException("All Message IDs towards $peer are in use", CoapException.Reason.IDENTIFIERS_EXHAUSTED)
    }

    /** Extends the reservation of an ID that was just sent, so it covers the message's lifetime. */
    fun extend(peer: Endpoint, messageId: Int, until: Duration) {
        val key = MessageKey(peer, messageId)
        if ((reservations[key]?.until ?: Duration.ZERO) < until) reserve(key, until)
    }

    /** Forgets every reservation for [peer], whose endpoint can never be used again (an ended DTLS session). */
    fun forget(peer: Endpoint) {
        reservations.keys.removeAll { it.peer == peer }
        perPeer.remove(peer)
    }

    fun nextExpiry(): Duration? = expiries.firstOrNull()?.until

    fun prune(now: Duration) {
        while (true) {
            val reservation = expiries.firstOrNull() ?: return
            if (reservation.until > now) return
            expiries.removeFirst()
            // A later extension left a newer reservation behind; only the latest one removes the key.
            if (reservations[reservation.key] === reservation) {
                reservations.remove(reservation.key)
                count(reservation.key.peer, -1)
            }
        }
    }

    val reservedCount: Int get() = reservations.size

    /** IDs reserved towards [peer], for tests. */
    fun reservedCount(peer: Endpoint): Int = perPeer[peer] ?: 0

    private fun reserve(key: MessageKey, until: Duration) {
        val reservation = Reservation(key, until)
        if (reservations.put(key, reservation) == null) count(key.peer, +1)
        // Reservations are made with "now + lifetime", so the queue stays ordered by expiry.
        expiries.addLast(reservation)
    }

    private fun count(peer: Endpoint, change: Int) {
        val count = (perPeer[peer] ?: 0) + change
        if (count > 0) perPeer[peer] = count else perPeer.remove(peer)
    }

    companion object {
        /** RFC 7252 §3: Message IDs are 16 bits. */
        const val MESSAGE_ID_SPACE: Int = 0x10000
    }
}

/** A Message ID scoped to the peer it was exchanged with (RFC 7252 §4.4, §9.1.1). */
internal data class MessageKey(val peer: Endpoint, val messageId: Int)
