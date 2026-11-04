package dev.kcoap.transport.ktor

import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionId
import io.ktor.network.selector.SelectorManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class KtorUdpTransportTest {
    private fun real(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(10.seconds) { block() } }

    @Test
    fun datagramsTravelBetweenTwoSocketsWithCanonicalAddresses() = real {
        val a = KtorUdpTransport.bind("127.0.0.1")
        val b = KtorUdpTransport.bind("127.0.0.1", maxDatagramSize = 2000)
        try {
            assertEquals("127.0.0.1", a.localEndpoint.host)
            assertTrue(a.localEndpoint.port > 0)
            assertEquals(1152, a.maxDatagramSize)
            assertEquals(2000, b.maxDatagramSize)

            a.send(b.localEndpoint, byteArrayOf(1, 2, 3))
            val received = assertIs<DatagramEvent.Received>(b.receive())
            assertEquals(a.localEndpoint, received.from)
            assertContentEquals(byteArrayOf(1, 2, 3), received.datagram)

            b.send(received.from, ByteArray(1500) { 7 })
            assertEquals(1500, (a.receive() as DatagramEvent.Received).datagram.size, "larger datagrams are received")
            assertTrue(a.toString().contains(a.localEndpoint.port.toString()))
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun ipv6LoopbackIsReportedInCanonicalForm() = real {
        val a = KtorUdpTransport.bind("::1")
        val b = KtorUdpTransport.bind("::1")
        try {
            a.send(Endpoint.Udp("::1", b.localEndpoint.port), byteArrayOf(9))
            val received = b.receive() as DatagramEvent.Received
            assertEquals(Endpoint.Udp("::1", a.localEndpoint.port), received.from)
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun sendingIsLimitedToUdpEndpointsAndTheDatagramSize() = real {
        val selector = SelectorManager(Dispatchers.IO)
        val a = KtorUdpTransport.bind("127.0.0.1", selector = selector)
        try {
            assertFailsWith<IllegalArgumentException> { a.send(Endpoint.Udp("127.0.0.1", 9), ByteArray(1153)) }
            val secure = Endpoint.Secure(SessionId(1), 0, Endpoint.Udp("127.0.0.1", 9), PeerIdentity.None)
            assertFailsWith<IllegalArgumentException> { a.send(secure, ByteArray(1)) }
            assertFailsWith<IllegalArgumentException> { KtorUdpTransport.bind(port = 70000) }
            assertFailsWith<IllegalArgumentException> { KtorUdpTransport.bind(maxDatagramSize = 10) }
        } finally {
            a.close()
            selector.close()
        }
    }

    @Test
    fun bindingATakenPortFails() = real {
        val a = KtorUdpTransport.bind("127.0.0.1")
        try {
            assertFailsWith<Exception> { KtorUdpTransport.bind("127.0.0.1", a.localEndpoint.port) }
        } finally {
            a.close()
        }
    }
}
