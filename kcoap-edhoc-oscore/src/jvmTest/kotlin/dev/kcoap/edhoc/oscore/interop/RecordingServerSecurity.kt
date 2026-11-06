package dev.kcoap.edhoc.oscore.interop

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.transport.Endpoint
import java.util.Collections

/**
 * [delegate], recording every request as it arrived, before verification, with the peer it came from and what
 * verification made of it.
 */
internal class RecordingServerSecurity(private val delegate: ServerMessageSecurity) : ServerMessageSecurity {
    /** One request: as it [arrived] from [peer], and [verified] when it was protected and verified. */
    class Arrival(val arrived: CoapMessage, val peer: Endpoint, val verified: UnprotectedRequest?)

    /** The requests so far, in order. */
    val arrivals: MutableList<Arrival> = Collections.synchronizedList(mutableListOf())

    override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
        val verified = try {
            delegate.unprotectRequest(request, peer)
        } catch (e: Exception) {
            arrivals += Arrival(request, peer, null)
            throw e
        }
        arrivals += Arrival(request, peer, verified)
        return verified
    }
}
