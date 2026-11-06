package dev.kcoap.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class TransportTypesTest {
    private val udp = Endpoint.Udp("192.0.2.1", 5683)
    private val secure = Endpoint.Secure(SessionId(9), 1, udp, PeerIdentity.RawPublicKey(byteArrayOf(1, 2)))

    @Test
    fun eventsCarryTheirData() {
        val bytes = byteArrayOf(1, 2, 3)
        val received = DatagramEvent.Received(udp, bytes)
        assertSame(udp, received.from)
        assertSame(bytes, received.datagram, "datagrams are handed over without copying")

        val ended = DatagramEvent.SessionEnded(secure, SessionEndReason.REPLACED)
        assertSame(secure, ended.endpoint)
        assertEquals(SessionEndReason.REPLACED, ended.reason)
        assertEquals(5, SessionEndReason.entries.size)

        val error = SessionEndedException(secure, SessionEndReason.TIMEOUT)
        assertEquals(SessionEndReason.TIMEOUT, error.reason)
        assertSame(secure, error.endpoint)
        assertEquals("DTLS session ended (TIMEOUT): Secure(9, epoch=1, via=Udp(host=192.0.2.1, port=5683), RAW_PUBLIC_KEY)", error.message)
    }

    @Test
    fun endpointVariants() {
        assertEquals(Endpoint.Tcp("h", 5683), Endpoint.Tcp("h", 5683))
        assertFailsWith<IllegalArgumentException> { Endpoint.Tcp("h", 70000) }
        assertEquals(Endpoint.WebSocket("wss://h/.well-known/coap"), Endpoint.WebSocket("wss://h/.well-known/coap"))
        assertEquals(Endpoint.Opaque("ble", "aa:bb"), Endpoint.Opaque("ble", "aa:bb"))
        assertNotEquals<Endpoint>(Endpoint.Opaque("ble", "aa:bb"), udp)
        assertEquals(1, secure.epoch)
        assertSame(udp, secure.via)
        assertEquals(SessionId(9), secure.session)
    }

    @Test
    fun identitiesExposeCopies() {
        val rpk = PeerIdentity.RawPublicKey(byteArrayOf(1, 2))
        assertContentEquals(byteArrayOf(1, 2), rpk.subjectPublicKeyInfo)
        assertEquals("RawPublicKey(2 bytes)", rpk.toString())
        assertEquals(rpk.hashCode(), PeerIdentity.RawPublicKey(byteArrayOf(1, 2)).hashCode())
        assertNotEquals<PeerIdentity>(rpk, PeerIdentity.None)

        val psk = PeerIdentity.PreSharedKey("id".encodeToByteArray())
        assertEquals("PreSharedKey(2 bytes)", psk.toString())
        assertEquals(psk.hashCode(), PeerIdentity.PreSharedKey("id".encodeToByteArray()).hashCode())

        val chain = PeerIdentity.Certificate(listOf(byteArrayOf(5)))
        assertEquals("Certificate(1 certificates)", chain.toString())
        assertEquals(chain.hashCode(), PeerIdentity.Certificate(listOf(byteArrayOf(5))).hashCode())
        assertNotEquals<PeerIdentity>(chain, psk)
        assertNotEquals(PeerIdentity.Certificate(listOf(byteArrayOf(5))), PeerIdentity.Certificate(listOf(byteArrayOf(6))))
    }
}
