package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity

/**
 * A request as the messaging layer delivered it, deduplicated but not yet verified by message security,
 * and the ways to answer it at the messaging layer.
 */
internal class InboundRequest(
    private val endpoint: CoapEndpoint,
    val peer: Endpoint,
    /** The request as received, possibly OSCORE protected. */
    val message: CoapMessage,
    /** The connection it came on (RFC 8323), where its response must go; null for a datagram. */
    val connection: ConnectionDriver? = null,
    /** Its number on [connection], for Custody and Release. */
    val sequence: Long = -1,
) {
    /** Over a reliable transport every request is answered, as if Confirmable (RFC 8323 §3.3). */
    val isConfirmable: Boolean get() = connection != null || message.type == CoapMessageType.CON

    /** What channel security authenticated: TLS on the connection, or the DTLS session of the endpoint. */
    val peerIdentity: PeerIdentity
        get() = connection?.peerIdentity ?: (peer as? Endpoint.Secure)?.identity ?: PeerIdentity.None

    /**
     * Sends [response] with the request's token: on the request's connection, or piggybacked if still
     * possible, else separately as [separateType] (Confirmable requests only). Returns the separate
     * datagram response's transmission.
     */
    suspend fun respond(
        response: CoapMessage,
        owner: TransmissionOwner? = null,
        separateType: CoapMessageType = CoapMessageType.CON,
    ): Transmission? {
        // RFC 7252 §5.3.1: the token is echoed unchanged; OSCORE does not protect it, so the stack keeps it.
        val withToken = if (response.hasToken(message.token)) response else response.toBuilder().apply { token = message.token }.build()
        return endpoint.respond(this, withToken, owner, separateType)
    }

    /** Sends an Empty ACK now, promising a separate response (RFC 7252 §5.2.2); nothing on a connection. */
    suspend fun acknowledge() {
        endpoint.acknowledge(this)
    }

    /** The request is deliberately not answered (RFC 7967): an Empty ACK for a Confirmable datagram. */
    suspend fun finishWithoutResponse() {
        endpoint.finishWithoutResponse(this)
    }

    /** Rejects the request with a Reset, or silently for a Non-confirmable request or on a connection. */
    suspend fun reject(reason: String) {
        endpoint.reject(this, reason)
    }

    override fun toString(): String = "InboundRequest($peer, $message)"
}

/** What the server side of an endpoint implements. */
internal interface RequestHandler {
    /** Processes one request; runs in its own coroutine. */
    suspend fun handle(request: InboundRequest)

    /**
     * Everything bound to [peer] ends, observations included: its DTLS session ended (RFC 7252 §9.1.1),
     * or its connection closed (RFC 8323 §7.4).
     */
    suspend fun onSessionEnded(peer: Endpoint) {}
}
