package dev.kcoap.edhoc.oscore

import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException

/**
 * An EDHOC session failed on the client's side after message_2 verified: its OSCORE context could not be derived
 * (RFC 9528 Appendix A.1), because the sequence number store or the crypto backend threw. [detail] and [cause] say
 * why, for this side's logs. The error is error code 1 (RFC 9528 §6.2), and there is nothing to send to the server:
 * [errorMessage] is null, as the client answers no message of the server's here.
 *
 * It mirrors [EdhocServerFailure], so that [EdhocClientHandshake.run] and [EdhocClientHandshake.runCombined] fail
 * only with an [EdhocException] or another [dev.kcoap.common.KcoapException], which their `@Throws` lists.
 */
internal class EdhocClientFailure(detail: String, cause: Throwable) :
    EdhocException(EdhocError.unspecified(DIAGNOSTIC), detail, cause) {
    override val errorMessage: ByteArray? get() = null

    companion object {
        const val DIAGNOSTIC = "OSCORE context not derived"
    }
}
