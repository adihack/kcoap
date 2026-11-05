package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.Cbor
import dev.kcoap.cbor.CborBytes
import dev.kcoap.cbor.CborException
import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborReader
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.OptionSet
import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException

/**
 * How EDHOC messages travel in CoAP messages (RFC 9528 Appendix A.2, forward message flow): POST
 * requests to `/.well-known/edhoc` and 2.04 responses. Requests are prefixed so the server can
 * correlate them: message_1 with the CBOR value `true`, later messages with the server's C_R.
 *
 * These are pure request and response builders and a payload parser, in the application model of
 * kcoap-coap ([CoapRequest], [CoapResponse]); [EdhocServer] and [EdhocClientHandshake] put them to work.
 */
public object EdhocOverCoap {
    /** RFC 9528 §10.7. */
    public const val WELL_KNOWN_PATH: String = ".well-known/edhoc"

    /** RFC 9528 §10.10: the resource type clients use to discover an EDHOC resource. */
    public const val RESOURCE_TYPE: String = "core.edhoc"

    private const val CBOR_TRUE: Byte = 0xF5.toByte()

    /**
     * POST to the EDHOC resource [resource] carrying `true, message_1` (Content-Format
     * application/cid-edhoc+cbor-seq, RFC 9528 Appendix A.2.1).
     */
    public fun message1Request(resource: CoapUri, message1: ByteArray): CoapRequest =
        post(resource, byteArrayOf(CBOR_TRUE) + message1)

    /**
     * POST to the EDHOC resource [resource] carrying `C_R, message`, for message_3 or an error message in the
     * forward flow (RFC 9528 Appendix A.2.1).
     */
    public fun continuationRequest(resource: CoapUri, responderId: ConnectionIdentifier, message: ByteArray): CoapRequest =
        post(resource, responderId.encode() + message)

    /** What a server found in the payload of a request to its EDHOC resource. */
    public sealed interface ServerRequest {
        /** `true` came first: a new session starts with this message_1. */
        public class NewSession(public val message1: ByteArray) : ServerRequest

        /** A connection identifier came first: [message] continues the session [responderId] names. */
        public class Continuation(public val responderId: ConnectionIdentifier, public val message: ByteArray) : ServerRequest
    }

    /**
     * Splits the payload of a request to the EDHOC resource into its prefix and the EDHOC message.
     *
     * The payload comes from the peer, so a malformed one is an EDHOC failure, not a caller mistake: the
     * exception's [EdhocException.errorMessage] is the EDHOC error (code 1) to answer it with, in a 4.00
     * response (RFC 9528 Appendix A.2.3, [errorResponse]).
     *
     * @throws EdhocException when the payload does not start with `true` or a connection identifier in the
     *   representation of RFC 9528 §3.3.2, or nothing follows it.
     */
    public fun parseRequest(payload: ByteArray): ServerRequest {
        if (payload.isEmpty()) throw malformed("empty payload")
        if (payload[0] == CBOR_TRUE) {
            if (payload.size == 1) throw malformed("no message_1 after true")
            return ServerRequest.NewSession(payload.copyOfRange(1, payload.size))
        }
        val reader = CborReader(payload)
        val item = try {
            reader.readEncodedItem()
        } catch (e: CborException) {
            throw malformed("no CBOR item for a connection identifier", e)
        }
        // readEncodedItem decoded the item in full, so decoding it again cannot fail.
        val identifier = when (val value = Cbor.decode(item)) {
            // RFC 9528 §3.3.2: a one-byte integer stands for the byte string of its own encoding.
            is CborInt -> {
                if (value.value !in -24..23) throw malformed("connection identifiers as integers are -24..23")
                ConnectionIdentifier(item)
            }
            is CborBytes -> ConnectionIdentifier(value.bytes)
            else -> throw malformed("a connection identifier is an integer or a byte string, got $value")
        }
        if (reader.isAtEnd) throw malformed("no EDHOC message after the connection identifier")
        return ServerRequest.Continuation(identifier, payload.copyOfRange(reader.position, payload.size))
    }

    /** 2.04 Changed carrying message_2 or message_4 (Content-Format application/edhoc+cbor-seq). */
    public fun messageResponse(message: ByteArray): CoapResponse =
        CoapResponse(CoapCode.CHANGED, EDHOC_CONTENT, message)

    /** 2.04 Changed without payload, when message_3 completes a session without message_4. */
    public fun completedResponse(): CoapResponse = CoapResponse.changed()

    /**
     * An EDHOC error message in an error response (RFC 9528 Appendix A.2.3): 4.00 for client errors,
     * 5.00 for server errors, Content-Format application/edhoc+cbor-seq.
     */
    public fun errorResponse(errorMessage: ByteArray, isServerError: Boolean = false): CoapResponse =
        CoapResponse(if (isServerError) CoapCode.INTERNAL_SERVER_ERROR else CoapCode.BAD_REQUEST, EDHOC_CONTENT, errorMessage)

    private val EDHOC_CONTENT = OptionSet.build { contentFormat(ContentFormat.EDHOC_CBOR_SEQ) }

    private fun post(resource: CoapUri, payload: ByteArray): CoapRequest =
        CoapRequest.Builder(CoapCode.POST, resource).apply {
            contentFormat(ContentFormat.CID_EDHOC_CBOR_SEQ)
            this.payload = payload
        }.build()

    /**
     * EDHOC error code 1 (RFC 9528 §6.2), as kcoap-edhoc's parsers raise for a malformed message. The peer is
     * told only [MALFORMED_REQUEST]; [detail] stays in the exception message, for this side's logs.
     */
    private fun malformed(detail: String, cause: Throwable? = null): EdhocException =
        EdhocException(EdhocError.unspecified(MALFORMED_REQUEST), "$MALFORMED_REQUEST: $detail", cause)

    private const val MALFORMED_REQUEST = "Malformed EDHOC request"
}
