package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions

/**
 * Which responses without the OSCORE option the client hands on, unauthenticated, and which it drops.
 *
 * A server sends its OSCORE errors unprotected: 4.02 when the COSE object does not decode, 4.01 when it has no
 * context for the request or the request is a replay, 4.00 when it does not decrypt (RFC 8613 §8.2, §7.4); an
 * intermediary sends its own errors, and an overloaded kcoap server refuses a request with 5.03 before OSCORE.
 * Since the token is visible on the wire, anyone on the path can send such a response too.
 *
 * - **A request that takes one response** gets every unprotected 4.xx and 5.xx, so the application can react,
 *   for example by deriving the context again. Such a response can do no more than end the request.
 * - **A request carrying an Inner Q-Block1 or Q-Block2 option** belongs to a body whose client takes the
 *   responses on its tokens as control messages for the whole body, for as long as the body lasts (RFC 9177
 *   §4.3, §4.4): a 4.08 with Content-Format 272 has blocks sent again, a 4.13 restarts the body with smaller
 *   blocks, a 4.01 with Echo has requests sent again with that value, a response with a Block or Q-Block
 *   option is taken as part of the body, and a 4.02 before any answer marks the peer as not taking Q-Block
 *   under OSCORE for every client of the endpoint. None of these is an OSCORE error, and the Echo challenge of
 *   Appendix B.1.2 is protected, so in answer to an Inner Q-Block request each of them would steer a protected
 *   body from outside OSCORE, the amplification and downgrade RFC 9177 §11 warns about. Such a request gets
 *   only the unprotected errors that end its body: 4.00, 4.01 and 5.xx, none of them with Echo or with an option
 *   of a block-wise body. Everything else is dropped like a response that fails verification (RFC 8613 §8.4).
 *   That includes 4.02: a server that does not take Q-Block under OSCORE answers with an inner, protected 4.02,
 *   since its CoAP layer finds the critical Inner option it does not know, whereas an unprotected one comes
 *   from a server that could not decode the COSE object, or has no OSCORE, and the request then ends as a
 *   timeout instead of turning Q-Block off for the peer.
 */
internal object UnprotectedResponses {
    /** The options of a block-wise body (RFC 7959, RFC 9177), which no OSCORE error carries. */
    private val BODY_OPTIONS = listOf(
        CoapOptions.BLOCK1, CoapOptions.BLOCK2, CoapOptions.Q_BLOCK1, CoapOptions.Q_BLOCK2,
        CoapOptions.SIZE1, CoapOptions.SIZE2,
    ).map { it.number }

    /**
     * Why [response], which carries no OSCORE option, is dropped, or null to hand it on. [takesSeveral] says
     * whether its request carried an Inner Q-Block option ([RequestBinding.allowsSeveralResponses]).
     */
    fun rejection(response: CoapMessage, takesSeveral: Boolean): String? {
        val code = response.code
        if (!code.isClientError && !code.isServerError) return "Unprotected response to an OSCORE request"
        if (!takesSeveral) return null
        val endsBody = code == CoapCode.BAD_REQUEST || code == CoapCode.UNAUTHORIZED || code.isServerError
        return when {
            !endsBody -> "Unprotected $code to a Q-Block request"
            CoapOptions.ECHO.number in response.options -> "Unprotected $code with Echo to a Q-Block request"
            BODY_OPTIONS.any { it in response.options } -> "Unprotected $code with a block option to a Q-Block request"
            else -> null
        }
    }
}
