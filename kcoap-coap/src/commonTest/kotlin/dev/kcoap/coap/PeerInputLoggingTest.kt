package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.MessageRejected
import dev.kcoap.common.hexToBytes
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogRecord
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * What a peer sends is logged once, at DEBUG and without a stack trace (docs/logging.md): a UDP source address proves
 * nothing (RFC 7252 §11.4), so anyone can send what an endpoint rejects at network rate, and at WARN with a stack
 * trace each datagram would cost a line in a log that prints WARN by default. Each rejection is an event instead.
 */
class PeerInputLoggingTest {
    private val records = mutableListOf<LogRecord>()
    private val logs = LoggerFactory(LogConfig.build { level = LogLevel.DEBUG; sink = LogSink { records += it } })

    private fun StackFixture.logged(): CoapEndpointConfig = endpointConfig().toBuilder().apply { loggerFactory = logs }.build()

    /** Fails a send as the JDK's DatagramChannel does to port 0 (RFC 768: no reply port), or to [unreachable]. */
    private class FailingSends(private val inner: InMemoryTransport, private val unreachable: Endpoint? = null) : DatagramTransport by inner {
        val localEndpoint: Endpoint.Udp get() = inner.localEndpoint
        var failed = 0

        override suspend fun send(to: Endpoint, datagram: ByteArray) {
            if ((to as? Endpoint.Udp)?.port == 0) {
                failed++
                throw Exception("Can't send to port 0")
            }
            if (to == unreachable) {
                failed++
                throw Exception("Network is unreachable")
            }
            inner.send(to, datagram)
        }
    }

    private fun assertNothingAtWarn() {
        assertTrue(records.none { it.level >= LogLevel.WARN }, "${records.filter { it.level >= LogLevel.WARN }}")
    }

    @Test
    fun rfc768APingFromSourcePort0IsDroppedWithoutAnAnswerOrAWarning() = runTest {
        val fixture = StackFixture(this)
        val transport = FailingSends(fixture.transport())
        val endpoint = CoapEndpoint(transport, backgroundScope, fixture.logged())
        endpoint.attach()
        val portZero = Endpoint.Udp("192.0.2.9", 0)

        // A 4-byte CoAP ping, which RFC 7252 §4.3 answers with a Reset, and a Confirmable request.
        fixture.network.spoof(portZero, transport.localEndpoint, UdpMessageCodec.encode(CoapMessage.empty(CoapMessageType.CON, 0x1234)))
        fixture.network.spoof(
            portZero,
            transport.localEndpoint,
            UdpMessageCodec.encode(CoapMessage.request(CoapCode.GET) { type = CoapMessageType.CON; messageId = 0x2345 }),
        )
        delay(1.seconds)

        assertEquals(0, transport.failed, "nothing was sent to port 0")
        val rejected = fixture.events.filterIsInstance<MessageRejected>()
        assertEquals(listOf(MessageRejected.Reason.INVALID_SOURCE, MessageRejected.Reason.INVALID_SOURCE), rejected.map { it.reason })
        assertNothingAtWarn()
        assertEquals(2, records.count { "source port 0" in it.message })
        assertTrue(records.all { it.error == null }, "$records")
    }

    @Test
    fun aSendTheNetworkRefusesIsAWarningWithoutAStackTrace() = runTest {
        val fixture = StackFixture(this)
        val unreachable = Endpoint.Udp("192.0.2.1", 5683)
        val transport = FailingSends(fixture.transport(), unreachable)
        val client = CoapClient(CoapEndpoint(transport, backgroundScope, fixture.logged()), CoapClientConfig { requestTimeout = 200.seconds })

        val error = runCatching { client.get("coap://192.0.2.1/x") }.exceptionOrNull()

        // A local condition, which retransmission covers: one WARN line per datagram, with the error's message only.
        assertEquals(CoapException.Reason.TRANSMISSION_TIMEOUT, (error as CoapException).reason)
        val warnings = records.filter { it.level == LogLevel.WARN }
        assertEquals(transport.failed, warnings.size)
        assertEquals(5, warnings.size, "the request and its MAX_RETRANSMIT retransmissions")
        assertTrue(warnings.all { it.error == null && it.message.endsWith("failed: Network is unreachable") }, "$warnings")
    }

    @Test
    fun rfc7252Section4_2RejectedDatagramsAreOneDebugLineEachWithoutAStackTrace() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.logged()) { resource("r") { get { CoapResponse.content("ok") } } }
        val address = fixture.address(server.endpoint)
        val peer = Endpoint.Udp("192.0.2.7", 5683)
        records.clear()

        // A Confirmable message with a format error (TKL 9 is reserved, §3), an ACK carrying a request, and a Reset
        // that is not Empty: one is answered with a Reset, the others are silently ignored (§4.2).
        fixture.network.spoof(peer, address, "49011111".hexToBytes())
        fixture.network.spoof(peer, address, UdpMessageCodec.encode(CoapMessage.request(CoapCode.GET) { type = CoapMessageType.ACK; messageId = 0x2222 }))
        fixture.network.spoof(
            peer,
            address,
            UdpMessageCodec.encode(CoapMessage.build { type = CoapMessageType.RST; code = CoapCode.CONTENT; messageId = 0x3333 }),
        )
        delay(1.seconds)

        val events = fixture.events.filter { it is EndpointEvent.MalformedMessage || it is MessageRejected }
        assertEquals(3, events.size, "$events")
        assertEquals(3, records.size, "one line per input: $records")
        assertTrue(records.all { it.level == LogLevel.DEBUG && it.error == null }, "$records")
    }
}
