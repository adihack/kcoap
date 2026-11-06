package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.ReliableMessageCodec
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.coap.stack.Signal
import dev.kcoap.testkit.InMemoryByteStream
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Connection
import dev.kcoap.transport.Connector
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Cleanup in catch and finally runs under NonCancellable, so a cancelled request, collector or upload
 * leaves nothing behind even when the lock it needs is busy at that moment. The tests hold the lock
 * themselves, as the receive loop, the timer loop or another sender on another thread would.
 */
class CancellationCleanupTest {
    /** Delays the next [slowSends] datagrams by a second each, as a busy socket would. */
    private class SlowSendTransport(private val inner: InMemoryTransport) : DatagramTransport by inner {
        var slowSends = 0

        override suspend fun send(to: Endpoint, datagram: ByteArray) {
            if (slowSends > 0) {
                slowSends--
                delay(1.seconds)
            }
            inner.send(to, datagram)
        }
    }

    private class Gauge : ObservableResource("gauge") {
        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("1")
    }

    /** Holds every connection attempt at [gate], as a slow TCP or TLS handshake would. */
    private class GatedConnector(private val inner: Connector) : Connector by inner {
        val connecting = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun connect(peer: Endpoint): Connection {
            connecting.complete(Unit)
            gate.await()
            return inner.connect(peer)
        }
    }

    private val tcpPeer = Endpoint.Tcp("127.0.0.1", 5683)

    private fun encode(signal: Signal): ByteArray = ReliableMessageCodec.Tcp.encode(signal.toMessage())

    /** A request exchange as the client opens it, which reserves its place on the connection while it waits. */
    private suspend fun openExchange(endpoint: CoapEndpoint) = endpoint.openExchange(
        tcpPeer,
        CoapMessage.request(CoapCode.GET) { options { uriPath("r") } },
        confirmable = true,
        security = null,
    )

