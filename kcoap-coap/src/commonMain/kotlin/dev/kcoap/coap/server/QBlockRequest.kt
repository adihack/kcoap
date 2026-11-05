package dev.kcoap.coap.server

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.stack.InboundRequest

/**
 * A request of a Q-Block body as the server's Q-Block state machines name it ([dev.kcoap.coap.stack.QBlockAction]'s
 * `R`): how to answer it at the messaging layer ([inbound]), the request itself ([request], inner when protected),
 * and the security context that protects every response to it ([unprotected]; RFC 8613 §8.3, a new Partial IV
 * for each response after the first). RFC 9177 sends several responses on one request: the blocks of a set on
 * the original request, 4.08 lists on a request of the upload (§4.3, §4.4).
 */
internal class QBlockRequest(
    val inbound: InboundRequest,
    val request: CoapMessage,
    val unprotected: UnprotectedRequest?,
) {
    val token: ByteArray get() = request.token

    override fun toString(): String = "QBlockRequest(${inbound.peer}, ${request.tokenHex})"
}
