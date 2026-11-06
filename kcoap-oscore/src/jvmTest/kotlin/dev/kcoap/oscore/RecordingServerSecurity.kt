package dev.kcoap.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.transport.Endpoint
import java.util.Collections

/**
 * An [OscoreServer] that records what OSCORE sees on the server side: every request it verified, as the inner
 * request and the freshness challenge (RFC 8613 Appendix B.1.2) the stack answers it with instead of processing
 * it, if any. The challenge travels encrypted, so only here, not on the wire, is its Echo option visible. Safe to
 * use from any coroutine.
 */
internal class RecordingServerSecurity(private val oscore: OscoreServer) : ServerMessageSecurity {
    /** A request [oscore] verified: the inner [message], and the protected 4.01 with Echo it is answered with, if any. */
    class Verified(val message: CoapMessage, val challenge: CoapMessage?) {
        override fun toString(): String = "${message.code} ${message.options.path}" + (challenge?.let { " -> ${it.code}" } ?: "")
    }

    val requests: MutableList<Verified> = Collections.synchronizedList(mutableListOf())

    override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? =
        oscore.unprotectRequest(request, peer)?.also { requests += Verified(it.message, it.challenge) }
}
