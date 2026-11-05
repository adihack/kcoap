package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-side message security chosen per server: a [dev.kcoap.coap.client.CoapClient] is created with
 * one `PeerSecurity`, and a protection is installed for a server once a handshake with it completes.
 * Requests to the EDHOC resource ([unprotectedPaths]) go out unprotected.
 *
 * ```
 * val security = PeerSecurity()
 * val client = CoapClient(transport, scope, CoapClientConfig(security = security))
 * val (_, protection) = EdhocClientHandshake.over(client, server, config, crypto).runCombined()
 * security.install(serverEndpoint, protection)
 * ```
 *
 * A request to a server without a protection fails with [MessageSecurityException] before anything is sent,
 * also after [remove], so a missing [install] never sends plaintext silently (ADR 0008). A client that talks to
 * some servers without message security sets [allowUnprotected]. That includes discovery before the handshake:
 * `client.discover(server)` asks `/.well-known/core`, which is not in [unprotectedPaths] by default, so finding the
 * EDHOC resource (`rt=core.edhoc`) takes a plain client, or `/.well-known/core` added to [unprotectedPaths]; so does
 * an EDHOC resource at another path than the default. A client with Q-Block in `QBlockMode.PROBE` under message
 * security needs nothing of the kind: its probe, refused until [install], leaves the request to the rules here.
 *
 * @param unprotectedPaths Uri-Paths always sent unprotected, by default the EDHOC resource.
 * @param allowUnprotected whether requests to a server without a protection go out unprotected; false by
 *   default, which fails them.
 */
public class PeerSecurity(
    private val unprotectedPaths: Set<String> = setOf("/${EdhocOverCoap.WELL_KNOWN_PATH}"),
    private val allowUnprotected: Boolean = false,
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

    /**
     * The installed protection's answer for [peer]; null for [unprotectedPaths], and for a server without a
     * protection when [allowUnprotected] is set.
     *
     * @throws MessageSecurityException when [peer] has no protection and [allowUnprotected] is not set.
     */
    override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
        if (request.options.path in unprotectedPaths) return null
        val security = lock.withLock { protections[peer] }
        if (security == null) {
            if (allowUnprotected) return null
            throw MessageSecurityException("No message security installed for $peer")
        }
        return security.protectRequest(request, peer)
    }
}
