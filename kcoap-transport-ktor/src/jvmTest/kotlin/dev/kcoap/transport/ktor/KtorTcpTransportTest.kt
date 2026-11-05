package dev.kcoap.transport.ktor

import dev.kcoap.transport.ByteStreamConnection
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import io.ktor.network.selector.SelectorManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class KtorTcpTransportTest {
    private fun real(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(10.seconds) { block() } }

    private suspend fun ByteStreamConnection.readExactly(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(buffer, filled, count - filled)
            check(read > 0) { "end of stream after $filled bytes" }
            filled += read
        }
        return buffer
    }

    @Test
    fun bytesTravelBothWaysAndTheEndIsReported() = real {
        val listener = KtorTcpListener.bind("127.0.0.1")
        val connector = KtorTcpConnector()
        try {
            assertEquals("127.0.0.1", listener.localEndpoint.host)
            assertEquals(CoapScheme.COAP_TCP, listener.scheme)
            assertEquals(setOf(CoapScheme.COAP_TCP), connector.schemes)
            coroutineScope {
                val accepted = async { listener.accept() }
                val client = assertIs<ByteStreamConnection>(connector.connect(listener.localEndpoint))
                val server = assertIs<ByteStreamConnection>(accepted.await())
                assertEquals(listener.localEndpoint, client.remote)
                assertEquals("127.0.0.1", (server.remote as Endpoint.Tcp).host)
                assertEquals(PeerIdentity.None, server.peerIdentity)

                client.write(ByteArray(5000) { it.toByte() })
                assertContentEquals(ByteArray(5000) { it.toByte() }, server.readExactly(5000))
                server.write(byteArrayOf(7))
                assertContentEquals(byteArrayOf(7), client.readExactly(1))

                client.close()
                assertEquals(-1, server.read(ByteArray(1), 0, 1))
                server.close()
                assertTrue(server.toString().startsWith("KtorTcpConnection("))
            }
        } finally {
            listener.close()
            listener.close()
            connector.close()
        }
        assertNull(listener.accept(), "a closed listener accepts nothing")
    }

    @Test
    fun ipv6LoopbackIsReportedInCanonicalForm() = real {
        val selector = SelectorManager(Dispatchers.IO)
        val listener = KtorTcpListener.bind("::1", selector = selector)
        val connector = KtorTcpConnector(selector)
        try {
            assertEquals("::1", listener.localEndpoint.host)
            coroutineScope {
                val accepted = async { listener.accept() }
                connector.connect(Endpoint.Tcp("::1", listener.localEndpoint.port)).close()
                assertEquals("::1", (accepted.await()!!.remote as Endpoint.Tcp).host)
            }
        } finally {
            listener.close()
            selector.close()
        }
    }

    @Test
    fun onlyPlainTcpPeersAreReachedAndPortsAreChecked() = real {
        val connector = KtorTcpConnector()
        try {
            assertFailsWith<IllegalArgumentException> { connector.connect(Endpoint.Tcp("127.0.0.1", 5684, usesTls = true)) }
            assertFailsWith<IllegalArgumentException> { connector.connect(Endpoint.WebSocket.of("127.0.0.1", 80)) }
            assertFailsWith<IllegalArgumentException> { KtorTcpListener.bind(port = 70000) }
            val taken = KtorTcpListener.bind("127.0.0.1")
            try {
                assertFailsWith<Exception> { KtorTcpListener.bind("127.0.0.1", taken.localEndpoint.port) }
            } finally {
                taken.close()
            }
            assertFailsWith<Exception> { connector.connect(Endpoint.Tcp("127.0.0.1", taken.localEndpoint.port)) }
            assertEquals("KtorTcpConnector", connector.toString())
            assertTrue(taken.toString().contains("${taken.localEndpoint.port}"))
        } finally {
            connector.close()
        }
    }
}
