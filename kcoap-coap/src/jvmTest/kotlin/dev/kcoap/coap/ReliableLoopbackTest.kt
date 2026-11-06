package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.transport.ktor.KtorTcpConnector
import dev.kcoap.transport.ktor.KtorTcpListener
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import io.ktor.server.cio.CIO
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** CoapClient and CoapServer over real TCP and WebSocket sockets on the loopback interface (RFC 8323). */
class ReliableLoopbackTest {
    private class Clock : ObservableResource("time") {
        var ticks = 0
        override suspend fun handleGet(context: RequestContext) = CoapResponse.content("tick $ticks")
    }

    private fun loopback(block: suspend (server: CoapServer, client: CoapClient, tcp: String, ws: String) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val events: MutableList<EndpointEvent> = Collections.synchronizedList(mutableListOf())
        val tcp = KtorTcpListener.bind("127.0.0.1")
        val ws = KtorWebSocketListener.start(CIO, "127.0.0.1", 0)
        var stored = ByteArray(0)
        val clock = Clock()
        val server = CoapServer(listOf(tcp, ws), scope) {
            resource("hello") { get { CoapResponse.content("Hello over ${peer::class.simpleName}") } }
            resource("store") {
                get { CoapResponse.content(stored) }
                put {
                    stored = payload
                    CoapResponse.changed()
                }
            }
            add(clock)
        }
        scope.launch {
            while (isActive) {
                delay(100.milliseconds)
                clock.ticks++
                clock.notifyObservers()
            }
        }
        val client = CoapClient(
            listOf(KtorTcpConnector(), KtorWebSocketConnector()), scope,
            endpointConfig = CoapEndpointConfig {
                eventListener = EndpointEventListener { events += it }
                reliable = ReliableTransportConfig(maxMessageSize = 4096)
            },
        )
        try {
            withTimeout(30.seconds) {
                block(server, client, "coap+tcp://127.0.0.1:${tcp.localEndpoint.port}", ws.localEndpoint!!.uri.replace("ws://", "coap+ws://").removeSuffix("/.well-known/coap"))
            }
            assertTrue(events.none { it is EndpointEvent.AbortSent || it is EndpointEvent.AbortReceived }, "$events")
        } finally {
            client.close()
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun getPutAndBertOverBothTransports() = loopback { _, client, tcp, ws ->
        for (base in listOf(tcp, ws)) {
            val greeting = client.get("$base/hello").payloadAsText()
            assertEquals(if (base == tcp) "Hello over Tcp" else "Hello over WebSocket", greeting)
            val body = ByteArray(20_000) { (it % 251).toByte() }
            assertEquals(CoapCode.CHANGED, client.put("$base/store", body).code, "a BERT upload into the server's 8704 bytes")
            assertContentEquals(body, client.get("$base/store").payload, "BERT blocks of the client's 4096 bytes")
        }
    }

    @Test
    fun observeAndPingOverBothTransports() = loopback { _, client, tcp, ws ->
        for (base in listOf(tcp, ws)) {
            val ticks = client.observe("$base/time").take(3).toList().map { it.payloadAsText() }
            assertEquals(3, ticks.size)
            assertTrue(ticks.all { it.startsWith("tick ") })
            assertTrue(client.ping(base))
        }
    }
}
