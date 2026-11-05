package dev.kcoap.coap

/**
 * A CoAP exchange that could not complete because of the peer, the network or the endpoint itself: no
 * acknowledgement, a Reset, a DTLS session that ended, a response that violates the protocol, or an
 * endpoint that is closed or whose transport failed. [reason] says which.
 *
 * Responses with error codes (4.xx, 5.xx) are not exceptions; they are returned as responses.
 */
public class CoapException(
    message: String,
    public val reason: Reason,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Why the exchange failed. */
    public enum class Reason {
        /** A Confirmable message was not acknowledged within MAX_TRANSMIT_WAIT (RFC 7252 §4.2). */
        TRANSMISSION_TIMEOUT,

        /** No response arrived in time after the request was sent or acknowledged. */
        RESPONSE_TIMEOUT,

        /** The peer rejected the message with a Reset (RFC 7252 §4.2, §4.3). */
        RESET,

        /** The DTLS session to the peer ended; RFC 7252 §9.1.1 forbids retransmitting across epochs. */
        SESSION_ENDED,

        /**
         * The endpoint was closed: by its `close()`, or because the scope it runs in was cancelled. Requests
         * open at that moment fail with it, and so does every request after it.
         */
        CLOSED,

        /**
         * The endpoint's own transport failed, not the peer: its datagram transport stopped receiving, which
         * closes the endpoint for good, or refused to carry a message to the peer at all, which no
         * retransmission can fix. The cause is the transport's exception.
         */
        TRANSPORT_FAILED,

        /** A response violated the protocol, for example an unrecognized critical option (RFC 7252 §5.4.1). */
        BAD_RESPONSE,

        /** A block-wise transfer could not be completed (RFC 7959), for example blocks of changing ETags. */
        BLOCKWISE_FAILED,

        /** A message did not fit into the transport's datagram size. */
        MESSAGE_TOO_LARGE,

        /** All Message IDs or tokens for a peer are in use (RFC 7252 §4.4, §5.3.1). */
        IDENTIFIERS_EXHAUSTED,

        /**
         * The connection to the peer (RFC 8323) could not be opened, or it closed, was released or aborted
         * before the exchange completed. The message says which; RFC 8323 §3.4 leaves reconnecting to the
         * application.
         */
        CONNECTION_CLOSED,
    }
}
