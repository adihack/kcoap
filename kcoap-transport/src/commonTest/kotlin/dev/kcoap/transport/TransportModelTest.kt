package dev.kcoap.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransportModelTest {
    private val session = Endpoint.Secure(SessionId(4), 1, Endpoint.Udp("192.0.2.1", 5684), PeerIdentity.None)

    @Test
    fun datagramEventsCarryWhatTheMessagingLayerNeeds() {
        val received = DatagramEvent.Received(Endpoint.Udp("192.0.2.1", 5683), byteArrayOf(1))
        assertEquals(Endpoint.Udp("192.0.2.1", 5683), received.from)
        assertContentEquals(byteArrayOf(1), received.datagram)
        val ended = DatagramEvent.SessionEnded(session, SessionEndReason.REPLACED)
        assertEquals(session, ended.endpoint)
        assertEquals(SessionEndReason.REPLACED, ended.reason)
        assertEquals(5, SessionEndReason.entries.size)
        val exception = SessionEndedException(session, SessionEndReason.TIMEOUT)
        assertEquals(session, exception.endpoint)
        assertEquals(SessionEndReason.TIMEOUT, exception.reason)
        assertTrue(exception.message!!.contains("TIMEOUT"))
    }

    @Test
    fun identitiesHashAndPrintWithoutContent() {
        val psk = PeerIdentity.PreSharedKey(byteArrayOf(1, 2))
        assertEquals(PeerIdentity.PreSharedKey(byteArrayOf(1, 2)).hashCode(), psk.hashCode())
        assertEquals("PreSharedKey(2 bytes)", psk.toString())
        val rpk = PeerIdentity.RawPublicKey(byteArrayOf(3))
        assertContentEquals(byteArrayOf(3), rpk.subjectPublicKeyInfo)
        assertEquals(PeerIdentity.RawPublicKey(byteArrayOf(3)).hashCode(), rpk.hashCode())
        assertEquals("RawPublicKey(1 bytes)", rpk.toString())
        val certificate = PeerIdentity.Certificate(listOf(byteArrayOf(4), byteArrayOf(5)))
        assertEquals(PeerIdentity.Certificate(listOf(byteArrayOf(4), byteArrayOf(5))).hashCode(), certificate.hashCode())
        assertEquals("Certificate(2 certificates)", certificate.toString())
    }

    @Test
    fun endpointsValidateAndPrint() {
        assertFailsWith<IllegalArgumentException> { Endpoint.Tcp("192.0.2.1", -1) }
        assertEquals("ws://example.com/.well-known/coap", Endpoint.WebSocket("ws://example.com/.well-known/coap").uri)
        assertEquals("ble", Endpoint.Opaque("ble", "00:11").link)
        assertEquals("Secure(4, epoch=1, via=Udp(host=192.0.2.1, port=5684), NO_SEC)", session.toString())
    }
}
