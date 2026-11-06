package dev.kcoap.testkit

import dev.kcoap.transport.ByteStreamConnection
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.MessageConnection
import dev.kcoap.transport.PeerIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class InMemoryConnectionsTest {
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
    fun byteStreamsCarryBytesBothWaysInChunks() = runTest {
        val connections = InMemoryConnections(chunkSize = 3)
        val taps = mutableListOf<Pair<Endpoint, Int>>()
        connections.tap = { from, _, bytes -> taps += from to bytes.size }
        val listener = connections.listen(5683, clientIdentity = PeerIdentity.PreSharedKey(byteArrayOf(1)))
        assertEquals(Endpoint.Tcp("127.0.0.1", 5683), listener.localEndpoint)
        val client = assertIs<ByteStreamConnection>(connections.connector.connect(Endpoint.Tcp("127.0.0.1", 5683)))
        val accepted = assertIs<InMemoryByteStream>(listener.accept())
        assertEquals(Endpoint.Tcp("127.0.0.1", 5683), client.remote)
        assertEquals(Endpoint.Tcp("127.0.0.1", 40000), accepted.remote)
        assertEquals(PeerIdentity.PreSharedKey(byteArrayOf(1)), accepted.peerIdentity)
        assertEquals(PeerIdentity.None, client.peerIdentity)
        assertTrue(accepted.isAccepted)

        client.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        val buffer = ByteArray(10)
        assertEquals(3, accepted.read(buffer, 0, 10), "one chunk at a time")
        assertEquals(2, accepted.read(buffer, 3, 2))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), buffer.copyOfRange(0, 5))
        assertContentEquals(byteArrayOf(6, 7), accepted.readExactly(2))
        accepted.write(byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), client.readExactly(1))
        assertEquals(listOf<Pair<Endpoint, Int>>(Endpoint.Tcp("127.0.0.1", 40000) to 7, Endpoint.Tcp("127.0.0.1", 5683) to 1), taps)

        accepted.inject(byteArrayOf(42))
        assertEquals(42, accepted.readExactly(1)[0].toInt())

        client.close()
        assertTrue((accepted as InMemoryConnection).isClosed)
        assertEquals(-1, accepted.read(buffer, 0, 1))
        assertFailsWith<IllegalStateException> { accepted.write(byteArrayOf(1)) }
        assertEquals(2, connections.connections().size)
        assertTrue(client.toString().startsWith("InMemoryByteStream(to"))
    }

    @Test
    fun messageStreamsKeepMessagesWhole() = runTest {
        val connections = InMemoryConnections(chunkSize = 2)
        val listener = connections.listen(8080, CoapScheme.COAP_WS)
        val client = assertIs<MessageConnection>(connections.connector.connect(Endpoint.WebSocket.of("127.0.0.1", 8080)))
        val accepted = assertIs<InMemoryMessageStream>(listener.accept())
        assertEquals(Endpoint.WebSocket("ws://127.0.0.1:40000/.well-known/coap"), accepted.remote)
        client.send(byteArrayOf(1, 2, 3, 4, 5))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), accepted.receive())
        accepted.inject(byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), accepted.receive())
        accepted.close()
        assertNull(client.receive())
        assertFailsWith<IllegalStateException> { client.send(byteArrayOf(1)) }
        assertTrue(accepted.toString().endsWith("accepted)"))
    }

    @Test
    fun aResetBreaksBothSides() = runTest {
        val connections = InMemoryConnections()
        val listener = connections.listen(5683)
        val client = connections.connector.connect(Endpoint.Tcp("127.0.0.1", 5683)) as InMemoryByteStream
        val accepted = listener.accept() as InMemoryByteStream
        client.write(byteArrayOf(1))
        client.reset()
        assertFailsWith<InMemoryConnectionReset> { accepted.read(ByteArray(1), 0, 1) }
        assertFailsWith<InMemoryConnectionReset> { client.read(ByteArray(1), 0, 1) }

        val ws = connections.listen(80, CoapScheme.COAP_WS)
        val other = connections.connector.connect(Endpoint.WebSocket.of("127.0.0.1", 80)) as InMemoryMessageStream
        (ws.accept() as InMemoryMessageStream).reset()
        assertFailsWith<InMemoryConnectionReset> { other.receive() }
        assertFalse(listOf(client, accepted, other).any { !it.isClosed })
    }

    /** RFC 7252 §6.6 (RFC 8323 §8.1, §8.2): a listener is reached at every endpoint equal to its own address. */
    @Test
    fun rfc7252Section6_6TcpListenersAreReachedAtEveryEqualEndpoint() = runTest {
        val connections = InMemoryConnections()
        val named = connections.listen(5683, host = "Server")
        assertEquals(Endpoint.Tcp("server", 5683), named.localEndpoint)
        for (peer in listOf(named.localEndpoint, Endpoint.Tcp("Server", 5683), Endpoint.Tcp("SERVER", 5683))) {
            assertEquals(peer, connections.connector.connect(peer).remote)
            assertIs<InMemoryByteStream>(named.accept())
        }

        val literal = connections.listen(5684, CoapScheme.COAPS_TCP, host = "[2001:DB8::1]")
        assertEquals(Endpoint.Tcp("2001:db8::1", 5684, usesTls = true), literal.localEndpoint)
        for (peer in listOf(literal.localEndpoint, Endpoint.Tcp("2001:db8:0::1", 5684, usesTls = true))) {
            connections.connector.connect(peer)
            assertIs<InMemoryByteStream>(literal.accept())
        }
        assertFailsWith<IllegalArgumentException> { connections.listen(5684, CoapScheme.COAPS_TCP, host = "2001:db8::1") }
        assertFailsWith<IllegalArgumentException> { connections.listen(5683, host = "server") }
        assertFailsWith<InMemoryConnectionRefused> { connections.connector.connect(Endpoint.Tcp("2001:db8::1", 5684)) }

        val bracketedLoopback = connections.listen(7000, host = "[::1]")
        connections.connector.connect(bracketedLoopback.localEndpoint)
        assertIs<InMemoryByteStream>(bracketedLoopback.accept())
    }

    /** RFC 8323 §8.3, §8.4 with RFC 6455 §3: a WebSocket URI without a port reaches the listener at 80 or 443. */
    @Test
    fun rfc8323Section8_3WebSocketListenersAreReachedAtEveryEqualUri() = runTest {
        val connections = InMemoryConnections()
        val plain = connections.listen(80, CoapScheme.COAP_WS)
        val reaching = listOf(
            plain.localEndpoint,
            Endpoint.WebSocket("ws://127.0.0.1/.well-known/coap"),
            Endpoint.WebSocket("WS://127.0.0.1:80/.well-known/coap"),
            Endpoint.WebSocket("ws://127.0.0.1"),
            Endpoint.WebSocket("ws://127.0.0.1/another/path?x=1"),
        )
        for (peer in reaching) {
            assertEquals(peer, connections.connector.connect(peer).remote)
            assertIs<InMemoryMessageStream>(plain.accept())
        }

        val named = connections.listen(80, CoapScheme.COAP_WS, host = "Server")
        connections.connector.connect(Endpoint.WebSocket("ws://SERVER/.well-known/coap"))
        assertIs<InMemoryMessageStream>(named.accept())

        val secure = connections.listen(443, CoapScheme.COAPS_WS, host = "[2001:DB8::1]")
        assertEquals("wss://[2001:DB8::1]:443/.well-known/coap", (secure.localEndpoint as Endpoint.WebSocket).uri)
        for (peer in listOf(secure.localEndpoint, Endpoint.WebSocket("wss://[2001:db8:0::1]/.well-known/coap"))) {
            connections.connector.connect(peer)
            assertIs<InMemoryMessageStream>(secure.accept())
        }

        val refused = listOf(
            Endpoint.WebSocket("ws://127.0.0.1:8080/.well-known/coap"),
            Endpoint.WebSocket("wss://127.0.0.1:80/.well-known/coap"),
            Endpoint.WebSocket("ws://[2001:db8::1]:443/.well-known/coap"),
            Endpoint.WebSocket("127.0.0.1:80"),
        )
        for (peer in refused) assertFailsWith<InMemoryConnectionRefused>(peer.toString()) { connections.connector.connect(peer) }
    }

    @Test
    fun connectingNeedsAListener() = runTest {
        val connections = InMemoryConnections()
        assertEquals(4, connections.connector.schemes.size)
        val refused = assertFailsWith<InMemoryConnectionRefused> { connections.connector.connect(Endpoint.Tcp("127.0.0.1", 1)) }
        assertEquals(Endpoint.Tcp("127.0.0.1", 1), refused.peer)
        assertFailsWith<IllegalArgumentException> { connections.connector.connect(Endpoint.Udp("127.0.0.1", 1)) }
        assertFailsWith<InMemoryConnectionRefused> {
            connections.connector.connect(Endpoint.Tcp("127.0.0.1", 5684, usesTls = true))
        }

        val secure = connections.listen(5684, CoapScheme.COAPS_TCP, host = "::1")
        assertEquals(Endpoint.Tcp("::1", 5684, usesTls = true), secure.localEndpoint)
        assertFailsWith<IllegalArgumentException> { connections.listen(5684, CoapScheme.COAPS_TCP, host = "::1") }
        assertFailsWith<IllegalArgumentException> { connections.listen(5683, CoapScheme.COAP) }
        assertFailsWith<IllegalArgumentException> { InMemoryConnections(chunkSize = 0) }
        connections.connector.connect(Endpoint.Tcp("::1", 5684, usesTls = true))

        secure.close()
        assertNull(secure.accept(), "closing drops what was not accepted")
        assertFailsWith<InMemoryConnectionRefused> { connections.connector.connect(Endpoint.Tcp("::1", 5684, usesTls = true)) }
        assertEquals("InMemoryConnectionListener(Tcp(host=::1, port=5684, usesTls=true))", secure.toString())
        assertEquals("InMemoryConnections.connector", connections.connector.toString())
    }
}
