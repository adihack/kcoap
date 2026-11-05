package dev.kcoap.coap.stack

import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

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

    /**
     * We rejected a message with a Reset, or dropped it, because we lacked the context to process it. On
     * reliable transports, which have no Message IDs, [messageId] is 0.
     */
    public class MessageRejected(override val peer: Endpoint, public val messageId: Int, public val reason: String) : EndpointEvent {
        override fun toString(): String = "MessageRejected(peer=$peer, mid=$messageId, reason=$reason)"
    }

    /** The DTLS session to [peer] ended; its exchanges and observations were failed (RFC 7252 §9.1.1). */
    public class SessionEnded(override val peer: Endpoint) : EndpointEvent {
        override fun toString(): String = "SessionEnded(peer=$peer)"
    }

    /**
     * A connection to [peer] was opened ([isInitiator]) or accepted (RFC 8323); the CSM exchange follows.
     */
    public class ConnectionOpened(override val peer: Endpoint, public val isInitiator: Boolean) : EndpointEvent {
        override fun toString(): String = "ConnectionOpened(peer=$peer, initiator=$isInitiator)"
    }

    /**
     * The peer sent a CSM (RFC 8323 §5.3). The values are the settings in force after it, since CSM options
     * are cumulative: its Max-Message-Size, and whether it announced Block-Wise-Transfer.
     */
    public class CsmReceived(
        override val peer: Endpoint,
        public val maxMessageSize: Long,
        public val blockWiseTransfer: Boolean,
    ) : EndpointEvent {
        override fun toString(): String = "CsmReceived(peer=$peer, maxMessageSize=$maxMessageSize, blockWiseTransfer=$blockWiseTransfer)"
    }

    /**
     * The peer sent a Release (RFC 8323 §5.5): the connection takes no new requests and closes once the
     * requests in progress are answered. Clients do not reconnect to [peer] for [holdOff]. The
     * [alternativeAddresses] are reported, not followed: a connection there would not inherit the security
     * properties of this one (§10.1), so following them is the application's decision.
     */
    public class ReleaseReceived(
        override val peer: Endpoint,
        public val alternativeAddresses: List<String>,
        public val holdOff: Duration?,
        public val diagnostic: String?,
    ) : EndpointEvent {
        override fun toString(): String =
            "ReleaseReceived(peer=$peer, alternativeAddresses=$alternativeAddresses, holdOff=$holdOff, diagnostic=$diagnostic)"
    }

    /** The peer aborted the connection (RFC 8323 §5.6). */
    public class AbortReceived(override val peer: Endpoint, public val badCsmOption: Int?, public val diagnostic: String?) : EndpointEvent {
        override fun toString(): String = "AbortReceived(peer=$peer, badCsmOption=$badCsmOption, diagnostic=$diagnostic)"
    }

    /**
     * This endpoint aborted the connection (RFC 8323 §5.6), for example after a missing CSM, a message
     * format error, a message above its Max-Message-Size or an unknown critical signaling option.
     */
    public class AbortSent(override val peer: Endpoint, public val diagnostic: String, public val badCsmOption: Int?) : EndpointEvent {
        override fun toString(): String = "AbortSent(peer=$peer, diagnostic=$diagnostic, badCsmOption=$badCsmOption)"
    }

    /**
     * The connection to [peer] closed. Its exchanges failed with [dev.kcoap.coap.CoapException.Reason.CONNECTION_CLOSED]
     * and its observations ended (RFC 8323 §3.4, §7.4).
     */
    public class ConnectionClosed(override val peer: Endpoint, public val reason: String) : EndpointEvent {
        override fun toString(): String = "ConnectionClosed(peer=$peer, reason=$reason)"
    }

    /**
     * A resource bound was reached, so the endpoint dropped or refused something [peer] sent or asked for
     * instead of keeping more state (RFC 7252 §11.3). [limit] says which bound and so what happened; the
     * configuration property it names documents both.
     */
    public class LimitReached(override val peer: Endpoint, public val limit: Limit) : EndpointEvent {
        /** The bounds an endpoint keeps. */
        public enum class Limit {
            /** [CoapEndpointConfig.maxDeduplicationEntries]: a new message was dropped. */
            DEDUPLICATION_ENTRIES,

            /** [CoapEndpointConfig.maxConcurrentRequests]: a request was answered with 5.03, or dropped. */
            CONCURRENT_REQUESTS,

            /**
             * [CoapEndpointConfig.maxExchangesPerPeer]: a request failed, or a separate response or
             * notification was not sent.
             */
            EXCHANGES_PER_PEER,

            /** `CoapServerConfig.maxObservers`: a registration was served without Observe. */
            OBSERVERS,

            /** `CoapServerConfig.maxObserversPerResource`: a registration was served without Observe. */
            OBSERVERS_PER_RESOURCE,

            /** `CoapServerConfig.maxObserversPerPeer`: a registration was served without Observe. */
            OBSERVERS_PER_PEER,

            /** [ReliableTransportConfig.maxPendingConnections]: an accepted connection was closed. */
            PENDING_CONNECTIONS,
        }

        override fun toString(): String = "LimitReached(peer=$peer, limit=$limit)"
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
