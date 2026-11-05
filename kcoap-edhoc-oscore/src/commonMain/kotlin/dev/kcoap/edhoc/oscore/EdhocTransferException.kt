package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapResponse
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException

/**
 * An EDHOC handshake over CoAP failed in the transfer, not in EDHOC itself: the server answered a request to
 * its EDHOC resource with something that is neither a 2.04 nor an EDHOC error response (RFC 9528
 * Appendix A.2), such as a 4.04 from a server without one, or the peers could not agree on a cipher suite
 * within a few rounds of RFC 9528 §6.3.
 *
 * It is an [EdhocException], so one `catch` covers every way [EdhocClientHandshake] fails for the peer's
 * part. As for [dev.kcoap.edhoc.EdhocPeerErrorException], there is nothing to answer: [errorMessage] is null.
 *
 * @property response the response that was not EDHOC, or null when the failure was not about one response.
 */
public class EdhocTransferException(message: String, public val response: CoapResponse? = null) :
    EdhocException(EdhocError.unspecified(message), message) {
    override val errorMessage: ByteArray? get() = null
}
