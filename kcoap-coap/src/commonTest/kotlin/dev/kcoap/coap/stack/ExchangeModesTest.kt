package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ReliableFixture
import dev.kcoap.coap.StackFixture
import dev.kcoap.coap.codec.ReliableMessageCodec
import dev.kcoap.testkit.InMemoryByteStream
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The exchange layer under several responses per request and Q-Block pacing: the response modes of
 * [ClientExchange] ([ResponseMode.Single], [ResponseMode.Stream], [ResponseMode.Inbox]) and the body lease
 * that holds one NSTART slot for a whole Non-confirmable body (RFC 9177 §7.2). The server is a [RawPeer]
 * that answers with whatever the test builds, on virtual time.
 */
class ExchangeModesTest {
    private fun get(path: String = "big"): CoapMessage = CoapMessage.request(CoapCode.GET) { options { uriPath(path) } }

    /** Option 9999: odd, so critical (RFC 7252 §5.4.1), and unknown to the client. */
    private val unknownCritical = CoapOption(9999, byteArrayOf(1))

    private fun StackFixture.sentBy(endpoint: CoapEndpoint, type: CoapMessageType): List<CoapMessage> =
        sentBy(endpoint).filter { it.type == type }

    // ---------------------------------------------------------------- response modes

    @Test
    fun rfc9177Section4_4AnInboxExchangeReceivesSeveralResponsesOnOneTokenWithoutAReset() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)

        val exchange = endpoint.openExchange(peer.address, get(), confirmable = false, security = null, mode = ResponseMode.Inbox(inbox))
        val request = peer.receive()
        val types = listOf(CoapMessageType.NON, CoapMessageType.CON, CoapMessageType.NON, CoapMessageType.CON, CoapMessageType.NON)
        val responses = types.mapIndexed { index, type -> peer.response(request, type, "block $index") }
        responses.forEach { peer.send(it) }
        val events = List(responses.size) { inbox.receive() }

        assertEquals(responses.map { it.payloadAsText() }, events.map { assertIs<ExchangeEvent.Response>(it).message.payloadAsText() })
        assertTrue(events.all { it.exchange === exchange })
        assertTrue(events.first().toString().startsWith("Response("))
        assertTrue(exchange.isOpen, "the exchange stays open for more")
        assertEquals(1, endpoint.exchangeCount())
        assertEquals(emptyList(), fixture.sentBy(endpoint, CoapMessageType.RST))
        assertEquals(
            responses.filter { it.type == CoapMessageType.CON }.map { it.messageId },
            fixture.sentBy(endpoint, CoapMessageType.ACK).map { it.messageId },
            "each Confirmable response is acknowledged",
        )
        assertEquals(0, inbox.lost)

        // RFC 9177 §4.3: a client that is done with a body forgets its tokens, so a late response gets a Reset.
        exchange.cancel()
        assertEquals(0, endpoint.exchangeCount())
        val late = peer.response(request, CoapMessageType.NON, "late")
        peer.send(late)
        delay(1.seconds)
        assertEquals(listOf(late.messageId), fixture.sentBy(endpoint, CoapMessageType.RST).map { it.messageId })
        assertNull(inbox.tryReceive())
    }

    @Test
    fun rfc9177Figure18AConfirmableInboxExchangeStaysOpenAfterAPiggybackedResponse() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)

        val exchange = endpoint.openExchange(peer.address, get(), confirmable = true, security = null, mode = ResponseMode.Inbox(inbox))
        val request = peer.receive()
        peer.send(
            CoapMessage.response(request, CoapCode.CONTENT) {
                type = CoapMessageType.ACK
                messageId = request.messageId
                payload("block 0")
            },
        )
        val separate = listOf(peer.response(request, CoapMessageType.CON, "block 1"), peer.response(request, CoapMessageType.CON, "block 2"))
        separate.forEach { peer.send(it) }
        val events = List(3) { inbox.receive() }

        assertEquals(listOf("block 0", "block 1", "block 2"), events.map { assertIs<ExchangeEvent.Response>(it).message.payloadAsText() })
        assertTrue(exchange.isOpen, "the piggybacked response does not end an inbox exchange")
        assertEquals(1, endpoint.exchangeCount())
        assertEquals(separate.map { it.messageId }, fixture.sentBy(endpoint, CoapMessageType.ACK).map { it.messageId })
        assertEquals(emptyList(), fixture.sentBy(endpoint, CoapMessageType.RST))
        delay(endpoint.parameters.maxTransmitWait)
        assertEquals(1, fixture.sentBy(endpoint, CoapMessageType.CON).size, "the piggybacked response ended the retransmission")
        exchange.cancel()
    }

    @Test
    fun rfc9177AppendixBAnInboxExchangeOnAConnectionReceivesSeveralResponsesOnOneToken() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server {
            resource("big") {
                get {
                    delay(1000.seconds)
                    CoapResponse.content("late")
                }
            }
        }
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)

        val exchange = endpoint.openExchange(Endpoint.Tcp("127.0.0.1", 5683), get(), false, null, ResponseMode.Inbox(inbox))
        runCurrent()
        val clientSide = fixture.connections.connections().first() as InMemoryByteStream
        repeat(3) { index ->
            val response = CoapMessage.build {
                code = CoapCode.CONTENT
                token = exchange.token
                payload("block $index")
            }
            clientSide.inject(ReliableMessageCodec.Tcp.encode(response))
        }
        val events = List(3) { inbox.receive() }

        assertEquals(listOf("block 0", "block 1", "block 2"), events.map { assertIs<ExchangeEvent.Response>(it).message.payloadAsText() })
        assertTrue(events.all { it.exchange === exchange })
        assertTrue(exchange.isOpen, "the first response on a connection does not end an inbox exchange")
        assertEquals(1, endpoint.exchangeCount())
        assertEquals(0, inbox.lost)
        exchange.cancel()
        assertEquals(0, endpoint.exchangeCount())
    }

    @Test
    fun rfc7252Section5_2ASingleExchangeEndsWithItsFirstResponseAndResetsTheNext() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()

        val exchange = endpoint.openExchange(peer.address, get(), confirmable = false, security = null)
        assertEquals(ResponseMode.Single, exchange.mode)
        val request = peer.receive()
        peer.send(peer.response(request, CoapMessageType.NON, "first"))
        assertEquals("first", endpoint.awaitResponse(exchange, 10.seconds).payloadAsText())
        val second = peer.response(request, CoapMessageType.CON, "second")
        peer.send(second)
        delay(1.seconds)

        assertEquals(listOf(second.messageId), fixture.sentBy(endpoint, CoapMessageType.RST).map { it.messageId })
        assertFalse(exchange.isOpen)
    }

    @Test
    fun rfc7252Section4_3AnInboxExchangeReportsAResetAndEnds() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)

        val exchange = endpoint.openExchange(peer.address, get(), false, null, ResponseMode.Inbox(inbox))
        peer.send(CoapMessage.empty(CoapMessageType.RST, peer.receive().messageId))
        val event = assertIs<ExchangeEvent.Reset>(inbox.receive())

        assertSame(exchange, event.exchange)
        assertTrue(event.toString().startsWith("Reset("))
        assertFalse(exchange.isOpen)
        assertEquals(0, endpoint.exchangeCount())
    }

    @Test
    fun rfc7252Section4_2AnInboxExchangeReportsATimeoutAndEnds() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)

        val exchange = endpoint.openExchange(peer.address, get(), true, null, ResponseMode.Inbox(inbox))
        val event = assertIs<ExchangeEvent.TimedOut>(inbox.receive())

        assertSame(exchange, event.exchange)
        assertTrue(event.toString().startsWith("TimedOut("))
        assertTrue(fixture.now <= endpoint.parameters.maxTransmitWait, "given up within MAX_TRANSMIT_WAIT: ${fixture.now}")
        assertEquals(5, fixture.sentBy(endpoint, CoapMessageType.CON).size, "the request and MAX_RETRANSMIT retransmissions")
        assertFalse(exchange.isOpen)
        assertEquals(0, endpoint.exchangeCount())
    }

    @Test
    fun anInboxExchangeReportsTheEndpointClosing() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)
        val exchange = endpoint.openExchange(peer.address, get(), false, null, ResponseMode.Inbox(inbox))

        endpoint.close()
        val event = assertIs<ExchangeEvent.Failed>(inbox.receive())

        assertSame(exchange, event.exchange)
        assertEquals(CoapException.Reason.CLOSED, event.cause.reason)
        assertTrue(event.toString().contains("CLOSED"))
        assertNull(inbox.tryReceive(), "one event per exchange")
    }

    @Test
    fun rfc7252Section5_4_1AnInboxExchangeRejectsAResponseWithAnUnknownCriticalOption() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)

        endpoint.openExchange(peer.address, get(), false, null, ResponseMode.Inbox(inbox))
        val request = peer.receive()
        val good = peer.response(request, CoapMessageType.CON, "good")
        val bad = peer.response(request, CoapMessageType.CON, "bad") { options { add(unknownCritical) } }
        peer.send(good)
        peer.send(bad)

        assertEquals("good", assertIs<ExchangeEvent.Response>(inbox.receive()).message.payloadAsText())
        val failure = assertIs<ExchangeEvent.Failed>(inbox.receive())
        assertEquals(CoapException.Reason.BAD_RESPONSE, failure.cause.reason)
        assertEquals(listOf(good.messageId), fixture.sentBy(endpoint, CoapMessageType.ACK).map { it.messageId })
        assertEquals(listOf(bad.messageId), fixture.sentBy(endpoint, CoapMessageType.RST).map { it.messageId })
        assertEquals(0, endpoint.exchangeCount())
    }

    @Test
    fun aFullInboxCountsFurtherEventsAsLost() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox(capacity = 2)

        val exchange = endpoint.openExchange(peer.address, get(), false, null, ResponseMode.Inbox(inbox))
        val request = peer.receive()
        repeat(4) { peer.send(peer.response(request, CoapMessageType.NON, "block $it")) }
        delay(1.seconds)

        assertEquals(2, inbox.lost)
        assertEquals(listOf("block 0", "block 1"), List(2) { (inbox.tryReceive() as ExchangeEvent.Response).message.payloadAsText() })
        assertNull(inbox.tryReceive())
        assertTrue(exchange.isOpen, "a lost response does not end the exchange")
        assertTrue(inbox.toString().contains("lost=2"))
        assertEquals(emptyList(), fixture.sentBy(endpoint, CoapMessageType.RST))
    }

    @Test
    fun rfc9177Section7_1AConfirmableInboxExchangeReportsAnEmptyAckAndStaysOpen() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)

        val exchange = endpoint.openExchange(peer.address, get(), confirmable = true, security = null, mode = ResponseMode.Inbox(inbox))
        val request = peer.receive()
        peer.send(CoapMessage.empty(CoapMessageType.ACK, request.messageId))
        val acknowledged = assertIs<ExchangeEvent.Acknowledged>(inbox.receive())
        peer.send(peer.response(request, CoapMessageType.CON, "separate"))
        val response = assertIs<ExchangeEvent.Response>(inbox.receive())

        assertSame(exchange, acknowledged.exchange)
        assertTrue(acknowledged.toString().startsWith("Acknowledged("))
        assertEquals("separate", response.message.payloadAsText())
        assertTrue(exchange.isOpen)
        exchange.cancel()
    }

    @Test
    fun aTimedReceiveReturnsNullWithoutTakingAnEventAndOtherwiseTheNextEvent() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)
        endpoint.openExchange(peer.address, get(), false, null, ResponseMode.Inbox(inbox))
        val request = peer.receive()

        assertNull(inbox.receive(2.seconds))
        assertEquals(2.seconds, fixture.now)
        val late = async { inbox.receive(10.seconds) }
        delay(1.seconds)
        peer.send(peer.response(request, CoapMessageType.NON, "in time"))

        assertEquals("in time", assertIs<ExchangeEvent.Response>(late.await()).message.payloadAsText())
        assertEquals(3.seconds, fixture.now)
        assertNull(inbox.tryReceive())
    }

    @Test
    fun anInboxExchangeHasNoResponsesToAwait() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val exchange = endpoint.openExchange(peer.address, get(), false, null, ResponseMode.Inbox(ExchangeInbox(1)))

        assertFailsWith<IllegalStateException> { exchange.awaitResponse() }
        assertFailsWith<IllegalStateException> { exchange.receiveCatching() }
        assertEquals("Inbox", exchange.mode.toString())
        assertTrue(exchange.toString().contains("mode=Inbox"))
        assertEquals(48, ExchangeInbox.forBody(TransmissionParameters()).capacity, "4 × MAX_PAYLOADS + 8")
        assertFailsWith<IllegalArgumentException> { ExchangeInbox(0) }
        exchange.cancel()
    }

    // ---------------------------------------------------------------- body leases

    @Test
    fun rfc9177Section7_2TheMessagesOfABodyShareItsLeaseInsteadOfNstart() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val inbox = ExchangeInbox.forBody(endpoint.parameters)

        val lease = endpoint.leaseBody(peer.address, 3000)
        assertEquals(BodyLease.State.HELD, lease.state)
        repeat(10) {
            endpoint.openExchange(peer.address, get("block$it"), false, null, ResponseMode.Inbox(inbox), pacing = Pacing.Body(lease))
        }
        // An ordinary Non-confirmable request takes a slot of its own, so it waits for the body (NSTART 1).
        endpoint.openExchange(peer.address, get("other"), false, null)
        delay(10.seconds)
        val burst = fixture.traffic.filter { it.from == fixture.address(endpoint) }
        assertEquals(10, burst.size, "one burst of MAX_PAYLOADS blocks")
        assertTrue(burst.all { it.at == Duration.ZERO })

        endpoint.releaseBody(lease)
        delay(1.seconds)
        val other = fixture.traffic.last { it.from == fixture.address(endpoint) }
        assertEquals("/other", other.message?.options?.path)
        assertEquals(10.seconds, other.at, "it went when the body ended")
        assertEquals(BodyLease.State.RELEASED, lease.state)
        assertEquals(0, endpoint.leaseCount())
    }

    @Test
    fun rfc9177Section7_2ASecondBodyWaitsForTheFirstLease() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()

        val first = endpoint.leaseBody(peer.address, 3000)
        val second = async { endpoint.leaseBody(peer.address, 100) }
        delay(30.seconds)
        assertFalse(second.isCompleted)
        assertEquals(2, endpoint.leaseCount())

        endpoint.releaseBody(first)
        val lease = second.await()
        assertEquals(30.seconds, fixture.now)
        assertEquals(BodyLease.State.HELD, lease.state)
        endpoint.releaseBody(first)
        assertEquals(BodyLease.State.RELEASED, first.state, "releasing twice changes nothing")
        assertEquals(1, endpoint.leaseCount())
    }

    @Test
    fun rfc9177Section7_2ALeaseReleasesItselfAtTheBodyTimeAtProbingRate() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()

        val first = endpoint.leaseBody(peer.address, 100)
        val grantedAt = async {
            endpoint.leaseBody(peer.address, 100)
            fixture.now
        }

        assertEquals(100.seconds, grantedAt.await(), "100 bytes at PROBING_RATE 1 byte/s")
        assertEquals(BodyLease.State.EXPIRED, first.state)
    }

    @Test
    fun rfc9177Section7_2ALeaseOfALargeBodyIsCappedAtNonProbingWait() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()

        val drawn = endpoint.leaseBody(fixture.transport().localEndpoint, 3000)
        assertTrue(drawn.nonTimeoutRandom in 2.seconds..3.seconds, "NON_TIMEOUT_RANDOM ${drawn.nonTimeoutRandom}")
        assertEquals(245.seconds + drawn.nonTimeoutRandom, drawn.hold)

        endpoint.leaseBody(peer.address, 3000, nonTimeoutRandom = 2.seconds)
        val grantedAt = async {
            endpoint.leaseBody(peer.address, 100)
            fixture.now
        }
        // 3000 bytes at 1 byte/s would be 3000 s; NON_PROBING_WAIT caps it at 245 s + NON_TIMEOUT_RANDOM.
        assertEquals(247.seconds, grantedAt.await())
    }

    @Test
    fun aCallerCancelledWhileWaitingForALeaseLeavesTheQueue() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()

        val first = endpoint.leaseBody(peer.address, 3000)
        val waiting = launch { endpoint.leaseBody(peer.address, 100) }
        delay(1.seconds)
        assertEquals(2, endpoint.leaseCount())
        waiting.cancelAndJoin()
        assertEquals(1, endpoint.leaseCount())

        endpoint.releaseBody(first)
        assertEquals(0, endpoint.leaseCount())
        endpoint.openExchange(peer.address, get(), true, null)
        assertEquals(CoapMessageType.CON, peer.receive().type)
        assertEquals(1.seconds, fixture.now, "the slot is free at once")
    }

    @Test
    fun closingTheEndpointFailsLeasesAndTheirWaiters() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()

        val first = endpoint.leaseBody(peer.address, 3000)
        val waiting = async { runCatching { endpoint.leaseBody(peer.address, 100) } }
        delay(1.seconds)
        endpoint.close()

        assertEquals(CoapException.Reason.CLOSED, assertIs<CoapException>(waiting.await().exceptionOrNull()).reason)
        assertEquals(BodyLease.State.FAILED, first.state)
        assertEquals(CoapException.Reason.CLOSED, assertFailsWith<CoapException> { endpoint.leaseBody(peer.address, 100) }.reason)
    }

    @Test
    fun rfc7252Section9_1_1AnEndedSessionFailsLeasesAndTheirWaiters() = runTest {
        val fixture = StackFixture(this)
        val endpoint = fixture.endpoint()
        val session = Endpoint.Secure(SessionId(1), 0, Endpoint.Udp("127.0.0.1", 9), PeerIdentity.None)

        val first = endpoint.leaseBody(session, 3000)
        val waiting = async { runCatching { endpoint.leaseBody(session, 100) } }
        delay(1.seconds)
        (endpoint.transport as InMemoryTransport).inject(DatagramEvent.SessionEnded(session, SessionEndReason.CLOSED_BY_PEER))

        assertEquals(CoapException.Reason.SESSION_ENDED, assertIs<CoapException>(waiting.await().exceptionOrNull()).reason)
        assertEquals(BodyLease.State.FAILED, first.state)
        val error = assertFailsWith<CoapException> {
            endpoint.openExchange(session, get(), false, null, pacing = Pacing.Body(first))
        }
        assertEquals(CoapException.Reason.SESSION_ENDED, error.reason)
        assertEquals(0, endpoint.exchangeCount())
        assertEquals(0, endpoint.leaseCount())
    }

    @Test
    fun rfc9177Section7_1AConfirmableBodyTakesNoLeaseAndAMessageNeedsAUsableOne() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val other = fixture.transport().localEndpoint
        val endpoint = fixture.endpoint()
        val lease = endpoint.leaseBody(peer.address, 100)

        assertFailsWith<IllegalArgumentException> {
            endpoint.openExchange(peer.address, get(), confirmable = true, security = null, pacing = Pacing.Body(lease))
        }
        assertFailsWith<IllegalArgumentException> { endpoint.openExchange(other, get(), false, null, pacing = Pacing.Body(lease)) }
        val notGranted = BodyLease(peer.address, 1.seconds, 2.seconds)
        assertFailsWith<IllegalStateException> { endpoint.openExchange(peer.address, get(), false, null, pacing = Pacing.Body(notGranted)) }

        delay(101.seconds)
        assertEquals(BodyLease.State.EXPIRED, lease.state)
        endpoint.openExchange(peer.address, get("late"), false, null, pacing = Pacing.Body(lease))
        assertEquals("/late", peer.receive().options.path, "a body may go on after its lease expired")

        endpoint.releaseBody(lease)
        assertFailsWith<IllegalStateException> { endpoint.openExchange(peer.address, get(), false, null, pacing = Pacing.Body(lease)) }
        assertEquals(1, endpoint.exchangeCount(), "the refused requests left nothing behind")
        assertTrue(Pacing.Body(lease).toString().contains("RELEASED"))
        assertFailsWith<IllegalArgumentException> { endpoint.leaseBody(peer.address, -1) }
    }

    /**
     * RFC 8323 §2 has no NSTART: the lease towards a peer on a connection is [ConnectionLink.lease], granted at
     * once without opening the connection, which the body's first message does.
     */
    @Test
    fun rfc8323Section2ALeaseTowardsAReliablePeerIsGrantedAtOnceAndPacesNothing() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("x") { get { CoapResponse.content("x") } } }
        val endpoint = fixture.endpoint()
        val tcp = Endpoint.Tcp("127.0.0.1", 5683)

        val lease = endpoint.leaseBody(tcp, 3000)
        assertFalse(lease.isPaced)
        assertEquals(BodyLease.State.HELD, lease.state)
        assertTrue(lease.granted.isCompleted)
        assertEquals(Duration.ZERO, lease.nonTimeoutRandom)
        assertEquals(2.seconds, endpoint.leaseBody(tcp, 3000, nonTimeoutRandom = 2.seconds).nonTimeoutRandom)
        assertEquals(0, endpoint.leaseCount())
        runCurrent()
        assertTrue(fixture.connections.connections().isEmpty(), "no connection opened for a lease")
        endpoint.releaseBody(lease)
        assertEquals(BodyLease.State.RELEASED, lease.state)
        endpoint.releaseBody(lease)
        assertEquals(BodyLease.State.RELEASED, lease.state, "idempotent")

        endpoint.close()
        assertEquals(CoapException.Reason.CLOSED, assertFailsWith<CoapException> { endpoint.leaseBody(tcp, 100) }.reason)
    }

    @Test
    fun rfc7252Section11_3ALeaseCountsAgainstMaxExchangesPerPeer() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val events = mutableListOf<EndpointEvent>()
        val config = CoapEndpointConfig(timeSource = testScheduler.timeSource, maxExchangesPerPeer = 1, eventListener = { events += it })
        val endpoint = CoapEndpoint(fixture.transport(), backgroundScope, config)

        endpoint.leaseBody(peer.address, 100)
        val error = assertFailsWith<CoapException> { endpoint.leaseBody(peer.address, 100) }

        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, error.reason)
        assertTrue(events.any { it is EndpointEvent.LimitReached && it.limit == EndpointEvent.LimitReached.Limit.EXCHANGES_PER_PEER })
        assertEquals(1, endpoint.leaseCount())
    }
}
