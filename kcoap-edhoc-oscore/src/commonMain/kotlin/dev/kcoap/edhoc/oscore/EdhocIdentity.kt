package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.edhoc.AuthenticationCredential
import dev.kcoap.edhoc.CredentialIdentifier
import dev.kcoap.oscore.OscoreIdentity

/**
 * The peer behind the requests an OSCORE context verifies when an EDHOC session established that context
 * (RFC 9528 Appendix A.1): the authentication credential EDHOC verified for the peer, and the context's OSCORE
 * identity. [oscoreContext] attaches it to the context it derives ([dev.kcoap.oscore.SecurityContext.identity]),
 * so [EdhocServer]'s contexts carry it, and resource handlers and access policies see it as the request's
 * `securityIdentity` (ADR 0008).
 *
 * Authorize on [peerCredential]: it is what EDHOC authenticated (RFC 9528 §3.5), and it belongs to the context,
 * whereas the Recipient ID in [oscore] is a connection identifier the server allocated (RFC 9668 §4.1.2), which
 * a later session may get again once this context is gone.
 *
 * ```
 * resource("lights") {
 *     access = AccessPolicy { request ->
 *         (request.securityIdentity as? EdhocIdentity)?.peerCredential in operators
 *     }
 * }
 * ```
 *
 * Two are equal when their OSCORE identities and credentials are, however the credential was identified.
 *
 * @property oscore the Recipient ID and ID Context of the context.
 * @property peerCredential the peer's authentication credential, CRED_I or CRED_R, as EDHOC verified it.
 * @property peerCredentialIdentifier how the peer identified [peerCredential] in the handshake, ID_CRED_I or
 *   ID_CRED_R (RFC 9528 §3.5.3).
 */
public class EdhocIdentity(
    public val oscore: OscoreIdentity,
    public val peerCredential: AuthenticationCredential,
    public val peerCredentialIdentifier: CredentialIdentifier,
) : SecurityIdentity {
    override val description: String get() = "EDHOC $peerCredential, ${oscore.description}"

    override fun equals(other: Any?): Boolean =
        other is EdhocIdentity && oscore == other.oscore && peerCredential == other.peerCredential

    override fun hashCode(): Int = 31 * oscore.hashCode() + peerCredential.hashCode()

    override fun toString(): String = description
}
