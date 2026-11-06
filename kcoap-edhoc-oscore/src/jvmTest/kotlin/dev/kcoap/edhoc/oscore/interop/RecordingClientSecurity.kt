package dev.kcoap.edhoc.oscore.interop

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.transport.Endpoint
import java.util.Collections

/**
 * [delegate], recording both sides of what OSCORE hides on the wire: every request before and after protection,
 * and every response as it arrived and as verification left it.
 */
internal class RecordingClientSecurity(private val delegate: ClientMessageSecurity) : ClientMessageSecurity {
    /**
     * One protected exchange with [peer]: the [request] as the client stated it and the message [sent] for it,
     * then the response as it [arrived] and what verification made of it, the [response].
     */
    class Exchange(val request: CoapMessage, val sent: CoapMessage, val peer: Endpoint) {
        @Volatile
        var arrived: CoapMessage? = null

        @Volatile
        var response: ResponseVerification? = null
    }

    /** The protected exchanges so far, in order. */
    val exchanges: MutableList<Exchange> = Collections.synchronizedList(mutableListOf())

    override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
        val protected = delegate.protectRequest(request, peer) ?: return null
        val exchange = Exchange(request, protected.message, peer).also { exchanges += it }
        return object : ProtectedRequest {
            override val message: CoapMessage get() = protected.message

            override suspend fun unprotectResponse(response: CoapMessage): ResponseVerification {
                exchange.arrived = response
                return protected.unprotectResponse(response).also { exchange.response = it }
            }
        }
    }
}
