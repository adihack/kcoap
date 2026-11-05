package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity

/**
 * What an [AccessPolicy] decides on: a request, before the server did any work for it, or for
 * `/.well-known/core` a GET of a resource it would list.
 *
 * @property method the request method.
 * @property path the Uri-Path segments of the request, also for a path that does not exist.
 * @property resource the resource at [path], or null when there is none.
 * @property peer where the request came from.
 * @property securityIdentity who message security (OSCORE) authenticated, end to end; null for a request
 *   without it. For a context that EDHOC established, kcoap-edhoc-oscore's `EdhocIdentity`, which carries the
 *   peer's credential.
 * @property peerIdentity what channel security (DTLS, or TLS on a connection) authenticated for the hop.
 */
public class AccessRequest(
    public val method: CoapCode,
    public val path: List<String>,
    public val resource: CoapResource?,
    public val peer: Endpoint,
    public val securityIdentity: SecurityIdentity?,
    public val peerIdentity: PeerIdentity,
) {
    override fun toString(): String =
        "AccessRequest($method /${path.joinToString("/")} from $peer, ${securityIdentity?.description ?: "no message security"})"
}
