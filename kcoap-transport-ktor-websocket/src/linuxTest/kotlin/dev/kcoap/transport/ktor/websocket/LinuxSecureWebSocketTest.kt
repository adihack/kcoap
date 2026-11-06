package dev.kcoap.transport.ktor.websocket

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.EndpointResolver
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Why `coaps+ws` does not run on Linux native (docs/platforms.md): the default client's CIO engine takes its TLS
 * from ktor-network-tls, whose handshake there is a stub that throws, so `KtorWebSocketConnector()` does not list
 * `coaps+ws`, and a CoAP client on it refuses such a URI before resolving it.
 */
class LinuxSecureWebSocketTest {
    private fun real(block: suspend () -> Unit) = runBlocking {
        withContext(Dispatchers.Default) { withTimeout(20.seconds) { block() } }
    }

    /** When an update makes this test fail, ktor brought TLS to Linux native. */
    @Test
    fun upstreamCioHasNoTlsOnLinuxNativeSoWssFails() = real {
        // Any listening socket will do: the client fails before it would send its ClientHello.
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {}
        server.startSuspend(wait = false)
        // The default client, passed as an application's own would be: the default connector would refuse wss.
        val client = defaultClient()
        val connector = KtorWebSocketConnector(client)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val error = assertFails { connector.connect(Endpoint.WebSocket.of("127.0.0.1", port, usesTls = true)) }
            val messages = generateSequence(error) { it.cause }.map { it.message.orEmpty() }.toList()
            assertTrue(messages.any { "TLS sessions are not supported on Native platform" in it }, "$messages")
        } finally {
            connector.close()
            client.close()
            server.stopSuspend(0, 1000)
        }
    }

    /** RFC 8323 §9.2: without TLS a CoAP client on the default connector refuses `coaps+ws` before resolving it. */
    @Test
    fun rfc8323Section9_2ACoapClientOnTheDefaultConnectorRefusesCoapsWs() = real {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        var lookups = 0
        val resolver = EndpointResolver { uri ->
            lookups++
            EndpointResolver.Default.resolve(uri)
        }
        val connector = KtorWebSocketConnector()
        val client = CoapClient(listOf(connector), scope, CoapClientConfig { this.resolver = resolver })
        try {
            assertEquals(setOf(CoapScheme.COAP_WS), connector.schemes)
            val error = assertFailsWith<CoapException> { client.get("coaps+ws://127.0.0.1:8443/x") }
            assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, error.reason)
            assertTrue("coaps+ws needs TLS (RFC 8323 §9)" in error.message.orEmpty(), error.message)
            assertEquals(0, lookups, "refused before the host is resolved")
            assertFailsWith<IllegalArgumentException> { connector.connect(Endpoint.WebSocket.of("127.0.0.1", 8443, usesTls = true)) }
        } finally {
            client.close()
            scope.cancel()
        }
    }
}
