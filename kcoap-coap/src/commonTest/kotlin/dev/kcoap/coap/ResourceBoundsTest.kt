package dev.kcoap.coap

import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.testkit.InMemoryByteStream
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every bound on what a peer can make the endpoint keep or do (RFC 7252 §11.3): reached, it refuses only
 * the extra work, answers with no more than it received, and reports [EndpointEvent.LimitReached].
 */
class ResourceBoundsTest {
    private fun StackFixture.limits(limit: Limit): Int = events.count { it is EndpointEvent.LimitReached && it.limit == limit }

    private fun ReliableFixture.limits(limit: Limit): Int = events.count { it is EndpointEvent.LimitReached && it.limit == limit }

    private fun request(type: CoapMessageType, messageId: Int, token: Byte, path: String, observe: Long? = null) =
        CoapMessage.request(CoapCode.GET) {
            this.type = type
            this.messageId = messageId
            this.token = byteArrayOf(token)
            options {
                observe?.let { observe(it) }
                uriPath(path)
            }
        }

    private suspend fun TestScope.answerTo(peer: InMemoryTransport, to: Endpoint, message: CoapMessage): CoapMessage? {
        peer.send(to, UdpMessageCodec.encode(message))
        val event = withTimeoutOrNull(1.seconds) { peer.receive() } ?: return null
        return UdpMessageCodec.decode((event as DatagramEvent.Received).datagram)
    }

    @Test
    fun rfc7252Section11_3AServerWithAFullDeduplicationCacheDropsNewRequestsSilently() = runTest {
        val fixture = StackFixture(this)
        val config = CoapEndpointConfig(maxDeduplicationEntries = 1, timeSource = testScheduler.timeSource, eventListener = { fixture.events += it })
        val server = fixture.server(endpointConfig = config) { resource("r") { get { CoapResponse.content("ok") } } }
        val address = fixture.address(server.endpoint)
        val peer = fixture.transport()

        assertEquals(CoapCode.CONTENT, answerTo(peer, address, request(CoapMessageType.CON, 1, 1, "r"))?.code)
        assertNull(answerTo(peer, address, request(CoapMessageType.CON, 2, 2, "r")), "no answer to a possibly spoofed source")
        assertEquals(1, fixture.limits(Limit.DEDUPLICATION_ENTRIES))
        assertEquals(CoapCode.CONTENT, answerTo(peer, address, request(CoapMessageType.CON, 1, 1, "r"))?.code, "duplicates are still answered")
    }

    @Test
    fun rfc7252Section5_9_3_4RequestsBeyondTheHandlerBoundGet5_03WithMaxAge() = runTest {
        val fixture = StackFixture(this)
        val gate = CompletableDeferred<Unit>()
        val config = CoapEndpointConfig(maxConcurrentRequests = 1, timeSource = testScheduler.timeSource, eventListener = { fixture.events += it })
        val server = fixture.server(endpointConfig = config) {
            resource("slow") {
                get {
                    gate.await()
                    CoapResponse.content("done")
                }
            }
        }
        val client = fixture.client()
        val first = async { client.get(fixture.uri(server, "/slow")) }
        delay(100)

        val refused = client.get(fixture.uri(server, "/slow"))
        assertEquals(CoapCode.SERVICE_UNAVAILABLE, refused.code)
        assertEquals(2L, refused.options.maxAge)
        val piggybacked = fixture.sentBy(server.endpoint).single { it.code == CoapCode.SERVICE_UNAVAILABLE }
        assertEquals(CoapMessageType.ACK, piggybacked.type, "piggybacked: no larger than the request")

        // A Non-confirmable request beyond the bound is dropped.
        val peer = fixture.transport()
        assertNull(answerTo(peer, fixture.address(server.endpoint), request(CoapMessageType.NON, 7, 7, "slow")))
        assertEquals(2, fixture.limits(Limit.CONCURRENT_REQUESTS))

        gate.complete(Unit)
        assertEquals("done", first.await().payloadAsText())
        assertEquals("done", client.get(fixture.uri(server, "/slow")).payloadAsText(), "the permit came back")
    }

    @Test
    fun rfc8323RequestsOnAConnectionBeyondTheHandlerBoundGet5_03() = runTest {
        val fixture = ReliableFixture(this)
        val gate = CompletableDeferred<Unit>()
        val server = fixture.server(endpointConfig = CoapEndpointConfig(maxConcurrentRequests = 1, timeSource = testScheduler.timeSource, eventListener = { fixture.events += it })) {
            resource("slow") {
                get {
                    gate.await()
                    CoapResponse.content("done")
                }
            }
        }
        val client = fixture.client()
        val first = async { client.get(fixture.uri("/slow")) }
        delay(100)

        assertEquals(CoapCode.SERVICE_UNAVAILABLE, client.get(fixture.uri("/slow")).code)
        assertEquals(1, fixture.limits(Limit.CONCURRENT_REQUESTS))
        gate.complete(Unit)
        assertEquals("done", first.await().payloadAsText())
        server.close()
    }

    @Test
    fun exchangesInProgressWithOnePeerAreBounded() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client(endpointConfig = CoapEndpointConfig(maxExchangesPerPeer = 2, timeSource = testScheduler.timeSource, eventListener = { fixture.events += it }))
        val pending = List(2) { launch { runCatching { client.get("coap://127.0.0.1:9/x") } } }
        delay(100)

        val error = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/x") }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, error.reason)
        assertTrue("maxExchangesPerPeer" in error.message!!)
        assertEquals(1, fixture.limits(Limit.EXCHANGES_PER_PEER))
        val elsewhere = launch { runCatching { client.get("coap://127.0.0.1:10/x") } }
        delay(100)
        assertEquals(3, client.endpoint.exchangeCount(), "another peer has its own bound")

