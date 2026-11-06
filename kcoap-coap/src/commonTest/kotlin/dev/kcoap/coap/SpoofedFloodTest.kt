package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * RFC 7252 §11.3, §11.4: a UDP source address proves nothing, so a flood of Confirmable requests from any number
 * of forged addresses ([dev.kcoap.testkit.InMemoryNetwork.spoof]) must leave every structure it fills bounded, in
 * total and per peer, while a client at a real address is still served. One test per bound: deduplication
 * (§4.5), Message IDs (§4.4), exchanges in progress, and observers (RFC 7641 §4.1, §4.5).
 */
class SpoofedFloodTest {
    /** The [index]th forged source: no transport is attached there, so nothing sent to it is ever answered. */
    private fun spoofed(index: Int) = Endpoint.Udp("10.${(index shr 16) and 0xFF}.${(index shr 8) and 0xFF}.${index and 0xFF}", 40_000)

    private fun StackFixture.limits(limit: Limit): Int = events.count { it is EndpointEvent.LimitReached && it.limit == limit }

    private fun request(code: CoapCode, type: CoapMessageType, messageId: Int, path: String, observe: Long? = null) =
        CoapMessage.request(code) {
            this.type = type
            this.messageId = messageId and 0xFFFF
            token = byteArrayOf((messageId shr 8).toByte(), messageId.toByte())
            options {
                observe?.let { observe(it) }
                uriPath(path)
            }
            if (code == CoapCode.POST) payload("x")
        }

    /**
     * Sends [perSource] messages from each of [count] forged sources, [message] numbering them from 0, in bursts of
     * [burst] with 10 ms between them, so the server's handlers run as they would on a multi-threaded dispatcher
     * rather than all after the whole flood.
     */
    private suspend fun StackFixture.flood(
        server: CoapServer,
        count: Int,
        perSource: Int = 1,
        burst: Int = 50,
        message: (Int) -> CoapMessage,
    ) {
        val to = address(server.endpoint)
        for (index in 0 until count * perSource) {
            network.spoof(spoofed(index / perSource), to, UdpMessageCodec.encode(message(index)))
            if ((index + 1) % burst == 0) delay(10.milliseconds)
        }
    }

    private fun StackFixture.bounded(block: CoapEndpointConfig.Builder.() -> Unit): CoapEndpointConfig =
        endpointConfig().toBuilder().apply(block).build()

