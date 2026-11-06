package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * A request as the messaging layer delivered it, deduplicated but not yet verified by message security,
 * and the ways to answer it at the messaging layer, on the [link] it came on.
 */
internal class InboundRequest(
    /** The link the request came on, where its response must go (on a connection, that connection: RFC 8323 §3.3). */
    val link: PeerLink,
    /** The request as received, possibly OSCORE protected. */
    val message: CoapMessage,
    /** Its number on a connection, for Custody and Release (RFC 8323 §5.4.1, §5.5); -1 for a datagram. */
    val sequence: Long = -1,
) {
    /** The client's endpoint. */
    val peer: Endpoint get() = link.peer

    /** Over a link without message types every request is answered, as if Confirmable (RFC 8323 §3.3). */
    val isConfirmable: Boolean get() = !link.capabilities.hasMessageTypes || message.type == CoapMessageType.CON

    /**
     * Whether [respond], [finishWithoutResponse] or [reject] completed. A request on a connection that is
     * not answered when its handler ends, because it was cancelled or never ran, still gets an answer.
     */
    @Volatile
    var isAnswered: Boolean = false
        private set

    /**
     * Set when the request is answered, or dropped, before anything acted on it: message security rejected it,
     * the server refused it (access policy, unknown path, unrecognized critical option, proxying, a body too
     * large) without message security having verified it, or the endpoint was overloaded. Processing it again
     * then ends the same way, so its deduplication entry may be forgotten for room (RFC 7252 §4.5). A request
     * message security verified is never repeatable: verifying recorded it, as OSCORE records its Partial IV in
     * the replay window (RFC 8613 §7.4). Read on a datagram link when the answer goes out ([PeerLink.respond]),
     * or when the request is dropped ([PeerLink.leaveUnanswered]).
     */
    @Volatile
    var isRepeatable: Boolean = false

    /** What channel security authenticated: TLS on the connection, or the DTLS session of the endpoint. */
    val peerIdentity: PeerIdentity get() = link.peerIdentity

    /**
     * Sends [response] with the request's token: piggybacked if still possible, else in a message of its own,
     * Confirmable for a Confirmable request when [confirmable]; on a connection, on that connection. Returns
     * the delivery of a separate datagram response, whose outcome goes to [owner].
     */
    suspend fun respond(response: CoapMessage, owner: DeliveryOwner? = null, confirmable: Boolean = true): Delivery? {
        // RFC 7252 §5.3.1: the token is echoed unchanged; OSCORE does not protect it, so the stack keeps it.
        val withToken = if (response.hasToken(message.token)) response else response.toBuilder().apply { token = message.token }.build()
        return link.respond(this, withToken, owner, confirmable).also { isAnswered = true }
    }

    /** Sends an Empty ACK now, promising a separate response (RFC 7252 §5.2.2); nothing on a connection. */
    suspend fun acknowledge() {
        link.acknowledge(this)
    }

    /** The request is deliberately not answered (RFC 7967): an Empty ACK for a Confirmable datagram. */
    suspend fun finishWithoutResponse() {
        link.finishWithoutResponse(this)
        isAnswered = true
    }

    /** Rejects the request with a Reset, or silently for a Non-confirmable request or on a connection. */
    suspend fun reject(reason: String) {
        link.reject(this, reason)
        isAnswered = true
    }

    /** Leaves a Non-confirmable request without an answer ([PeerLink.leaveUnanswered]). */
    suspend fun leaveUnanswered() {
        link.leaveUnanswered(this)
    }

    override fun toString(): String = "InboundRequest($peer, $message)"
}

/** What the server side of an endpoint implements. */
internal interface RequestHandler {
    /**
     * Added to the context of every request coroutine: a server puts its own [kotlinx.coroutines.Job] here,
     * a child of the endpoint's, so that stopping the server cancels its handlers on a shared endpoint.
     */
    val context: CoroutineContext

    /** Processes one request; runs in its own coroutine. */
    suspend fun handle(request: InboundRequest)

    /**
     * Everything bound to [peer] ends, observations included: its DTLS session ended (RFC 7252 §9.1.1),
     * or its connection closed (RFC 8323 §7.4).
     */
    suspend fun onSessionEnded(peer: Endpoint) {}

    /** The endpoint closed: nothing arrives or goes out any more, so everything bound to it can go. */
    suspend fun onEndpointClosed()
}
