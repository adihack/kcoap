package dev.kcoap.coap.stack

import dev.kcoap.transport.Endpoint

/**
 * Something that happened on a [CoapEndpoint], for metrics and diagnostics. Messages themselves are
 * seen through a [MessageInterceptor]; events cover what has no message of its own.
 */
public sealed interface EndpointEvent {
    /** The peer the event concerns. */
    public val peer: Endpoint

    /** A Confirmable message was sent again (RFC 7252 §4.2); [attempt] counts from 1. */
    public class Retransmitted(override val peer: Endpoint, public val messageId: Int, public val attempt: Int) : EndpointEvent {
        override fun toString(): String = "Retransmitted(peer=$peer, mid=$messageId, attempt=$attempt)"
    }

    /** A Confirmable message was not acknowledged after MAX_RETRANSMIT retransmissions. */
    public class TransmissionTimedOut(override val peer: Endpoint, public val messageId: Int) : EndpointEvent {
        override fun toString(): String = "TransmissionTimedOut(peer=$peer, mid=$messageId)"
    }

    /** The peer answered one of our messages with a Reset. */
    public class ResetReceived(override val peer: Endpoint, public val messageId: Int) : EndpointEvent {
        override fun toString(): String = "ResetReceived(peer=$peer, mid=$messageId)"
    }

    /** A message arrived again within its deduplication lifetime (RFC 7252 §4.5). */
    public class DuplicateReceived(override val peer: Endpoint, public val messageId: Int) : EndpointEvent {
        override fun toString(): String = "DuplicateReceived(peer=$peer, mid=$messageId)"
    }

    /** A datagram was not a well-formed message (RFC 7252 §4.2); it was answered with a Reset if Confirmable. */
    public class MalformedMessage(override val peer: Endpoint, public val reason: String) : EndpointEvent {
        override fun toString(): String = "MalformedMessage(peer=$peer, reason=$reason)"
    }

    /** We rejected a message with a Reset, or dropped it, because we lacked the context to process it. */
    public class MessageRejected(override val peer: Endpoint, public val messageId: Int, public val reason: String) : EndpointEvent {
        override fun toString(): String = "MessageRejected(peer=$peer, mid=$messageId, reason=$reason)"
    }

    /** The DTLS session to [peer] ended; its exchanges and observations were failed (RFC 7252 §9.1.1). */
    public class SessionEnded(override val peer: Endpoint) : EndpointEvent {
        override fun toString(): String = "SessionEnded(peer=$peer)"
    }

    /** A client was added to the observers of [path] (RFC 7641 §4.1). */
    public class ObserverAdded(override val peer: Endpoint, public val path: String) : EndpointEvent {
        override fun toString(): String = "ObserverAdded(peer=$peer, path=$path)"
    }

    /** A client was removed from the observers of [path]; [reason] says why (RST, timeout, deregistration). */
    public class ObserverRemoved(override val peer: Endpoint, public val path: String, public val reason: String) : EndpointEvent {
        override fun toString(): String = "ObserverRemoved(peer=$peer, path=$path, reason=$reason)"
    }
}

/**
 * Receives [EndpointEvent]s. Called synchronously on the endpoint's coroutines, so it must be quick and
 * must not throw; exceptions are caught and logged.
 */
public fun interface EndpointEventListener {
    /** Called for every event. */
    public fun onEvent(event: EndpointEvent)

    public companion object {
        /** Ignores all events. */
        public val None: EndpointEventListener = EndpointEventListener { }
    }
}
