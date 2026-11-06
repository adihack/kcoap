package dev.kcoap.coap.stack

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * The bounded queue that the exchanges of one Q-Block body ([ResponseMode.Inbox]) report into, and that
 * the body's driver reads. Reporting never waits, so the receive loop is never held up by a slow driver: an
 * event that does not fit is not taken, and counted in [lost]. A Non-confirmable response that does not fit is
 * lost as if the network had lost the datagram, which the RFC 9177 recovery (4.08 and missing-block requests,
 * the NON_RECEIVE_TIMEOUT ladder) is built for. A Confirmable one is not acknowledged, since an ACK would tell
 * its sender that it arrived (RFC 7252 §4.2): it comes again after ACK_TIMEOUT ([CoapEndpoint.onResponse]).
 *
 * The endpoint asks whether a Confirmable response fits before it verifies it, with a [Reservation]: verifying
 * a response under OSCORE records its Partial IV, and the retransmission of a response the inbox did not take
 * carries the same one, so it would be refused as a replay (RFC 8613 §7.4) and lost after all.
 * Safe to use from any coroutine.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ExchangeInbox(val capacity: Int) {
    init {
        require(capacity >= 1) { "An inbox needs room for at least one event: $capacity" }
    }

    private val events = Channel<ExchangeEvent>(capacity)
    private val dropped = AtomicInt(0)

    /**
     * The events queued and the room that [Reservation]s hold, never above [capacity]: an event is counted before
     * it is sent and uncounted after it was received, so a counted event always fits the channel.
     */
    private val occupied = AtomicInt(0)

    /** Events not taken because the inbox was full; a Confirmable response among them comes again. */
    val lost: Int get() = dropped.load()

    /**
     * Holds room for one event, which an [offer] with the reservation then cannot be refused; null, and counted
     * in [lost], when the inbox is full. Every reservation is used by that [offer] or given back with
     * [Reservation.release].
     */
    fun tryReserve(): Reservation? {
        if (claim()) return Reservation(this)
        dropped.incrementAndFetch()
        return null
    }

    /**
     * Queues [event], in the room [reservation] holds when one is given; false, and counted in [lost], when the
     * inbox is full. An offer with a reservation that was not used yet always succeeds.
     */
    fun offer(event: ExchangeEvent, reservation: Reservation? = null): Boolean {
        require(reservation == null || reservation.inbox === this) { "A reservation of another inbox: $reservation" }
        if (reservation?.use() != true && !claim()) {
            dropped.incrementAndFetch()
            return false
        }
        check(events.trySend(event).isSuccess) { "An inbox refused a counted event: $this" }
        return true
    }

    /** The next event, waiting for one. */
    suspend fun receive(): ExchangeEvent = events.receive().also { free() }

    /** The next event, or null when none is queued. */
    fun tryReceive(): ExchangeEvent? = events.tryReceive().getOrNull()?.also { free() }

    /**
     * The next event, waiting at most [timeout] for one; null when none came. Unlike a `receive` inside
     * `withTimeoutOrNull`, the timeout never takes an event out of the inbox and loses it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun receive(timeout: Duration): ExchangeEvent? = select {
        events.onReceive { it.also { free() } }
        onTimeout(timeout) { null }
    }

    /** Takes room for one event; false when the inbox is full. */
    private fun claim(): Boolean {
        while (true) {
            val count = occupied.load()
            if (count >= capacity) return false
            if (occupied.compareAndSet(count, count + 1)) return true
        }
    }

    private fun free() {
        occupied.decrementAndFetch()
    }

    override fun toString(): String = "ExchangeInbox(capacity=$capacity, lost=$lost)"

    /** Room for one event in [inbox] ([tryReserve]), used once: by an [offer] with it, or given back by [release]. */
    class Reservation internal constructor(internal val inbox: ExchangeInbox) {
        private val used = AtomicBoolean(false)

        /** Gives the room back, unless an [offer] used it; once is enough, and later calls do nothing. */
        fun release() {
            if (use()) inbox.free()
        }

        /** Whether this call is the one that used the reservation. */
        internal fun use(): Boolean = used.compareAndSet(false, true)

        override fun toString(): String = "Reservation(${if (used.load()) "used" else "held"}, $inbox)"
    }

    companion object {
        /**
         * An inbox for one body under [parameters]: 4 × MAX_PAYLOADS + 8 events, room for several sets of
         * MAX_PAYLOADS responses (a set, the blocks sent again after a 4.08 or a missing-block request, and
         * their duplicates) and the outcomes of the body's requests, so a driver that is a little behind
         * loses nothing.
         */
        fun forBody(parameters: TransmissionParameters): ExchangeInbox = ExchangeInbox(4 * parameters.maxPayloads + 8)

        /**
         * An inbox for one body on a reliable transport (RFC 9177 Appendix B), which never drops an event of the
         * body: there nothing is asked for again, so a dropped block would stall the body until it timed out,
         * and a connection reads many back-to-back blocks at once. It has room for a response body of
         * [maxBodySize] bytes in 16-byte blocks, besides what [forBody] has room for. The queue grows as events
         * come, so the room costs nothing until it is used, and the capacity still bounds what a server that
         * floods the body's tokens can queue.
         */
        fun forReliableBody(parameters: TransmissionParameters, maxBodySize: Int): ExchangeInbox {
            val blocks = (maxBodySize.toLong() + Blockwise.MIN_BLOCK_SIZE - 1) / Blockwise.MIN_BLOCK_SIZE
            val capacity = blocks + 4L * parameters.maxPayloads + 8
            return ExchangeInbox(capacity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
}
