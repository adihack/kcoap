package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.Cbor
import dev.kcoap.cbor.CborBytes
import dev.kcoap.cbor.CborException
import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborReader
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.ContentFormat
import dev.kcoap.edhoc.ConnectionIdentifier

/**
 * How EDHOC messages travel in CoAP messages (RFC 9528 Appendix A.2, forward message flow): POST
 * requests to `/.well-known/edhoc` and 2.04 responses. Requests are prefixed so the server can
 * correlate them: message_1 with the CBOR value `true`, later messages with the server's C_R.
 *
 * These are pure message builders and parsers; [EdhocServer] and [EdhocClientHandshake] put them to
 * work.
 */
public object EdhocOverCoap {
    /** RFC 9528 §10.7. */
    public const val WELL_KNOWN_PATH: String = ".well-known/edhoc"

    /** RFC 9528 §10.10: the resource type clients use to discover an EDHOC resource. */
    public const val RESOURCE_TYPE: String = "core.edhoc"

    private const val CBOR_TRUE: Byte = 0xF5.toByte()

    /** POST carrying `true, message_1` (Content-Format application/cid-edhoc+cbor-seq). */
    public fun message1Request(message1: ByteArray, path: String = WELL_KNOWN_PATH): CoapMessage =
        CoapMessage.request(CoapCode.POST) {
            options {
                uriPath(path)
                contentFormat(ContentFormat.CID_EDHOC_CBOR_SEQ)
            }
            payload = byteArrayOf(CBOR_TRUE) + message1
        }

    /** POST carrying `C_R, message` for message_3 or an error message in the forward flow. */
    public fun continuationRequest(responderId: ConnectionIdentifier, message: ByteArray, path: String = WELL_KNOWN_PATH): CoapMessage =
        CoapMessage.request(CoapCode.POST) {
            options {
                uriPath(path)
                contentFormat(ContentFormat.CID_EDHOC_CBOR_SEQ)
            }
            payload = responderId.encode() + message
        }

    /** What a server found in the payload of a request to its EDHOC resource. */
    public sealed interface ServerRequest {
        /** `true` came first: a new session starts with this message_1. */
        public class NewSession(public val message1: ByteArray) : ServerRequest

        /** A connection identifier came first: [message] continues the session [responderId] names. */
        public class Continuation(public val responderId: ConnectionIdentifier, public val message: ByteArray) : ServerRequest
    }

    /**
     * Splits a request payload into its prefix and the EDHOC message.
     *
     * @throws IllegalArgumentException when the payload does not start with `true` or a connection
     *   identifier in the representation of RFC 9528 §3.3.2, or nothing follows it.
     */
    public fun parseRequest(payload: ByteArray): ServerRequest {
        require(payload.isNotEmpty()) { "Empty EDHOC request" }
        if (payload[0] == CBOR_TRUE) {
            require(payload.size > 1) { "No message_1 after true" }
            return ServerRequest.NewSession(payload.copyOfRange(1, payload.size))
        }
        val reader = CborReader(payload)
        val item = try {
            reader.readEncodedItem()
        } catch (e: CborException) {
            throw IllegalArgumentException("Malformed connection identifier prefix", e)
        }
        val identifier = when (val value = Cbor.decode(item)) {
            // RFC 9528 §3.3.2: a one-byte integer stands for the byte string of its own encoding.
            is CborInt -> {
                require(value.value in -24..23) { "Connection identifiers as integers are -24..23" }
                ConnectionIdentifier(item)
            }
            is CborBytes -> ConnectionIdentifier(value.bytes)
            else -> throw IllegalArgumentException("A connection identifier is an integer or a byte string, got $value")
        }
        require(!reader.isAtEnd) { "No EDHOC message after the connection identifier" }
        return ServerRequest.Continuation(identifier, payload.copyOfRange(reader.position, payload.size))
    }

    /** 2.04 Changed carrying message_2 or message_4 (Content-Format application/edhoc+cbor-seq). */
    public fun messageResponse(request: CoapMessage, message: ByteArray): CoapMessage =
        CoapMessage.response(request, CoapCode.CHANGED) {
            options { contentFormat(ContentFormat.EDHOC_CBOR_SEQ) }
            payload = message
        }

    /** 2.04 Changed without payload, when message_3 completes a session without message_4. */
    public fun completedResponse(request: CoapMessage): CoapMessage = CoapMessage.response(request, CoapCode.CHANGED)

    /**
     * An EDHOC error message in an error response (RFC 9528 Appendix A.2.3): 4.00 for client errors,
     * 5.00 for server errors, Content-Format application/edhoc+cbor-seq.
     */
    public fun errorResponse(request: CoapMessage, errorMessage: ByteArray, isServerError: Boolean = false): CoapMessage =
        CoapMessage.response(request, if (isServerError) CoapCode.INTERNAL_SERVER_ERROR else CoapCode.BAD_REQUEST) {
            options { contentFormat(ContentFormat.EDHOC_CBOR_SEQ) }
            payload = errorMessage
        }
}
