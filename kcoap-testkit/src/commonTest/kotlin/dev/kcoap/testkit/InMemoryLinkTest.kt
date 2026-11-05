package dev.kcoap.testkit

import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionEndedException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class InMemoryLinkTest {
    @Test
    fun sessionsShowEveryPeerAsASecureEndpointAndEndTogether() = runTest {
        val network = InMemoryNetwork(backgroundScope, Random(1))
        val identity = PeerIdentity.PreSharedKey(byteArrayOf(7))
        val a = InMemorySessions(network.attach(maxDatagramSize = 100), identity)
        val b = InMemorySessions(network.attach())
        assertEquals(100, a.maxDatagramSize)

        val toB = a.sessionTo(b.localEndpoint)
        assertSame(toB, a.sessionTo(b.localEndpoint), "one session per address")
        assertEquals(identity, toB.identity)
        assertEquals(b.localEndpoint, toB.via)
        a.send(toB, byteArrayOf(1, 2))
        val received = assertIs<DatagramEvent.Received>(b.receive())
        val fromA = assertIs<Endpoint.Secure>(received.from)
        assertEquals(a.localEndpoint, fromA.via)
        assertEquals(PeerIdentity.None, fromA.identity)
        assertContentEquals(byteArrayOf(1, 2), received.datagram)
        b.send(fromA, byteArrayOf(3))
        assertEquals(toB, assertIs<DatagramEvent.Received>(a.receive()).from, "the same session both ways")
        assertFailsWith<IllegalArgumentException> { a.send(b.localEndpoint, byteArrayOf(4)) }

        a.endAll(SessionEndReason.ALERT)

        val ended = assertIs<DatagramEvent.SessionEnded>(a.receive())
        assertEquals(toB, ended.endpoint)
        assertEquals(SessionEndReason.ALERT, ended.reason)
        val refused = assertFailsWith<SessionEndedException> { a.send(toB, byteArrayOf(5)) }
        assertEquals(toB, refused.endpoint)
        assertNotEquals<Endpoint>(toB, a.sessionTo(b.localEndpoint), "a new session after the end")
        assertTrue("InMemorySessions" in a.toString())
        a.close()
        assertTrue(network.toString().isNotEmpty())
    }

    @Test
    fun datagramLinksGiveEachSideItsSessionsAndCutThemAll() = runTest {
        val link = InMemoryLink.Datagrams(InMemoryNetwork(backgroundScope, Random(2)))
        assertEquals(CoapScheme.COAP, link.scheme)
        assertFailsWith<IllegalStateException> { link.uri }
        val server = link.server()
        val client = link.client()
        val address = (server.transport as InMemorySessions).localEndpoint
        assertEquals("coap://${address.host}:${address.port}", link.uri)
        assertTrue(server.connectors.isEmpty() && server.listeners.isEmpty())

        val peer = assertIs<Endpoint.Secure>(client.peer(address))
        assertEquals(address, peer.via)
        val other = Endpoint.Tcp("127.0.0.1", 1)
        assertSame(other, client.peer(other), "only addresses become sessions")
        assertSame(address, server.peer(address), "the server side addresses nobody by URI")

        client.transport!!.send(peer, byteArrayOf(1))
        assertIs<DatagramEvent.Received>(server.transport!!.receive())
        link.cut()

        assertIs<DatagramEvent.SessionEnded>(client.transport.receive())
        assertIs<DatagramEvent.SessionEnded>(server.transport.receive())
        assertEquals("InMemoryLink.Datagrams", link.toString())
    }

    @Test
    fun connectionLinksListenConnectAndCutEveryConnection() = runTest {
        val connections = InMemoryConnections()
        val identity = PeerIdentity.PreSharedKey(byteArrayOf(1))
        val link = InMemoryLink.Connections(connections, CoapScheme.COAP_WS, port = 8080, clientIdentity = identity)
        assertEquals("coap+ws://127.0.0.1:8080", link.uri)
        assertEquals(CoapScheme.COAP_WS, link.scheme)
        val server = link.server()
        val client = link.client()
        assertNull(server.transport)
        val target = Endpoint.WebSocket.of("127.0.0.1", 8080, usesTls = false)
        assertSame(target, client.peer(target))

        val opened = client.connectors.single().connect(target)
        val accepted = server.listeners.single().accept()!!
        assertEquals(identity, accepted.peerIdentity)
        link.cut()

        assertTrue(connections.connections().all { it.isClosed })
        assertTrue(opened.toString().isNotEmpty())
        assertEquals("InMemoryLink.Connections(coap+ws)", link.toString())
        assertFailsWith<IllegalArgumentException> { InMemoryLink.Connections(connections, CoapScheme.COAPS) }
    }
}
