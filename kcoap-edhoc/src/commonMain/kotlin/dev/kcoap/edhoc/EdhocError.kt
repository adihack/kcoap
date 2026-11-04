package dev.kcoap.edhoc

import dev.kcoap.cbor.CborArray
import dev.kcoap.cbor.CborBool
import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborText
import dev.kcoap.cbor.CborValue
import dev.kcoap.cbor.CborWriter

/**
 * An EDHOC error message, RFC 9528 §6: the CBOR sequence `( ERR_CODE : int, ERR_INFO : any )`.
 *
 * Error messages are always fatal for the session that sends them. The codes of RFC 9528 Table 3 have
 * typed accessors: [diagnostic] for code 1 and [cipherSuites] (SUITES_R) for code 2.
 */
public class EdhocError(
    /** ERR_CODE. 0 is reserved for success and never appears on the wire (RFC 9528 §6.1). */
    public val code: Int,
    /** ERR_INFO; its type depends on [code]. */
    public val info: CborValue,
) {
    /** The diagnostic text of an unspecified error (code 1, RFC 9528 §6.2), or null. */
    public val diagnostic: String? get() = if (code == UNSPECIFIED_ERROR) (info as? CborText)?.value else null

    /**
     * SUITES_R of a wrong-selected-cipher-suite error (code 2, RFC 9528 §6.3), or null when the code is
     * different or ERR_INFO is not of type `suites`. The order carries no meaning.
     */
    public val cipherSuites: List<Int>? get() = if (code == WRONG_SELECTED_CIPHER_SUITE) suitesOf(info) else null

    /** The error message bytes. */
    public fun encode(): ByteArray = CborWriter().writeInt(code).write(info).toByteArray()

    override fun equals(other: Any?): Boolean = other is EdhocError && code == other.code && info == other.info

    override fun hashCode(): Int = 31 * code + info.hashCode()

    override fun toString(): String = "EdhocError($code, $info)"

    public companion object {
        /** Reserved for success; internal use only (RFC 9528 §6.1). */
        public const val SUCCESS: Int = 0

        /** RFC 9528 §6.2; ERR_INFO is a text string. */
        public const val UNSPECIFIED_ERROR: Int = 1

        /** RFC 9528 §6.3; ERR_INFO is SUITES_R. */
        public const val WRONG_SELECTED_CIPHER_SUITE: Int = 2

        /** RFC 9528 §6.4; ERR_INFO is `true`. */
        public const val UNKNOWN_CREDENTIAL_REFERENCED: Int = 3

        /** Error code 1 with a human-readable [diagnostic] in English. */
        public fun unspecified(diagnostic: String): EdhocError = EdhocError(UNSPECIFIED_ERROR, CborText(diagnostic))

        /**
         * Error code 2 with SUITES_R. A single suite is encoded as an int, several as an array
         * (RFC 9528 §6.3).
         */
        public fun wrongSelectedCipherSuite(suitesR: List<Int>): EdhocError {
            require(suitesR.isNotEmpty()) { "SUITES_R needs at least one cipher suite" }
            return EdhocError(WRONG_SELECTED_CIPHER_SUITE, encodeSuites(suitesR))
        }

        /** Error code 3 (RFC 9528 §6.4). */
        public fun unknownCredentialReferenced(): EdhocError = EdhocError(UNKNOWN_CREDENTIAL_REFERENCED, CborBool(true))

        /**
         * Parses an error message.
         *
         * @throws EdhocException when [bytes] is not an error message.
         */
        public fun decode(bytes: ByteArray): EdhocError {
            val reader = MessageReader(bytes, "error message")
            val code = reader.readInt()
            val info = reader.readValue()
            reader.expectEnd()
            return EdhocError(code, info)
        }

        /** `suites = [ 2* int ] / int` (RFC 9528 §5.2.1). */
        internal fun encodeSuites(suites: List<Int>): CborValue =
            if (suites.size == 1) CborInt(suites[0].toLong()) else CborArray(suites.map { CborInt(it.toLong()) })

        /** Null unless [value] is `[ 2* int ] / int` with values in the Int range. */
        internal fun suitesOf(value: CborValue): List<Int>? = when (value) {
            is CborInt -> value.value.toIntOrNull()?.let(::listOf)
            is CborArray -> if (value.size < 2) {
                null
            } else {
                value.items.map { item -> (item as? CborInt)?.value?.toIntOrNull() ?: return null }
            }
            else -> null
        }

        private fun Long.toIntOrNull(): Int? = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
    }
}

/**
 * An EDHOC session failed. [error] is what RFC 9528 §6 says to tell the peer, and [errorMessage] is that
 * error message encoded, ready to send, or null when nothing may be sent.
 *
 * Messages and diagnostics never contain key material or secret-dependent detail: a failed MAC and a
 * wrong key look the same.
 */
public open class EdhocException(
    public val error: EdhocError,
    message: String = error.diagnostic ?: error.toString(),
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** ERR_CODE of [error]. */
    public val errorCode: Int get() = error.code

    /**
     * The error message to send to the peer, or null when there is nothing to answer: the peer sent an
     * error itself (RFC 9528 §6), or the failure happened before any message was received.
     */
    public open val errorMessage: ByteArray? get() = if (isReply) error.encode() else null

    /** False when no peer message is waiting for an answer, as when creating message_1 fails. */
    internal var isReply: Boolean = true

    internal companion object {
        fun unspecified(diagnostic: String, cause: Throwable? = null): EdhocException =
            EdhocException(EdhocError.unspecified(diagnostic), cause = cause)

        fun malformed(what: String, cause: Throwable? = null): EdhocException =
            unspecified("Malformed $what", cause)
    }
}

/**
 * The peer sent an EDHOC error message instead of the expected message. The session is aborted and no
 * error is sent back ([errorMessage] is null): error messages are never answered (RFC 9528 §6, §6.1).
 */
public class EdhocPeerErrorException(error: EdhocError) : EdhocException(error, "The peer sent $error") {
    override val errorMessage: ByteArray? get() = null
}
