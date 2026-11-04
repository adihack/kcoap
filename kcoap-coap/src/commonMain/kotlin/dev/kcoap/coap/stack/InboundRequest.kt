package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.transport.Endpoint

/**
 * A request as the messaging layer delivered it, deduplicated but not yet verified by message security,
 * and the ways to answer it at the messaging layer.
 */
internal class InboundRequest(
    private val endpoint: CoapEndpoint,
    val peer: Endpoint,
    /** The request as received, possibly OSCORE protected. */
    val message: CoapMessage,
) {
    val isConfirmable: Boolean get() = message.type == CoapMessageType.CON

    /**
     * Sends [response] with the request's token: piggybacked if still possible, else separately as
     * [separateType] (Confirmable requests only). Returns the separate response's transmission.
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

    /** Sends an Empty ACK now, promising a separate response (RFC 7252 §5.2.2). */
    suspend fun acknowledge() {
        endpoint.acknowledge(this)
    }

    /** Rejects the request with a Reset, or silently for a Non-confirmable request that is not answered. */
    suspend fun reject(reason: String) {
        endpoint.reject(this, reason)
    }

    override fun toString(): String = "InboundRequest($peer, $message)"
}

/** What the server side of an endpoint implements. */
internal interface RequestHandler {
    /** Processes one request; runs in its own coroutine. */
    suspend fun handle(request: InboundRequest)

    /** RFC 7252 §9.1.1: everything bound to [peer] ends, observations included. */
    suspend fun onSessionEnded(peer: Endpoint) {}
}
