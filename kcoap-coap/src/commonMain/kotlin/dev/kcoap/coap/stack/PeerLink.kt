package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.time.Duration

/**
 * How the exchange layer of a [CoapEndpoint] reaches one peer: the seam between tokens, matching, Block-wise,
 * Observe and message security above, and the messaging layer of one transport shape below (ADR 0005).
 *
 * - [DatagramLink] runs RFC 7252 messaging over a [dev.kcoap.transport.DatagramTransport]: Confirmable and
 *   Non-confirmable messages, Message IDs, ACKs and Resets, retransmission, NSTART and body leases.
 * - [ConnectionLink] runs RFC 8323 messaging on one connection: no message types, no Reset, CSM limits and
 *   Custody, every request answered.
 *
 * Code above a link sees [limits], [capabilities] and how what it sent ended ([DeliveryOutcome]), never a
 * transmission or a connection. Every call is safe from any coroutine; a link serializes what it does under
 * a lock of its own, never held across a suspension.
 */
internal interface PeerLink {
    /** The peer, compared by [Endpoint] equality; on a connection, the one the connection goes to. */
    val peer: Endpoint

    /** What one message to [peer] may be: the datagram size, or the peer's Max-Message-Size (RFC 8323 §5.3.1). */
    val limits: MessageLimits

    /** What the link does, which decides what the layers above may expect of it. */
    val capabilities: LinkCapabilities

    /** What channel security authenticated about [peer]: the DTLS session of the endpoint, or TLS on the connection. */
    val peerIdentity: PeerIdentity

    // ---------------------------------------------------------------- requests this side sends

    /**
     * Checks [pacing] for a request that goes [confirmable] on this link before anything is registered for
     * it: a body lease paces Non-confirmable messages to [peer] only (RFC 9177 §7.1, §7.2).
     *
     * @throws IllegalArgumentException for a lease towards another peer, or a Confirmable request with one.
     */
    fun checkPacing(confirmable: Boolean, pacing: Pacing)

    /**
     * [request] with the header it goes out with on this link and [token]: a message type (Confirmable when
     * [confirmable]) and a new Message ID on a datagram link (RFC 7252 §4.4), only the token on a connection,
     * which has neither (RFC 8323 §3.2). Message security protects it as it is, and the header is kept.
     *
     * @throws CoapException like [CoapEndpoint.closeCause] once the endpoint is closed.
     */
    suspend fun address(request: CoapMessage, token: ByteArray, confirmable: Boolean): CoapMessage

    /**
     * Sends [request], [address]ed and protected, and reports how it ends to [owner]. [queued] gets its
     * [Delivery] under the link's lock, before anything goes out, so a caller cancelled meanwhile can still
     * [settle] it. A connection writes it in order and reports nothing ([Delivery.Written]).
     *
     * @throws CoapException with [CoapException.Reason.MESSAGE_TOO_LARGE], with
     *   [CoapException.Reason.IDENTIFIERS_EXHAUSTED] beyond [CoapEndpointConfig.maxExchangesPerPeer] slot
     *   holders, with [CoapException.Reason.CONNECTION_CLOSED] on a closed connection, or like
     *   [CoapEndpoint.closeCause] once the endpoint is closed.
     * @throws IllegalStateException for a body lease that is not granted or already released.
     */
    suspend fun sendRequest(request: CoapMessage, pacing: Pacing, owner: DeliveryOwner, queued: (Delivery) -> Unit)

    /**
     * Stops working on [delivery] without an outcome: a request answered, or cancelled. Frees what it held,
     * such as its NSTART slot. Cleanup, so it runs to the end in a cancelled coroutine too.
     */
    suspend fun settle(delivery: Delivery)

    /**
     * An exchange this link counted ended; [token] is its token if no newer exchange took it over. A
     * connection uses it for its idle Release (RFC 8323 §5.5); a datagram link counts nothing.
     */
    suspend fun exchangeClosed(token: String?)

    /**
     * A CoAP ping: an Empty Confirmable message answered with a Reset (RFC 7252 §4.3), or a Ping answered
     * with a Pong (RFC 8323 §5.4), the latter only once the peer processed every request before it when
     * [custody] is set (§5.4.1). True if the peer answered.
     */
    suspend fun ping(custody: Boolean): Boolean

    /**
     * Takes the NSTART slot of a Non-confirmable Q-Block body of [bodyBytes] bytes (RFC 9177 §7.2), waiting
     * while [peer]'s slots are taken; see [CoapEndpoint.leaseBody]. A connection has no NSTART (RFC 8323 §2):
     * its lease is granted at once and paces nothing.
     */
    suspend fun leaseBody(bodyBytes: Int, nonTimeoutRandom: Duration?): BodyLease

    // ---------------------------------------------------------------- responses to them

    /**
     * A response that came on its own, matched and taken by the exchange layer, or dropped after it failed
     * verification: a Confirmable one is acknowledged (RFC 7252 §5.2.2). Nothing on a connection.
     */
    suspend fun acceptResponse(response: CoapMessage)

    /**
     * A response the exchange layer matched but could not take now: the [ExchangeInbox] of its body was full, which
     * for a Confirmable one is found before it is verified ([CoapEndpoint.onResponse]). A Confirmable one gets no
     * ACK, which would tell the sender that it arrived (RFC 7252 §4.2), and deduplication forgets it (§4.5), so the
     * retransmission its sender sends after ACK_TIMEOUT is delivered as new. A Non-confirmable one counts as lost.
     * Nothing on a connection, which delivers every response once and whose body inbox has room for every block
     * (RFC 9177 Appendix B, [ExchangeInbox.forReliableBody]).
     */
    suspend fun deferResponse(response: CoapMessage)

