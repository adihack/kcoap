package dev.kcoap.edhoc.oscore

import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException

/**
 * An EDHOC session failed on the server's side after message_3 verified: the context repository, the sequence
 * number store or the session listener threw. The peer is told only [DIAGNOSTIC], in a 5.00 response, as
 * RFC 9528 Appendix A.2.3 recommends for server errors; [detail] and [cause] say why, for this side's logs.
 */
internal class EdhocServerFailure(detail: String, cause: Throwable) :
    EdhocException(EdhocError.unspecified(DIAGNOSTIC), detail, cause) {
    companion object {
        const val DIAGNOSTIC = "EDHOC failed"
    }
}
