package dev.kcoap.coap.server

import dev.kcoap.transport.PeerIdentity

/**
 * Who may use a resource (ADR 0008). A [CoapServer] asks the policy that governs the resource a request is for
 * right after it found the resource, before it does any work for the request: a refused request gets 4.01
 * Unauthorized, or 4.03 Forbidden when message security authenticated it (RFC 7252 §5.9.2.2, §5.9.2.4), and
 * reaches no handler, buffers no Block1 or Q-Block1 body, registers, replaces or cancels no observation and
 * gets no Q-Block2 burst. The refusal is protected when the request was.
 *
 * The policy that governs a resource is its own [CoapResource.access], else its nearest ancestor's, else the
 * server's [CoapServerConfig.defaultAccess], which refuses requests without message security on a server that
 * has it. A request for a path that does not exist is governed by its nearest existing ancestor, so it gets the
 * same refusal as an existing resource there would, and 4.04 only when that policy permits it: the names below
 * a protected resource do not leak. `/.well-known/core` lists a resource only when its policy permits a GET by
 * the caller (RFC 6690 §4 leaves the choice of links to the server).
 *
 * ```
 * CoapServer(transport, scope, CoapServerConfig(security = OscoreServer(contexts))) {
 *     resource("status") { access = AccessPolicy.Public; get { CoapResponse.content("up") } }
 *     resource("lights") { get { CoapResponse.content("on") } } // the default: OSCORE only
 *     resource("admin") {
 *         access = AccessPolicy { it.securityIdentity == operator }
 *         post { CoapResponse.changed() }
 *     }
 * }
 * ```
 *
 * A policy runs for every request to the resources it governs, from many coroutines at once, so it must be
 * thread safe and should be quick. One that throws is answered with 5.00, like a failing handler.
 */
public fun interface AccessPolicy {
    /** Whether [request] may be served. */
    public suspend fun permits(request: AccessRequest): Boolean

    /**
     * Every request, authenticated or not. For resources meant for everyone: the EDHOC resource, whose requests
     * run before any key exists, `/.well-known/core`, and anything a server offers in the clear.
     */
    public data object Public : AccessPolicy {
        override suspend fun permits(request: AccessRequest): Boolean = true
    }

    /**
     * Only requests that message security verified (OSCORE, RFC 8613), whatever the transport and its channel
     * security. The default of a server with [CoapServerConfig.security]; `/.well-known/core` marks the resources
     * under it with `osc` (RFC 8613 §9).
     */
    public data object MessageSecurityRequired : AccessPolicy {
        override suspend fun permits(request: AccessRequest): Boolean = request.securityIdentity != null
    }

    /**
     * Requests that message security verified, or whose channel security authenticated the peer (DTLS, or TLS on
     * a connection): authorization on either identity, as docs/architecture.md describes for the two kinds of
     * security.
     */
    public data object Authenticated : AccessPolicy {
        override suspend fun permits(request: AccessRequest): Boolean =
            request.securityIdentity != null || request.peerIdentity != PeerIdentity.None
    }
}
