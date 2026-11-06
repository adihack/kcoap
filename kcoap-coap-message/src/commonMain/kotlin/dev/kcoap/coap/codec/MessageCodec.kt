package dev.kcoap.coap.codec

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType

/**
 * Turns messages into wire bytes and back, for transports that deliver whole messages: a UDP datagram
 * ([UdpMessageCodec]) or a WebSocket frame ([ReliableMessageCodec.WebSocket]). Byte streams use
 * [ReliableStreamDecoder] to find message boundaries first.
 */
public interface MessageCodec {
    public fun encode(message: CoapMessage): ByteArray

    /** @throws CoapFormatException when [bytes] are not a well-formed message. */
    public fun decode(bytes: ByteArray): CoapMessage
}

/**
 * A message that could not be parsed (RFC 7252 §4.2 calls this a message format error).
 *
 * [messageId] and [type] are set when the header was readable, so the messaging layer can answer a
 * malformed Confirmable message with a Reset as RFC 7252 §4.2 requires. [silentlyIgnore] marks input
 * that must be dropped without any answer, such as an unknown protocol version (§3).
 */
public class CoapFormatException(
    message: String,
    public val messageId: Int? = null,
    public val type: CoapMessageType? = null,
    public val silentlyIgnore: Boolean = false,
) : Exception(message)