    /**
     * A response the exchange layer rejects for [reason], with [detail] for a log: it matches no exchange
     * (RFC 7252 §5.3.2, RFC 7641 §3.5), or carries a critical option the exchange does not recognize (§5.4.1). A
     * datagram link answers it with a Reset; a connection has none (RFC 8323 §7.2) and drops it. Either reports
     * an [EndpointEvent.MessageRejected].
     */
    suspend fun rejectResponse(response: CoapMessage, reason: EndpointEvent.MessageRejected.Reason, detail: String? = null)

    // ---------------------------------------------------------------- requests this side receives

    /**
     * Answers [request] with [response]: piggybacked if still possible, else in a message of its own, which
     * goes Confirmable only for a Confirmable request when [confirmable] (RFC 7252 §5.2.2); on a connection
     * in the order the requests came (RFC 8323 §3.3). Returns the [Delivery] of a separate response, whose
     * outcome goes to [owner]; null when it was piggybacked or written on a connection.
     */
    suspend fun respond(request: InboundRequest, response: CoapMessage, owner: DeliveryOwner?, confirmable: Boolean): Delivery?

    /** Promises a separate response with an Empty ACK (RFC 7252 §5.2.2); a connection has none (RFC 8323 §3.1). */
    suspend fun acknowledge(request: InboundRequest)

    /**
     * [request] is done without a response (RFC 7967): an Empty ACK for a Confirmable datagram, and on a
     * connection a note for Custody and Release (RFC 8323 §5.4.1, §5.5).
     */
    suspend fun finishWithoutResponse(request: InboundRequest)

    /**
     * Rejects [request] for [reason], with [detail] for a log: with a Reset over datagrams (RFC 7252 §4.2, §4.3),
     * reported as an [EndpointEvent.MessageRejected], silently on a connection, which has no Reset (RFC 8323 §3.1).
     */
    suspend fun reject(request: InboundRequest, reason: EndpointEvent.MessageRejected.Reason, detail: String? = null)

    /**
     * Leaves [request], a Non-confirmable one, without an answer, as an overloaded endpoint drops it. When nothing
     * acted on it ([InboundRequest.isRepeatable]), a datagram link lets deduplication forget it for room (RFC 7252
     * §4.5). A connection answers every request (RFC 8323 §3.3), so it is never asked.
     */
    suspend fun leaveUnanswered(request: InboundRequest)

    // ---------------------------------------------------------------- messages this side starts

    /**
     * Sends a notification or another response in a message of its own: Confirmable when [confirmable] and
     * the link has message types, holding its NSTART slot for [slotHold] when Non-confirmable (RFC 7641
     * §4.5.1). Its outcome goes to [owner]; on a connection it is written and done, and a closed connection
     * is reported to [owner] as [DeliveryOutcome.Failed].
     *
     * @throws CoapException like [CoapEndpoint.closeCause] once the endpoint is closed, or as [sendRequest]
     *   throws on a datagram link.
     */
    suspend fun notify(message: CoapMessage, confirmable: Boolean, owner: DeliveryOwner, slotHold: Duration?): Delivery

    /**
     * Replaces the outstanding Confirmable notification [delivery] with [message] at its next timeout
     * (RFC 7641 §4.5.2). False when it is not outstanding, or the link has nothing to supersede, so the
     * caller sends [message] with [notify].
     */
    suspend fun supersede(delivery: Delivery, message: CoapMessage): Boolean

    /**
     * How the notifications of one observation registered at [registeredAt] go on this link (RFC 7641 §4.5,
     * §7): a datagram link chooses Confirmable or Non-confirmable for each, interspersing Confirmable ones
     * every [confirmableInterval] and, to a client that [isAuthenticated] is false for, every few
     * notifications; a connection has no message types and ignores the choice.
     */
    fun notificationPolicy(registeredAt: Duration, isAuthenticated: Boolean, confirmableInterval: Duration): NotificationPolicy
}

/**
 * What a [PeerLink] does: the layers above ask these instead of the transport, so Block-wise, Observe, Q-Block
 * and message security behave the same on every link.
 *
 * @property hasReset whether the peer can reject a message with a Reset (RFC 7252 §4.2), which also ends an
 *   observation whose token the client forgot (RFC 7641 §3.6); RFC 8323 has none, so there the client must
 *   deregister.
 * @property hasMessageTypes whether messages are Confirmable or Non-confirmable (RFC 7252 §4): only then do
 *   ACKs, retransmissions, NSTART and Q-Block's sets and timers (RFC 9177 §7) exist. Without them every request
 *   is answered (RFC 8323 §3.3) and a Q-Block body goes back to back (RFC 9177 Appendix B).
 * @property isOrdered whether messages arrive in the order they were sent, so Observe values are not needed
 *   to order notifications (RFC 8323 §7.1).
 * @property verifiesAddress whether setting the link up showed that the peer receives at its address, as
 *   the handshake of a connection does; then no address verification is needed before a large response
 *   (RFC 9175 §2.4 item 3).
 */
internal class LinkCapabilities private constructor(
    val hasReset: Boolean,
    val hasMessageTypes: Boolean,
    val isOrdered: Boolean,
    val verifiesAddress: Boolean,
) {
    override fun toString(): String = if (this === DATAGRAM) "datagram" else "connection"

    companion object {
        /** RFC 7252 messaging over datagrams. */
        val DATAGRAM: LinkCapabilities = LinkCapabilities(hasReset = true, hasMessageTypes = true, isOrdered = false, verifiesAddress = false)

        /** RFC 8323 messaging on a connection. */
        val CONNECTION: LinkCapabilities = LinkCapabilities(hasReset = false, hasMessageTypes = false, isOrdered = true, verifiesAddress = true)
    }
}
