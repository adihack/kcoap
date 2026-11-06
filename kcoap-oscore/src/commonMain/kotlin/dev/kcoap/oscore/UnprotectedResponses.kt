package dev.kcoap.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ResponseVerification

/**
 * What the client makes of a response without the OSCORE option to an OSCORE request.
 *
 * A server sends its OSCORE errors unprotected: 4.02 when the COSE object does not decode, 4.01 when it has no
 * context for the request or the request is a replay, 4.00 when it does not decrypt (RFC 8613 §8.2, §7.4); an
 * intermediary sends its own errors, and an overloaded kcoap server refuses a request with 5.03 before OSCORE.
 * Since the token is visible on the wire, anyone on the path can send such a response too.
 *
 * Every unprotected 4.xx and 5.xx is therefore handed on as [ResponseVerification.Unverified], whatever the
 * request: a request that takes one response, an Observe registration, or a request of a Q-Block body (RFC 9177
 * §4.3, §4.4). The stack acts on nothing in it (ADR 0008): no Echo retry, no block sent again for a 4.08, no
 * restart for a 4.13, no Q-Block fallback for a 4.02, no end of an established observation. It only ends a
 * request that has no verified response yet, with `CoapException.Reason.UNVERIFIED_RESPONSE`, so the
 * application sees the error instead of a timeout; that includes the unprotected 4.02 of a server without
 * OSCORE. The narrower rule of QB-5, which dropped the errors that could steer a Q-Block body, is part of this
 * general one: none of them steers anything any more.
 *
 * Every other unprotected response, a success or an informational code, is dropped like one that fails
 * verification (RFC 8613 §8.4).
 */
internal object UnprotectedResponses {
    /** [response], which carries no OSCORE option, as unverified, or null to drop it. */
    fun classify(response: CoapMessage): ResponseVerification.Unverified? {
        val code = response.code
        if (!code.isClientError && !code.isServerError) return null
        return ResponseVerification.Unverified(response, "sent without OSCORE (RFC 8613 §8.2)")
    }
}
