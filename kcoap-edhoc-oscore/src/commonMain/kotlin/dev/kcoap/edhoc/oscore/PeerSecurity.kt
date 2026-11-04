package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-side message security chosen per server: a [dev.kcoap.coap.client.CoapClient] is created with
 * one `PeerSecurity`, and a protection is installed for a server once a handshake with it completes.
 * Requests to servers without one, and requests to the EDHOC resource itself, go out unprotected.
 *
 * ```
 * val security = PeerSecurity()
 * val client = CoapClient(transport, scope, CoapClientConfig(security = security))
 * val (_, protection) = EdhocClientHandshake.over(client, server, config, crypto).runCombined()
 * security.install(serverEndpoint, protection)
 * ```
 *
 * @param unprotectedPaths Uri-Paths always sent unprotected, by default the EDHOC resource.
 */
public class PeerSecurity(
    private val unprotectedPaths: Set<String> = setOf("/${EdhocOverCoap.WELL_KNOWN_PATH}"),
) : ClientMessageSecurity {
    private val lock = Mutex()
    private val protections = mutableMapOf<Endpoint, ClientMessageSecurity>()

    public suspend fun install(peer: Endpoint, security: ClientMessageSecurity) {
        lock.withLock { protections[peer] = security }
    }

    public suspend fun remove(peer: Endpoint) {
        lock.withLock { protections.remove(peer) }
    }

    public suspend fun isProtected(peer: Endpoint): Boolean = lock.withLock { peer in protections }

    override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
        if (request.options.path in unprotectedPaths) return null
        val security = lock.withLock { protections[peer] } ?: return null
        return security.protectRequest(request, peer)
    }
}
