package dev.kcoap.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoapSchemeTest {
    @Test
    fun defaultPorts() {
        val expected = mapOf(
            "coap" to 5683, // RFC 7252 §6.1
            "coaps" to 5684, // RFC 7252 §6.2
            "coap+tcp" to 5683, // RFC 8323 §8.1
            "coaps+tcp" to 5684, // RFC 8323 §8.2
            "coap+ws" to 80, // RFC 8323 §8.3
            "coaps+ws" to 443, // RFC 8323 §8.4
        )
        assertEquals(expected, CoapScheme.entries.associate { it.scheme to it.defaultPort })
    }

    @Test
    fun transportAndSecurityPerScheme() {
        assertEquals(TransportKind.DATAGRAM, CoapScheme.COAPS.kind)
        assertEquals(ChannelSecurityKind.DTLS, CoapScheme.COAPS.security)
        assertEquals(TransportKind.BYTE_STREAM, CoapScheme.COAPS_TCP.kind)
        assertEquals(ChannelSecurityKind.TLS, CoapScheme.COAPS_TCP.security)
        assertEquals(TransportKind.MESSAGE_STREAM, CoapScheme.COAPS_WS.kind)
        assertEquals(ChannelSecurityKind.TLS, CoapScheme.COAPS_WS.security)
        assertEquals(
            setOf(CoapScheme.COAPS),
            CoapScheme.entries.filter { it.security == ChannelSecurityKind.DTLS }.toSet(),
        )
        assertEquals(
            setOf(CoapScheme.COAP, CoapScheme.COAP_TCP, CoapScheme.COAP_WS),
            CoapScheme.entries.filter { it.security == ChannelSecurityKind.NONE }.toSet(),
        )
    }

    @Test
    fun lookupIsCaseInsensitive() {
        assertEquals(CoapScheme.COAPS_TCP, CoapScheme.fromScheme("COAPS+TCP"))
        assertEquals(CoapScheme.COAP, CoapScheme.fromScheme("coap"))
        assertNull(CoapScheme.fromScheme("http"))
    }
}
