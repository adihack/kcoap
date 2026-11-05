package dev.kcoap.coap.codec

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UdpMessageCodecTest {
    /** RFC 8613 Appendix C.4: the unprotected request `GET coap://localhost/tv1`. */
    private val c4Request = "44015d1f00003974396c6f63616c686f737483747631"

    /** RFC 8613 Appendix C.7: the unprotected response `2.05 Content "Hello World!"`. */
    private val c7Response = "64455d1f00003974ff48656c6c6f20576f726c6421"

    /** RFC 8613 Appendix C.4: the same request protected with OSCORE. */
    private val c4Protected = "44025d1f00003974396c6f63616c686f7374620914ff612f1092f1776f1c1668b3825e"

    /** RFC 8613 Appendix C.6: a protected request with a kid context in the OSCORE option. */
    private val c6Protected = "44022f8eef9bbf7a396c6f63616c686f73746b19140837cbf3210017a2d3ff72cd7273fd331ac45cffbe55c3"

    @Test
    fun decodesTheRfc8613Request() {
        val message = UdpMessageCodec.decode(c4Request.hexToBytes())
        assertEquals(CoapMessageType.CON, message.type)
        assertEquals(CoapCode.GET, message.code)
        assertEquals(0x5d1f, message.messageId)
        assertEquals("00003974", message.tokenHex)
        assertEquals("localhost", message.options.uriHost)
        assertEquals(listOf("tv1"), message.options.uriPath)
        assertEquals(0, message.payloadSize)
        assertEquals(c4Request, UdpMessageCodec.encode(message).toHex())
    }

    @Test
    fun decodesTheRfc8613Response() {
        val message = UdpMessageCodec.decode(c7Response.hexToBytes())
        assertEquals(CoapMessageType.ACK, message.type)
        assertEquals(CoapCode.CONTENT, message.code)
        assertEquals("Hello World!", message.payloadAsText())
        assertEquals(c7Response, UdpMessageCodec.encode(message).toHex())
    }

    @Test
    fun roundTripsProtectedMessages() {
        for (hex in listOf(c4Protected, c6Protected)) {
            val message = UdpMessageCodec.decode(hex.hexToBytes())
            assertEquals(CoapCode.POST, message.code, "OSCORE requests are POST on the outside")
            assertTrue(CoapOptions.OSCORE.number in message.options)
            assertEquals(hex, UdpMessageCodec.encode(message).toHex())
        }
        assertEquals("0914", UdpMessageCodec.decode(c4Protected.hexToBytes()).options.oscore?.toHex())
        assertEquals("19140837cbf3210017a2d3", UdpMessageCodec.decode(c6Protected.hexToBytes()).options.oscore?.toHex())
    }

    @Test
    fun extendedOptionDeltasAndLengths() {
        val message = CoapMessage.request(CoapCode.POST) {
            messageId = 0xFFFF
            token = "0102030405060708".hexToBytes()
            options {
                add(CoapOption(12, ByteArray(0)))
                add(CoapOption(12 + 13, ByteArray(13) { 1 }))
                add(CoapOption(25 + 268, ByteArray(268) { 2 }))
                add(CoapOption(293 + 269, ByteArray(269) { 3 }))
                add(CoapOption(65535, ByteArray(2000) { 4 }))
            }
            payload = ByteArray(10) { 9 }
        }
        val encoded = UdpMessageCodec.encode(message)
        assertEquals(message, UdpMessageCodec.decode(encoded))
    }

    @Test
    fun emptyMessages() {
        val ack = CoapMessage.empty(CoapMessageType.ACK, 0x1234)
        assertEquals("60001234", UdpMessageCodec.encode(ack).toHex())
        assertEquals(ack, UdpMessageCodec.decode("60001234".hexToBytes()))
        assertTrue(UdpMessageCodec.decode("70000001".hexToBytes()).isEmpty)
    }

    @Test
    fun formatErrorsKeepTheHeaderForAReset() {
        val error = assertFailsWith<CoapFormatException> { UdpMessageCodec.decode("49011234".hexToBytes()) }
        assertEquals(0x1234, error.messageId)
        assertEquals(CoapMessageType.CON, error.type)

        val malformed = mapOf(
            "490112340102" to "reserved token length 9",
            "42011234ab" to "token past the end",
            "61001234" to "empty message with a token",
            "6000123401" to "empty message with bytes after the header",
            "40011234ff" to "payload marker without payload",
            "40011234f0" to "reserved delta nibble",
            "400112340f" to "reserved length nibble",
            "40011234d0" to "truncated one-byte delta extension",
            "40011234e000" to "truncated two-byte delta extension",
            "4001123403ab" to "option value past the end",
            "40011234e0ffff00" to "option number above 65535",
        )
        for ((hex, reason) in malformed) {
            assertFailsWith<CoapFormatException>(reason) { UdpMessageCodec.decode(hex.hexToBytes()) }
        }
        assertNull(assertFailsWith<CoapFormatException> { UdpMessageCodec.decode("4001".hexToBytes()) }.messageId)
    }

    @Test
    fun unknownVersionIsSilentlyIgnored() {
        val error = assertFailsWith<CoapFormatException> { UdpMessageCodec.decode("84011234".hexToBytes()) }
        assertTrue(error.silentlyIgnore)
    }

    @Test
    fun payloadIsKeptExactly() {
        val bytes = ByteArray(300) { it.toByte() }
        val message = CoapMessage.request(CoapCode.PUT) { payload = bytes }
        assertContentEquals(bytes, UdpMessageCodec.decode(UdpMessageCodec.encode(message)).payload)
    }
}
