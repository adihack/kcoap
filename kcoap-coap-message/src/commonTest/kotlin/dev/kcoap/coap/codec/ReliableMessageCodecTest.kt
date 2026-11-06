package dev.kcoap.coap.codec

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReliableMessageCodecTest {
    private fun request(payloadSize: Int) = CoapMessage.request(CoapCode.GET) {
        token = "0a0b".hexToBytes()
        options { uriPath("sensors/temp") }
        payload = ByteArray(payloadSize) { it.toByte() }
    }

    /** RFC 8323 §3.2: Len is the size of options plus payload, here Uri-Path "temp" = 5 bytes. */
    @Test
    fun shortMessageUsesTheLengthNibble() {
        val message = CoapMessage.request(CoapCode.GET) {
            token = "0a".hexToBytes()
            options { uriPath("temp") }
        }
        assertEquals("51010a b474656d70".replace(" ", ""), ReliableMessageCodec.Tcp.encode(message).toHex())
        assertEquals(message.copyForReliable(), ReliableMessageCodec.Tcp.decode(ReliableMessageCodec.Tcp.encode(message)))
    }

    @Test
    fun everyExtendedLengthForm() {
        // The options take 13 bytes; with a payload (plus its marker) the body selects Len 13, 14 or 15.
        for ((payload, nibble) in listOf(0 to 0xD, 254 to 0xD, 255 to 0xE, 70_000 to 0xF)) {
            val message = request(payload)
            val encoded = ReliableMessageCodec.Tcp.encode(message)
            assertEquals(nibble, (encoded[0].toInt() and 0xFF) ushr 4, "payload $payload")
            assertEquals(message.copyForReliable(), ReliableMessageCodec.Tcp.decode(encoded))
        }
    }

    /** RFC 8323 §4.2: over WebSockets Len is always 0. */
    @Test
    fun webSocketFramesHaveNoLength() {
        val message = request(400)
        val encoded = ReliableMessageCodec.WebSocket.encode(message)
        assertEquals(0x02, encoded[0].toInt())
        assertEquals(message.copyForReliable(), ReliableMessageCodec.WebSocket.decode(encoded))
        assertFailsWith<CoapFormatException> { ReliableMessageCodec.WebSocket.decode(ReliableMessageCodec.Tcp.encode(request(1))) }
    }

    @Test
    fun signalingMessages() {
        // CSM with Max-Message-Size 1152 (option 2) and Block-Wise-Transfer (option 4).
        val csm = CoapMessage.build {
            code = CoapCode.CSM
            options {
                add(CoapOption.uint(2, 1152))
                add(CoapOption.empty(4))
            }
        }
        val encoded = ReliableMessageCodec.Tcp.encode(csm)
        assertEquals("40e122048020", encoded.toHex())
        val decoded = ReliableMessageCodec.Tcp.decode(encoded)
        assertTrue(decoded.isSignaling)
        assertEquals(1152, decoded.options.first(2)?.uintValue())
    }

    @Test
    fun streamDecoderSplitsAndReassembles() {
        val messages = listOf(request(0), request(20), request(500))
        val stream = messages.map { ReliableMessageCodec.Tcp.encode(it) }.reduce { a, b -> a + b }
        val decoder = ReliableStreamDecoder()
        val received = mutableListOf<CoapMessage>()
        stream.toList().chunked(7).forEach { chunk -> received += decoder.feed(chunk.toByteArray()) }
        assertEquals(messages.map { it.copyForReliable() }, received)
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun streamDecoderEnforcesTheSizeLimit() {
        val decoder = ReliableStreamDecoder(maxMessageSize = 100)
        assertFailsWith<CoapFormatException> { decoder.feed(ReliableMessageCodec.Tcp.encode(request(200)).copyOf(6)) }
    }

    @Test
    fun malformedFrames() {
        assertFailsWith<CoapFormatException>("empty") { ReliableMessageCodec.Tcp.decode(ByteArray(0)) }
        assertFailsWith<CoapFormatException>("token length") { ReliableMessageCodec.Tcp.decode("0901".hexToBytes()) }
        assertFailsWith<CoapFormatException>("length mismatch") { ReliableMessageCodec.Tcp.decode("2001".hexToBytes()) }
        assertFailsWith<CoapFormatException>("empty with content") { ReliableMessageCodec.Tcp.decode("10 00 b0".replace(" ", "").hexToBytes()) }
        assertFailsWith<CoapFormatException>("truncated") { ReliableMessageCodec.Tcp.decode("d0".hexToBytes()) }
    }

    /** Reliable transports have no type or Message ID; decoding yields CON and 0. */
    private fun CoapMessage.copyForReliable(): CoapMessage = toBuilder().apply {
        type = dev.kcoap.coap.CoapMessageType.CON
        messageId = 0
    }.build()
}
