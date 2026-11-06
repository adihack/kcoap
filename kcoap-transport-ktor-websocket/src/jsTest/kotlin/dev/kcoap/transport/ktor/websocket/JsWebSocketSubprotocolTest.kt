package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.MessageConnection
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.close
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The connector on ktor's JS engine against a real WebSocket server. The JS engine has no headers to
 * set: it passes the requested subprotocols to the WebSocket constructor's `protocols` argument and
 * reports the one the server selected from `WebSocket.protocol` as the response's Sec-WebSocket-Protocol,
 * which the connector checks (RFC 8323 §4.1).
 *
 * On Node.js the server is an npm `ws` server started here ([NodeWebSocketServer]); in a browser page
 * it is the JVM listener the page names ([BrowserPage]), which `BrowserAgainstJvmListenerTest` serves.
 */
class JsWebSocketSubprotocolTest {
    private val coap = CoapScheme.WEBSOCKET_SUBPROTOCOL

    /** Real I/O: off the test scheduler, whose virtual time would skip ahead while a socket waits. */
    private fun real(block: suspend () -> Unit) = runTest(timeout = 30.seconds) {
        withContext(Dispatchers.Default) { block() }
    }

    /** RFC 8323 §4.1: the client asks for the subprotocol coap and goes on only when the server selected it. */
    @Test
    fun rfc8323Section4_1TheConnectorRequestsCoapAndReadsItBackFromTheNegotiatedProtocol() = real {
        val page = BrowserPage.current()
        if (page != null) {
            assertNegotiatesCoap(page.coap)
            return@real
        }
        val server = NodeWebSocketServer.start { offered -> coap.takeIf { it in offered } }
        try {
            assertNegotiatesCoap(server.endpoint)
            assertEquals(listOf(setOf(coap), setOf(coap)), server.offered, "each handshake requests coap alone")
            assertEquals(listOf(coap, coap), server.selected)
        } finally {
            server.close()
        }
    }

    /** RFC 8323 §4.1: a WebSocket server that selects no subprotocol is not a CoAP server. */
    @Test
    fun rfc8323Section4_1AServerThatSelectsNoSubprotocolIsRefused() = real {
        val page = BrowserPage.current()
        if (page != null) {
            assertRefused(page.plain)
            return@real
        }
        val server = NodeWebSocketServer.start { null }
        try {
            assertRefused(server.endpoint)
            assertEquals(listOf(setOf(coap)), server.offered)
            assertEquals(listOf(""), server.selected, "the server completed its side with no subprotocol")
        } finally {
            server.close()
        }
    }

    private suspend fun assertNegotiatesCoap(endpoint: Endpoint.WebSocket) {
        // What the connector relies on: the subprotocol the server selected, read back from the handshake.
        val client = HttpClient { install(WebSockets) }
        try {
            val session = client.webSocketSession(endpoint.uri) { header(HttpHeaders.SecWebSocketProtocol, coap) }
            assertEquals(coap, session.call.response.headers[HttpHeaders.SecWebSocketProtocol])
            session.close()
        } finally {
            client.close()
        }
        // The connector itself on its default client, and a message there and back over the WebSocket.
        val connector = KtorWebSocketConnector()
        try {
            val connection = assertIs<MessageConnection>(connector.connect(endpoint))
            assertEquals(endpoint, connection.remote)
            val message = ByteArray(1500) { it.toByte() }
            connection.send(message)
            assertContentEquals(message, connection.receive(), "the server echoes binary frames")
            connection.close()
        } finally {
            connector.close()
        }
    }

    private suspend fun assertRefused(endpoint: Endpoint.WebSocket) {
        val connector = KtorWebSocketConnector()
        try {
            assertFails { connector.connect(endpoint) }
        } finally {
            connector.close()
        }
    }
}
