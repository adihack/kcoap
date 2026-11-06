package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.CoapResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.common.hexToBytes
import dev.kcoap.testkit.DatagramFate
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class RequestResponseTest {
    @Test
    fun rfc7252Section5_2_1AGetIsAnsweredPiggybacked() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("hello") { get { CoapResponse.content("Hello, $peer") } } }
        val client = fixture.client()

        val response = client.get(fixture.uri(server, "/hello"))

        assertEquals(CoapCode.CONTENT, response.code)
        assertEquals("Hello, ${fixture.address(client.endpoint)}", response.payloadAsText())
        assertEquals(ContentFormat.TEXT_PLAIN, response.contentFormat)
        val wire = fixture.traffic.mapNotNull { it.message }
        assertEquals(listOf(CoapMessageType.CON, CoapMessageType.ACK), wire.map { it.type })
        assertEquals(wire[0].messageId, wire[1].messageId)
        assertEquals(wire[0].tokenHex, wire[1].tokenHex)
        assertEquals(8, wire[0].token.size, "8-byte random tokens by default")
    }

    @Test
    fun rfc7252Section5_2_2ASlowHandlerGetsAnEmptyAckAndASeparateResponse() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("slow") {
                get {
                    delay(10.seconds)
                    CoapResponse.content("done")
                }
            }
        }
        val client = fixture.client()

        val response = client.get(fixture.uri(server, "/slow"))

        assertEquals("done", response.payloadAsText())
        val wire = fixture.traffic.mapNotNull { it.message }
        assertEquals(
            listOf(CoapMessageType.CON, CoapMessageType.ACK, CoapMessageType.CON, CoapMessageType.ACK),
            wire.map { it.type },
        )
        assertTrue(wire[1].isEmpty, "the first ACK is empty")
        assertEquals(1.seconds, fixture.traffic[1].at, "sent after the separate-response delay")
        assertEquals(CoapCode.CONTENT, wire[2].code)
        assertTrue(wire[3].isEmpty && wire[3].messageId == wire[2].messageId, "the client acknowledges the response")
    }

    @Test
    fun aHandlerReadsMethodPathQueryAndBodyFromItsContext() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("sensors") {
                resource("temp") {
                    post {
                        // The message is plumbing (InternalKcoapApi); the context's own properties show the same.
                        check(message.options.path == path && message.code == method)
                        CoapResponse.content("$method $path ${query.joinToString("&")} ${payloadAsText()}")
                    }
                }
            }
        }
        val client = fixture.client()

        val response = client.post(fixture.uri(server, "/sensors/temp?unit=C"), "21".encodeToByteArray())
        assertEquals("0.02 POST /sensors/temp unit=C 21", response.payloadAsText())
    }

    @Test
    fun aHandlerCanAcknowledgeEarly() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.endpointConfig(separateResponseDelay = null)) {
            resource("slow") {
                get {
                    acknowledge()
                    delay(30.seconds)
                    CoapResponse.content("done")
                }
            }
        }
        val client = fixture.client()

        assertEquals("done", client.get(fixture.uri(server, "/slow")).payloadAsText())
        assertEquals(0, fixture.events.count { it is EndpointEvent.Retransmitted }, "the Empty ACK stopped retransmission")
    }

    @Test
    fun rfc7252Section5_2_3NonConfirmableRequestsGetNonConfirmableResponses() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("hello") { get { CoapResponse.content("hi") } } }
        val client = fixture.client()

        val response = client.get(fixture.uri(server, "/hello")) { nonConfirmable() }

        assertEquals("hi", response.payloadAsText())
        assertEquals(listOf(CoapMessageType.NON, CoapMessageType.NON), fixture.traffic.mapNotNull { it.message?.type })
    }

    @Test
    fun theServerAnswersEveryMethodThroughTheResourceTemplate() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            add(object : CoapResource("all") {
                override suspend fun handleGet(context: RequestContext) = CoapResponse.content("get")
                override suspend fun handlePost(context: RequestContext) = CoapResponse(CoapCode.CREATED, payload = context.payload)
                override suspend fun handlePut(context: RequestContext) = CoapResponse.changed()
                override suspend fun handleDelete(context: RequestContext) = CoapResponse.deleted()
                override suspend fun handleFetch(context: RequestContext) = CoapResponse.content("fetch ${context.payloadAsText()}")
                override suspend fun handlePatch(context: RequestContext) = CoapResponse.changed()
                override suspend fun handleIPatch(context: RequestContext) = CoapResponse.changed()
            })
            add(object : CoapResource("none") {})
        }
        val client = fixture.client()
        val all = fixture.uri(server, "/all")

        assertEquals("get", client.get(all).payloadAsText())
        assertEquals("x", client.post(all, "x".encodeToByteArray()).payloadAsText())
        assertEquals(CoapCode.CHANGED, client.put(all, "x".encodeToByteArray(), ContentFormat.TEXT_PLAIN).code)
        assertEquals(CoapCode.DELETED, client.delete(all).code)
        assertEquals("fetch q", client.fetch(all, "q".encodeToByteArray()).payloadAsText())
        assertEquals(CoapCode.CHANGED, client.patch(all, "p".encodeToByteArray()).code)
        assertEquals(CoapCode.CHANGED, client.ipatch(all, "p".encodeToByteArray()).code)

        val none = fixture.uri(server, "/none")
        for (method in listOf(CoapCode.GET, CoapCode.POST, CoapCode.PUT, CoapCode.DELETE, CoapCode.FETCH, CoapCode.PATCH, CoapCode.IPATCH)) {
            assertEquals(CoapCode.METHOD_NOT_ALLOWED, client.request(method, CoapUri.parse(none)).code, "$method")
        }
        assertEquals(CoapCode.METHOD_NOT_ALLOWED, client.request(CoapCode.of(0, 9), CoapUri.parse(none)).code)
    }

    @Test
    fun stringAndUriOverloadsAreEquivalent() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("r") {
                get { CoapResponse.content("g") }
                post { CoapResponse.changed() }
                put { CoapResponse.changed() }
                delete { CoapResponse.deleted() }
                fetch { CoapResponse.content("f") }
                patch { CoapResponse.changed() }
                ipatch { CoapResponse.changed() }
            }
        }
        val client = fixture.client()
        val text = fixture.uri(server, "/r")
        val uri = CoapUri.parse(text)
        assertEquals(client.get(text), client.get(uri))
        assertEquals(client.post(text), client.post(uri))
        assertEquals(client.put(text), client.put(uri))
        assertEquals(client.delete(text), client.delete(uri))
        assertEquals(client.fetch(text), client.fetch(uri))
        assertEquals(client.patch(text), client.patch(uri))
        assertEquals(client.ipatch(text), client.ipatch(uri))
    }

    @Test
    fun rfc7252Section5_9UnknownPathsAndContainersAre404() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("group") { resource("leaf") { get { CoapResponse.content("leaf") } } }
        }
        val client = fixture.client()

        assertEquals(CoapCode.NOT_FOUND, client.get(fixture.uri(server, "/missing")).code)
        assertEquals(CoapCode.NOT_FOUND, client.get(fixture.uri(server, "/group")).code)
        assertEquals(CoapCode.NOT_FOUND, client.get(fixture.uri(server, "/")).code)
        assertEquals("leaf", client.get(fixture.uri(server, "/group/leaf")).payloadAsText())
        assertEquals(CoapCode.METHOD_NOT_ALLOWED, client.post(fixture.uri(server, "/group/leaf")).code)
    }

    @Test
    fun rfc7252Section5_4_1UnrecognizedCriticalOptionsGet402() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("r") { get { CoapResponse.content("ok") } }
            resource("conditional") {
                recognize(CoapOptions.IF_MATCH.number)
                get { CoapResponse.content(if (preconditionsHold(byteArrayOf(1))) "match" else "no match") }
            }
        }
        val client = fixture.client()
        val r = fixture.uri(server, "/r")

        val unknown = client.get(r) { options { add(CoapOption(65001, byteArrayOf(1))) } }
        assertEquals(CoapCode.BAD_OPTION, unknown.code)
        assertEquals("Unrecognized critical option 65001", unknown.payloadAsText(), "§5.5.2 diagnostic payload")
        // Unknown elective options are ignored.
        assertEquals(CoapCode.CONTENT, client.get(r) { options { add(CoapOption(65000, byteArrayOf(1))) } }.code)
        // If-Match is critical and only recognized where a resource implements it.
        assertEquals(CoapCode.BAD_OPTION, client.get(r) { options { ifMatch(byteArrayOf(1)) } }.code)
        val conditional = fixture.uri(server, "/conditional")
        assertEquals("match", client.get(conditional) { options { ifMatch(byteArrayOf(1)) } }.payloadAsText())
        assertEquals("no match", client.get(conditional) { options { ifMatch(byteArrayOf(2)) } }.payloadAsText())
        // §5.4.3 a length outside the registered range, §5.4.5 a repeated non-repeatable option.
        assertEquals(CoapCode.BAD_OPTION, client.get(r) { options { add(CoapOption(CoapOptions.URI_HOST.number, ByteArray(0))) } }.code)
        assertEquals(CoapCode.BAD_OPTION, client.get(r) {
            options {
                add(CoapOption.string(CoapOptions.URI_HOST.number, "a"))
                add(CoapOption.string(CoapOptions.URI_HOST.number, "b"))
            }
        }.code)
        // OSCORE is not recognized by a server without message security.
        assertEquals(CoapCode.BAD_OPTION, client.get(r) { options { oscore(byteArrayOf()) } }.code)
    }

    @Test
    fun rfc7252Section5_4_1ANonConfirmableRequestWithAnUnrecognizedCriticalOptionIsRejected() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("ok") } } }
        val client = fixture.client()

        val error = assertFailsWith<CoapException> {
            client.get(fixture.uri(server, "/r")) {
                nonConfirmable()
                options { add(CoapOption(65001, byteArrayOf(1))) }
            }
        }
        assertEquals(CoapException.Reason.RESET, error.reason)
    }

    @Test
    fun rfc7252Section5_10_4AnUnavailableAcceptedFormatGets406() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("text") { get { CoapResponse.content("x") } }
            resource("raw") { get { CoapResponse.content(byteArrayOf(1)) } }
            resource("error") { get { CoapResponse(CoapCode.FORBIDDEN) } }
        }
        val client = fixture.client()

        assertEquals(CoapCode.NOT_ACCEPTABLE, client.get(fixture.uri(server, "/text")) { accept(ContentFormat.JSON) }.code)
        assertEquals(CoapCode.CONTENT, client.get(fixture.uri(server, "/text")) { accept(ContentFormat.TEXT_PLAIN) }.code)
        assertEquals(CoapCode.CONTENT, client.get(fixture.uri(server, "/raw")) { accept(ContentFormat.JSON) }.code)
        assertEquals(CoapCode.FORBIDDEN, client.get(fixture.uri(server, "/error")) { accept(ContentFormat.JSON) }.code)
    }

    @Test
    fun rfc7252Section5_10_2ProxyRequestsGet505() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { }
        val client = fixture.client()

        val response = client.get(fixture.uri(server)) { options { proxyUri("coap://example.com/x") } }
        assertEquals(CoapCode.PROXYING_NOT_SUPPORTED, response.code)
        assertEquals(CoapCode.PROXYING_NOT_SUPPORTED, client.get(fixture.uri(server)) { options { proxyScheme("http") } }.code)
    }

    @Test
    fun aFailingHandlerProduces500() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("boom") { get { error("broken") } } }
        val client = fixture.client()

        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, client.get(fixture.uri(server, "/boom")).code)
    }

    @Test
    fun rfc7967NoResponseSuppressesTheChosenClasses() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { post { CoapResponse.changed() } } }
        val client = fixture.client()
        val uri = fixture.uri(server, "/r")

        // 2.xx suppressed: the Confirmable request is only acknowledged, so no response ever comes.
        val error = assertFailsWith<CoapException> {
            client.post(uri) { options { noResponse(2) } }
        }
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, error.reason)
        assertEquals(CoapCode.CHANGED, client.post(uri) { options { noResponse(8) } }.code)
    }

    @Test
    fun rfc7252Section4_2AConfirmableRequestWithoutAnswerTimesOutAfterMaxTransmitWait() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client()

        val start = fixture.now
        val error = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/nobody") }

        assertEquals(CoapException.Reason.TRANSMISSION_TIMEOUT, error.reason)
        assertTrue(fixture.now - start <= 93.seconds)
        assertEquals(5, fixture.traffic.size, "one transmission and MAX_RETRANSMIT retransmissions")
        assertTrue(fixture.traffic.all { it.fate == DatagramFate.UNREACHABLE })
        assertEquals(4, fixture.events.count { it is EndpointEvent.Retransmitted })
    }

    @Test
    fun rfc7252Section4_2AnEndpointWithoutServerResetsRequests() = runTest {
        val fixture = StackFixture(this)
        // An endpoint that receives but has no server to give requests to.
        val plain = fixture.endpoint().also { it.attach() }
        val client = fixture.client()

        val error = assertFailsWith<CoapException> {
            client.get("coap://${fixture.address(plain).host}:${fixture.address(plain).port}/x")
        }
        assertEquals(CoapException.Reason.RESET, error.reason)
    }

    @Test
    fun rfc7252Section4_3PingIsAnsweredWithReset() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { }
        val client = fixture.client()

        assertTrue(client.ping(fixture.uri(server)))
        assertEquals(listOf(CoapMessageType.CON, CoapMessageType.RST), fixture.traffic.mapNotNull { it.message?.type })
        assertFalse(client.ping("coap://127.0.0.1:9"))
    }

    @Test
    fun rfc7252Section5_3_2ResponsesMatchOnlyFromTheRequestedPeer() = runTest {
        val fixture = StackFixture(this)
        val target = fixture.transport()
        val impostor = fixture.transport()
        val client = fixture.client()

        val pending = async { client.get("coap://${target.localEndpoint.host}:${target.localEndpoint.port}/x") }
        val request = UdpMessageCodec.decode((target.receive() as DatagramEvent.Received).datagram)
        val clientAddress = fixture.address(client.endpoint)

        // A response with the right token but from another endpoint is rejected with RST.
        val forged = CoapMessage.response(request, CoapCode.CONTENT) {
            type = CoapMessageType.CON
            messageId = 1
            payload("forged")
        }
        impostor.send(clientAddress, UdpMessageCodec.encode(forged))
        val reset = UdpMessageCodec.decode((impostor.receive() as DatagramEvent.Received).datagram)
        assertEquals(CoapMessageType.RST, reset.type)

        // The real server answers separately: Empty ACK first, then a Confirmable response.
        target.send(clientAddress, UdpMessageCodec.encode(CoapMessage.empty(CoapMessageType.ACK, request.messageId)))
        target.send(clientAddress, UdpMessageCodec.encode(forged.toBuilder().apply { messageId = 2; payload("real") }.build()))
        assertEquals("real", pending.await().payloadAsText())
        val ack = UdpMessageCodec.decode((target.receive() as DatagramEvent.Received).datagram)
        assertEquals(CoapMessage.empty(CoapMessageType.ACK, 2), ack)

        // §5.3.2 implementation note: the retransmitted response is acknowledged again, not reset.
        target.send(clientAddress, UdpMessageCodec.encode(forged.toBuilder().apply { messageId = 2; payload("real") }.build()))
        assertEquals(ack, UdpMessageCodec.decode((target.receive() as DatagramEvent.Received).datagram))
    }

    @Test
    fun aSeparateResponseBeforeTheAckEndsRetransmission() = runTest {
        val fixture = StackFixture(this)
        val target = fixture.transport()
        val client = fixture.client()

        val pending = async { client.get("coap://${target.localEndpoint.host}:${target.localEndpoint.port}/x") }
        val request = UdpMessageCodec.decode((target.receive() as DatagramEvent.Received).datagram)
        val response = CoapMessage.response(request, CoapCode.CONTENT) {
            type = CoapMessageType.NON
            messageId = 9
        }
        target.send(fixture.address(client.endpoint), UdpMessageCodec.encode(response))
        assertEquals(CoapCode.CONTENT, pending.await().code)
        delay(100.seconds)
        assertEquals(1, fixture.sentBy(client.endpoint).size, "no retransmission after the response")
    }

    @Test
    fun rfc7252Section4_5LossAndDuplicationAreMaskedAndRequestsProcessedOnce() = runTest {
        val fixture = StackFixture(this, seed = 5)
        var processed = 0
        val server = fixture.server {
            resource("counter") {
                post {
                    processed++
                    CoapResponse.content("$processed")
                }
            }
        }
        val client = fixture.client()
        fixture.network.conditions = LinkConditions(lossRate = 0.25, duplicationRate = 0.3, jitter = 50.milliseconds)

        repeat(20) { client.post(fixture.uri(server, "/counter")) }

        assertEquals(20, processed, "every request handled exactly once")
        assertTrue(fixture.traffic.any { it.fate == DatagramFate.LOST })
        assertTrue(fixture.traffic.any { it.fate == DatagramFate.DUPLICATED })
        assertTrue(fixture.events.any { it is EndpointEvent.DuplicateReceived })
    }

    @Test
    fun rfc7252Section4_7NstartSerializesRequestsToOnePeer() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("slow") {
                get {
                    acknowledge()
                    delay(5.seconds)
                    CoapResponse.content("ok")
                }
            }
        }
        val client = fixture.client()
        fixture.network.filter = { it.to != fixture.address(client.endpoint) || UdpMessageCodec.decode(it.bytes).type != CoapMessageType.ACK }

        val first = async { client.get(fixture.uri(server, "/slow")) }
        val second = async { client.get(fixture.uri(server, "/slow")) }
        delay(1.seconds)
        // The server's ACKs are lost, so the first request stays outstanding and the second waits.
        assertEquals(1, fixture.sentBy(client.endpoint).count { it.isRequest })
        fixture.network.filter = { true }
        assertEquals("ok", first.await().payloadAsText())
        assertEquals("ok", second.await().payloadAsText())
    }

    @Test
    fun cancellingARequestStopsItsRetransmission() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client()

        val pending = async { client.get("coap://127.0.0.1:9/x") }
        delay(3.seconds)
        pending.cancel()
        delay(100.seconds)
        assertTrue(fixture.traffic.size <= 2, "at most one retransmission before cancelling: ${fixture.traffic}")
    }

    @Test
    fun aResponseTimeoutAppliesAfterTheAck() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("never") {
                get {
                    delay(1000.seconds)
                    CoapResponse.content("late")
                }
            }
        }
        val client = fixture.client(CoapClientConfig { requestTimeout = 30.seconds })

        val error = assertFailsWith<CoapException> { client.get(fixture.uri(server, "/never")) }
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, error.reason)
    }

    @Test
    fun rfc7252Section9_1_1AnEndedSessionFailsItsExchanges() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client()
        val session = Endpoint.Secure(dev.kcoap.transport.SessionId(1), 0, Endpoint.Udp("127.0.0.1", 9), dev.kcoap.transport.PeerIdentity.None)

        val pending = async {
            runCatching {
                client.endpoint.openExchange(session, CoapMessage.request(CoapCode.GET), true, null)
                    .let { client.endpoint.awaitResponse(it, 100.seconds) }
            }
        }
        delay(5.seconds)
        (client.endpoint.transport as dev.kcoap.testkit.InMemoryTransport)
            .inject(DatagramEvent.SessionEnded(session, dev.kcoap.transport.SessionEndReason.CLOSED_BY_PEER))
        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.SESSION_ENDED, error.reason)
        val sent = fixture.traffic.size
        delay(100.seconds)
        assertEquals(sent, fixture.traffic.size, "no retransmission across epochs")
        assertTrue(fixture.events.any { it is EndpointEvent.SessionEnded })
    }

    @Test
    fun interceptorsSeeEveryMessageAndEventsAreReported() = runTest {
        val fixture = StackFixture(this)
        val seen = mutableListOf<String>()
        val tracer = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage {
                seen += "out ${message.type}"
                return message
            }

            override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage {
                seen += "in ${message.type}"
                return message
            }
        }
        val server = fixture.server { resource("r") { get { CoapResponse.content("x") } } }
        val client = fixture.client(endpointConfig = fixture.endpointConfig(interceptors = listOf(tracer)))

        client.get(fixture.uri(server, "/r"))
        assertEquals(listOf("out CON", "in ACK"), seen)
    }

    @Test
    fun rfc7252Section4_2MalformedConfirmableDatagramsAreReset() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { }
        val raw = fixture.transport()

        raw.send(fixture.address(server.endpoint), "4901abcd".hexToBytes())
        val reset = UdpMessageCodec.decode((raw.receive() as DatagramEvent.Received).datagram)
        assertEquals(CoapMessage.empty(CoapMessageType.RST, 0xabcd), reset)
        assertTrue(fixture.events.any { it is EndpointEvent.MalformedMessage })
    }

    @Test
    fun closingTheEndpointFailsOpenExchanges() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client()

        val pending = async { runCatching { client.get("coap://127.0.0.1:9/x") } }
        delay(1.seconds)
        client.endpoint.close()
        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.CLOSED, error.reason)
        assertTrue(client.endpoint.isClosed)
        client.endpoint.close()
        val after = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/x") }
        assertEquals(CoapException.Reason.CLOSED, after.reason)
    }

    @Test
    fun clientAndServerShareOneEndpoint() = runTest {
        val fixture = StackFixture(this)
        val first = fixture.endpoint()
        val second = fixture.endpoint()
        val serverOnFirst = dev.kcoap.coap.server.CoapServer(first) { resource("a") { get { CoapResponse.content("from first") } } }
        dev.kcoap.coap.server.CoapServer(second) { resource("b") { get { CoapResponse.content("from second") } } }
        val clientOnFirst = dev.kcoap.coap.client.CoapClient(first)
        val clientOnSecond = dev.kcoap.coap.client.CoapClient(second)

        val secondAddress = fixture.address(second)
        assertEquals("from second", clientOnFirst.get("coap://${secondAddress.host}:${secondAddress.port}/b").payloadAsText())
        assertEquals("from first", clientOnSecond.get(fixture.uri(serverOnFirst, "/a")).payloadAsText())
        assertFailsWith<IllegalStateException> { dev.kcoap.coap.server.CoapServer(first) { } }
    }
}
