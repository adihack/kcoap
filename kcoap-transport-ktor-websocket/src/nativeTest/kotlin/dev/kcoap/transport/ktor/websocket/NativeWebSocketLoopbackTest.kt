package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.MessageConnection
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The connector's default client against the server adapter over real loopback sockets, natively: on
 * Apple the client runs on the Darwin engine (URLSession's WebSocket task), on Linux on CIO. The server
 * runs on CIO everywhere.
 */
class NativeWebSocketLoopbackTest {
    // On a worker thread: an engine on URLSession must not wait for a main queue that runBlocking holds.
    private fun real(block: suspend () -> Unit) = runBlocking {
        withContext(Dispatchers.Default) { withTimeout(20.seconds) { block() } }
    }

    /** RFC 8323 §4.1: the default client asks for coap, the listener selects it, and messages travel. */
    @Test
    fun rfc8323Section4_1TheDefaultClientNegotiatesCoapWithTheListener() = real {
        val listener = KtorWebSocketListener.start(CIO, "127.0.0.1", 0)
        val connector = KtorWebSocketConnector()
        try {
            val endpoint = listener.localEndpoint!!
            coroutineScope {
                val accepted = async { listener.accept() }
                val client = assertIs<MessageConnection>(connector.connect(endpoint))
                val server = assertIs<MessageConnection>(accepted.await())
                assertEquals(endpoint, client.remote)

                client.send(ByteArray(3000) { it.toByte() })
                assertContentEquals(ByteArray(3000) { it.toByte() }, server.receive())
                server.send(byteArrayOf(9))
                assertContentEquals(byteArrayOf(9), client.receive())

                client.close()
                assertNull(server.receive(), "the end of the WebSocket ends the connection")
            }
        } finally {
            connector.close()
            listener.close()
        }
    }

    /** RFC 8323 §4.1: a server that selects no subprotocol is refused, whichever side fails the handshake. */
    @Test
    fun rfc8323Section4_1ServersMustSelectTheSubprotocol() = real {
        val plain = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing { webSocket("/.well-known/coap") { incoming.receiveCatching() } }
        }
        plain.startSuspend(wait = false)
        val connector = KtorWebSocketConnector()
        try {
            val port = plain.engine.resolvedConnectors().first().port
            assertFails { connector.connect(Endpoint.WebSocket.of("127.0.0.1", port)) }
        } finally {
            connector.close()
            plain.stopSuspend(0, 1000)
        }
    }
}
