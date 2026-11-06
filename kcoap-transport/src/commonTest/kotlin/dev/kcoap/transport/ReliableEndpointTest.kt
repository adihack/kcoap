package dev.kcoap.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReliableEndpointTest {
    /** RFC 8323 §8: coap+tcp and coaps+tcp are distinct origins, even on the same host and port. */
    @Test
    fun tlsIsPartOfATcpPeer() {
        val plain = Endpoint.Tcp("192.0.2.1", 5684)
        val secured = Endpoint.Tcp("192.0.2.1", 5684, usesTls = true)
        assertNotEquals(plain, secured)
        assertFalse(plain.usesTls)
        assertEquals(CoapScheme.COAP_TCP, plain.scheme)
        assertEquals(CoapScheme.COAPS_TCP, secured.scheme)
        assertEquals(Endpoint.Tcp("192.0.2.1", 5684, usesTls = false), plain)
    }

    /** RFC 8323 §8.3 and §8.4: the WebSocket endpoint is the authority plus /.well-known/coap. */
    @Test
    fun webSocketEndpointsOfAnAuthority() {
        val plain = Endpoint.WebSocket.of("example.org", 80)
        assertEquals("ws://example.org:80/.well-known/coap", plain.uri)
        assertFalse(plain.usesTls)
        assertEquals(CoapScheme.COAP_WS, plain.scheme)

        val secured = Endpoint.WebSocket.of("2001:db8::1", 443, usesTls = true)
        assertEquals("wss://[2001:db8::1]:443/.well-known/coap", secured.uri)
        assertTrue(secured.usesTls)
        assertEquals(CoapScheme.COAPS_WS, secured.scheme)
        assertTrue(Endpoint.WebSocket("WSS://h").usesTls, "URI schemes are case insensitive")
        assertFailsWith<IllegalArgumentException> { Endpoint.WebSocket.of("h", 70000) }
    }

    @Test
    fun webSocketConstantsAreTheRegisteredOnes() {
        assertEquals("coap", CoapScheme.WEBSOCKET_SUBPROTOCOL)
        assertEquals("/.well-known/coap", CoapScheme.WEBSOCKET_PATH)
    }

    @Test
    fun connectorsAndListenersHaveDefaults() = runTest {
        val connection = object : MessageConnection {
            override val remote: Endpoint = Endpoint.WebSocket.of("h", 80)
            override val peerIdentity: PeerIdentity = PeerIdentity.None
            override suspend fun send(message: ByteArray) {}
            override suspend fun receive(): ByteArray? = null
            override suspend fun close() {}
        }
        val connector = object : Connector {
            override val schemes = setOf(CoapScheme.COAP_WS)
            override suspend fun connect(peer: Endpoint): Connection = connection
        }
        connector.close()
        val listener = object : ConnectionListener {
            override val scheme = CoapScheme.COAP_WS
            override suspend fun accept(): Connection? = null
            override suspend fun close() {}
        }
        val connected: Connection = connector.connect(connection.remote)
        val kind = when (connected) {
            is ByteStreamConnection -> TransportKind.BYTE_STREAM
            is MessageConnection -> TransportKind.MESSAGE_STREAM
        }
        assertEquals(connector.schemes.single().kind, kind)
        assertNull(listener.accept())
        assertNull((connected as MessageConnection).receive())
    }
}
