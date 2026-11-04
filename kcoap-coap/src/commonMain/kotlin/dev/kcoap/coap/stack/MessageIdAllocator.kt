package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/**
 * Hands out Message IDs so that none is reused towards the same peer within its lifetime
 * (RFC 7252 §4.4). One counter serves all peers, as the §4.4 implementation note suggests, starting at a
 * random value to make off-path attacks harder; IDs still reserved for a peer are skipped.
 */
internal class MessageIdAllocator(initial: Int) {
    private var next = initial and 0xFFFF
    private val reservedUntil = HashMap<MessageKey, Duration>()
    private val expiries = ArrayDeque<Pair<MessageKey, Duration>>()

    /** Reserves and returns a free ID for [peer] until [until]. */
    fun allocate(peer: Endpoint, now: Duration, until: Duration): Int {
        repeat(MESSAGE_ID_SPACE) {
            val candidate = next
            next = (next + 1) and 0xFFFF
            val key = MessageKey(peer, candidate)
            val reserved = reservedUntil[key]
            if (reserved == null || reserved <= now) {
                reserve(key, until)
                return candidate
            }
        }
        throw CoapException("All Message IDs towards $peer are in use", CoapException.Reason.IDENTIFIERS_EXHAUSTED)
    }

    /** Extends the reservation of an ID that was just sent, so it covers the message's lifetime. */
    fun extend(peer: Endpoint, messageId: Int, until: Duration) {
        val key = MessageKey(peer, messageId)
        if ((reservedUntil[key] ?: Duration.ZERO) < until) reserve(key, until)
    }

    /** Forgets every reservation for [peer], whose endpoint can never be used again (an ended DTLS session). */
    fun forget(peer: Endpoint) {
        reservedUntil.keys.removeAll { it.peer == peer }
    }

    fun nextExpiry(): Duration? = expiries.firstOrNull()?.second

    fun prune(now: Duration) {
        while (true) {
            val (key, expiry) = expiries.firstOrNull() ?: return
            if (expiry > now) return
            expiries.removeFirst()
            // A later extension left a newer queue entry behind; only the latest one removes the key.
            if (reservedUntil[key] == expiry) reservedUntil.remove(key)
        }
    }

    val reservedCount: Int get() = reservedUntil.size

    private fun reserve(key: MessageKey, until: Duration) {
        reservedUntil[key] = until
        // Reservations are made with "now + lifetime", so the queue stays ordered by expiry.
        expiries.addLast(key to until)
    }

    private companion object {
        const val MESSAGE_ID_SPACE = 0x10000
    }
}

/** A Message ID scoped to the peer it was exchanged with (RFC 7252 §4.4, §9.1.1). */
internal data class MessageKey(val peer: Endpoint, val messageId: Int)
