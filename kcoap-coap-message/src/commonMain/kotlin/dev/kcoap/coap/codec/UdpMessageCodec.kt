package dev.kcoap.coap.codec

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.common.ByteArrayBuilder

/**
 * The CoAP over UDP message format (RFC 7252 §3):
 *
 * ```
 *  0                   1                   2                   3
 * |Ver| T |  TKL  |      Code     |          Message ID           |
 * |   Token (if any, TKL bytes) ...
 * |   Options (if any) ...
 * |1 1 1 1 1 1 1 1|    Payload (if any) ...
 * ```
 */
public object UdpMessageCodec : MessageCodec {
    private const val VERSION = 1
    private const val HEADER_LENGTH = 4

    override fun encode(message: CoapMessage): ByteArray {
        val token = message.token
        val out = ByteArrayBuilder(HEADER_LENGTH + token.size + 16 + message.payloadSize)
        out.append((VERSION shl 6) or (message.type.value shl 4) or token.size)
        out.append(message.code.value)
        out.appendBigEndian(message.messageId.toLong(), 2)
        out.append(token)
        OptionCodec.write(message.options, message.payload, out)
        return out.toByteArray()
    }

    override fun decode(bytes: ByteArray): CoapMessage {
        if (bytes.size < HEADER_LENGTH) throw CoapFormatException("Datagram of ${bytes.size} bytes is shorter than a header")
        val first = bytes[0].toInt() and 0xFF
        // Two bits, and the four types are declared in the order of their values (RFC 7252 §3).
        val type = CoapMessageType.entries[(first ushr 4) and 0x3]
        val messageId = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val fail = { reason: String -> CoapFormatException(reason, messageId, type) }

        // RFC 7252 §3: messages with an unknown version number MUST be silently ignored.
        if (first ushr 6 != VERSION) {
            throw CoapFormatException("Unknown CoAP version ${first ushr 6}", messageId, type, silentlyIgnore = true)
        }
        val tokenLength = first and 0x0F
        // RFC 7252 §3: lengths 9 to 15 are reserved and MUST be processed as a message format error.
        if (tokenLength > CoapMessage.MAX_TOKEN_LENGTH) throw fail("Reserved token length $tokenLength")
        if (HEADER_LENGTH + tokenLength > bytes.size) throw fail("Token runs past the datagram end")

        val code = CoapCode(bytes[1].toInt() and 0xFF)
        if (code.isEmpty) {
            // RFC 7252 §4.1: an Empty message has TKL 0 and nothing after the Message ID.
            if (tokenLength != 0 || bytes.size != HEADER_LENGTH) throw fail("Empty message with content")
            return CoapMessage.empty(type, messageId)
        }

        val token = bytes.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + tokenLength)
        val rest = OptionCodec.read(bytes, HEADER_LENGTH + tokenLength, bytes.size, fail)
        return CoapMessage(type, code, messageId, token, rest.options, rest.payload)
    }
}