    @Test
    fun aRequestCancelledWhileTheEndpointLockIsBusyLeavesNoExchangeBehind() = runTest {
        val fixture = StackFixture(this)
        val protecting = CompletableDeferred<Unit>()
        val gated = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
                protecting.complete(Unit)
                awaitCancellation()
            }
        }
        val client = fixture.client(CoapClientConfig { security = gated })
        val request = launch { runCatching { client.get("coap://127.0.0.1:9/x") } }
        protecting.await()
        assertEquals(1, client.endpoint.exchangeCount())

        client.endpoint.lock.lock()
        request.cancel()
        runCurrent()
        client.endpoint.lock.unlock()
        request.join()
        assertEquals(0, client.endpoint.exchangeCount())
    }

    @Test
    fun aCancelledCollectorStillEndsItsObservationWhenTheClientLockIsBusy() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge()
        val server = fixture.server { add(gauge) }
        val client = fixture.client()
        val collector = launch { client.observe(fixture.uri(server, "/gauge")).collect() }
        delay(1.seconds)
        assertEquals(1, gauge.observerCount)

        client.observationLock.lock()
        collector.cancel()
        runCurrent()
        client.observationLock.unlock()
        collector.join()
        delay(5.seconds)
        assertEquals(0, client.observationCount())
        assertEquals(0, gauge.observerCount, "deregistered (RFC 7641 §3.6)")
    }

    @Test
    fun aCancelledUploadFreesItsRequestTagWhenTheTagLockIsBusy() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client(CoapClientConfig { preferredBlockSize = 16 })
        val upload = launch { runCatching { client.put("coap://127.0.0.1:9/x", ByteArray(100)) } }
        delay(1.seconds)
        assertEquals(1, client.blockwise.tagsInUseCount())

        client.blockwise.tagLock.lock()
        upload.cancel()
        runCurrent()
        client.blockwise.tagLock.unlock()
        upload.join()
        assertEquals(0, client.blockwise.tagsInUseCount())
    }

    @Test
    fun aConfirmableRequestCancelledWhileItIsSentFreesTheNstartSlotForTheNext() = runTest {
        cancelledWhileSendingFreesTheSlot(confirmable = true)
    }

    @Test
    fun aNonConfirmableRequestCancelledWhileItIsSentFreesTheNstartSlotForTheNext() = runTest {
        cancelledWhileSendingFreesTheSlot(confirmable = false)
    }

    private suspend fun TestScope.cancelledWhileSendingFreesTheSlot(confirmable: Boolean) {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("ok") } } }
        val transport = SlowSendTransport(fixture.transport())
        val client = CoapClient(CoapEndpoint(transport, backgroundScope, fixture.endpointConfig()))
        val uri = fixture.uri(server, "/r")
        transport.slowSends = 1
        val first = launch { runCatching { client.get(uri) { isConfirmable = confirmable } } }
        delay(500.milliseconds)
        first.cancel()

        // RFC 7252 §4.7: the second request waits for the first's NSTART slot, which the cancellation frees.
        val started = fixture.now
        assertEquals("ok", client.get(uri) { isConfirmable = confirmable }.payloadAsText())
        assertTrue(fixture.now - started < 1.seconds, "the next request went out at once, not after a timeout")
        assertTrue(fixture.events.none { it is EndpointEvent.Retransmitted }, "nothing retransmits for the cancelled request")
    }

    @Test
    fun closingTheEndpointFailsARequestWaitingForItsConnectionWithClosed() = runTest {
        val connecting = CompletableDeferred<Unit>()
        val hanging = object : Connector {
            override val schemes: Set<CoapScheme> = setOf(CoapScheme.COAP_TCP)

            override suspend fun connect(peer: Endpoint): Connection {
                connecting.complete(Unit)
                awaitCancellation()
            }
        }
        val fixture = ReliableFixture(this)
        val client = CoapClient(CoapEndpoint(backgroundScope, fixture.endpointConfig(), connectors = listOf(hanging)))
        val pending = async { runCatching { client.get("coap+tcp://127.0.0.1:5683/x") } }
        connecting.await()

        client.endpoint.close()
        val error = assertIs<CoapException>(withTimeout(1.seconds) { pending.await() }.exceptionOrNull())
        assertEquals(CoapException.Reason.CLOSED, error.reason)
    }

    @Test
    fun aCancelledPingLeavesNoPingThatALaterPongWouldAnswer() = runTest {
        val fixture = ReliableFixture(this)
        val listener = fixture.listen()
        val peerSide = async { (listener.accept() as InMemoryByteStream).also { it.write(encode(Signal.Csm(null, false))) } }
        val endpoint = fixture.endpoint()
        val first = launch { endpoint.ping(tcpPeer) }
        delay(1.seconds)
        first.cancel()
        val server = peerSide.await()

        // Pongs come in order, and one with an empty token (as libcoap sends) answers the oldest Ping pending.
        val second = async { endpoint.ping(tcpPeer) }
        delay(1.seconds)
        server.write(encode(Signal.Pong(custody = false)))
        assertTrue(second.await(), "the Pong answered the live Ping, not the cancelled one")
        endpoint.close()
    }

    @Test
    fun rfc8323Section5_5ARequestCancelledWhileItsConnectionOpensLetsItBeReleasedAsIdle() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("r") { get { CoapResponse.content("ok") } } }
        val connector = GatedConnector(fixture.connections.connector)
        val config = fixture.endpointConfig(ReliableTransportConfig(idleTimeout = 5.seconds))
        val endpoint = CoapEndpoint(backgroundScope, config, connectors = listOf(connector))
        val request = launch { openExchange(endpoint) }
        connector.connecting.await()

        // Cancelled before the opener took the waiting requests: it gives its place back itself.
        request.cancel()
        request.join()
        connector.gate.complete(Unit)
        delay(10.seconds)
        assertEquals(1, fixture.sent(CoapCode.RELEASE, byServer = false).size, "released once idle")
        endpoint.close()
    }

    @Test
    fun rfc8323Section5_5ARequestCancelledRightAfterTheHandOverLetsItBeReleasedAsIdle() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("r") { get { CoapResponse.content("ok") } } }
        val connector = GatedConnector(fixture.connections.connector)
        val config = fixture.endpointConfig(ReliableTransportConfig(idleTimeout = 5.seconds))
        val endpoint = CoapEndpoint(backgroundScope, config, connectors = listOf(connector))
        // The request runs on a dispatcher of its own, so it can be cancelled after the opener counted it on the
        // connection but before it resumed, as a busy thread pool or a single event loop may arrange.
        val own = TestCoroutineScheduler()
        val requests = CoroutineScope(StandardTestDispatcher(own) + Job())
        val request = requests.launch { openExchange(endpoint) }
        own.runCurrent()
        connector.connecting.await()
        connector.gate.complete(Unit)
        delay(1.seconds)

        request.cancel()
        own.runCurrent()
        assertTrue(request.isCancelled)
        delay(10.seconds)
        assertEquals(1, fixture.sent(CoapCode.RELEASE, byServer = false).size, "released once idle")
        requests.cancel()
        endpoint.close()
    }

    @Test
    fun aResponseTheMessagingLayerCommittedToGoesOutWhenItsHandlerIsCancelled() = runTest {
        val fixture = StackFixture(this)
        val inner = fixture.transport()
        val transport = SlowSendTransport(inner)
        val endpoint = CoapEndpoint(transport, backgroundScope, fixture.endpointConfig())
        val answering = CompletableDeferred<Unit>()
        val server = CoapServer(endpoint) {
            resource("r") {
                get {
                    transport.slowSends = 1
                    answering.complete(Unit)
                    CoapResponse.content("ok")
                }
            }
        }
        val client = fixture.client()
        val response = async { client.get("coap://${inner.localEndpoint.host}:${inner.localEndpoint.port}/r") }
        answering.await()
        delay(500.milliseconds)

        // The piggybacked response is on its way out when stopping the server cancels its handler.
        server.close()
        assertEquals("ok", response.await().payloadAsText())
        assertTrue(fixture.events.none { it is EndpointEvent.Retransmitted }, "the ACK went out at once, not on a retransmission")
        endpoint.close()
    }

    @Test
    fun rfc8323Section5_5AConnectionWhoseLastAnswerACancelledHandlerSentStillCloses() = runTest {
        drainedByACancelledHandler(noResponse = false)
    }

    @Test
    fun rfc8323Section5_5AConnectionWhoseLastRequestACancelledHandlerLeftUnansweredStillCloses() = runTest {
        // RFC 7967: the request is finished without a response, which completes the drain just as well.
        drainedByACancelledHandler(noResponse = true)
    }

    private suspend fun TestScope.drainedByACancelledHandler(noResponse: Boolean) {
        val fixture = ReliableFixture(this)
        val endpoint = CoapEndpoint(backgroundScope, fixture.endpointConfig(), listeners = listOf(fixture.listen()))
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val server = CoapServer(endpoint) {
            resource("slow") {
                get {
                    started.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    CoapResponse.content("done")
                }
            }
        }
        val peer = fixture.connections.connector.connect(tcpPeer) as InMemoryByteStream
        peer.write(encode(Signal.Csm(null, false)))
        val request = CoapMessage.request(CoapCode.GET) {
            token = byteArrayOf(1)
            options {
                uriPath("slow")
                if (noResponse) noResponse(0x02)
            }
        }
        peer.write(ReliableMessageCodec.Tcp.encode(request))
        started.await()
        // RFC 8323 §5.5: released, the server closes the connection once the request in progress is answered.
        peer.write(encode(Signal.Release(emptyList(), null, "bye")))
        delay(100.milliseconds)

        // The answer completes the drain, and the close then waits for the endpoint's lock, which another thread
        // could hold at that moment; meanwhile stopping the server cancels the handler that runs the close.
        endpoint.lock.lock()
        gate.complete(Unit)
        runCurrent()
        server.close()
        runCurrent()
        endpoint.lock.unlock()
        delay(1.seconds)
        assertEquals(1, fixture.events.count { it is EndpointEvent.ConnectionClosed }, "the close ran to the end")
        assertTrue(peer.isClosed)
        endpoint.close()
    }
}
