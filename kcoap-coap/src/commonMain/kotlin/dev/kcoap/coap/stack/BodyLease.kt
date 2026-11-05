package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred

/**
 * The one NSTART slot (RFC 7252 §4.7) that a whole Non-confirmable Q-Block body holds, from its first
 * message to its end. RFC 9177 §7.2 applies PROBING_RATE per body, not per block, so the messages of the
 * body (blocks, Continues and missing-block requests) go out under [Pacing.Body] without a slot of their
 * own: a burst of MAX_PAYLOADS blocks is not held up by NSTART 1, and the body still counts as one
 * interaction towards the peer, so a second body to the same peer waits for it.
 *
 * A lease waits in the peer's NSTART queue like a [Transmission] ([State.QUEUED]). Once granted
 * ([State.HELD]) it holds the slot until the body ends ([CoapEndpoint.releaseBody]), or for [hold] at most:
 * the body's bytes at PROBING_RATE, capped at NON_PROBING_WAIT (§7.2). A peer that never answers is
 * therefore probed no faster than PROBING_RATE, and the next body waits no longer than NON_PROBING_WAIT;
 * after that the lease has [State.EXPIRED] and the body may go on without a slot.
 *
 * A Confirmable body takes no lease: its blocks take a slot each, so NSTART runs them in lock-step (§7.1),
 * and a lease next to them would deadlock at NSTART 1. On a reliable transport there is no NSTART
 * (RFC 8323 §2), and the lease is granted at once and paces nothing ([isPaced] false).
 *
 * Owned and mutated by [UdpMessaging] under the endpoint's lock; the driver holds it as a handle.
 */
internal class BodyLease(
    override val peer: Endpoint,
    /** How long the lease holds its slot at most once granted: min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT). */
    val hold: Duration,
    /** NON_TIMEOUT_RANDOM of the body (RFC 9177 §7.2), drawn once per body; the body's Q-Block cores use it. */
    val nonTimeoutRandom: Duration,
    /** False on a reliable transport, where the lease takes no slot. */
    val isPaced: Boolean = true,
) : SlotHolder {
    /** Where the lease is; changed under the endpoint's lock, safe to read from any coroutine. */
    @Volatile
    var state: State = State.CREATED

    /** When a held lease expires: [hold] after it was granted. */
    var deadline: Duration = Duration.ZERO

    /** Why the lease [State.FAILED]: the endpoint closed or the peer's DTLS session ended. */
    var failure: CoapException? = null

    /** Completes when the lease is granted, or fails with [failure]. */
    val granted: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * Throws unless a message may go out under this lease: it must have been granted and not released.
     * After it expired the body may go on; only the next body no longer waits for it.
     */
    fun checkUsable() {
        when (state) {
            State.HELD, State.EXPIRED -> Unit
            State.FAILED -> failure?.let { throw CoapException(it.message ?: "Body lease failed", it.reason, it.cause) }
            State.CREATED, State.QUEUED -> throw IllegalStateException("The body lease towards $peer is not granted yet")
            State.RELEASED -> throw IllegalStateException("The body lease towards $peer was released: its body has ended")
        }
    }

    enum class State {
        /** Not yet handed to the messaging layer. */
        CREATED,

        /** Waiting for an NSTART slot. */
        QUEUED,

        /** Holding the slot. */
        HELD,

        /** Held the slot for [hold]; the body may go on, but takes no slot any more. */
        EXPIRED,

        /** The body ended and gave the slot back. */
        RELEASED,

        /** The endpoint closed or the peer's session ended; see [failure]. */
        FAILED,
    }

    override fun toString(): String = "BodyLease($peer, $state, hold=$hold)"

    companion object {
        /** A lease towards a reliable peer: granted at once, holding nothing (RFC 8323 has no NSTART). */
        fun unpaced(peer: Endpoint, nonTimeoutRandom: Duration): BodyLease =
            BodyLease(peer, Duration.ZERO, nonTimeoutRandom, isPaced = false).apply {
                state = State.HELD
                granted.complete(Unit)
            }
    }
}
