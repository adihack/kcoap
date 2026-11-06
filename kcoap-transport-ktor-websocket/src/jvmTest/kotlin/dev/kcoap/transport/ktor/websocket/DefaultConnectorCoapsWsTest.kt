package dev.kcoap.transport.ktor.websocket

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.EndpointResolver
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Why `KtorWebSocketConnector()` serves no `coaps+ws` on the JVM and Android: its CIO engine runs TLS on
 * ktor-network-tls, whose name check accepts a certificate without DNS names for any host name (pinned by
 * `upstreamKtorCioAcceptsACertificateWithoutDnsNamesForAnyHost` in kcoap-transport-ktor). A CoAP client on it
 * therefore refuses a `coaps+ws` URI before resolving it (RFC 8323 §9.2), and the connector refuses a `wss`
 * endpoint itself, so not even a TCP connection reaches the server.
 */
class DefaultConnectorCoapsWsTest {
    private fun real(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(20.seconds) { block() } }

    /** Whether a client connected to [server] since it was opened: accept() would return its connection. */
    private fun anyoneConnected(server: ServerSocket): Boolean {
        server.soTimeout = 200
        return try {
            server.accept().close()
            true
        } catch (e: SocketTimeoutException) {
            false
        }
    }

    @Test
    fun rfc8323Section9_2ACoapClientOnTheDefaultConnectorRefusesCoapsWsBeforeResolvingIt() = real {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            var lookups = 0
            val resolver = EndpointResolver { uri ->
                lookups++
                EndpointResolver.Default.resolve(uri)
            }
            val connector = KtorWebSocketConnector()
            val client = CoapClient(listOf(connector), scope, CoapClientConfig { this.resolver = resolver })
            try {
                assertEquals(setOf(CoapScheme.COAP_WS), connector.schemes)
                val error = assertFailsWith<CoapException> { client.get("coaps+ws://localhost:${server.localPort}/x") }
                assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, error.reason)
                assertTrue("coaps+ws needs TLS (RFC 8323 §9)" in error.message.orEmpty(), error.message)
                assertEquals(0, lookups, "refused before the host is resolved")
                assertFalse(anyoneConnected(server), "nothing reached the server")
            } finally {
                client.close()
                scope.cancel()
            }
        }
    }

    /** The connector refuses a `wss` endpoint before it opens a socket, as its schemes leave `coaps+ws` out. */
    @Test
    fun theDefaultConnectorRefusesAWssEndpointBeforeConnecting() = real {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val connector = KtorWebSocketConnector()
            try {
                val error = assertFailsWith<IllegalArgumentException> {
                    connector.connect(Endpoint.WebSocket.of("localhost", server.localPort, usesTls = true))
                }
                assertTrue("does not authenticate wss servers (RFC 8323 §9.2)" in error.message.orEmpty(), error.message)
                assertFalse(anyoneConnected(server), "nothing reached the server")
            } finally {
                connector.close()
            }
        }
    }
}
