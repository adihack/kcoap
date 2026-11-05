package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LoggerFactory
import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.testkit.Stress
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The endpoint under real concurrency: [Stress] runs every test on Dispatchers.Default, a thread pool on
 * the JVM and Kotlin/Native, the event loop on the web. Each iteration builds its own endpoints in the
 * iteration's background scope, on the real clock, and checks invariants that a race would break: every
 * call completes or fails the documented way, nothing is left registered, and the bounds hold.
 */
@OptIn(ExperimentalAtomicApi::class)
class ConcurrencyStressTest {
    /** Collects events from any thread. */
    private class EventLog : EndpointEventListener {
        private val events = AtomicReference<List<EndpointEvent>>(emptyList())

        override fun onEvent(event: EndpointEvent) {
            while (true) {
                val current = events.load()
                if (events.compareAndSet(current, current + event)) return
            }
        }

        fun all(): List<EndpointEvent> = events.load()
    }

    private class Gauge : ObservableResource("gauge") {
        private val value = AtomicInt(0)

        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("${value.load()}")

        fun change() {
            value.addAndFetch(1)
            notifyObservers()
        }
    }

    /** Logs only what went wrong; the endpoints here start and stop hundreds of times. */
    private val quietLogs = LoggerFactory(LogConfig.build { level = LogLevel.WARN })
    private val quiet = CoapEndpointConfig(loggerFactory = quietLogs)

    private fun uriOf(server: CoapServer, path: String): String =
        "coap://127.0.0.1:${(server.endpoint.transport as InMemoryTransport).localEndpoint.port}$path"

    private fun assertClosedOr(result: Result<Any?>, success: (Any?) -> Unit) {
        result.fold(success) { error ->
            val coap = assertIs<CoapException>(error, "only the endpoint's own failure: $error")
            assertEquals(CoapException.Reason.CLOSED, coap.reason, coap.message)
        }
    }

    @Test
    fun closeRacingRequestsNeverHangsAndLeavesNothingRegistered() = runTest(timeout = 2.minutes) {
        Stress.run(iterations = 40) {
            val network = InMemoryNetwork()
            val server = CoapServer(network.attach(), background, endpointConfig = quiet) { resource("r") { get { CoapResponse.content("ok") } } }
            val endpoint = CoapEndpoint(network.attach(), background, quiet)
            val client = CoapClient(endpoint)
            val uri = uriOf(server, "/r")
            val requests = List(6) { suspend { client.get(uri).payloadAsText() } }

            val results = Stress.race<Any>(*requests.toTypedArray(), { endpoint.close() })
            results.dropLast(1).forEach { result -> assertClosedOr(result) { assertEquals("ok", it) } }
            assertTrue(endpoint.isClosed)
            assertEquals(0, endpoint.exchangeCount(), "no exchange registered after the drain")
            assertClosedOr(runCatching { client.get(uri) }) { fail("a request after close() succeeded") }
        }
    }

    @Test
    fun closeRacingObserveCollectorsEndsEveryOneOfThem() = runTest(timeout = 2.minutes) {
        Stress.run(iterations = 30) {
            val network = InMemoryNetwork()
            val gauge = Gauge()
            val server = CoapServer(network.attach(), background, endpointConfig = quiet) { add(gauge) }
            val client = CoapClient(network.attach(), background, endpointConfig = quiet)
            val uri = uriOf(server, "/gauge")
            val collectors = List(4) { suspend { client.observe(uri).toList() } }

            val results = Stress.race<Any>(*collectors.toTypedArray(), {
                delay(1)
                client.endpoint.close()
            })
            results.dropLast(1).forEach { result -> assertClosedOr(result) { } }
            assertEquals(0, client.observationCount())
        }
    }

