package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.CborException
import dev.kcoap.cbor.CborReader
import dev.kcoap.cbor.CborType
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException
import dev.kcoap.oscore.OscoreException
import dev.kcoap.oscore.OscoreOptionValue
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The client side of the EDHOC + OSCORE request (RFC 9668 §3.2): the first request protected with the
 * new OSCORE context also carries EDHOC message_3, saving a round trip. Later requests are plain OSCORE.
 *
 * [oscore] protects requests with the context derived from the session; [message3] is the Initiator's
 * message_3, sent once.
 */
public class CombinedRequestSecurity(
    private val oscore: ClientMessageSecurity,
    message3: ByteArray,
) : ClientMessageSecurity {
    private val lock = Mutex()
    private var pendingMessage3: ByteArray? = message3.copyOf()

    /** Whether message_3 still waits for a request to travel with. */
    public suspend fun isMessage3Pending(): Boolean = lock.withLock { pendingMessage3 != null }

    override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
        val protected = oscore.protectRequest(request, peer) ?: return null
        // §3.2.2 step 2.1: only the first inner block may carry message_3.
        if ((request.options.block1?.number ?: 0L) != 0L) return protected
        val message3 = lock.withLock { pendingMessage3.also { pendingMessage3 = null } } ?: return protected
        return Combined(protected, message3)
    }

    private class Combined(private val protected: ProtectedRequest, message3: ByteArray) : ProtectedRequest {
        // §3.2.1 steps 3 to 5: COMB_PAYLOAD = EDHOC_MSG_3 | OSCORE_PAYLOAD, plus the empty EDHOC option.
        override val message: CoapMessage = protected.message.toBuilder().apply {
            payload = message3 + payload
            options { edhoc() }
        }.build()

        override suspend fun unprotectResponse(response: CoapMessage): CoapMessage = protected.unprotectResponse(response)
    }
}

/**
 * The server side of the EDHOC + OSCORE request (RFC 9668 §3.3): a request with the EDHOC option has its
 * message_3 split off and processed by [edhoc], which adds the new OSCORE context, and the rest is
 * verified by [oscore] as an ordinary OSCORE request. Requests without the EDHOC option go to [oscore]
 * directly.
 *
 * Errors follow the RFC: a malformed combined request is answered with 4.00, a failed EDHOC step with
 * an EDHOC error message (Content-Format application/edhoc+cbor-seq), and a failed OSCORE step with the
 * OSCORE errors of RFC 8613 §8.2.
 */
public class CombinedRequestServer(
    private val edhoc: EdhocServer,
    private val oscore: OscoreServer,
) : ServerMessageSecurity {

    override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
        if (!request.options.edhoc) return oscore.unprotectRequest(request, peer)

        // Step 1: the request needs the OSCORE option and a payload of EDHOC_MSG_3 | OSCORE_PAYLOAD.
        val optionValue = request.options.oscore ?: throw badRequest("EDHOC option without OSCORE option")
        val kid = try {
            OscoreOptionValue.decode(optionValue).kid
        } catch (e: OscoreException) {
            throw badRequest("Malformed OSCORE option")
        } ?: throw badRequest("EDHOC + OSCORE request without kid")
        val payload = request.payload
        val (message3, ciphertext) = split(payload) ?: throw badRequest("Malformed EDHOC + OSCORE payload")

        // Steps 2 to 5: kid is C_R; complete the EDHOC session and add its OSCORE context.
        try {
            edhoc.completeCombined(ConnectionIdentifier(kid), message3, peer)
        } catch (e: EdhocException) {
            val error = e.errorMessage ?: EdhocError.unspecified("EDHOC failed").encode()
            throw MessageSecurityException(
                "EDHOC processing failed: ${e.message}",
                CoapCode.BAD_REQUEST,
                diagnostic = null,
                cause = e,
                errorPayload = error,
                contentFormat = ContentFormat.EDHOC_CBOR_SEQ,
            )
        }

        // Steps 6 to 8: the plain OSCORE request, without message_3 and the EDHOC option.
        val rebuilt = request.toBuilder().apply {
            this.payload = ciphertext
            options { remove(CoapOptions.EDHOC.number) }
        }.build()
        return oscore.unprotectRequest(rebuilt, peer)
    }

    private companion object {
        fun badRequest(reason: String) = MessageSecurityException(reason, CoapCode.BAD_REQUEST, reason)

        /** EDHOC_MSG_3 is one CBOR byte string (CIPHERTEXT_3); the OSCORE ciphertext follows it. */
        fun split(payload: ByteArray): Pair<ByteArray, ByteArray>? {
            if (payload.isEmpty()) return null
            val reader = CborReader(payload)
            return try {
                if (reader.peekType() != CborType.BYTES) return null
                val message3 = reader.readEncodedItem()
                if (reader.isAtEnd) return null
                message3 to payload.copyOfRange(reader.position, payload.size)
            } catch (_: CborException) {
                null
            }
        }
    }
}
