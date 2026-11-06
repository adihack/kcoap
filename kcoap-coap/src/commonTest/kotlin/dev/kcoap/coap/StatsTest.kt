package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.transport.DatagramEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * `CoapEndpoint.stats()` and `CoapServer.stats()`: counts of what is held now, read without a lock, that follow each
 * layer as it takes and gives back state.
 */
class StatsTest {
    @Test
    fun endpointStatsCountWhatTheEndpointHoldsNow() = runTest {
        val fixture = StackFixture(this)
        val release = CompletableDeferred<Unit>()
        val server = fixture.server {
            resource("slow") {
                get {
                    release.await()
                    CoapResponse.content("done")
                }
            }
        }
        val client = fixture.client()
        assertEquals(
            "EndpointStats(exchanges=0, runningHandlers=0, transmissions=0, deduplicationEntries=0, reservedMessageIds=0, connections=0)",
            client.endpoint.stats().toString(),
        )

        val pending = async { client.get(fixture.uri(server, "/slow")) }
        delay(100.milliseconds)

        // Before the server's Empty ACK (after 1 s): the request is in flight, its handler waits.
        val asking = client.endpoint.stats()
        assertEquals(1, asking.exchanges)
        assertEquals(1, asking.transmissions, "the Confirmable request until it is acknowledged")
        assertEquals(1, asking.reservedMessageIds)
        assertEquals(0, asking.runningHandlers)
        val serving = server.endpoint.stats()
        assertEquals(1, serving.runningHandlers)
        assertEquals(1, serving.deduplicationEntries)
        assertEquals(0, serving.exchanges)
        assertEquals(0, serving.connections, "a datagram endpoint has none")

        release.complete(Unit)
        assertEquals("done", pending.await().payloadAsText())
        delay(1.milliseconds)

        val done = client.endpoint.stats()
        assertEquals(0 to 0, done.exchanges to done.transmissions)
        assertEquals(1, done.reservedMessageIds, "a Message ID stays reserved for EXCHANGE_LIFETIME (RFC 7252 §4.4)")
        assertEquals(0, server.endpoint.stats().runningHandlers)
        delay(client.endpoint.parameters.exchangeLifetime)
        assertEquals(0, client.endpoint.stats().reservedMessageIds)
        assertEquals(0, server.endpoint.stats().deduplicationEntries)
    }

    @Test
    fun serverStatsCountObserversAndBlockwiseTransfers() = runTest {
        val fixture = StackFixture(this)
        val counter = object : ObservableResource("counter") {
            override suspend fun handleGet(context: RequestContext) = CoapResponse.content("1")
        }
        val big = ByteArray(300) { it.toByte() }
        val server = fixture.server {
            add(counter)
            resource("big") {
                get { CoapResponse.content(big) }
                put { CoapResponse.changed() }
            }
        }
        val client = fixture.client(CoapClientConfig { preferredBlockSize = 64 })
        assertEquals(
            "ServerStats(observers=0, block1Transfers=0, block2Transfers=0, qBlock1Bodies=0, qBlock2Bodies=0)",
            server.stats().toString(),
        )

        val observing = async { client.observe(fixture.uri(server, "/counter")).take(2).toList() }
        delay(1.seconds)
        assertEquals(1, server.stats().observers)
        counter.notifyObservers()
        observing.await()
        delay(1.seconds)
        assertEquals(0, server.stats().observers, "the collector stopped and deregistered (RFC 7641 §3.6)")

        // RFC 7959 §2.4: the representation stays for its later blocks until EXCHANGE_LIFETIME.
        assertEquals(300, client.get(fixture.uri(server, "/big")).payloadSize)
        assertEquals(1, server.stats().block2Transfers)

        // RFC 7959 §2.5: the first block of an upload, whose others never come.
        val raw = fixture.transport()
        val block = CoapMessage.request(CoapCode.PUT) {
            messageId = 1
            token = byteArrayOf(1)
            options {
                uriPath("big")
                block1(BlockOption(0, true, BlockOption.sizeExponentFor(64)))
            }
            payload = ByteArray(64)
        }
        raw.send(fixture.address(server.endpoint), UdpMessageCodec.encode(block))
        val answer = UdpMessageCodec.decode((raw.receive() as DatagramEvent.Received).datagram)
        assertEquals(CoapCode.CONTINUE, answer.code)
        assertEquals(1, server.stats().block1Transfers)
    }
}
