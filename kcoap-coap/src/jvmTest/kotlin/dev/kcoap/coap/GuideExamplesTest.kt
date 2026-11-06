package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.client.RetryAfter
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.server.ResourceAttributes
import dev.kcoap.coap.server.ResourceBuilder
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorTcpConnector
import dev.kcoap.transport.ktor.KtorTcpListener
import dev.kcoap.transport.ktor.KtorUdpTransport
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import io.ktor.server.cio.CIO
import java.net.DatagramSocket
import java.net.ServerSocket
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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

    val config = CoapEndpointConfig {
        parameters = TransmissionParameters(ackTimeout = 3.seconds, maxRetransmit = 6)
        separateResponseDelay = 500.milliseconds
        interceptors = listOf(object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage) = message.also { println("-> $to $it") }
            override fun onReceive(from: Endpoint, message: CoapMessage) = message.also { println("<- $from $it") }
        })
        eventListener = EndpointEventListener { event -> if (event is EndpointEvent.Retransmitted) println(event) }
    }

    suspend fun reliableServer(scope: CoroutineScope, tcpPort: Int, webSocketPort: Int): CoapServer {
        val listeners = listOf(
            KtorTcpListener.bind("127.0.0.1", tcpPort), // coap+tcp
            KtorWebSocketListener.start(CIO, "127.0.0.1", webSocketPort), // coap+ws, at /.well-known/coap
        )
        return CoapServer(listeners, scope) {
            resource("hello") { get { CoapResponse.content("Hello over ${peer::class.simpleName}") } }
        }
    }

    suspend fun reliableClient(scope: CoroutineScope, tcpPort: Int, webSocketPort: Int) {
        val client = CoapClient(listOf(KtorTcpConnector(), KtorWebSocketConnector()), scope)
        println(client.get("coap+tcp://127.0.0.1:$tcpPort/hello").payloadAsText())
        println(client.get("coap+ws://127.0.0.1:$webSocketPort/hello").payloadAsText())
        println(client.ping("coap+tcp://127.0.0.1:$tcpPort"))
        client.close()
    }

    val reliableConfig = CoapEndpointConfig {
        reliable = ReliableTransportConfig(
            maxMessageSize = 64 * 1024 + 512, // announced in the CSM; BERT blocks of up to 64 KiB
            idleTimeout = 2.minutes, // release connections this side opened after 2 idle minutes
            keepAliveInterval = 30.seconds, // Ping a quiet connection; abort it if nothing answers
        )
    }

    suspend fun qBlockServer(scope: CoroutineScope, port: Int): CoapServer {
        val big = ByteArray(20_000) { (it % 251).toByte() }
        var stored = ByteArray(0)
        val config = CoapServerConfig(qBlock = QBlockServerConfig(isEnabled = true)) // off by default
        return CoapServer(KtorUdpTransport.bind("127.0.0.1", port), scope, config) {
            resource("big") { get { CoapResponse.content(big, ContentFormat.OCTET_STREAM) } }
            resource("upload") {
                put {
                    stored = payload
                    CoapResponse.changed()
                }
                get { CoapResponse.content(stored, ContentFormat.OCTET_STREAM) }
            }
        }
    }

    suspend fun qBlockClient(scope: CoroutineScope, port: Int) {
        val config = CoapClientConfig { qBlock = QBlockClientConfig(mode = QBlockMode.PROBE) } // OFF by default
        val client = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope, config)
        val server = "coap://127.0.0.1:$port"
        // Non-confirmable requests get Non-confirmable bursts; Confirmable ones go block by block.
        val body = client.get("$server/big") { nonConfirmable() }.payload
        client.put("$server/upload", body) { nonConfirmable() }
        println("${body.size} bytes down and up")
        client.close()
    }

    @Test
    fun overConnections() = runTest {
        val connections = InMemoryConnections()
        val config = CoapEndpointConfig { timeSource = testScheduler.timeSource }
        CoapServer(listOf(connections.listen(5683)), backgroundScope, endpointConfig = config) {
            resource("hello") { get { CoapResponse.content("Hello") } }
        }
        val client = CoapClient(listOf(connections.connector), backgroundScope, endpointConfig = config)
        assertEquals("Hello", client.get("coap+tcp://127.0.0.1:5683/hello").payloadAsText())
    }

    @Test
    fun greets() = runTest {
        val network = InMemoryNetwork(backgroundScope, Random(1))
        network.conditions = LinkConditions(lossRate = 0.3, duplicationRate = 0.1)
        val config = CoapEndpointConfig { timeSource = testScheduler.timeSource }
        val serverTransport = network.attach()
        CoapServer(serverTransport, backgroundScope, endpointConfig = config) {
            resource("hello") { get { CoapResponse.content("Hello") } }
        }
        val client = CoapClient(network.attach(), backgroundScope, endpointConfig = config)
        val address = serverTransport.localEndpoint
        assertEquals("Hello", client.get("coap://${address.host}:${address.port}/hello").payloadAsText())
    }

    val limits = CoapEndpointConfig {
        maxDeduplicationEntries = 5_000
        maxDeduplicationEntriesPerPeer = 500
        maxReservedMessageIds = 20_000
        maxExchanges = 1_024
        eventListener = EndpointEventListener { event ->
            if (event is EndpointEvent.LimitReached) println("refused for ${event.peer}: ${event.limit}")
        }
    }

    /** The application's policy behind the guide's 4.29 example; here every second request is one too many. */
    class Limiter {
        private var count = 0

        fun tooOften(peer: Endpoint): Boolean = ++count % 2 == 0
    }

    fun ResourceBuilder.readings(limiter: Limiter) = resource("reading") {
        get {
            if (limiter.tooOften(peer)) CoapResponse.tooManyRequests(30.seconds, "one reading every 30 s")
            else CoapResponse.content("21.5")
        }
    }

    suspend fun readPolitely(client: CoapClient, uri: String): CoapResponse =
        RetryAfter(maxWait = 1.minutes).request(client, CoapRequest(CoapCode.GET, CoapUri.parse(uri)))

    // ---------------------------------------------------------------- running them

    @Test
    fun theLimitsExamplesWork() = runTest {
        val network = InMemoryNetwork(backgroundScope)
        val config = limits.toBuilder().apply { timeSource = testScheduler.timeSource }.build()
        assertEquals(500, config.maxDeduplicationEntriesPerPeer)
        val serverTransport = network.attach()
        CoapServer(serverTransport, backgroundScope, endpointConfig = config) { readings(Limiter()) }
        val client = CoapClient(network.attach(), backgroundScope, endpointConfig = config)
        val uri = "coap://${serverTransport.localEndpoint.host}:${serverTransport.localEndpoint.port}/reading"

        assertEquals("21.5", readPolitely(client, uri).payloadAsText())
        val start = testScheduler.currentTime
        // The second request gets 4.29 with Max-Age 30; the third, 30 seconds later, is served.
        assertEquals("21.5", readPolitely(client, uri).payloadAsText())
        assertTrue(testScheduler.currentTime - start >= 30_000)
    }

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
    fun theTcpAndWebSocketExamplesWork() = runBlocking(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            withTimeout(30.seconds) {
                val tcpPort = ServerSocket(0).use { it.localPort }
                val webSocketPort = ServerSocket(0).use { it.localPort }
                val server = reliableServer(scope, tcpPort, webSocketPort)
                reliableClient(scope, tcpPort, webSocketPort)
                server.close()
            }
            assertEquals(listOf("Hello over Tcp", "Hello over WebSocket", "true"), output)
            assertEquals(66048, reliableConfig.reliable.maxMessageSize)
        } finally {
            scope.cancel()
        }
    }

    /**
     * The Q-Block example over real UDP: the probe finds support, the body goes down and up in Non-confirmable
     * bursts (after the server checked the client's address with Echo, the default policy), and the defaults
     * are what the guide says: Q-Block off on both sides.
     */
    @Test
    fun theQBlockExampleWorks() = runBlocking(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            withTimeout(30.seconds) {
                val port = freePort()
                val server = qBlockServer(scope, port)
                qBlockClient(scope, port)
                val state = server.qBlock.state()
                assertTrue(state.servedBodies >= 1, "the GET went down in Q-Block2: $state")
                assertTrue(state.completedBodies >= 1, "the PUT went up in Q-Block1: $state")
                val check = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope)
                val stored = check.get("coap://127.0.0.1:$port/upload").payload
                assertEquals((0 until 20_000).map { (it % 251).toByte() }, stored.toList(), "the upload arrived whole")
                check.close()
                server.close()
            }
            assertEquals(listOf("20000 bytes down and up"), output)
            assertEquals(QBlockMode.OFF, QBlockClientConfig().mode, "the client's Q-Block is off by default")
            assertEquals(false, QBlockServerConfig().isEnabled, "the server's Q-Block is off by default")
            assertEquals(false, QBlockClientConfig().withMessageSecurity)
            assertEquals(false, QBlockServerConfig().withMessageSecurity)
            assertEquals(false, QBlockClientConfig().onReliableTransports)
            assertEquals(true, QBlockServerConfig().onReliableTransports)
            assertEquals(AmplificationPolicy.Q_BLOCK_BURSTS, CoapServerConfig().amplification)
            assertEquals(10, TransmissionParameters().maxPayloads)
            assertEquals(1.hours, QBlockClientConfig().capabilityLifetime)
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
