package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.ReliableMessageCodec
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.InboundRequest
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.coap.stack.RequestHandler
import dev.kcoap.coap.stack.Signal
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogRecord
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import dev.kcoap.testkit.InMemoryByteStream
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The server pipeline is one failure boundary (EO-1): whatever throws in it, the client gets an answer
 * and nothing escapes to the exception handler of the scope, which runTest turns into a failed test.
 * Local send failures are not peer timeouts (EO-2).
 */
class FailureBoundaryTest {
    private val anyone = object : SecurityIdentity {
        override val description: String = "anyone"
    }

    /** Passes requests through; [protect] decides what protecting the n-th response (from 1) does. */
    private inner class PassThrough(private val protect: (Int, CoapMessage) -> CoapMessage = { _, it -> it }) : ServerMessageSecurity {
        var protected = 0

        override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest = object : UnprotectedRequest {
            override val message: CoapMessage = request
            override val identity: SecurityIdentity = anyone

            override suspend fun protectResponse(response: CoapMessage): CoapMessage = protect(++protected, response)
        }
    }

    private class Gauge(var big: Boolean = false) : ObservableResource("gauge") {
        override suspend fun handleGet(context: RequestContext): CoapResponse =
            if (big) CoapResponse.content("1").withOptions { locationPath("x".repeat(200)) } else CoapResponse.content("1")

        fun change() = notifyObservers()
    }