    @Test
    fun rfc7252Section4_5AFloodOfGetsKeepsTheDeduplicationCacheBoundedAndALegitimatePeerServed() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.bounded { maxDeduplicationEntries = 400 }) {
            resource("r") { get { CoapResponse.content("ok") } }
        }
        fixture.flood(server, 2_000) { request(CoapCode.GET, CoapMessageType.CON, it, "r") }
        delay(1.seconds)

        val sizes = server.endpoint.messagingSizes()!!
        assertTrue(sizes.deduplicationEntries <= 400, "$sizes")
        assertTrue(sizes.deduplicationPeers <= 400, "$sizes")
        // Answered GETs may be processed again (§4.5), so each new one took the place of the oldest: nothing refused.
        assertEquals(0, fixture.limits(Limit.DEDUPLICATION_ENTRIES))
        assertEquals("ok", fixture.client().get(fixture.uri(server, "/r")).payloadAsText())
    }

    @Test
    fun rfc7252Section11_3AFloodOfPostsKeepsThePlacesOfIdempotentRequestsFree() = runTest {
        val fixture = StackFixture(this)
        var stored = 0
        val server = fixture.server(
            endpointConfig = fixture.bounded {
                maxDeduplicationEntries = 400
                maxDeduplicationEntriesPerPeer = 20
            },
        ) {
            resource("r") { get { CoapResponse.content("ok") } }
            resource("store") {
                post {
                    stored++
                    CoapResponse.changed()
                }
            }
        }
        // 50 sources, 40 POSTs each, all with an effect, so none may be processed twice.
        fixture.flood(server, 50, perSource = 40) { request(CoapCode.POST, CoapMessageType.CON, it, "store") }
        delay(1.seconds)

        val sizes = server.endpoint.messagingSizes()!!
        assertTrue(sizes.deduplicationEntries <= 300, "three quarters at most hold POSTs: $sizes")
        assertTrue(fixture.limits(Limit.DEDUPLICATION_ENTRIES_PER_PEER) > 0, "one source's share is 20")
        assertTrue(fixture.limits(Limit.DEDUPLICATION_ENTRIES) > 0, "the POSTs filled three quarters")
        assertEquals(sizes.deduplicationEntries, stored, "every POST remembered ran exactly once")
        // The quarter kept free serves a GET of a client at a real address.
        assertEquals("ok", fixture.client().get(fixture.uri(server, "/r")).payloadAsText())
    }

    @Test
    fun rfc7252Section4_5AFloodOfRefusedPostsLeavesRoomForALegitimatePost() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.bounded { maxDeduplicationEntries = 400 }) {
            resource("store") { post { CoapResponse.changed() } }
        }
        // A path that does not exist: answered with 4.04 before anything acted on it, so repeatable.
        fixture.flood(server, 2_000) { request(CoapCode.POST, CoapMessageType.CON, it, "missing") }
        delay(1.seconds)

        assertTrue(server.endpoint.messagingSizes()!!.deduplicationEntries <= 400)
        assertEquals(0, fixture.limits(Limit.DEDUPLICATION_ENTRIES))
        assertEquals(CoapCode.CHANGED, fixture.client().post(fixture.uri(server, "/store"), "x".encodeToByteArray()).code)
    }

    @Test
    fun rfc7252Section4_4AFloodOfNonConfirmableGetsKeepsTheReservedMessageIdsBounded() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.bounded { maxReservedMessageIds = 300 }) {
            resource("r") { get { CoapResponse.content("ok") } }
        }
        // Every Non-confirmable request gets a Non-confirmable response, with a Message ID of its own.
        fixture.flood(server, 1_000) { request(CoapCode.GET, CoapMessageType.NON, it, "r") }
        delay(1.seconds)

        assertEquals(300, server.endpoint.messagingSizes()!!.reservedMessageIds)
        assertEquals(700, fixture.limits(Limit.MESSAGE_IDS))
        // A piggybacked response needs no new Message ID, so a Confirmable GET is still served.
        val client = fixture.client(CoapClientConfig { requestTimeout = 5.seconds })
        assertEquals("ok", client.get(fixture.uri(server, "/r")).payloadAsText())
        // §4.4 forbids reusing the IDs before EXCHANGE_LIFETIME; then Non-confirmable responses go out again.
        delay(server.endpoint.parameters.exchangeLifetime)
        assertEquals("ok", client.get(fixture.uri(server, "/r")) { nonConfirmable() }.payloadAsText())
    }

    @Test
    fun rfc7252Section11_3AFloodOfSlowRequestsKeepsTheMessagesInProgressBounded() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.bounded { maxExchanges = 50 }) {
            resource("slow") {
                get {
                    delay(2.seconds)
                    CoapResponse.content("done")
                }
            }
        }
        // 100 requests that get an Empty ACK, then a separate Confirmable response no forged source acknowledges.
        fixture.flood(server, 100) { request(CoapCode.GET, CoapMessageType.CON, it, "slow") }
        delay(2.5.seconds)
        assertEquals(50, server.endpoint.messagingSizes()!!.transmissions)
        assertEquals(50, fixture.limits(Limit.EXCHANGES), "the other 50 separate responses were not sent")

        // Ten seconds on, each of them is unacknowledged after a retransmission, and gives way to a real client's.
        delay(10.seconds)
        val answer = fixture.client().get(fixture.uri(server, "/slow"))
        assertEquals("done", answer.payloadAsText())
        assertTrue(server.endpoint.messagingSizes()!!.transmissions <= 50)
        assertEquals(51, fixture.limits(Limit.EXCHANGES), "one given up for the real client's response")
    }

    @Test
    fun exchangesOfAClientAreBoundedInTotal() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client(endpointConfig = fixture.bounded { maxExchanges = 2 })
        val pending = List(2) { async { runCatching { client.get("coap://127.0.0.1:${9 + it}/x") } } }
        delay(100)

        val error = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:11/x") }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, error.reason)
        assertTrue("maxExchanges" in error.message!!)
        assertEquals(1, fixture.limits(Limit.EXCHANGES))
        pending.forEach { it.cancel() }
    }

    private class Gauge(name: String) : ObservableResource(name) {
        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("1")
    }

    @Test
    fun rfc7641Section4_5AFloodOfRegistrationsKeepsTheObserversBoundedUntilTheyFailToAcknowledge() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge("g")
        val server = fixture.server(CoapServerConfig(maxObservers = 50, maxObserversPerResource = 50)) { add(gauge) }
        fixture.flood(server, 200) { request(CoapCode.GET, CoapMessageType.CON, it, "g", observe = 0) }
        delay(1.seconds)
        assertEquals(50, gauge.observerCount)
        assertEquals(150, fixture.limits(Limit.OBSERVERS))

        // RFC 7641 §4.1: a real client is served as if it asked without Observe, and its flow ends.
        val client = fixture.client()
        val plain = client.observe(fixture.uri(server, "/g")).toList()
        assertNull(plain.single().options.observe)

        // RFC 7641 §4.5: the Confirmable notifications of a change go unacknowledged, so the forged observers go.
        gauge.notifyObservers()
        delay(server.endpoint.parameters.maxTransmitWait + 1.seconds)
        assertEquals(0, gauge.observerCount)
        val registered = client.observe(fixture.uri(server, "/g")).first()
        assertTrue(registered.options.observe != null, "registered once there is room")
    }
}
