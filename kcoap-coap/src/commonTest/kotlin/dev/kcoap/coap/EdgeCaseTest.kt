package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.EndpointResolver
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.CoapResource
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.PlatformCsprng
import dev.kcoap.coap.stack.TokenGenerator
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionEndedException
import dev.kcoap.transport.SessionId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class EdgeCaseTest {
    /** Wraps a transport to fail on demand, as a DTLS layer or a broken socket would. */
    private class FaultyTransport(private val inner: InMemoryTransport) : DatagramTransport {
        var sendFailure: ((Endpoint) -> Exception?) = { null }
        var receiveFailure: Exception? = null
        override val maxDatagramSize: Int get() = inner.maxDatagramSize
        override suspend fun send(to: Endpoint, datagram: ByteArray) {
            sendFailure(to)?.let { throw it }
            inner.send(if (to is Endpoint.Secure) to.via else to, datagram)
        }

        override suspend fun receive(): DatagramEvent {
            receiveFailure?.let { throw it }
            return inner.receive()
        }

        override suspend fun close() = inner.close()
    }

    private val session = Endpoint.Secure(SessionId(5), 2, Endpoint.Udp("127.0.0.1", 9), PeerIdentity.None)

    @Test
    fun rfc7252Section9_1_1ASendToAnEndedSessionFailsTheExchangeAtOnce() = runTest {
        val fixture = StackFixture(this)
        val transport = FaultyTransport(fixture.transport())
        transport.sendFailure = { to -> if (to is Endpoint.Secure) SessionEndedException(to, SessionEndReason.ALERT) else null }
        val endpoint = CoapEndpoint(transport, backgroundScope, fixture.endpointConfig())

        val exchange = endpoint.openExchange(session, CoapMessage.request(CoapCode.GET), true, null)
        val error = assertFailsWith<CoapException> { endpoint.awaitResponse(exchange, 10.seconds) }
        assertEquals(CoapException.Reason.SESSION_ENDED, error.reason)
        assertTrue(fixture.events.any { it is EndpointEvent.SessionEnded })
    }

    @Test
    fun aFailedSendIsCoveredByRetransmission() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("ok") } } }
        val transport = FaultyTransport(fixture.transport())
        var failures = 1
        transport.sendFailure = { if (failures-- > 0) IllegalStateException("network down") else null }
        val client = CoapClient(CoapEndpoint(transport, backgroundScope, fixture.endpointConfig()))

        assertEquals("ok", client.get(fixture.uri(server, "/r")).payloadAsText())
        assertTrue(fixture.events.any { it is EndpointEvent.Retransmitted })
    }

    @Test
    fun aTransportThatCannotReceiveClosesTheEndpointWithItsError() = runTest {
        val fixture = StackFixture(this)
        val transport = FaultyTransport(fixture.transport())
        transport.receiveFailure = IllegalStateException("socket gone")
        val endpoint = CoapEndpoint(transport, backgroundScope, fixture.endpointConfig())
        endpoint.attach()
        delay(1.seconds)
        assertEquals(CoapException.Reason.TRANSPORT_FAILED, endpoint.closeCause?.reason)
        assertEquals("socket gone", endpoint.closeCause?.cause?.message)
        assertTrue(endpoint.toString().contains("CoapEndpoint"))
        endpoint.close()
        assertEquals(CoapException.Reason.TRANSPORT_FAILED, endpoint.awaitClosed().reason, "the first transition stays")
    }

    @Test
    fun rfc7252Section5_3_1TokensInUseAreNotReusedTowardsAPeer() = runTest {
        val fixture = StackFixture(this)
        val constant = TokenGenerator { byteArrayOf(7) }
        val client = fixture.client(endpointConfig = fixture.endpointConfig(tokenGenerator = constant))

        val first = async { runCatching { client.get("coap://127.0.0.1:9/x") } }
        delay(1.seconds)
        val error = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/y") }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, error.reason)
        // Another peer may use the same token.
        val elsewhere = async { runCatching { client.get("coap://127.0.0.1:10/z") } }
        delay(1.seconds)
        assertTrue(fixture.traffic.count { it.message?.tokenHex == "07" } >= 2)
        first.cancel()
        elsewhere.cancel()
    }

    @Test
    fun tokenGeneratorsProduceTheRequestedTokens() {
        assertEquals(4, TokenGenerator.random(4, Random(1)).next(Endpoint.Udp("127.0.0.1", 1)).size)
        assertEquals(8, TokenGenerator.random().next(Endpoint.Udp("127.0.0.1", 1)).size, "secure random by default")
        assertFailsWith<IllegalArgumentException> { TokenGenerator.random(9) }
        assertFailsWith<IllegalArgumentException> { TokenGenerator.sessionSequence(-1) }
        // RFC 9175 §4.2: per DTLS session, a sequence number starting at zero.
        val sequence = TokenGenerator.sessionSequence(random = Random(1))
        assertEquals(listOf(0, 1, 1), listOf(sequence.next(session), sequence.next(session), sequence.next(session)).map { it.size })
        sequence.forget(session)
        assertEquals(0, sequence.next(session).size)
        assertEquals(8, sequence.next(Endpoint.Udp("127.0.0.1", 1)).size)
        TokenGenerator { ByteArray(0) }.forget(session)
    }

    @Test
    fun thePlatformCsprngFillsExactlyTheRangeItIsGiven() {
        val sentinel: Byte = 0x55
        for (size in 0..40) {
            val array = ByteArray(size + 4) { sentinel }
            assertSame(array, PlatformCsprng.nextBytes(array, 2, size + 2))
            assertEquals(listOf(sentinel, sentinel), array.take(2), "before the range")
            assertEquals(listOf(sentinel, sentinel), array.takeLast(2), "after the range")
            if (size >= 8) assertTrue(array.copyOfRange(2, size + 2).any { it != sentinel }, "random bytes for $size")
        }
        // 15 random bytes come from each UUID; 16 take a second one, and two draws differ.
        assertFalse(PlatformCsprng.nextBytes(16).contentEquals(PlatformCsprng.nextBytes(16)))
        assertEquals(0, PlatformCsprng.nextBits(0))
        assertTrue(PlatformCsprng.nextInt(10) in 0 until 10)
        assertFailsWith<IllegalArgumentException> { PlatformCsprng.nextBytes(ByteArray(4), 3, 2) }
        assertFailsWith<IllegalArgumentException> { PlatformCsprng.nextBytes(ByteArray(4), 0, 5) }
        assertFailsWith<IllegalArgumentException> { PlatformCsprng.nextBytes(ByteArray(4), -1, 2) }
    }

    @Test
    fun theEndpointConfigurationKeepsACopyOfItsInterceptors() {
        val interceptors = mutableListOf<MessageInterceptor>(object : MessageInterceptor {})
        val config = CoapEndpointConfig { this.interceptors = interceptors }
        interceptors.clear()
        assertEquals(1, config.interceptors.size)
    }

    @Test
    fun rfc9175Section4_2EveryEndpointOfOneConfigurationHasItsOwnTokenSequence() = runTest {
        val fixture = StackFixture(this)
        val shared = CoapEndpointConfig { timeSource = testScheduler.timeSource }
        assertNull(shared.tokenGenerator)
        val first = CoapEndpoint(FaultyTransport(fixture.transport()), backgroundScope, shared)
        val second = CoapEndpoint(FaultyTransport(fixture.transport()), backgroundScope, shared)

        val a = first.openExchange(session, CoapMessage.request(CoapCode.GET), true, null)
        val b = second.openExchange(session, CoapMessage.request(CoapCode.GET), true, null)
        assertEquals(0, a.token.size, "sequence number 0 of the first endpoint")
        assertEquals(0, b.token.size, "sequence number 0 of the second endpoint, not 1 of a shared one")
        a.cancel()
        b.cancel()
    }

    @Test
    fun aTokenGeneratorMustStayWithinEightBytes() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client(endpointConfig = fixture.endpointConfig(tokenGenerator = TokenGenerator { ByteArray(9) }))
        assertFailsWith<IllegalArgumentException> { client.get("coap://127.0.0.1:9/x") }
    }

    @Test
    fun aFailingProtectionFailsTheRequestWithSecurityAndSendsNothing() = runTest {
        val fixture = StackFixture(this)
        val security = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest =
                throw MessageSecurityException("No context for $peer")
        }
        val client = fixture.client(CoapClientConfig { this.security = security })
        val error = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/x") }
        assertEquals(CoapException.Reason.SECURITY, error.reason)
        assertIs<MessageSecurityException>(error.cause)
        assertEquals(0, fixture.traffic.size)
        assertEquals(0, client.endpoint.exchangeCount(), "the token is free again")
    }

    @Test
    fun aSecurityLayerReturningNullSendsUnprotected() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("plain") } } }
        val security = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? = null
        }
        val client = fixture.client(CoapClientConfig { this.security = security })
        assertEquals("plain", client.get(fixture.uri(server, "/r")).payloadAsText())
    }

    @Test
    fun rfc7252Section5_4_1AResponseWithAnUnrecognizedCriticalOptionIsRejected() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val client = fixture.client()
        val pending = async { runCatching { client.get("coap://${transport.localEndpoint.host}:${transport.localEndpoint.port}/x") } }
        val event = transport.receive() as DatagramEvent.Received
        val request = UdpMessageCodec.decode(event.datagram)
        val response = CoapMessage.response(request, CoapCode.CONTENT) {
            type = CoapMessageType.ACK
            messageId = request.messageId
            options { add(CoapOption(65001, byteArrayOf(1))) }
        }
        transport.send(event.from, UdpMessageCodec.encode(response))
        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason)
    }

    @Test
    fun rfc7959Section2_9_3A413WithASmallerSzxRestartsTheUploadOnce() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val client = fixture.client()
        val received = mutableListOf<BlockOption>()
        backgroundScope.async {
            while (true) {
                val event = transport.receive() as DatagramEvent.Received
                val request = UdpMessageCodec.decode(event.datagram)
                val block = request.options.block1!!
                received += block
                val response = CoapMessage.response(request, if (block.size > 64) CoapCode.REQUEST_ENTITY_TOO_LARGE else if (block.more) CoapCode.CONTINUE else CoapCode.CHANGED) {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                    options { block1(BlockOption(block.number, block.more, minOf(block.sizeExponent, 2))) }
                }
                transport.send(event.from, UdpMessageCodec.encode(response))
            }
        }

        val response = client.put("coap://${transport.localEndpoint.host}:${transport.localEndpoint.port}/x", ByteArray(2000))

        assertEquals(CoapCode.CHANGED, response.code)
        assertEquals(BlockOption(0, true, 6), received.first())
        assertEquals(BlockOption(0, true, 2), received[1], "started over with 64-byte blocks")
        assertEquals(2000 / 64 + 1, received.size - 1)
    }

    @Test
    fun aRequestWhoseOptionsLeaveNoRoomForABlockIsRefused() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client(maxDatagramSize = 80)
        val error = assertFailsWith<CoapException> {
            client.put("coap://127.0.0.1:9/x", ByteArray(500)) { options { repeat(6) { add(CoapOption(2000 + it * 2, ByteArray(8))) } } }
        }
        assertEquals(CoapException.Reason.MESSAGE_TOO_LARGE, error.reason)
    }

    @Test
    fun aBodyLargerThanTheServerAcceptsGets413EvenWithoutBlock1() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(CoapServerConfig(maxRequestBodySize = 50)) { resource("r") { put { CoapResponse.changed() } } }
        val raw = fixture.transport()
        val request = CoapMessage.request(CoapCode.PUT) {
            messageId = 1
            options { uriPath("r") }
            payload = ByteArray(100)
        }
        raw.send(fixture.address(server.endpoint), UdpMessageCodec.encode(request))
        val response = UdpMessageCodec.decode((raw.receive() as DatagramEvent.Received).datagram)
        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, response.code)
        assertEquals(50L, response.options.size1)
    }

    @Test
    fun rfc7641Section4_1OnlyRegisteredObserversGetObserve() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("x").withOptions { observe(5) } } } }
        val client = fixture.client()
        assertNull(client.get(fixture.uri(server, "/r")).options.observe)
    }

    @Test
    fun rfc7967NoResponseSuppressesErrorsToo() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.endpointConfig(separateResponseDelay = null)) { }
        val client = fixture.client(CoapClientConfig { requestTimeout = 5.seconds })
        val error = assertFailsWith<CoapException> { client.get(fixture.uri(server, "/missing")) { options { noResponse(8) } } }
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, error.reason)
        assertEquals(CoapCode.NOT_FOUND, client.get(fixture.uri(server, "/missing")) { options { noResponse(16) } }.code)
    }

    @Test
    fun rfc9175Section2_3AnObservationRegistrationAnswersTheEchoChallenge() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("fresh") {
                observable()
                get {
                    if (options.echo == null) {
                        CoapResponse.error(CoapCode.UNAUTHORIZED).withOptions { echo(byteArrayOf(1, 2)) }
                    } else {
                        CoapResponse.content("observed")
                    }
                }
            }
        }
        val client = fixture.client()
        assertEquals("observed", client.observe(fixture.uri(server, "/fresh")).first().payloadAsText())
    }

    @Test
    fun aServerAnswersEvenWhenAResourceMisbehavesOutsideItsHandler() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            add(object : CoapResource("odd") {
                override val recognizedOptions: Set<Int> get() = error("broken resource")
            })
        }
        val client = fixture.client()
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, client.get(fixture.uri(server, "/odd")).code)
    }

    @Test
    fun brokenListenersDoNotBreakTheEndpoint() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("ok") } } }
        val config = CoapEndpointConfig {
            timeSource = testScheduler.timeSource
            eventListener = EndpointEventListener { error("listener bug") }
            loggerFactory = fixture.loggerFactory
        }
        val client = CoapClient(fixture.transport(), backgroundScope, endpointConfig = config)
        val coin = Random(4)
        fixture.network.filter = { datagram -> datagram.from != fixture.address(server.endpoint) || coin.nextBoolean() }
        assertEquals("ok", client.get(fixture.uri(server, "/r")).payloadAsText())
        client.close()
    }

    @Test
    fun pingingCanBeCancelledAndFailsOnEndedSessions() = runTest {
        val fixture = StackFixture(this)
        val transport = FaultyTransport(fixture.transport())
        val endpoint = CoapEndpoint(transport, backgroundScope, fixture.endpointConfig())
        val pending = async { endpoint.ping(Endpoint.Udp("127.0.0.1", 9)) }
        delay(1.seconds)
        pending.cancel()
        delay(100.seconds)
        assertTrue(fixture.traffic.size <= 2)

        transport.sendFailure = { to -> if (to is Endpoint.Secure) SessionEndedException(to, SessionEndReason.CLOSED_LOCALLY) else null }
        val error = assertFailsWith<CoapException> { endpoint.ping(session) }
        assertEquals(CoapException.Reason.SESSION_ENDED, error.reason)
    }

    @Test
    fun configurationsAreValidated() {
        assertFailsWith<IllegalArgumentException> { CoapClientConfig { preferredBlockSize = 100 } }
        assertFailsWith<IllegalArgumentException> { CoapClientConfig { preferredBlockSize = 2048 } }
        assertFailsWith<IllegalArgumentException> { CoapClientConfig { requestTimeout = 0.seconds } }
        assertFailsWith<IllegalArgumentException> { CoapClientConfig { maxResponseBodySize = 0 } }
        assertFailsWith<IllegalArgumentException> { CoapClientConfig { securityReserve = -1 } }
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig { separateResponseDelay = (-1).milliseconds } }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(maxBlockSize = 100) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(maxRequestBodySize = 0) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(maxConcurrentTransfers = 0) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(confirmableNotificationInterval = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(nonConfirmableNotificationInterval = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(securityReserve = -1) }
        assertTrue(CoapClientConfig.Default.toString().startsWith("CoapClientConfig("))
        assertTrue(CoapServerConfig().toString().startsWith("CoapServerConfig("))
        assertTrue(CoapEndpointConfig.Default.toString().startsWith("CoapEndpointConfig("))
    }

    @Test
    fun theDefaultResolverAcceptsOnlyIpLiteralsAndLocalhostForDatagrams() = runTest {
        val resolver = EndpointResolver.Default
        assertEquals(Endpoint.Udp("::1", 5683), resolver.resolve(CoapUri.parse("coap://[0:0:0:0:0:0:0:1]")))
        assertEquals(Endpoint.Udp("127.0.0.1", 1234), resolver.resolve(CoapUri.parse("coap://localhost:1234")))
        assertFailsWith<IllegalArgumentException> { resolver.resolve(CoapUri.parse("coap://example.com")) }
        // RFC 3986 §3.2.2 and §7.4: a leading zero makes a name, which a resolver may read as octal.
        assertFailsWith<IllegalArgumentException> { resolver.resolve(CoapUri.parse("coap://010.0.0.1")) }
    }

    /** RFC 8323 §8: responses come on the connection, so names stay names for the connector to resolve. */
    @Test
    fun theDefaultResolverMapsReliableSchemesToConnectionEndpoints() = runTest {
        val resolver = EndpointResolver.Default
        assertEquals(Endpoint.Tcp("127.0.0.1", 5683), resolver.resolve(CoapUri.parse("coap+tcp://127.0.0.1")))
        assertEquals(Endpoint.Tcp("::1", 5684, usesTls = true), resolver.resolve(CoapUri.parse("coaps+tcp://[0::1]")))
        assertEquals(Endpoint.Tcp("example.com", 5683), resolver.resolve(CoapUri.parse("coap+tcp://example.com")))
        assertEquals(
            Endpoint.WebSocket("ws://example.com:80/.well-known/coap"),
            resolver.resolve(CoapUri.parse("coap+ws://example.com/sensors/temp?u=Cel")),
        )
        assertEquals(
            Endpoint.WebSocket("wss://[2001:db8::1]:8443/.well-known/coap"),
            resolver.resolve(CoapUri.parse("coaps+ws://[2001:DB8:0::1]:8443/")),
        )
    }

    @Test
    fun everythingHasAReadableToString() = runTest {
        val peer = Endpoint.Udp("192.0.2.1", 5683)
        val events = listOf(
            EndpointEvent.Retransmitted(peer, 1, 2), EndpointEvent.TransmissionTimedOut(peer, 1),
            EndpointEvent.ResetReceived(peer, 1), EndpointEvent.DuplicateReceived(peer, 1),
            EndpointEvent.MalformedMessage(peer, EndpointEvent.MalformedMessage.Reason.FORMAT_ERROR, "bad"),
            EndpointEvent.MessageRejected(peer, 1, EndpointEvent.MessageRejected.Reason.PING),
            EndpointEvent.SessionEnded(peer), EndpointEvent.ObserverAdded(peer, "/a"),
            EndpointEvent.ObserverRemoved(peer, "/a", EndpointEvent.ObserverRemoved.Reason.RESET),
            EndpointEvent.ObserverRemoved(peer, "/a", EndpointEvent.ObserverRemoved.Reason.FAILED, "why"),
        )
        events.forEach { assertTrue(it.toString().contains("192.0.2.1"), "$it") }
        assertTrue(CoapOptions.URI_PATH.toString() == "Uri-Path(11)")
        assertEquals(1, OptionSet.build { uriPath("a") }.size)

        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("r") {
                assertEquals(dev.kcoap.coap.server.ResourceAttributes.NONE, attributes)
                assertTrue(discoverable)
                get { CoapResponse.content(toString()) }
            }
        }
        val client = fixture.client()
        assertTrue(client.get(fixture.uri(server, "/r")).payloadAsText().startsWith("RequestContext(0.01 GET /r"))
        assertTrue(server.toString().startsWith("CoapServer("))
        assertTrue(client.toString().startsWith("CoapClient("))
        val plain = object : CoapResource("p") {}
        server.add(plain)
        assertEquals(dev.kcoap.coap.server.ResourceAttributes.NONE, plain.attributes)
        assertEquals(false, plain.isObservable)
    }

    @Test
    fun aServerOnItsOwnTransportClosesIt() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val server = CoapServer(transport, backgroundScope, endpointConfig = fixture.endpointConfig()) {
            resource("r") { get { CoapResponse.content("x") } }
        }
        val clientTransport = fixture.transport()
        val client = CoapClient(clientTransport, backgroundScope, endpointConfig = fixture.endpointConfig())
        assertEquals("x", client.get("coap://${transport.localEndpoint.host}:${transport.localEndpoint.port}/r").payloadAsText())
        server.close()
        client.close()
        assertTrue(transport.isClosed && clientTransport.isClosed)
    }
}
