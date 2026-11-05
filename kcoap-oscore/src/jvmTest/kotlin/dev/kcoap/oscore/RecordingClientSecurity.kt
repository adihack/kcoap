package dev.kcoap.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.transport.Endpoint
import java.util.Collections

/**
 * An [OscoreClient] that records what OSCORE sees on the client side: the requests before protection
 * ([requests]), the responses after verification ([responses]), and the reason for each response it refused
 * ([rejected]). Safe to use from any coroutine.
 */
internal class RecordingClientSecurity(private val oscore: OscoreClient) : ClientMessageSecurity {
    val requests: MutableList<CoapMessage> = Collections.synchronizedList(mutableListOf())
    val responses: MutableList<CoapMessage> = Collections.synchronizedList(mutableListOf())
    val rejected: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
        requests += request
        val protected = oscore.protectRequest(request, peer) ?: return null
        return object : ProtectedRequest {
            override val message: CoapMessage get() = protected.message

            override suspend fun unprotectResponse(response: CoapMessage): CoapMessage = try {
                protected.unprotectResponse(response).also { responses += it }
            } catch (e: MessageSecurityException) {
                rejected += e.message.orEmpty()
                throw e
            }
        }
    }
}
