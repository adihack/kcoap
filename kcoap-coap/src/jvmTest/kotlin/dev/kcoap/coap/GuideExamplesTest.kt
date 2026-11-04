package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.server.ResourceAttributes
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorUdpTransport
import java.net.DatagramSocket
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/** The examples of `docs/guide-client-server.md`, compiled and run; ports are free ones instead of 5683. */
class GuideExamplesTest {
    private val output = mutableListOf<String>()
    private fun println(line: Any?) {
        output += line.toString()
    }

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    // ---------------------------------------------------------------- as in the guide

    suspend fun startServer(scope: CoroutineScope, port: Int): CoapServer {
        val clock = object : ObservableResource("time") {
            override val attributes = ResourceAttributes(resourceTypes = listOf("ticks"), title = "Clock")
            override suspend fun handleGet(context: RequestContext) =
                CoapResponse.content(Clock.System.now().toString()).withOptions { maxAge(1) }
        }
        var stored = "nothing yet"
        val server = CoapServer(KtorUdpTransport.bind("127.0.0.1", port), scope) {
            resource("hello") {
                attributes = ResourceAttributes(title = "Greeting")
                get { CoapResponse.content("Hello, $peer") }
            }
            resource("store") {
                get { CoapResponse.content(stored) }
                put {
                    stored = payloadAsText()
                    CoapResponse.changed()
                }
            }
            resource("slow") {
                get {
                    delay(2.seconds) // an Empty ACK goes out after 1 s; the response follows separately
                    CoapResponse.content("finally")
                }
            }
            add(clock)
        }
        scope.launch {
            while (isActive) {
                delay(1.seconds)
                clock.notifyObservers()
            }
        }
        return server
    }

    suspend fun useServer(scope: CoroutineScope, port: Int) {
        val client = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope)
        val server = "coap://127.0.0.1:$port"

        println(client.get("$server/hello").payloadAsText())
        client.put("$server/store", "on".encodeToByteArray(), ContentFormat.TEXT_PLAIN)
        println(client.get("$server/store") { accept(ContentFormat.TEXT_PLAIN) }.payloadAsText())
        println(client.get("$server/slow").payloadAsText())

        val links = client.discover(server, "rt=ticks")
        println(links.map { it.target })

        client.observe("$server/time").take(3).collect { println("time: ${it.payloadAsText()}") }
        client.close()
    }

    suspend fun peer(scope: CoroutineScope, port: Int): Pair<CoapClient, CoapServer> {
        val endpoint = CoapEndpoint(KtorUdpTransport.bind("127.0.0.1", port), scope)
        val server = CoapServer(endpoint) { resource("status") { get { CoapResponse.content("ok") } } }
        return CoapClient(endpoint) to server
    }

    val config = CoapEndpointConfig(
        parameters = TransmissionParameters(ackTimeout = 3.seconds, maxRetransmit = 6),
        separateResponseDelay = 500.milliseconds,
        interceptors = listOf(object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage) = message.also { println("-> $to $it") }
            override fun onReceive(from: Endpoint, message: CoapMessage) = message.also { println("<- $from $it") }
        }),
        eventListener = { event -> if (event is EndpointEvent.Retransmitted) println(event) },
    )

    @Test
    fun greets() = runTest {
        val network = InMemoryNetwork(backgroundScope, Random(1))
        network.conditions = LinkConditions(lossRate = 0.3, duplicationRate = 0.1)
        val config = CoapEndpointConfig(timeSource = testScheduler.timeSource)
        val serverTransport = network.attach()
        CoapServer(serverTransport, backgroundScope, endpointConfig = config) {
            resource("hello") { get { CoapResponse.content("Hello") } }
        }
        val client = CoapClient(network.attach(), backgroundScope, endpointConfig = config)
        val address = serverTransport.localEndpoint
        assertEquals("Hello", client.get("coap://${address.host}:${address.port}/hello").payloadAsText())
    }

    // ---------------------------------------------------------------- running them

    @Test
    fun theServerAndClientExamplesWork() = runBlocking(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            withTimeout(30.seconds) {
                val port = freePort()
                val server = startServer(scope, port)
                useServer(scope, port)
                server.close()
            }
            assertEquals("Hello, Udp(host=127.0.0.1", output[0].substringBefore(", port="))
            assertEquals(listOf("on", "finally", "[/time]"), output.subList(1, 4))
            assertEquals(3, output.count { it.startsWith("time: ") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun thePeerAndConfigurationExamplesWork() = runBlocking(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val port = freePort()
            val (client, server) = peer(scope, port)
            val traced = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope, endpointConfig = config)
            assertEquals("ok", traced.get("coap://127.0.0.1:$port/status").payloadAsText())
            assertEquals("ok", client.get("coap://127.0.0.1:$port/status").payloadAsText())
            assertEquals(2, output.count { it.startsWith("->") || it.startsWith("<-") })
            traced.close()
            server.close()
            client.endpoint.close()
        } finally {
            scope.cancel()
        }
    }
}
