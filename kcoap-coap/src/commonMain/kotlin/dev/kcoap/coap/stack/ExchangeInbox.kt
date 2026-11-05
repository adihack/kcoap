package dev.kcoap.coap.stack

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.channels.Channel

/**
 * The bounded queue that the exchanges of one Q-Block body ([ResponseMode.Inbox]) report into, and that
 * the body's driver reads. Reporting never waits, so the receive loop is never held up by a slow driver: an
 * event that does not fit is dropped and counted in [lost], as if the network had lost the datagram, which
 * the RFC 9177 recovery (4.08 and missing-block requests, the NON_RECEIVE_TIMEOUT ladder) is built for.
 * Safe to use from any coroutine.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ExchangeInbox(val capacity: Int) {
    init {
        require(capacity >= 1) { "An inbox needs room for at least one event: $capacity" }
    }

    private val events = Channel<ExchangeEvent>(capacity)
    private val dropped = AtomicInt(0)

    /** Events dropped because the inbox was full. */
    val lost: Int get() = dropped.load()

    /** Queues [event]; false, and counted in [lost], when the inbox is full. */
    fun offer(event: ExchangeEvent): Boolean {
        val queued = events.trySend(event).isSuccess
        if (!queued) dropped.incrementAndFetch()
        return queued
    }

    /** The next event, waiting for one. */
    suspend fun receive(): ExchangeEvent = events.receive()

    /** The next event, or null when none is queued. */
    fun tryReceive(): ExchangeEvent? = events.tryReceive().getOrNull()

    override fun toString(): String = "ExchangeInbox(capacity=$capacity, lost=$lost)"

    companion object {
        /**
         * An inbox for one body under [parameters]: 4 × MAX_PAYLOADS + 8 events, room for several sets of
         * MAX_PAYLOADS responses (a set, the blocks sent again after a 4.08 or a missing-block request, and
         * their duplicates) and the outcomes of the body's requests, so a driver that is a little behind
         * loses nothing.
         */
        fun forBody(parameters: TransmissionParameters): ExchangeInbox = ExchangeInbox(4 * parameters.maxPayloads + 8)
    }
}
