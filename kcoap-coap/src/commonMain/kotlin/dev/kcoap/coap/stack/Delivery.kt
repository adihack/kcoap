package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage

/**
 * A message a [PeerLink] sent on its own, as a handle for the layers above: they ask whether it still counts
 * as outstanding, and hand it back to [PeerLink.settle] or [PeerLink.supersede]. On a datagram link it is the
 * message's [Transmission]; on a connection, where a message is written and done, it is [Written].
 */
internal sealed interface Delivery {
    /**
     * Whether it still counts as outstanding (RFC 7641 §4.5.1): queued, or a Confirmable message awaiting its
     * ACK, or a Non-confirmable one holding its NSTART slot. Safe to read from any coroutine.
     */
    val isOutstanding: Boolean

    /** Whether the peer acknowledges it: a Confirmable message (RFC 7252 §4.2). */
    val isConfirmable: Boolean

    /** Whether the link stopped working on it. */
    val isDone: Boolean

    /** Whether a newer message replaces it at its next timeout (RFC 7641 §4.5.2). */
    val isSuperseded: Boolean

    /** A message written on a connection: done once written, since RFC 8323 has no acknowledgements (§3.1). */
    data object Written : Delivery {
        override val isOutstanding: Boolean get() = false
        override val isConfirmable: Boolean get() = false
        override val isDone: Boolean get() = true
        override val isSuperseded: Boolean get() = false
    }
}

/** How a [Delivery] ended, as its [PeerLink] reports it to its [DeliveryOwner]. */
internal sealed interface DeliveryOutcome {
    /**
     * The peer acknowledged it (RFC 7252 §4.2); [piggybacked] is the response, when the ACK of a request
     * carried one (§5.2.1).
     */
    class Delivered(val piggybacked: CoapMessage?) : DeliveryOutcome {
        override fun toString(): String = "Delivered(piggybacked=${piggybacked?.code})"
    }

    /**
     * It no longer counts as outstanding, without word from the peer: a Non-confirmable message held its
     * NSTART slot for as long as it was asked to (RFC 7252 §4.7). It stays matchable to a Reset.
     */
    data object Released : DeliveryOutcome

    /** The peer rejected it with a Reset (RFC 7252 §4.2, §4.3). */
    data object Rejected : DeliveryOutcome

    /** MAX_RETRANSMIT retransmissions went unacknowledged (RFC 7252 §4.2). */
    data object TimedOut : DeliveryOutcome

    /**
     * It could not be delivered for [cause]: the endpoint closed, the peer's DTLS session ended, its
     * connection closed, or the transport refused it.
     */
    class Failed(val cause: CoapException) : DeliveryOutcome {
        override fun toString(): String = "Failed(${cause.reason})"
    }
}

/** Is told how a [Delivery] it started ended. */
internal fun interface DeliveryOwner {
    suspend fun onOutcome(delivery: Delivery, outcome: DeliveryOutcome)
}
