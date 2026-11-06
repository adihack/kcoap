package dev.kcoap.coap.codec

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.common.ByteArrayBuilder

/**
 * The CoAP message format for reliable transports (RFC 8323 §3.2 and §4.2). There is no version, type
 * or Message ID; a length field (Len) gives the size of options and payload:
 *
 * ```
 * | Len  |  TKL  | Extended Length (0-4 bytes) | Code | Token (TKL bytes) | Options | 0xFF | Payload |
 * ```
 *
 * Over TCP and TLS, Len is 0–12 or selects a 1, 2 or 4-byte extended length. Over WebSockets the frame
 * carries the length, so Len MUST be 0 and there is no extended length.
 */
public class ReliableMessageCodec private constructor(private val webSocket: Boolean) : MessageCodec {

    override fun encode(message: CoapMessage): ByteArray {
        val token = message.token
        val body = ByteArrayBuilder(16 + message.payloadSize)
        OptionCodec.write(message.options, message.payload, body)
        val bodyBytes = body.toByteArray()

        val out = ByteArrayBuilder(6 + token.size + bodyBytes.size)
        if (webSocket) {
            out.append(token.size)
        } else {
            val length = bodyBytes.size.toLong()
            when {
                length < 13 -> out.append((length.toInt() shl 4) or token.size)
                length < 269 -> out.append((13 shl 4) or token.size).appendBigEndian(length - 13, 1)
                length < 65805 -> out.append((14 shl 4) or token.size).appendBigEndian(length - 269, 2)
                else -> out.append((15 shl 4) or token.size).appendBigEndian(length - 65805, 4)
            }
        }
        out.append(message.code.value)
        out.append(token)
        out.append(bodyBytes)
        return out.toByteArray()
    }

    /** Decodes exactly one message; [bytes] must contain nothing else. */
    override fun decode(bytes: ByteArray): CoapMessage {
        val frame = FrameHeader.parse(bytes, 0, bytes.size, webSocket)
            ?: throw CoapFormatException("Truncated message header")
        if (!webSocket && frame.bodyStart + frame.bodyLength != bytes.size.toLong()) {
            throw CoapFormatException("Length field does not match the message size")
        }
        return frame.decode(bytes, bytes.size)
    }

    public companion object {
        /** CoAP over TCP and TLS (RFC 8323 §3.2). */
        public val Tcp: ReliableMessageCodec = ReliableMessageCodec(webSocket = false)

        /** CoAP over WebSockets (RFC 8323 §4.2). */
        public val WebSocket: ReliableMessageCodec = ReliableMessageCodec(webSocket = true)
    }
}

/** The fixed part of a reliable-transport message, once enough bytes have arrived to read it. */
internal class FrameHeader(
    val tokenLength: Int,
    val code: CoapCode,
    /** Offset of the token. */
    val tokenStart: Int,
    /** Offset of the first option byte. */
    val bodyStart: Int,
    /** Len: options plus payload, in bytes. Meaningless for WebSocket frames. */
    val bodyLength: Long,
) {
    fun decode(bytes: ByteArray, end: Int): CoapMessage {
        val fail = { reason: String -> CoapFormatException(reason) }
        val token = bytes.copyOfRange(tokenStart, bodyStart)
        val rest = OptionCodec.read(bytes, bodyStart, end, fail)
        if (code.isEmpty && (token.isNotEmpty() || !rest.options.isEmpty() || rest.payload.isNotEmpty())) {
            throw CoapFormatException("Empty message with content")
        }
        return CoapMessage(CoapMessageType.CON, code, 0, token, rest.options, rest.payload)
    }

    companion object {
        /** Parses the header at [start], or returns null when more bytes are needed. */
        fun parse(bytes: ByteArray, start: Int, end: Int, webSocket: Boolean): FrameHeader? {
            if (start >= end) return null
            val first = bytes[start].toInt() and 0xFF
            val lengthNibble = first ushr 4
            val tokenLength = first and 0x0F
            if (tokenLength > CoapMessage.MAX_TOKEN_LENGTH) throw CoapFormatException("Reserved token length $tokenLength")
            if (webSocket && lengthNibble != 0) throw CoapFormatException("Len MUST be 0 over WebSockets")

            val extensionLength = when (lengthNibble) {
                13 -> 1
                14 -> 2
                15 -> 4
                else -> 0
            }
            val codeOffset = start + 1 + extensionLength
            if (codeOffset + 1 + tokenLength > end) return null

            var extension = 0L
            for (i in 0 until extensionLength) extension = (extension shl 8) or (bytes[start + 1 + i].toLong() and 0xFF)
            val bodyLength = when (lengthNibble) {
                13 -> extension + 13
                14 -> extension + 269
                15 -> extension + 65805
                else -> lengthNibble.toLong()
            }
            val code = CoapCode(bytes[codeOffset].toInt() and 0xFF)
            return FrameHeader(tokenLength, code, codeOffset + 1, codeOffset + 1 + tokenLength, bodyLength)
        }
    }
}

/**
 * Splits a TCP or TLS byte stream into messages (RFC 8323 §3.2). Feed it whatever bytes arrive; it
 * returns every message completed so far and keeps the rest.
 *
 * [maxMessageSize] bounds the memory one peer can make us hold; set it from the Max-Message-Size this
 * endpoint announces in its CSM (RFC 8323 §5.3.1).
 */
public class ReliableStreamDecoder(public val maxMessageSize: Long = DEFAULT_MAX_MESSAGE_SIZE) {
    private var buffer = ByteArray(0)

    /** Bytes received but not yet part of a complete message. */
    public val pendingBytes: Int get() = buffer.size

    /** @throws CoapFormatException on malformed input or a message larger than [maxMessageSize]. */
    public fun feed(bytes: ByteArray): List<CoapMessage> {
        buffer += bytes
        val messages = mutableListOf<CoapMessage>()
        var offset = 0
        while (true) {
            val header = FrameHeader.parse(buffer, offset, buffer.size, webSocket = false) ?: break
            val end = header.bodyStart + header.bodyLength
            if (end - offset > maxMessageSize) {
                throw CoapFormatException("Message of ${end - offset} bytes exceeds the limit of $maxMessageSize")
            }
            if (end > buffer.size) break
            messages += header.decode(buffer, end.toInt())
            offset = end.toInt()
        }
        buffer = buffer.copyOfRange(offset, buffer.size)
        return messages
    }

    public companion object {
        /** Generous default; RFC 8323 §5.3.1's own default of 1152 bytes is what peers assume of us. */
        public const val DEFAULT_MAX_MESSAGE_SIZE: Long = 1024 * 1024
    }
}
