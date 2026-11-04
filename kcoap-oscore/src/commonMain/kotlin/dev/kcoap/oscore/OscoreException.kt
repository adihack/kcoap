package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.security.MessageSecurityException

/**
 * Why an OSCORE message was rejected, with the error response RFC 8613 §8.2 and §7.4 suggest for a
 * server. The diagnostic strings are the ones the RFC proposes.
 */
public enum class OscoreError(public val responseCode: CoapCode, public val diagnostic: String) {
    /** §8.2 step 2: the OSCORE option or COSE object is malformed. */
    DECODE_FAILED(CoapCode.BAD_OPTION, "Failed to decode COSE"),

    /** §8.2 step 2: no Recipient Context for the 'kid' (and 'kid context'). */
    CONTEXT_NOT_FOUND(CoapCode.UNAUTHORIZED, "Security context not found"),

    /** §7.4: the Partial IV was already received. */
    REPLAY(CoapCode.UNAUTHORIZED, "Replay detected"),

    /** §8.2 step 6: the AEAD tag did not verify. */
    DECRYPTION_FAILED(CoapCode.BAD_REQUEST, "Decryption failed"),

    /** §7.2.1: the Sender Sequence Number reached its maximum; a new context is needed. */
    SEQUENCE_EXHAUSTED(CoapCode.INTERNAL_SERVER_ERROR, "Sequence numbers exhausted"),
}

/** Raised by OSCORE processing; converts to the stack's [MessageSecurityException]. */
public class OscoreException(public val error: OscoreError, detail: String? = null, cause: Throwable? = null) :
    Exception(detail?.let { "${error.diagnostic}: $it" } ?: error.diagnostic, cause) {

    public fun toMessageSecurityException(): MessageSecurityException =
        MessageSecurityException(message ?: error.diagnostic, error.responseCode, error.diagnostic, this)
}