        pending.first().cancel()
        delay(100)
        val again = launch { runCatching { client.get("coap://127.0.0.1:9/x") } }
        delay(100)
        assertEquals(3, client.endpoint.exchangeCount(), "a finished exchange frees its place")
        (pending + elsewhere + again).forEach { it.cancel() }
    }

    @Test
    fun exchangesInProgressOnAConnectionAreBoundedAndFreeTheirConnectionSlot() = runTest {
        val fixture = ReliableFixture(this)
        val gate = CompletableDeferred<Unit>()
        val server = fixture.server {
            resource("slow") {
                get {
                    gate.await()
                    CoapResponse.content("done")
                }
            }
        }
        val config = CoapEndpointConfig(maxExchangesPerPeer = 1, timeSource = testScheduler.timeSource, eventListener = { fixture.events += it })
        val client = fixture.client(endpointConfig = config)
        val first = async { client.get(fixture.uri("/slow")) }
        delay(100)

        val error = assertFailsWith<CoapException> { client.get(fixture.uri("/slow")) }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, error.reason)
        assertEquals(1, fixture.limits(Limit.EXCHANGES_PER_PEER))
        gate.complete(Unit)
        assertEquals("done", first.await().payloadAsText())
        assertEquals("done", client.get(fixture.uri("/slow")).payloadAsText(), "the refused request left no count behind")
        server.close()
    }

    private class Gauge(name: String) : ObservableResource(name) {
        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("1")
    }

    @Test
    fun rfc7641Section4_1RegistrationsBeyondTheObserverBoundsAreServedWithoutObserve() = runTest {
        val fixture = StackFixture(this)
        val (a, b, c) = listOf(Gauge("a"), Gauge("b"), Gauge("c"))
        val server = fixture.server(CoapServerConfig(maxObservers = 2, maxObserversPerResource = 1)) {
            add(a)
            add(b)
            add(c)
        }
        val first = fixture.client()
        val collectors = mutableListOf(launch { first.observe(fixture.uri(server, "/a")).collect() })
        delay(1.seconds)
        assertEquals(1, a.observerCount)

        // A second observer of a: served once, without Observe, so the client's flow ends after it.
        val second = fixture.client()
        val plain = second.observe(fixture.uri(server, "/a")).toList()
        assertEquals(listOf("1"), plain.map { it.payloadAsText() })
        assertNull(plain.single().options.observe)
        assertEquals(1, a.observerCount)
        assertEquals(1, fixture.limits(Limit.OBSERVERS_PER_RESOURCE))

        collectors += launch { second.observe(fixture.uri(server, "/b")).collect() }
        delay(1.seconds)
        assertEquals(1, b.observerCount)
        assertEquals(1, fixture.client().observe(fixture.uri(server, "/c")).toList().size)
        assertEquals(0, c.observerCount)
        assertEquals(1, fixture.limits(Limit.OBSERVERS))
        collectors.forEach { it.cancel() }
    }

    @Test
    fun rfc7641Section4_1AReRegistrationReplacesItsObserverAndCountsOnce() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge("g")
        val server = fixture.server(CoapServerConfig(maxObservers = 1, maxObserversPerResource = 1)) { add(gauge) }
        val peer = fixture.transport()
        val address = fixture.address(server.endpoint)

        assertTrue(answerTo(peer, address, request(CoapMessageType.CON, 1, 9, "g", observe = 0))?.options?.observe != null)
        assertTrue(answerTo(peer, address, request(CoapMessageType.CON, 2, 9, "g", observe = 0))?.options?.observe != null)
        assertNull(answerTo(peer, address, request(CoapMessageType.CON, 3, 8, "g", observe = 0))?.options?.observe)
        assertEquals(1, gauge.observerCount)
    }

    @Test
    fun rfc8323AcceptedConnectionsThatNeverSendACsmAreBounded() = runTest {
        val fixture = ReliableFixture(this)
        val reliable = ReliableTransportConfig(maxPendingConnections = 1)
        val server = fixture.server(endpointConfig = fixture.endpointConfig(reliable = reliable)) {
            resource("r") { get { CoapResponse.content("ok") } }
        }
        val silent = fixture.connections.connector.connect(Endpoint.Tcp("127.0.0.1", 5683)) as InMemoryByteStream
        delay(100)
        val refused = fixture.connections.connector.connect(Endpoint.Tcp("127.0.0.1", 5683)) as InMemoryByteStream
        delay(100)

        assertTrue(refused.isClosed, "beyond the bound: closed at once")
        assertTrue(!silent.isClosed, "the first one still has csmTimeout to send its CSM")
        assertEquals(1, fixture.limits(Limit.PENDING_CONNECTIONS))
        delay(11.seconds)
        assertTrue(silent.isClosed, "aborted without a CSM (RFC 8323 §3.3)")
        assertEquals("ok", fixture.client().get(fixture.uri("/r")).payloadAsText(), "the place came back")
        server.close()
    }

    @Test
    fun boundsMustAllowAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig(maxDeduplicationEntries = 0) }
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig(maxConcurrentRequests = 0) }
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig(maxExchangesPerPeer = 0) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(maxObservers = 0) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(maxObserversPerResource = 0) }
        assertFailsWith<IllegalArgumentException> { ReliableTransportConfig(maxPendingConnections = 0) }
        val defaults = CoapEndpointConfig()
        assertEquals(50_000, defaults.maxDeduplicationEntries)
        assertEquals(256, defaults.maxConcurrentRequests)
        assertEquals(256, defaults.maxExchangesPerPeer)
        assertEquals(10_000, CoapServerConfig().maxObservers)
        assertEquals(1_000, CoapServerConfig().maxObserversPerResource)
        assertEquals(128, ReliableTransportConfig().maxPendingConnections)
    }
}
