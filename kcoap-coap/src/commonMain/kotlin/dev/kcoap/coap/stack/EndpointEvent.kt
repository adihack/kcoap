package dev.kcoap.coap.stack

import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/**
 * Something that happened on a [CoapEndpoint], for metrics and diagnostics. Messages themselves are
 * seen through a [MessageInterceptor]; events cover what has no message of its own.
 *
 * New subtypes may be added before 1.0, so a `when` over events should have an `else` branch.
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
     * The connection to [peer] closed. Its exchanges failed with [CoapException.Reason.CONNECTION_CLOSED]
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
        /** The bounds an endpoint keeps. New ones may be added before 1.0; use an `else` branch. */
        public enum class Limit {
            /**
             * [CoapEndpointConfig.maxDeduplicationEntries]: a new message from [peer] was dropped, because every
             * message remembered must still be processed at most once (RFC 7252 §4.5), or because it was a
             * non-idempotent request, which would have had to be remembered too, while three quarters of the bound
             * hold such messages.
             */
            DEDUPLICATION_ENTRIES,

            /**
             * [CoapEndpointConfig.maxDeduplicationEntriesPerPeer]: a new message from [peer] was dropped, because
             * every message remembered from [peer] must still be processed at most once (RFC 7252 §4.5).
             */
            DEDUPLICATION_ENTRIES_PER_PEER,

            /** [CoapEndpointConfig.maxConcurrentRequests]: a request was answered with 5.03, or dropped. */
            CONCURRENT_REQUESTS,

            /**
             * [CoapEndpointConfig.maxExchangesPerPeer]: a request failed, or a separate response or
             * notification was not sent.
             */
            EXCHANGES_PER_PEER,

            /**
             * [CoapEndpointConfig.maxExchanges]: a request to [peer] failed, a separate response to [peer] was not
             * sent, or a notification to [peer] was not sent yet (it goes later, with the latest state); or a
             * Confirmable message to [peer] that a retransmission had not got acknowledged either was given up
             * early, to make room for a new one.
             */
            EXCHANGES,

            /**
             * [CoapEndpointConfig.maxReservedMessageIds]: a message to [peer] that needed a new Message ID, a
             * request, a separate response or a notification, was not sent (RFC 7252 §4.4), a notification not
             * yet (it goes later, with the latest state). A response to a Non-confirmable request is refused
             * already at three quarters of the bound.
             */
            MESSAGE_IDS,

            /**
             * [CoapEndpointConfig.maxReservedMessageIdsPerPeer]: a message to [peer] that needed a new Message
             * ID was not sent (RFC 7252 §4.4), as for `MESSAGE_IDS`.
             */
            MESSAGE_IDS_PER_PEER,

            /** `CoapServerConfig.maxObservers`: a registration was served without Observe. */
            OBSERVERS,

            /** `CoapServerConfig.maxObserversPerResource`: a registration was served without Observe. */
            OBSERVERS_PER_RESOURCE,

            /** `CoapServerConfig.maxObserversPerPeer`: a registration was served without Observe. */
            OBSERVERS_PER_PEER,

            /** [ReliableTransportConfig.maxPendingConnections]: an accepted connection was closed. */
            PENDING_CONNECTIONS,

            /** [ReliableTransportConfig.maxConnections]: an accepted connection was closed. */
            CONNECTIONS,

            /**
             * `CoapServerConfig.amplificationFactor`: a response to an address not yet verified was held back,
             * and a 4.01 Unauthorized with Echo went to that address instead (RFC 9175 §2.4 item 3).
             */
            AMPLIFICATION_FACTOR,

            /**
             * `CoapServerConfig.maxVerifiedAddresses`: [peer], the address verified longest ago, was forgotten
             * to make room for a newly verified one; it is challenged again when it next needs to be verified.
             */
            VERIFIED_ADDRESSES,

            /**
             * `CoapServerConfig.maxVerifiedAddresses`: the oldest outstanding Echo challenge, the one sent to
             * [peer], was dropped for a new one; that Echo value no longer verifies the address.
             */
            ADDRESS_CHALLENGES,

            /**
             * `CoapServerConfig.maxConcurrentTransfers`: the response held back longest, that of a POST or
             * PATCH from [peer] kept for its repeat with Echo, was dropped for a new one (RFC 9175 §2.4
             * item 3). When that repeat comes, the request runs again.
             */
            HELD_BACK_RESPONSES,

            /**
             * `QBlockServerConfig.maxPartialBodies`: a Q-Block1 upload from [peer] that would start one more
             * partial body was refused with 5.03 Service Unavailable and Max-Age (RFC 9177 §4.3, RFC 7959 §7.1).
             */
            PARTIAL_BODIES,

            /**
             * `QBlockServerConfig.maxBodiesPerPeer`: a new Q-Block1 upload from [peer] was refused with 5.03 and
             * Max-Age, or the oldest Q-Block2 body kept for [peer] was dropped for a new one.
             */
            BODIES_PER_PEER,

            /**
             * `QBlockServerConfig.maxCachedBodies`: the Q-Block2 body, or the record of a completed Q-Block1
             * body, used longest ago, that of [peer], was dropped for a new one. A later request for its missing
             * blocks runs a GET or FETCH again and gets 4.08 for other methods (RFC 9177 §4.4).
             */
            CACHED_BODIES,
        }

        override fun toString(): String = "LimitReached(peer=$peer, limit=$limit)"
    }

    /**
     * Missing blocks of a Q-Block body (RFC 9177 §4.3, §4.4, §5) were asked for, by a server or a client
     * with Q-Block on. [isRequestedByPeer] is true when [peer] asked, with a 4.08 Request Entity Incomplete
     * listing blocks of an upload or a request for missing Q-Block2 blocks of a response, and this endpoint
     * sends [blocks] again; false when this endpoint asked [peer] for [blocks] the same ways. Many of these
     * towards one peer show a lossy path, for which RFC 9177 §7.2 suggests a smaller MAX_PAYLOADS.
     */
    public class QBlockRecovery(
        override val peer: Endpoint,
        public val isRequestedByPeer: Boolean,
        public val blocks: List<Long>,
    ) : EndpointEvent {
        override fun toString(): String = "QBlockRecovery(peer=$peer, requestedByPeer=$isRequestedByPeer, blocks=$blocks)"
    }

    /**
     * A client with Q-Block on (`QBlockClientConfig`) uses RFC 7959 block-wise transfer instead of Q-Block
     * towards [peer] (RFC 9177 §4.1), for [reason]: the probe was answered with 4.02 Bad Option, a Reset or a
     * response without Q-Block, or not at all, or the server rejected a Q-Block request with 4.02 or a Reset
     * before answering anything else, in which case that request is sent again with RFC 7959. Reported once
     * per finding, not per request.
     */
    public class QBlockFallback(override val peer: Endpoint, public val reason: String) : EndpointEvent {
        override fun toString(): String = "QBlockFallback(peer=$peer, reason=$reason)"
    }

    /**
     * A partially received Q-Block body from [peer], an upload on a server or a response body on a client,
     * was given up and its state dropped (RFC 9177 §4.3, §4.4, §7.2): its missing blocks were asked for
     * NON_MAX_RETRANSMIT times without success, no block came for NON_PARTIAL_TIMEOUT, the peer answered a
     * 4.08 with a Reset, or the peer's session ended; [reason] says which. On a client the request fails.
     */
    public class PartialBodyDropped(override val peer: Endpoint, public val reason: String) : EndpointEvent {
        override fun toString(): String = "PartialBodyDropped(peer=$peer, reason=$reason)"
    }

    /** A client was added to the observers of [path] (RFC 7641 §4.1). */
    public class ObserverAdded(override val peer: Endpoint, public val path: String) : EndpointEvent {
        override fun toString(): String = "ObserverAdded(peer=$peer, path=$path)"
    }

    /**
     * A client was removed from the observers of [path]; [reason] says why, a bounded set fit for counting, and
     * [detail], when there is one, adds what a log needs, such as the exception of a failed notification.
     */
    public class ObserverRemoved(
        override val peer: Endpoint,
        public val path: String,
        public val reason: Reason,
        public val detail: String? = null,
    ) : EndpointEvent {
        /** Why an observer was removed. New reasons may be added before 1.0; use an `else` branch. */
        public enum class Reason {
            /** The client deregistered with a GET or FETCH carrying Observe 1 (RFC 7641 §3.6). */
            DEREGISTERED,

            /** The client answered a notification with a Reset (RFC 7641 §3.6, §4.5). */
            RESET,

            /** A Confirmable notification was not acknowledged after MAX_RETRANSMIT retransmissions (RFC 7641 §4.5). */
            NOT_ACKNOWLEDGED,

            /** The resource was removed, and its observers got a final 4.04 Not Found (RFC 7641 §3.2). */
            RESOURCE_REMOVED,

            /**
             * The registration or a notification was answered with an error code, which ends the observation
             * (RFC 7641 §3.2, §4.2), or with the 4.01 and Echo of an address check (RFC 9175 §2.4).
             */
            ERROR_RESPONSE,

            /**
             * The DTLS session or the connection that carried the observation ended (RFC 7252 §9.1.1, RFC 8323
             * §7.4).
             */
            SESSION_ENDED,

            /**
             * A notification or the registration's response could not be produced, protected or sent, as when
             * message security or its sequence-number store fails, or a link failed; [detail] says how.
             */
            FAILED,
        }

        override fun toString(): String =
            "ObserverRemoved(peer=$peer, path=$path, reason=$reason" + (detail?.let { ", detail=$it" } ?: "") + ")"
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
