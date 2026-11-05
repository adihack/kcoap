package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-side message security chosen per server: a [CoapClient] is created with one `PeerSecurity`, and a
 * protection is installed for a server once a handshake with it completes. Requests to the EDHOC resource
 * ([unprotectedPaths]) go out unprotected.
 *
 * ```
 * val security = PeerSecurity()
 * val client = CoapClient(transport, scope, CoapClientConfig(security = security))
 * val server = CoapUri.parse("coap://192.0.2.1")
 * val (_, protection) = EdhocClientHandshake.over(client, server, config, crypto).runCombined()
 * security.install(client, server, protection)
 * client.get("coap://192.0.2.1/lights")
 * ```
 *
 * Protections are kept per transport [Endpoint], the peer the client's requests go to, which is also all the
 * [ClientMessageSecurity] SPI sees of a request's destination. [install] with a [CoapUri] finds that endpoint
 * with the client's own resolver, as the client does for its requests.
 *
 * A request to a server without a protection fails with [MessageSecurityException] before anything is sent,
 * also after [remove], so a missing [install] never sends plaintext silently (ADR 0008). A client that talks to
 * some servers without message security sets [allowUnprotected]. That includes discovery before the handshake:
 * `client.discover(server)` asks `/.well-known/core`, which is not in [unprotectedPaths] by default, so finding the
 * EDHOC resource (`rt=core.edhoc`) takes a plain client, or `/.well-known/core` added to [unprotectedPaths]; so does
 * an EDHOC resource at another path than the default. A client with Q-Block in `QBlockMode.PROBE` under message
 * security needs nothing of the kind: its probe, refused until [install], leaves the request to the rules here.
 *
 * An application that uses OSCORE without EDHOC routes its contexts per peer with kcoap-oscore's
 * `OscoreClient(ClientContextResolver)` instead.
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

    /** Protects every request to [peer] with [security] from now on, replacing what was installed for it. */
    public suspend fun install(peer: Endpoint, security: ClientMessageSecurity) {
        lock.withLock { protections[peer] = security }
    }

    /**
     * Protects every request that [client] sends to the server of [server] with [security] from now on: the
     * endpoint of [server]'s scheme, host and port, which the client's own resolver
     * ([dev.kcoap.coap.client.CoapClientConfig.resolver]) finds, as for the client's requests. A resolver that
     * maps one name to different addresses over time needs [install] again for each.
     *
     * @return the endpoint the protection was installed for.
     * @throws IllegalArgumentException when the resolver cannot resolve the host of [server].
     */
    public suspend fun install(client: CoapClient, server: CoapUri, security: ClientMessageSecurity): Endpoint {
        val peer = client.config.resolver.resolve(server)
        install(peer, security)
        return peer
    }

    /** Removes the protection for [peer]: requests to it fail again, unless [allowUnprotected]. */
    public suspend fun remove(peer: Endpoint) {
        lock.withLock { protections.remove(peer) }
    }

    /** Whether a protection is installed for [peer]. */
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