    @Test
    fun observationRegistryStaysWithinItsBoundsUnderConcurrentChurn() = runTest(timeout = 2.minutes) {
        Stress.run(iterations = 15) {
            val network = InMemoryNetwork()
            val gauge = Gauge()
            val bound = 5
            val server = CoapServer(network.attach(), background, CoapServerConfig(maxObservers = bound, maxObserversPerResource = bound), quiet) {
                add(gauge)
            }
            val uri = uriOf(server, "/gauge")
            val clients = List(8) { CoapClient(network.attach(), background, endpointConfig = quiet) }
            val highest = AtomicInt(0)
            val changer = launch {
                while (isActive) {
                    gauge.change()
                    val count = gauge.observerCount
                    if (count > highest.load()) highest.store(count)
                    delay(1)
                }
            }

            val received = clients.map { client -> async { client.observe(uri).take(3).toList() } }.awaitAll()
            changer.cancel()
            // Registered clients got three states; refused ones a single plain response (RFC 7641 §4.1).
            assertTrue(received.all { it.size == 3 || (it.size == 1 && it.single().options.observe == null) }, "$received")
            assertTrue(highest.load() <= bound, "at most $bound observers, saw ${highest.load()}")
            withTimeoutOrNull(10.seconds) { while (gauge.observerCount > 0) delay(5) }
                ?: fail("${gauge.observerCount} observers still registered after their clients stopped")
        }
    }

    @Test
    fun theConnectionPoolOpensOneConnectionPerPeerForConcurrentRequests() = runTest(timeout = 2.minutes) {
        Stress.run(iterations = 20) {
            val connections = InMemoryConnections(chunkSize = 7)
            val ports = listOf(5683, 5684, 5685)
            for (port in ports) {
                CoapServer(listOf(connections.listen(port)), background, endpointConfig = quiet) { resource("r") { get { CoapResponse.content("$port") } } }
            }
            val events = EventLog()
            val config = CoapEndpointConfig(eventListener = events, loggerFactory = quietLogs, reliable = ReliableTransportConfig(idleTimeout = null))
            val client = CoapClient(listOf(connections.connector), background, endpointConfig = config)

            val answers = List(24) { i -> async { ports[i % 3] to client.get("coap+tcp://127.0.0.1:${ports[i % 3]}/r").payloadAsText() } }.awaitAll()
            answers.forEach { (port, answer) -> assertEquals("$port", answer) }
            val opened = events.all().filterIsInstance<EndpointEvent.ConnectionOpened>().filter { it.isInitiator }
            assertEquals(ports.size, opened.size, "one connection per peer: $opened")
            client.close()
        }
    }

    @Test
    fun reliableConnectionsServeRequestsThatRaceTheirIdleRelease() = runTest(timeout = 2.minutes) {
        Stress.run(iterations = 20) {
            val connections = InMemoryConnections(chunkSize = 7)
            CoapServer(listOf(connections.listen(5683)), background, endpointConfig = quiet) { resource("r") { get { CoapResponse.content("ok") } } }
            val config = CoapEndpointConfig(loggerFactory = quietLogs, reliable = ReliableTransportConfig(idleTimeout = 2.milliseconds, releaseTimeout = 50.milliseconds))
            val client = CoapClient(listOf(connections.connector), background, endpointConfig = config)

            repeat(4) {
                val answers = List(6) { async { client.get("coap+tcp://127.0.0.1:5683/r").payloadAsText() } }.awaitAll()
                assertEquals(List(6) { "ok" }, answers)
                delay((it % 3).toLong())
            }
            client.close()
        }
    }

    @Test
    fun closeRacingConnectionSetupFailsTheWaitingRequestsAndClosesEveryConnection() = runTest(timeout = 2.minutes) {
        Stress.run(iterations = 30) {
            val connections = InMemoryConnections()
            CoapServer(listOf(connections.listen(5683)), background, endpointConfig = quiet) { resource("r") { get { CoapResponse.content("ok") } } }
            val client = CoapClient(listOf(connections.connector), background, endpointConfig = quiet)
            val requests = List(4) { suspend { client.get("coap+tcp://127.0.0.1:5683/r").payloadAsText() } }

            val results = Stress.race<Any>(*requests.toTypedArray(), { client.endpoint.close() })
            results.dropLast(1).forEach { result ->
                result.onFailure { error ->
                    val reason = assertIs<CoapException>(error, "only the endpoint's own failure: $error").reason
                    assertTrue(reason == CoapException.Reason.CLOSED || reason == CoapException.Reason.CONNECTION_CLOSED, "$reason")
                }
            }
            assertEquals(0, client.endpoint.exchangeCount())
            withTimeoutOrNull(5.seconds) {
                while (!connections.connections().filter { !it.isAccepted }.all { it.isClosed }) delay(5)
            } ?: fail("a connection stayed open after close() raced its setup")
        }
    }
}
