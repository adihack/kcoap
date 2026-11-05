package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.transport.Endpoint

/**
 * What [UdpMessaging] asks its driver to do. The state machine never performs I/O itself: every call
 * appends effects, and the driver executes them in order.
 */
internal sealed interface MessagingEffect {
    /**
     * Send the encoded message [bytes] to [to]. [transmission] is the Confirmable or Non-confirmable
     * message they carry, so a send the transport refuses outright can fail it at once; null for ACKs,
     * Resets and piggybacked responses.
     */
    class Transmit(val to: Endpoint, val bytes: ByteArray, val transmission: Transmission? = null) : MessagingEffect

    /** A new (not duplicate) request arrived; answer it with [UdpMessaging.respond] or [UdpMessaging.reject]. */
    class DeliverRequest(val from: Endpoint, val message: CoapMessage) : MessagingEffect

    /**
     * A new separate response arrived in a Confirmable or Non-confirmable message. The upper layer must
     * answer it, with [UdpMessaging.acknowledge] if it accepts it, or [UdpMessaging.reject] if it matches
     * no exchange or has a critical option the exchange does not recognize (RFC 7252 §5.4.1). No timer
     * answers a Confirmable one in its place.
     */
    class DeliverResponse(val from: Endpoint, val message: CoapMessage) : MessagingEffect

    /** A transmission ended or released its NSTART slot. */
    class Completed(val transmission: Transmission, val outcome: TransmissionOutcome) : MessagingEffect

    /** A [BodyLease] got its NSTART slot; its body may start. */
    class LeaseGranted(val lease: BodyLease) : MessagingEffect

    /** A [BodyLease] failed with [cause]: the endpoint closed or the peer's DTLS session ended. */
    class LeaseFailed(val lease: BodyLease, val cause: CoapException) : MessagingEffect

    /** Something worth reporting to [EndpointEventListener]. */
    class Event(val event: EndpointEvent) : MessagingEffect
}

/** How a [Transmission] ended. */
internal sealed interface TransmissionOutcome {
    /** The peer acknowledged a Confirmable message; [piggybackedResponse] is set when it answered a request. */
    class Acknowledged(val piggybackedResponse: CoapMessage?) : TransmissionOutcome

    /** The peer rejected the message with a Reset. */
    data object Reset : TransmissionOutcome

    /** MAX_RETRANSMIT retransmissions went unacknowledged. */
    data object TimedOut : TransmissionOutcome

    /** A Non-confirmable message's NSTART slot was released after its hold time. The message stays matchable to a Reset. */
    data object SlotReleased : TransmissionOutcome

    /** The transmission was abandoned: the DTLS session ended, the endpoint closed, or the transport refused it. */
    class Failed(val cause: CoapException) : TransmissionOutcome
}

/** Is told how a transmission it started ended. */
internal fun interface TransmissionOwner {
    suspend fun onOutcome(transmission: Transmission, outcome: TransmissionOutcome)
}
