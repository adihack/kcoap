package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * An endpoint ends in one terminal transition, whichever way it ends: close(), cancellation of its scope,
 * or a datagram transport that fails. Every test bounds its waits, so a hang fails instead of stalling.
 */
class EndpointLifecycleTest {
    private class Gauge : ObservableResource("gauge") {
        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("1")
    }

    private fun TestScope.childScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    @Test
    fun closingTheEndpointEndsEveryObserveCollectorWithClosed() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { add(Gauge()) }
        val client = fixture.client()
        val collector = async { runCatching { client.observe(fixture.uri(server, "/gauge")).collect() } }
        delay(1.seconds)

        client.endpoint.close()
        val error = assertIs<CoapException>(withTimeout(10.seconds) { collector.await() }.exceptionOrNull())
        assertEquals(CoapException.Reason.CLOSED, error.reason)
    }

    @Test
    fun observingOnAClosedEndpointFailsAtOnce() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client()
        client.endpoint.close()

        val error = assertFailsWith<CoapException> { withTimeout(1.seconds) { client.observe("coap://127.0.0.1:9/x").collect() } }
        assertEquals(CoapException.Reason.CLOSED, error.reason)
    }

    @Test
    fun cancellingTheScopeClosesTheEndpointAndItsTransport() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val scope = childScope()
        val client = CoapClient(CoapEndpoint(transport, scope, fixture.endpointConfig()))
        val pending = async { runCatching { client.get("coap://127.0.0.1:9/x") } }
        delay(1.seconds)

        scope.cancel()
        val error = assertIs<CoapException>(withTimeout(1.seconds) { pending.await() }.exceptionOrNull())
        assertEquals(CoapException.Reason.CLOSED, error.reason)
        assertTrue(client.endpoint.isClosed)
        assertTrue(transport.isClosed, "the socket is not leaked")
        val started = fixture.now
        val next = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/x") }
        assertEquals(CoapException.Reason.CLOSED, next.reason)
        assertEquals(started, fixture.now, "fails at once instead of after EXCHANGE_LIFETIME")
    }

    @Test
    fun anEndpointCreatedInACancelledScopeIsClosedAtOnce() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val scope = childScope().apply { cancel() }

        val endpoint = CoapEndpoint(transport, scope, fixture.endpointConfig())
        runCurrent()
        assertTrue(endpoint.isClosed)
        assertTrue(transport.isClosed)
        assertEquals(CoapException.Reason.CLOSED, endpoint.closeCause?.reason)
    }

    @Test
    fun aTransportWhoseReceiveFailsClosesTheEndpointWithTheCause() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val client = CoapClient(CoapEndpoint(transport, backgroundScope, fixture.endpointConfig()))
        val pending = async { runCatching { client.get("coap://127.0.0.1:9/x") } }
        delay(1.seconds)
        assertNull(client.endpoint.closeCause)

        // The socket goes away underneath the endpoint, as after an OS reclaim or a network change.
        transport.close()
        val error = assertIs<CoapException>(withTimeout(1.seconds) { pending.await() }.exceptionOrNull())
        assertEquals(CoapException.Reason.TRANSPORT_FAILED, error.reason)
        val cause = assertIs<CoapException>(client.endpoint.closeCause)
        assertEquals(CoapException.Reason.TRANSPORT_FAILED, cause.reason)
        assertIs<ClosedReceiveChannelException>(cause.cause)
        val next = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/x") }
        assertEquals(CoapException.Reason.TRANSPORT_FAILED, next.reason)
        assertFailsWith<CoapException> { client.ping("coap://127.0.0.1:9") }
    }

    @Test
    fun aRequestQueuedBeforeTheServerExistsIsServed() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val peer = fixture.transport()
        val request = CoapMessage.request(CoapCode.GET) {
            type = CoapMessageType.CON
            messageId = 0x1234
            token = byteArrayOf(1)
            options { uriPath("hello") }
        }
        peer.send(transport.localEndpoint, UdpMessageCodec.encode(request))

        // The endpoint exists before its server; it must not answer the waiting request with a Reset.
        val endpoint = CoapEndpoint(transport, backgroundScope, fixture.endpointConfig())
        runCurrent()
        CoapServer(endpoint) { resource("hello") { get { CoapResponse.content("Hello") } } }

        val answer = UdpMessageCodec.decode((withTimeout(1.seconds) { peer.receive() } as DatagramEvent.Received).datagram)
        assertEquals(CoapMessageType.ACK, answer.type)
        assertEquals(CoapCode.CONTENT, answer.code)
    }

    @Test
    fun awaitClosedReturnsTheCauseOnceEveryCoroutineStopped() = runTest {
        val fixture = StackFixture(this)
        val handlerStarted = CompletableDeferred<Unit>()
        val server = fixture.server {
            resource("slow") {
                get {
                    handlerStarted.complete(Unit)
                    delay(1000.seconds)
                    CoapResponse.content("late")
                }
            }
        }
        val client = fixture.client()
        val pending = async { runCatching { client.get(fixture.uri(server, "/slow")) } }
        handlerStarted.await()

        val waiter = async { server.endpoint.awaitClosed() }
        runCurrent()
        assertFalse(waiter.isCompleted)
        server.endpoint.close()
        val cause = withTimeout(1.seconds) { waiter.await() }
        assertEquals(CoapException.Reason.CLOSED, cause.reason)
        assertSame(cause, server.endpoint.closeCause)
        server.endpoint.close()
        pending.cancel()
    }

    @Test
    fun stoppingAServerOnASharedEndpointCancelsItsHandlersAndTakesNoObservers() = runTest {
        val fixture = StackFixture(this)
        val endpoint = fixture.endpoint()
        val gate = CompletableDeferred<Unit>()
        val handlerStarted = CompletableDeferred<Unit>()
        val gauge = object : ObservableResource("gauge") {
            override suspend fun handleGet(context: RequestContext): CoapResponse {
                handlerStarted.complete(Unit)
                gate.await()
                return CoapResponse.content("1")
            }
        }
        val server = CoapServer(endpoint) { add(gauge) }
        val client = fixture.client()
        val collector = async { runCatching { client.observe(fixture.uri(server, "/gauge")).collect() } }
        handlerStarted.await()

        server.close()
        gate.complete(Unit)
        delay(5.seconds)
        assertEquals(0, gauge.observerCount, "a handler still running after close() registered nothing")
        assertFalse(endpoint.isClosed, "the server did not own the endpoint")
        collector.cancel()
    }

    @Test
    fun aHandlerThatIgnoresCancellationRegistersNoObserverAfterTheServerStopped() = runTest {
        val fixture = StackFixture(this)
        val endpoint = fixture.endpoint()
        val gate = CompletableDeferred<Unit>()
        val handlerStarted = CompletableDeferred<Unit>()
        val gauge = object : ObservableResource("gauge") {
            override suspend fun handleGet(context: RequestContext): CoapResponse {
                handlerStarted.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                return CoapResponse.content("1")
            }
        }
        val server = CoapServer(endpoint) { add(gauge) }
        val client = fixture.client()
        val collector = async { runCatching { client.observe(fixture.uri(server, "/gauge")).collect() } }
        handlerStarted.await()

        server.close()
        gate.complete(Unit)
        delay(5.seconds)
        assertEquals(0, gauge.observerCount, "served as a plain GET (RFC 7641 §4.1)")
        collector.cancel()
    }

    @Test
    fun rfc8323Section3_3ARequestWhoseHandlerTheStoppingServerCancelledIsStillAnswered() = runTest {
        val fixture = ReliableFixture(this)
        val endpoint = CoapEndpoint(backgroundScope, fixture.endpointConfig(), listeners = listOf(fixture.listen()))
        val started = CompletableDeferred<Unit>()
        val server = CoapServer(endpoint) {
            resource("slow") {
                get {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val client = fixture.client(CoapClientConfig(requestTimeout = 60.seconds))
        val pending = async { client.get(fixture.uri("/slow")) }
        started.await()

        server.close()
        assertEquals(CoapCode.SERVICE_UNAVAILABLE, withTimeout(1.seconds) { pending.await() }.code)
        // RFC 8323 §5.4.1: no request left unanswered holds back a Pong with Custody.
        assertTrue(client.endpoint.ping(Endpoint.Tcp("127.0.0.1", 5683), custody = true))
        endpoint.close()
    }

    @Test
    fun rfc8323Section3_3ARequestWhoseHandlerNeverRanIsStillAnswered() = runTest {
        val fixture = ReliableFixture(this)
        var delivered = false
        val spy = object : MessageInterceptor {
            override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage {
                if (message.isRequest) delivered = true
                return message
            }
        }
        val endpoint = CoapEndpoint(backgroundScope, fixture.endpointConfig(interceptors = listOf(spy)), listeners = listOf(fixture.listen()))
        val server = CoapServer(endpoint) { resource("r") { get { CoapResponse.content("ok") } } }
        val client = fixture.client(CoapClientConfig(requestTimeout = 60.seconds))
        val pending = async { client.get(fixture.uri("/r")) }

        // The handler coroutine is launched in the same step that delivers the request, and dispatched only
        // after this one resumes, so the server stops between the two and the handler never runs.
        while (!delivered) yield()
        server.close()
        assertEquals(CoapCode.SERVICE_UNAVAILABLE, withTimeout(1.seconds) { pending.await() }.code)
        assertTrue(client.endpoint.ping(Endpoint.Tcp("127.0.0.1", 5683), custody = true))
        endpoint.close()
    }

    @Test
    fun pingsAfterCloseFailWithClosed() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client()
        client.endpoint.close()
        assertEquals(CoapException.Reason.CLOSED, assertFailsWith<CoapException> { client.ping("coap://127.0.0.1:9") }.reason)

        val reliable = ReliableFixture(this).client()
        reliable.endpoint.close()
        assertEquals(
            CoapException.Reason.CLOSED,
            assertFailsWith<CoapException> { reliable.ping("coap+tcp://127.0.0.1:5683") }.reason,
        )
        assertEquals(
            CoapException.Reason.CLOSED,
            assertFailsWith<CoapException> { reliable.get("coap+tcp://127.0.0.1:5683/x") }.reason,
        )
    }
}
