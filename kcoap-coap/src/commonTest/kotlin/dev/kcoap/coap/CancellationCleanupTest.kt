package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.EndpointEvent
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        val client = fixture.client(CoapClientConfig(security = gated))
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
        val client = fixture.client(CoapClientConfig(blockSize = 16))
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

    private suspend fun kotlinx.coroutines.test.TestScope.cancelledWhileSendingFreesTheSlot(confirmable: Boolean) {
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
}
