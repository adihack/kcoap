package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.MessageConnection
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import dev.kcoap.transport.ktor.websocket.server.coapOverWebSockets
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/** The WebSocket client and server adapters over real loopback sockets. */
class KtorWebSocketTransportTest {
    private fun real(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(20.seconds) { block() } }

    /** RFC 8323 §4.1: subprotocol coap at /.well-known/coap, then binary messages both ways. */
    @Test
    fun rfc8323Section4_1MessagesTravelOverTheOpenedWebSocket() = real {
        val listener = KtorWebSocketListener.start(CIO, "127.0.0.1", 0)
        val connector = KtorWebSocketConnector()
        try {
            val endpoint = listener.localEndpoint!!
            assertTrue(endpoint.uri.startsWith("ws://127.0.0.1:") && endpoint.uri.endsWith("/.well-known/coap"), endpoint.uri)
            assertEquals(setOf(CoapScheme.COAP_WS), connector.schemes, "CIO does not authenticate wss servers")
            coroutineScope {
                val accepted = async { listener.accept() }
                val client = assertIs<MessageConnection>(connector.connect(endpoint))
                val server = assertIs<MessageConnection>(accepted.await())
                assertTrue((server.remote as Endpoint.WebSocket).uri.startsWith("ws://127.0.0.1:"), "${server.remote}")
                assertEquals(endpoint, client.remote)

                client.send(ByteArray(3000) { it.toByte() })
                assertContentEquals(ByteArray(3000) { it.toByte() }, server.receive())
                server.send(byteArrayOf(9))
                assertContentEquals(byteArrayOf(9), client.receive())

                client.close()
                assertNull(server.receive(), "the end of the WebSocket ends the connection")
            }
        } finally {
            listener.close()
            listener.close()
            connector.close()
        }
        assertNull(listener.accept())
        assertTrue(listener.toString().startsWith("KtorWebSocketListener("))
    }

    /** RFC 8323 §4.1: a server that does not select the subprotocol coap is not a CoAP server. */
    @Test
    fun rfc8323Section4_1ServersMustSelectTheSubprotocol() = real {
        val plain = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing { webSocket("/.well-known/coap") { incoming.receiveCatching() } }
        }
        plain.startSuspend(wait = false)
        val connector = KtorWebSocketConnector()
        try {
            val port = plain.engine.resolvedConnectors().first().port
            val error = assertFailsWith<IllegalStateException> { connector.connect(Endpoint.WebSocket.of("127.0.0.1", port)) }
            assertTrue("subprotocol coap" in error.message!!)
        } finally {
            connector.close()
            plain.stopSuspend(0, 1000)
        }
    }

    /** The CoAP endpoint answers only clients that ask for the subprotocol coap. */
    @Test
    fun rfc8323Section4_1ClientsWithoutTheSubprotocolAreRefused() = real {
        val listener = KtorWebSocketListener.start(CIO, "127.0.0.1", 0)
        val client = HttpClient { install(WebSockets) }
        try {
            assertFailsWith<Exception> { client.webSocketSession(listener.localEndpoint!!.uri) }
            assertFailsWith<IllegalArgumentException> { KtorWebSocketConnector(HttpClient()) }
            assertFailsWith<IllegalArgumentException> { KtorWebSocketConnector(client).connect(Endpoint.Tcp("127.0.0.1", 1)) }
            assertFailsWith<IllegalArgumentException> { KtorWebSocketListener(CoapScheme.COAP_TCP) }
            assertFailsWith<IllegalArgumentException> { KtorWebSocketListener.start(CIO, port = 70000) }
            assertFailsWith<IllegalArgumentException> { KtorWebSocketListener.start(CIO, maxFrameSize = 0) }
            assertEquals("KtorWebSocketConnector", KtorWebSocketConnector(client).toString())
        } finally {
            client.close()
            listener.close()
        }
    }

    /** An application mounts the endpoint in its own ktor server; a closed listener turns sessions away. */
    @Test
    fun theEndpointMountsInAnExistingApplication() = real {
        val listener = KtorWebSocketListener()
        val app = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing { coapOverWebSockets(listener) }
        }
        app.startSuspend(wait = false)
        val connector = KtorWebSocketConnector()
        try {
            val port = app.engine.resolvedConnectors().first().port
            assertNull(listener.localEndpoint)
            coroutineScope {
                val accepted = async { listener.accept() }
                val client = connector.connect(Endpoint.WebSocket.of("127.0.0.1", port)) as MessageConnection
                val server = accepted.await()!!
                assertTrue((server.remote as Endpoint.WebSocket).uri.startsWith("ws://"), "the listener's scheme names the peer")
                client.close()
            }
            listener.close()
            val late = connector.connect(Endpoint.WebSocket.of("127.0.0.1", port)) as MessageConnection
            assertNull(late.receive(), "a closed listener closes new sessions")
        } finally {
            connector.close()
            app.stopSuspend(0, 1000)
        }
    }

    /**
     * RFC 8323 §9.2: a `coaps+ws` listener says its connections run TLS, so a session that reached it on a plain
     * connector (CIO's server has no other) is closed with 1008 and never accepted.
     */
    @Test
    fun rfc8323Section9_2ACoapsWsListenerRefusesASessionThatDidNotArriveOverTls() = real {
        val listener = KtorWebSocketListener(CoapScheme.COAPS_WS)
        val app = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing { coapOverWebSockets(listener) }
        }
        app.startSuspend(wait = false)
        val client = HttpClient { install(WebSockets) }
        try {
            val port = app.engine.resolvedConnectors().first().port
            val session = client.webSocketSession(Endpoint.WebSocket.of("127.0.0.1", port).uri) {
                header(HttpHeaders.SecWebSocketProtocol, CoapScheme.WEBSOCKET_SUBPROTOCOL)
            }
            val reason = session.closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, "$reason")
            assertEquals("coaps+ws needs TLS", reason?.message)
            assertNull(withTimeoutOrNull(300.milliseconds) { listener.accept() }, "nothing was accepted")
        } finally {
            client.close()
            listener.close()
            app.stopSuspend(0, 1000)
        }
    }
}
