package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.coap.stack.InboundRequest
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Amplification mitigation by address verification (RFC 9175 §2.4 item 3, §2.6) end to end on virtual
 * time: which responses wait for a verified address, the 4.01 with Echo that replaces them, how a client
 * proves its address, and which clients are never asked.
 */
class AmplificationTest {
    private val all = CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES)
    private val large = "abcdefghij".repeat(60)

    private fun StackFixture.limits(limit: Limit): List<Endpoint> =
        events.filterIsInstance<EndpointEvent.LimitReached>().filter { it.limit == limit }.map { it.peer }

    private fun request(
        path: String,
        messageId: Int,
        token: Byte,
        code: CoapCode = CoapCode.GET,
        type: CoapMessageType = CoapMessageType.CON,
        echo: ByteArray? = null,
        options: OptionSet.Builder.() -> Unit = {},
    ) = CoapMessage.request(code) {
        this.type = type
        this.messageId = messageId
        this.token = byteArrayOf(token)
        options {
            uriPath(path)
            echo?.let { echo(it) }
            options()
        }
    }

    private suspend fun receive(peer: InMemoryTransport): CoapMessage? {
        val event = withTimeoutOrNull(10.seconds) { peer.receive() } ?: return null
        return UdpMessageCodec.decode((event as DatagramEvent.Received).datagram)
    }

    private suspend fun answerTo(peer: InMemoryTransport, server: CoapServer, fixture: StackFixture, message: CoapMessage): CoapMessage? {
        peer.send(fixture.address(server.endpoint), UdpMessageCodec.encode(message))
        return receive(peer)
    }

    private fun size(message: CoapMessage): Int = UdpMessageCodec.encode(message).size

    @Test
    fun rfc9175Section2_4ALargeResponseToAnUnverifiedAddressBecomesAShort401WithEcho() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(all) { resource("large") { get { CoapResponse.content(large) } } }
        val peer = fixture.transport()

        val first = request("large", messageId = 1, token = 1)
        val challenge = assertNotNull(answerTo(peer, server, fixture, first))
        assertEquals(CoapMessageType.ACK, challenge.type, "piggybacked")
        assertEquals(CoapCode.UNAUTHORIZED, challenge.code)
        val echo = assertNotNull(challenge.options.echo)
        assertEquals(8, echo.size, "RFC 9175 §5: 64 unpredictable bits")
        assertTrue(challenge.payload.isEmpty())
        assertTrue(size(challenge) <= 3 * size(first), "a short response: ${size(challenge)} for ${size(first)} bytes")
        assertEquals(listOf<Endpoint>(peer.localEndpoint), fixture.limits(Limit.AMPLIFICATION_FACTOR))

        val repeat = assertNotNull(answerTo(peer, server, fixture, request("large", messageId = 2, token = 2, echo = echo)))
        assertEquals(CoapCode.CONTENT, repeat.code)
        assertEquals(large, repeat.payloadAsText())

        // RFC 9175 §2.4 item 3: needed only once per endpoint.
        val later = assertNotNull(answerTo(peer, server, fixture, request("large", messageId = 3, token = 3)))
        assertEquals(CoapCode.CONTENT, later.code)
        assertEquals(1, fixture.limits(Limit.AMPLIFICATION_FACTOR).size)
    }

    @Test
    fun rfc9175Section2_4ResponsesUpToThreeTimesTheRequestNeedNoVerifiedAddress() = runTest {
        val fixture = StackFixture(this)
        // A Confirmable GET with a 1-byte token and Uri-Path "a" takes 7 bytes; its ACK takes 6 bytes plus the payload.
        val server = fixture.server(all) {
            resource("a") { get { CoapResponse(CoapCode.CONTENT, payload = ByteArray(15)) } }
            resource("b") { get { CoapResponse(CoapCode.CONTENT, payload = ByteArray(16)) } }
        }
        val peer = fixture.transport()

        val toA = request("a", messageId = 1, token = 1)
        val atLimit = assertNotNull(answerTo(peer, server, fixture, toA))
        assertEquals(CoapCode.CONTENT, atLimit.code)
        assertEquals(3 * size(toA), size(atLimit), "exactly three times the request")

        val beyond = assertNotNull(answerTo(peer, server, fixture, request("b", messageId = 2, token = 2)))
        assertEquals(CoapCode.UNAUTHORIZED, beyond.code, "one byte more")
    }

    @Test
    fun rfc9175Section2_4AChallengeIsPiggybackedOrNonConfirmableNeverASeparateConfirmableResponse() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(all) {
            resource("slow") {
                get {
                    delay(5.seconds)
                    CoapResponse.content(large)
                }
            }
            resource("large") { get { CoapResponse.content(large) } }
            resource("slowSmall") {
                get {
                    delay(5.seconds)
                    CoapResponse.content("tiny")
                }
            }
        }
        val peer = fixture.transport()

        // Past the separate-response delay the Empty ACK has gone out, so the challenge cannot be piggybacked.
        val emptyAck = assertNotNull(answerTo(peer, server, fixture, request("slow", messageId = 1, token = 1)))
        assertEquals(CoapMessageType.ACK, emptyAck.type)
        assertEquals(CoapCode.EMPTY, emptyAck.code)
        val separate = assertNotNull(receive(peer))
        assertEquals(CoapCode.UNAUTHORIZED, separate.code)
        assertEquals(CoapMessageType.NON, separate.type, "Non-confirmable, so nothing is retransmitted to an unverified address")

        val toNon = assertNotNull(answerTo(peer, server, fixture, request("large", messageId = 2, token = 2, type = CoapMessageType.NON)))
        assertEquals(CoapCode.UNAUTHORIZED, toNon.code)
        assertEquals(CoapMessageType.NON, toNon.type)

        // Within the factor, so not held back; but five Confirmable copies would be five times the factor.
        val smallRequest = request("slowSmall", messageId = 3, token = 3)
        assertEquals(CoapCode.EMPTY, answerTo(peer, server, fixture, smallRequest)?.code)
        val small = assertNotNull(receive(peer))
        assertEquals("tiny", small.payloadAsText())
        assertTrue(size(small) <= 3 * size(smallRequest))
        assertEquals(CoapMessageType.NON, small.type, "a separate response to an unverified address is Non-confirmable too")

        // Once the address is verified, separate responses are Confirmable again.
        val echo = assertNotNull(separate.options.echo)
        assertEquals(CoapCode.EMPTY, answerTo(peer, server, fixture, request("slow", messageId = 4, token = 4, echo = echo))?.code)
        val verified = assertNotNull(receive(peer))
        assertEquals(CoapCode.CONTENT, verified.code)
        assertEquals(CoapMessageType.CON, verified.type)

        delay(300.seconds)
        val toUnverified = fixture.sentBy(server.endpoint).filter { it.code == CoapCode.UNAUTHORIZED || it.payloadAsText() == "tiny" }
        assertEquals(3, toUnverified.size, "never retransmitted")
        assertTrue(toUnverified.none { it.type == CoapMessageType.CON })
    }

    @Test
    fun rfc7252Section5_2_2AKcoapClientTakesANonConfirmableSeparateResponseToItsConfirmableRequest() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(all) {
            resource("slowSmall") {
                get {
                    delay(5.seconds)
                    CoapResponse.content("tiny")
                }
            }
        }
        val client = fixture.client()

        assertEquals("tiny", client.get(fixture.uri(server, "/slowSmall")).payloadAsText())
        val response = fixture.sentBy(server.endpoint).single { it.code == CoapCode.CONTENT }
        assertEquals(CoapMessageType.NON, response.type)
    }

    @Test
    fun rfc9175Section2_3AKcoapClientRepeatsWithTheEchoValueAndGetsTheWholeBody() = runTest {
        val fixture = StackFixture(this)
        val body = "0123456789".repeat(300)
        val server = fixture.server(all) { resource("large") { get { CoapResponse.content(body) } } }
        val client = fixture.client()

        assertEquals(body, client.get(fixture.uri(server, "/large")).payloadAsText())
        val challenge = fixture.sentBy(server.endpoint).single { it.code == CoapCode.UNAUTHORIZED }
        val repeat = fixture.sentBy(client.endpoint).single { it.options.echo != null }
        assertContentEquals(challenge.options.echo, repeat.options.echo)

        assertEquals(body, client.get(fixture.uri(server, "/large")).payloadAsText())
        assertEquals(1, fixture.sentBy(server.endpoint).count { it.code == CoapCode.UNAUTHORIZED }, "the address stays verified")
    }

    @Test
    fun rfc9175Section2_3AnEchoValueVerifiesOnlyTheAddressItWasSentTo() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(all) { resource("large") { get { CoapResponse.content(large) } } }
        val victim = fixture.transport()
        val attacker = fixture.transport()

        val echo = assertNotNull(answerTo(attacker, server, fixture, request("large", 1, 1))?.options?.echo)
        val forVictim = assertNotNull(answerTo(victim, server, fixture, request("large", 2, 2, echo = echo)))
        assertEquals(CoapCode.UNAUTHORIZED, forVictim.code, "the attacker's value proves nothing for another address")
        assertFalse(echo.contentEquals(forVictim.options.echo))

        val unknown = assertNotNull(answerTo(attacker, server, fixture, request("large", 3, 3, echo = byteArrayOf(1, 2, 3))))
        assertEquals(CoapCode.UNAUTHORIZED, unknown.code, "a value the server never sent")
        assertContentEquals(echo, unknown.options.echo, "the outstanding value again")

        assertEquals(CoapCode.CONTENT, answerTo(victim, server, fixture, request("large", 4, 4, echo = forVictim.options.echo))?.code)
    }

    @Test
    fun rfc9175Section2_4APostRepeatedWithEchoGetsItsHeldBackResponseWithoutRunningAgain() = runTest {
        val fixture = StackFixture(this)
        var posts = 0
        var puts = 0
        val server = fixture.server(all) {
            resource("orders") {
                post {
                    posts++
                    CoapResponse(CoapCode.CREATED, payload = "order $posts: $large".encodeToByteArray())
                }
                put {
                    puts++
                    CoapResponse(CoapCode.CHANGED, payload = large.encodeToByteArray())
                }
            }
        }
        val client = fixture.client()

        val created = client.post(fixture.uri(server, "/orders"), "pay".encodeToByteArray())
        assertEquals(CoapCode.CREATED, created.code)
        assertEquals("order 1: $large", created.payloadAsText())
        assertEquals(1, posts, "RFC 7252 §5.1: a POST must not take effect twice")
        assertEquals(1, fixture.sentBy(server.endpoint).count { it.code == CoapCode.UNAUTHORIZED })

        // An idempotent PUT simply runs again, from an address the POST did not verify.
        val other = fixture.client()
        assertEquals(CoapCode.CHANGED, other.put(fixture.uri(server, "/orders"), "x".encodeToByteArray()).code)
        assertEquals(2, puts)
    }

    @Test
    fun rfc9175Section2_4ConcurrentPostsOfOneClientEachRunTheirHandlerOnce() = runTest {
        val fixture = StackFixture(this)
        val runs = mutableMapOf<String, Int>()
        val server = fixture.server(all) {
            for (name in listOf("a", "b")) {
                resource(name) {
                    post {
                        runs[name] = (runs[name] ?: 0) + 1
                        CoapResponse(CoapCode.CREATED, payload = "$name: $large".encodeToByteArray())
                    }
                }
            }
        }
        val client = fixture.client()

        val a = async { client.post(fixture.uri(server, "/a"), "1".encodeToByteArray()) }
        val b = async { client.post(fixture.uri(server, "/b"), "2".encodeToByteArray()) }

        assertEquals("a: $large", a.await().payloadAsText())
        assertEquals("b: $large", b.await().payloadAsText())
        assertEquals(mapOf("a" to 1, "b" to 1), runs, "RFC 7252 §5.1: neither POST takes effect twice")
        assertEquals(2, fixture.sentBy(server.endpoint).count { it.code == CoapCode.UNAUTHORIZED }, "both were challenged")
    }

    @Test
    fun rfc9175Section2_4EveryPostChallengedBeforeAnyRepeatKeepsItsOwnResponse() = runTest {
        val fixture = StackFixture(this)
        var runs = 0
        val server = fixture.server(all) {
            resource("orders") {
                post {
                    runs++
                    CoapResponse(CoapCode.CREATED, payload = "order $runs for ${payloadAsText()}: $large".encodeToByteArray())
                }
            }
        }
        val peer = fixture.transport()
        fun post(messageId: Int, token: Byte, body: String, echo: ByteArray? = null) =
            request("orders", messageId, token, CoapCode.POST, echo = echo).toBuilder().apply { payload = body.encodeToByteArray() }.build()

        // Three POSTs, two of them identical, are all challenged before the client repeats any of them.
        val echo = assertNotNull(answerTo(peer, server, fixture, post(1, 1, "x"))?.options?.echo)
        assertContentEquals(echo, answerTo(peer, server, fixture, post(2, 2, "y"))?.options?.echo)
        assertContentEquals(echo, answerTo(peer, server, fixture, post(3, 3, "x"))?.options?.echo)
        assertEquals(3, runs)

        val repeats = listOf(post(4, 4, "y", echo), post(5, 5, "x", echo), post(6, 6, "x", echo))
            .map { assertNotNull(answerTo(peer, server, fixture, it)).payloadAsText().substringBefore(':') }
        assertEquals(listOf("order 2 for y", "order 1 for x", "order 3 for x"), repeats, "each repeat gets a response of its own")
        assertEquals(3, runs, "no handler ran again")
        assertTrue(fixture.limits(Limit.HELD_BACK_RESPONSES).isEmpty())
    }

    @Test
    fun aHeldBackResponseEvictedForRoomIsReported() = runTest {
        val fixture = StackFixture(this)
        var runs = 0
        val server = fixture.server(CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES, maxConcurrentTransfers = 1)) {
            resource("orders") {
                post {
                    runs++
                    CoapResponse(CoapCode.CREATED, payload = large.encodeToByteArray())
                }
            }
        }
        val victim = fixture.transport()
        val spoofed = fixture.transport()
        val post = request("orders", 1, 1, CoapCode.POST)

        val echo = assertNotNull(answerTo(victim, server, fixture, post)?.options?.echo)
        assertEquals(CoapCode.UNAUTHORIZED, answerTo(spoofed, server, fixture, request("orders", 2, 2, CoapCode.POST))?.code)
        assertEquals(listOf<Endpoint>(victim.localEndpoint), fixture.limits(Limit.HELD_BACK_RESPONSES))

        // The victim's repeat finds nothing kept, so its POST runs again; the event above is the trace of it.
        assertEquals(CoapCode.CREATED, answerTo(victim, server, fixture, request("orders", 3, 3, CoapCode.POST, echo = echo))?.code)
        assertEquals(3, runs)
    }

    private object Recipient : SecurityIdentity {
        override val description: String get() = "toy recipient"
    }

    /** Message security that only marks requests with an empty OSCORE option, enough to authenticate them. */
    private object ToySecurity : ServerMessageSecurity, ClientMessageSecurity {
        override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
            if (request.options.oscore == null) return null
            return object : UnprotectedRequest {
                override val message = request.toBuilder().options { remove(CoapOptions.OSCORE.number) }.build()
                override val identity: SecurityIdentity get() = Recipient

                override suspend fun protectResponse(response: CoapMessage): CoapMessage = response
            }
        }

        override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest = object : ProtectedRequest {
            override val message = request.toBuilder().options { oscore(ByteArray(0)) }.build()

            override suspend fun unprotectResponse(response: CoapMessage) = ResponseVerification.Verified(response)
        }
    }

    @Test
    fun rfc9175Section2_6ClientsThatMessageSecurityAuthenticatedAreNotChallenged() = runTest {
        val fixture = StackFixture(this)
        // Public, so plain requests reach the resource too (ADR 0008: the default refuses them).
        val server = fixture.server(CoapServerConfig(security = ToySecurity, amplification = AmplificationPolicy.ALL_RESPONSES, defaultAccess = AccessPolicy.Public)) {
            resource("large") { get { CoapResponse.content(large) } }
        }
        val protected = fixture.client(CoapClientConfig(security = ToySecurity))

        assertEquals(large, protected.get(fixture.uri(server, "/large")).payloadAsText())
        assertTrue(fixture.sentBy(server.endpoint).none { it.code == CoapCode.UNAUTHORIZED })

        // The same server still asks a client that sends plain requests.
        assertEquals(large, fixture.client().get(fixture.uri(server, "/large")).payloadAsText())
        assertEquals(1, fixture.sentBy(server.endpoint).count { it.code == CoapCode.UNAUTHORIZED })
    }

    @Test
    fun rfc9175Section2_6ClientsThatDtlsAuthenticatedAreNotChallenged() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(all) { resource("large") { get { CoapResponse.content(large) } } }
        val serverTransport = server.endpoint.transport as InMemoryTransport
        val session = Endpoint.Secure(SessionId(3), 0, Endpoint.Udp("127.0.0.1", 9), PeerIdentity.PreSharedKey(byteArrayOf(1)))

        serverTransport.inject(DatagramEvent.Received(session, UdpMessageCodec.encode(request("large", 1, 1))))
        delay(1.seconds)
        // The response went to the session, which the in-memory network cannot reach.
        assertEquals(CoapCode.CONTENT, fixture.traffic.last().message?.code)
        assertTrue(fixture.limits(Limit.AMPLIFICATION_FACTOR).isEmpty())
    }

    @Test
    fun rfc9175Section2_6ClientsOnConnectionsAreNotChallenged() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server(config = all) { resource("large") { get { CoapResponse.content(large) } } }
        val client = fixture.client()

        assertEquals(large, client.get(fixture.uri("/large")).payloadAsText())
        assertTrue(fixture.sent(CoapCode.UNAUTHORIZED).isEmpty(), "a connection's handshake already showed the address")
    }

    @Test
    fun byDefaultOnlyQBlockBurstsWaitSoSingleResponsesGoOutAsBefore() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("large") { get { CoapResponse.content(large) } } }
        val peer = fixture.transport()

        assertEquals(CoapCode.CONTENT, answerTo(peer, server, fixture, request("large", 1, 1))?.code)
        assertTrue(fixture.limits(Limit.AMPLIFICATION_FACTOR).isEmpty())
    }

    @Test
    fun theBurstGateAsksOnlyForTheAddressesOfUnauthenticatedDatagramClients() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("large") { get { CoapResponse.content(large) } } }
        val peer = fixture.transport()
        val request = request("large", 1, 1)
        val inbound = InboundRequest(server.endpoint.datagramLinkTo(peer.localEndpoint), request)
        val burst = List(10) { CoapResponse.content(large).toMessage(request.token) }
        val probeAnswer = listOf(CoapResponse(CoapCode.CONTENT, payload = ByteArray(16)).toMessage(request.token))
        val authenticated = assertNotNull(ToySecurity.unprotectRequest(ToySecurity.protectRequest(request, peer.localEndpoint).message, peer.localEndpoint))

        // QB-3 calls only this, with isBurst = true, under the default Q_BLOCK_BURSTS.
        assertTrue(server.mustVerifyAddress(inbound, request, null, burst, isBurst = true))
        assertFalse(server.mustVerifyAddress(inbound, request, authenticated, burst, isBurst = true), "RFC 9175 §2.6: OSCORE authenticated it")
        assertFalse(server.mustVerifyAddress(inbound, request, null, probeAnswer, isBurst = true), "within the factor")
        assertFalse(server.mustVerifyAddress(inbound, request, null, burst, isBurst = false), "single responses are not checked by default")
        val session = Endpoint.Secure(SessionId(3), 0, peer.localEndpoint, PeerIdentity.PreSharedKey(byteArrayOf(1)))
        assertFalse(server.mustVerifyAddress(InboundRequest(server.endpoint.datagramLinkTo(session), request), request, null, burst, isBurst = true), "DTLS")
    }

    @Test
    fun withAmplificationOffNothingIsChallengedAndEchoIsIgnored() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(CoapServerConfig(amplification = AmplificationPolicy.OFF)) {
            resource("large") { get { CoapResponse.content(large) } }
        }
        val peer = fixture.transport()

        assertEquals(CoapCode.CONTENT, answerTo(peer, server, fixture, request("large", 1, 1))?.code)
        assertEquals(CoapCode.CONTENT, answerTo(peer, server, fixture, request("large", 2, 2, echo = byteArrayOf(7)))?.code)
        assertTrue(fixture.limits(Limit.AMPLIFICATION_FACTOR).isEmpty())
    }

    @Test
    fun rfc9177Section4_1TheQBlockProbeIsNeverChallengedButPlainDiscoveryIs() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(all) {
            repeat(20) { index -> resource("sensor$index") { get { CoapResponse.content("$index") } } }
        }
        val peer = fixture.transport()

        // libcoap's probe: a Confirmable GET /.well-known/core with Q-Block2 0/0/16.
        val probe = request(".well-known/core", 1, 1) { qBlock2(BlockOption(0, false, 0)) }
        val answer = assertNotNull(answerTo(peer, server, fixture, probe))
        assertEquals(CoapCode.BAD_OPTION, answer.code, "Q-Block is off; the short refusal goes out at once")
        assertTrue(size(answer) <= 3 * size(probe))

        // The whole link format is the classic amplifier, so a plain GET from the same address is challenged.
        val discovery = request(".well-known/core", 2, 2)
        assertEquals(CoapCode.UNAUTHORIZED, answerTo(peer, server, fixture, discovery)?.code)
    }

    private class Counter : ObservableResource("counter") {
        var value = 0

        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("$value")
    }

    @Test
    fun rfc7641Section7AnObserveRegistrationFromAnUnverifiedAddressIsChallengedWhateverItsSize() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter()
        val server = fixture.server(all) { add(counter) }
        val client = fixture.client()

        val values = async { client.observe(fixture.uri(server, "/counter")).take(2).toList() }
        delay(1.seconds)
        assertEquals(1, counter.observerCount)
        counter.value = 1
        counter.notifyObservers()

        assertEquals(listOf("0", "1"), values.await().map { it.payloadAsText() })
        val fromServer = fixture.sentBy(server.endpoint).filter { it.code != CoapCode.EMPTY }
        assertEquals(CoapCode.UNAUTHORIZED, fromServer.first().code, "a 1-byte state, challenged all the same")
        assertEquals(1, fixture.events.count { it is EndpointEvent.ObserverAdded }, "the challenged registration added nobody")
    }

    @Test
    fun aRegistrationWhoseAddressExpiredWhileItsHandlerRanRegistersNothing() = runTest {
        val fixture = StackFixture(this)
        val slow = object : ObservableResource("slow") {
            override suspend fun handleGet(context: RequestContext): CoapResponse {
                delay(3.seconds)
                return CoapResponse.content(large)
            }
        }
        val server = fixture.server(CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES, verifiedAddressLifetime = 2.seconds)) {
            add(slow)
        }
        val peer = fixture.transport()

        val echo = assertNotNull(answerTo(peer, server, fixture, request("slow", 1, 1, options = { observe(0) }))?.options?.echo)
        assertEquals(CoapCode.EMPTY, answerTo(peer, server, fixture, request("slow", 2, 2, echo = echo, options = { observe(0) }))?.code)
        val late = assertNotNull(receive(peer))
        assertEquals(CoapCode.UNAUTHORIZED, late.code, "verified at registration, expired by the response")
        assertEquals(CoapMessageType.NON, late.type)
        assertEquals(0, slow.observerCount)
    }

    @Test
    fun rfc7967NoResponseAppliesToTheChallengeToo() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(all) { resource("large") { get { CoapResponse.content(large) } } }
        val peer = fixture.transport()

        // Not interested in 2.xx: nothing is sent, so nothing needs a verified address.
        assertNull(answerTo(peer, server, fixture, request("large", 1, 1, type = CoapMessageType.NON, options = { noResponse(2) })))
        assertTrue(fixture.limits(Limit.AMPLIFICATION_FACTOR).isEmpty())

        // Not interested in 4.xx: the response is held back and the challenge suppressed.
        assertNull(answerTo(peer, server, fixture, request("large", 2, 2, type = CoapMessageType.NON, options = { noResponse(8) })))
        assertEquals(1, fixture.limits(Limit.AMPLIFICATION_FACTOR).size)
        assertTrue(fixture.sentBy(server.endpoint).isEmpty())
    }

    @Test
    fun rfc9175Section5EchoValuesDoNotComeFromTheEndpointsSeededRandom() = runTest {
        val fixture = StackFixture(this)
        // Both endpoints get the same seeded Random from the fixture.
        val first = fixture.server(all) { resource("large") { get { CoapResponse.content(large) } } }
        val second = fixture.server(all) { resource("large") { get { CoapResponse.content(large) } } }
        val peer = fixture.transport()

        val one = assertNotNull(answerTo(peer, first, fixture, request("large", 1, 1))?.options?.echo)
        val other = assertNotNull(answerTo(peer, second, fixture, request("large", 2, 2))?.options?.echo)
        assertFalse(one.contentEquals(other))
    }
}
