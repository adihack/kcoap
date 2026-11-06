package dev.kcoap.coap.security

import dev.kcoap.coap.CoapMessage

/**
 * What [ProtectedRequest.unprotectResponse] found a response to a protected request to be: [Verified], which the
 * stack acts on, or [Unverified], which it reports and acts on in no way (ADR 0008).
 *
 * A response that is neither, such as a forgery, a replay or an unprotected success, is not returned at all: the
 * security layer throws [MessageSecurityException] and the stack drops it (RFC 8613 §8.4).
 */
public sealed class ResponseVerification {
    /** The response as the layers above see it: decrypted for [Verified], as it came for [Unverified]. */
    public abstract val message: CoapMessage

    /**
     * The response verified: it answers this request and comes from the peer of the security context (RFC 8613
     * §8.4). The stack acts on it as on any response: a 4.01 with Echo has the request repeated with that value
     * (RFC 8613 Appendix B.1.2, RFC 9175 §2.3), a Block2 response has the body fetched, a Q-Block answer steers
     * the body, a notification goes to the observers.
     */
    public class Verified(override val message: CoapMessage) : ResponseVerification() {
        override fun toString(): String = "Verified(${message.code})"
    }

    /**
     * The response could not be verified: an error without message security, such as OSCORE's unprotected 4.01
     * or 4.02 (RFC 8613 §8.2) or an intermediary's 5.04, which anyone who saw the token can send. The stack acts
     * on nothing in it: it does not repeat the request for an Echo option, continue a block-wise body, change
     * what it knows about the peer, or end an observation. A request without a verified response yet ends with
     * kcoap-coap's `CoapException.Reason.UNVERIFIED_RESPONSE`, which carries [message]; an established
     * observation drops it.
     *
     * @property reason why it is unverified, for logs.
     */
    public class Unverified(override val message: CoapMessage, public val reason: String) : ResponseVerification() {
        override fun toString(): String = "Unverified(${message.code}: $reason)"
    }
}
