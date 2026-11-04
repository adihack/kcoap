package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.transport.Endpoint

/**
 * What [UdpMessaging] asks its driver to do. The state machine never performs I/O itself: every call
 * appends effects, and the driver executes them in order.
 */
internal sealed interface MessagingEffect {
    /** Send the encoded message [bytes] to [to]. */
    class Transmit(val to: Endpoint, val bytes: ByteArray) : MessagingEffect

    /** A new (not duplicate) request arrived; answer it with [UdpMessaging.respond] or [UdpMessaging.reject]. */
    class DeliverRequest(val from: Endpoint, val message: CoapMessage) : MessagingEffect

    /**
     * A new separate response arrived in a Confirmable or Non-confirmable message. The upper layer must
     * call [UdpMessaging.acknowledge] if it matches an exchange, or [UdpMessaging.reject] if not.
     */
    class DeliverResponse(val from: Endpoint, val message: CoapMessage) : MessagingEffect

    /** A transmission ended or released its NSTART slot. */
    class Completed(val transmission: Transmission, val outcome: TransmissionOutcome) : MessagingEffect

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

    /** The transmission was abandoned: the DTLS session ended or the endpoint closed. */
    class Failed(val cause: CoapException) : TransmissionOutcome
}

/** Is told how a transmission it started ended. */
internal fun interface TransmissionOwner {
    suspend fun onOutcome(transmission: Transmission, outcome: TransmissionOutcome)
}