    private fun assertInternalError(response: CoapResponse) {
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, response.code)
        assertEquals(0L, response.options.maxAge, "RFC 8613 §8.2 shape: Max-Age 0")
    }

    @Test
    fun aFailingCallbackBehindTheSecurityLayerIsAnsweredWith5_00() = runTest {
        val fixture = StackFixture(this)
        val failing = object : ServerMessageSecurity {
            override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest =
                error("context repository unavailable")
        }
        val server = fixture.server(CoapServerConfig(security = failing)) { resource("r") { get { CoapResponse.content("x") } } }
        val client = fixture.client()

        val started = fixture.now
        assertInternalError(client.get(fixture.uri(server, "/r")))
        assertTrue(fixture.now - started < 1.seconds, "answered at once, not left to time out")
    }

    @Test
    fun aFailingResponseProtectionIsAnsweredWithAnUnprotected5_00() = runTest {
        val fixture = StackFixture(this)
        val security = PassThrough { _, _ -> error("key store unavailable") }
        val server = fixture.server(CoapServerConfig(security = security)) { resource("r") { get { CoapResponse.content("x") } } }
        val client = fixture.client()

        assertInternalError(client.get(fixture.uri(server, "/r")))
    }

    @Test
    fun aChallengeThatCannotBeProtectedIsAnsweredWith5_00() = runTest {
        val fixture = StackFixture(this)
        val challenging = object : ServerMessageSecurity {
            override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest = object : UnprotectedRequest {
                override val message: CoapMessage = request
                override val identity: SecurityIdentity = anyone
                override val challenge: CoapMessage = CoapMessage.response(request, CoapCode.UNAUTHORIZED)

                override suspend fun protectResponse(response: CoapMessage): CoapMessage = error("sequence number store unavailable")
            }
        }
        val server = fixture.server(CoapServerConfig(security = challenging)) { resource("r") { get { CoapResponse.content("x") } } }
        val client = fixture.client()

        assertInternalError(client.get(fixture.uri(server, "/r")))
    }

    @Test
    fun aNotificationThatCannotBeProtectedRemovesTheObserverAndEscapesNothing() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge()
        // The registration response is protected; the first notification is not, as when the
        // sequence-number store fails on its next write.
        val security = PassThrough { count, response -> if (count == 1) response else error("sequence number store unavailable") }
        val server = fixture.server(CoapServerConfig(security = security)) { add(gauge) }
        val client = fixture.client()
        val collector = launch { client.observe(fixture.uri(server, "/gauge")).collect() }
        delay(1.seconds)
        assertEquals(1, gauge.observerCount)

        gauge.change()
        delay(1.seconds)
        assertEquals(0, gauge.observerCount)
        val removed = fixture.events.filterIsInstance<EndpointEvent.ObserverRemoved>().single()
        assertTrue("notification failed" in removed.reason, removed.reason)
        collector.cancel()
    }

    @Test
    fun aNotificationLargerThanTheDatagramRemovesTheObserverAndEscapesNothing() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge()
        val server = fixture.server(maxDatagramSize = 128) { add(gauge) }
        val client = fixture.client()
        val collector = launch { client.observe(fixture.uri(server, "/gauge")).collect() }
        delay(1.seconds)
        assertEquals(1, gauge.observerCount)

        gauge.big = true
        gauge.change()
        delay(1.seconds)
        // Block-wise answers a representation that fits no block with 5.00, which ends both sides.
        assertEquals(0, gauge.observerCount)
        assertTrue("5.00" in fixture.events.filterIsInstance<EndpointEvent.ObserverRemoved>().single().reason)
        assertTrue(collector.isCompleted, "the client's observation ended with the 5.00")
    }

    @Test
    fun rfc7641Section3_2ARegistrationWhoseResponseFitsNoMessageLeavesNoObserver() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge(big = true)
        val server = fixture.server(maxDatagramSize = 128) { add(gauge) }
        val client = fixture.client()

        // Served as 5.00 without Observe, which ends the client's observation at once.
        val responses = client.observe(fixture.uri(server, "/gauge")).toList()
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, responses.single().code)
        assertNull(responses.single().options.observe)
        assertEquals(0, gauge.observerCount, "no observer counts against the bounds for it")
        assertTrue("5.00" in fixture.events.filterIsInstance<EndpointEvent.ObserverRemoved>().single().reason)
    }

    @Test
    fun aNotificationTheMessagingLayerCannotSendRemovesTheObserverAndEscapesNothing() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge()
        // The server's own interceptor makes the notification exceed the datagram after block-wise sized it.
        val inflate = object : dev.kcoap.coap.stack.MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage? =
                if (message.type == CoapMessageType.CON && message.isResponse) message.toBuilder().apply { payload = ByteArray(2000) }.build() else message
        }
        val server = fixture.server(endpointConfig = fixture.endpointConfig(interceptors = listOf(inflate))) { add(gauge) }
        val client = fixture.client()
        val collector = launch { client.observe(fixture.uri(server, "/gauge")).collect() }
        delay(1.seconds)
        assertEquals(1, gauge.observerCount)

        gauge.change()
        delay(1.seconds)
        assertEquals(0, gauge.observerCount)
        assertTrue("notification failed" in fixture.events.filterIsInstance<EndpointEvent.ObserverRemoved>().single().reason)
        collector.cancel()
    }

    /** Refuses to send to one address, as a transport refuses an endpoint type it does not serve. */
    private class Refusing(private val inner: InMemoryTransport, private val refused: Endpoint) : DatagramTransport by inner {
        override suspend fun send(to: Endpoint, datagram: ByteArray) {
            require(to != refused) { "cannot send to $to" }
            inner.send(to, datagram)
        }
    }

    @Test
    fun aSendTheTransportRefusesFailsAtOnceAndIsNoPeerTimeout() = runTest {
        val fixture = StackFixture(this)
        val refused = Endpoint.Udp("127.0.0.1", 9)
        val client = CoapClient(CoapEndpoint(Refusing(fixture.transport(), refused), backgroundScope, fixture.endpointConfig()))

        val started = fixture.now
        val error = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/x") }
        assertEquals(CoapException.Reason.TRANSPORT_FAILED, error.reason)
        assertTrue(error.cause is IllegalArgumentException)
        assertEquals(started, fixture.now, "no MAX_TRANSMIT_WAIT before the failure")
        assertTrue(fixture.events.none { it is EndpointEvent.Retransmitted })
        assertEquals(
            CoapException.Reason.TRANSPORT_FAILED,
            assertFailsWith<CoapException> { client.ping("coap://127.0.0.1:9") }.reason,
        )
    }

    @Test
    fun aFailingProtectionOnAConnectionFailsTheRequestAndLeavesNothingRegistered() = runTest {
        val fixture = ReliableFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("x") } } }
        val failing = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest =
                throw MessageSecurityException("No context for $peer")
        }
        val client = fixture.client(CoapClientConfig(security = failing))

        val error = assertFailsWith<CoapException> { client.get(fixture.uri("/r")) }
        assertEquals(CoapException.Reason.SECURITY, error.reason)
        assertIs<MessageSecurityException>(error.cause)
        assertEquals(0, client.endpoint.exchangeCount())
        server.close()
    }

    @Test
    fun aFailureWhileTheTimersCloseAConnectionIsContainedAndFreesThePeersSlot() = runTest {
        val fixture = ReliableFixture(this)
        val records = mutableListOf<LogRecord>()
        val loggers = LoggerFactory(
            LogConfig.build {
                level = LogLevel.ERROR
                sink = LogSink { records += it }
            },
        )
        // A peer that sends its CSM and then ignores everything, a Release included.
        val listener = fixture.listen()
        backgroundScope.launch {
            while (true) {
                val connection = listener.accept() as? InMemoryByteStream ?: break
                connection.write(ReliableMessageCodec.Tcp.encode(Signal.Csm(null, false).toMessage()))
            }
        }
        val reliable = ReliableTransportConfig(idleTimeout = 1.seconds, releaseTimeout = 1.seconds)
        val config = CoapEndpointConfig(
            timeSource = testScheduler.timeSource, eventListener = { fixture.events += it }, loggerFactory = loggers, reliable = reliable,
        )
        val endpoint = CoapEndpoint(backgroundScope, config, connectors = listOf(fixture.connections.connector))
        endpoint.requestHandler = object : RequestHandler {
            override val context: CoroutineContext = EmptyCoroutineContext

            override suspend fun handle(request: InboundRequest) = Unit

            override suspend fun onSessionEnded(peer: Endpoint) = error("a bug while dropping the peer's state")

            override suspend fun onEndpointClosed() = error("a bug while stopping")
        }
        val peer = Endpoint.Tcp("127.0.0.1", 5683)
        endpoint.limits(peer)

        // Idle, the connection is released after 1 s; the peer never closes it, so after releaseTimeout the
        // connection's timers close it, and the failure surfaces in their processing.
        delay(5.seconds)
        assertTrue(records.any { "Timer processing failed" in it.message && it.error?.message == "a bug while dropping the peer's state" })
        endpoint.limits(peer)
        assertEquals(2, fixture.events.count { it is EndpointEvent.ConnectionOpened }, "the old connection's slot was freed")

        // Closing runs every step, and closes every connection, although the same bugs strike again.
        endpoint.close()
        assertEquals(CoapException.Reason.CLOSED, endpoint.awaitClosed().reason)
        assertTrue(fixture.connections.connections().all { it.isClosed })
        assertTrue(records.any { "Failed to stop the server while closing" in it.message })
        assertTrue(records.any { "Closing the connection to $peer failed" in it.message })
    }

    @Test
    fun aGuardedCoroutineWhoseCleanupFailsToo() = runTest {
        val fixture = StackFixture(this)
        val records = mutableListOf<LogRecord>()
        val loggers = LoggerFactory(
            LogConfig.build {
                level = LogLevel.ERROR
                sink = LogSink { records += it }
            },
        )
        val endpoint = CoapEndpoint(fixture.transport(), backgroundScope, CoapEndpointConfig(loggerFactory = loggers, timeSource = testScheduler.timeSource))

        endpoint.launchGuarded("do the work", onFailure = { error("cleanup failed as well") }) { error("work failed") }.join()
        assertEquals(listOf("work failed", "cleanup failed as well"), records.map { it.error?.message })
    }

    @Test
    fun aTransportThatFailsToCloseStillEndsTheEndpoint() = runTest {
        val fixture = StackFixture(this)
        val inner = fixture.transport()
        val stubborn = object : DatagramTransport by inner {
            override suspend fun close() = error("cannot close")
        }
        val endpoint = CoapEndpoint(stubborn, backgroundScope, fixture.endpointConfig())

        endpoint.close()
        assertEquals(CoapException.Reason.CLOSED, endpoint.awaitClosed().reason)
    }

    @Test
    fun aFailureEscapingAnEndpointCoroutineIsLoggedWhenTheScopeHasNoHandler() = runTest {
        val fixture = StackFixture(this)
        val records = mutableListOf<LogRecord>()
        val loggers = LoggerFactory(
            LogConfig.build {
                level = LogLevel.ERROR
                sink = LogSink { records += it }
            },
        )
        val bare = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val endpoint = CoapEndpoint(fixture.transport(), bare, CoapEndpointConfig(loggerFactory = loggers, timeSource = testScheduler.timeSource))

        endpoint.scope.launch { error("a bug in an endpoint coroutine") }
        runCurrent()
        assertTrue(records.any { it.error?.message == "a bug in an endpoint coroutine" }, "logged instead of crashing the process")
        bare.cancel()
    }
}
