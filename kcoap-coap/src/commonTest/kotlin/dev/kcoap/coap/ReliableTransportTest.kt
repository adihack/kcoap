package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.ObserveCancellation
import dev.kcoap.coap.codec.ReliableMessageCodec
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.coap.stack.Signal
import dev.kcoap.testkit.InMemoryByteStream
import dev.kcoap.testkit.InMemoryConnection
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** End-to-end tests of CoAP over reliable transports (RFC 8323) on in-memory connections and virtual time. */
class ReliableTransportTest {
    private class Counter : ObservableResource("counter") {
        var value = 0
        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("$value")

        fun change() {
            value++
            notifyObservers()
        }
    }

    private fun body(size: Int) = ByteArray(size) { ('a' + it % 26).code.toByte() }

    // ---------------------------------------------------------------- requests and responses

    /** RFC 8323 §3.3 Figure 2: CSMs first, then a request and its response, without Type or Message ID. */
    @Test
    fun rfc8323Section3_3AGetOverTcp() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("hello") { get { CoapResponse.content("Hello, $peer") } } }
        val client = fixture.client()

        val response = client.get(fixture.uri("/hello"))

        assertEquals("Hello, ${Endpoint.Tcp("127.0.0.1", 40000)}", response.payloadAsText())
        val codes = fixture.wire.map { it.message.code }
        assertEquals(listOf(CoapCode.CSM, CoapCode.CSM, CoapCode.GET, CoapCode.CONTENT), codes)
        val (clientCsm, serverCsm) = fixture.wire.take(2).map { it.message }
        assertEquals(ReliableTransportConfig.DEFAULT_MAX_MESSAGE_SIZE, clientCsm.options.first(2)?.uintValue())
        assertTrue(4 in serverCsm.options)
        assertEquals(fixture.wire[2].message.tokenHex, fixture.wire[3].message.tokenHex)
        assertTrue(fixture.events.any { it is EndpointEvent.ConnectionOpened && it.isInitiator })
        assertTrue(fixture.events.any { it is EndpointEvent.ConnectionOpened && !it.isInitiator })
        assertEquals(2, fixture.events.count { it is EndpointEvent.CsmReceived })
    }

    /**
     * A listener runs outside the endpoint's locks (EndpointEventListener): a slow one must not hold up every accept,
     * connect and teardown. ConnectionOpened still comes before the connection's own events, its CSM first.
     */
    @Test
    fun rfc8323Section5_3ConnectionOpenedIsReportedOutsideTheLockAndBeforeTheCsm() = runTest {
        val fixture = ReliableFixture(this)
        var serverEndpoint: CoapEndpoint? = null
        var clientEndpoint: CoapEndpoint? = null
        val serverEvents = mutableListOf<Pair<EndpointEvent, Boolean>>()
        val clientEvents = mutableListOf<Pair<EndpointEvent, Boolean>>()
        fun recording(into: MutableList<Pair<EndpointEvent, Boolean>>, endpoint: () -> CoapEndpoint?) =
            fixture.endpointConfig().toBuilder().apply {
                eventListener = EndpointEventListener { into += it to (endpoint()?.isConnectionsLocked == true) }
            }.build()
        val server = fixture.server(endpointConfig = recording(serverEvents) { serverEndpoint }) {
            resource("r") { get { CoapResponse.content("ok") } }
        }
        serverEndpoint = server.endpoint
        val client = fixture.client(endpointConfig = recording(clientEvents) { clientEndpoint })
        clientEndpoint = client.endpoint

        assertEquals("ok", client.get(fixture.uri("/r")).payloadAsText())

        for ((side, events) in listOf("server" to serverEvents, "client" to clientEvents)) {
            val connection = events.map { it.first }.filter { it is EndpointEvent.ConnectionOpened || it is EndpointEvent.CsmReceived }
            assertEquals(listOf(true, false), connection.map { it is EndpointEvent.ConnectionOpened }, "$side: $connection")
            assertTrue(events.none { it.second }, "$side: every event outside the connections' lock")
        }
    }

    /** RFC 8323 §4: the same over a WebSocket, one message per frame with Len 0. */
    @Test
    fun rfc8323Section4AGetOverWebSockets() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server(port = 8080, scheme = CoapScheme.COAP_WS) { resource("hello") { get { CoapResponse.content("Hello") } } }
        val client = fixture.client()
        val response = client.get(fixture.uri("/hello", 8080, CoapScheme.COAP_WS))
        assertEquals("Hello", response.payloadAsText())
        assertEquals(listOf(CoapCode.CSM, CoapCode.CSM, CoapCode.GET, CoapCode.CONTENT), fixture.wire.map { it.message.code })
        assertTrue(fixture.wire.all { it.to is Endpoint.WebSocket })
    }

    /** RFC 8323 §3: one connection serves every request, also concurrent ones, which share the opening. */
    @Test
    fun rfc8323Section3OneConnectionIsReused() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("n") { get { CoapResponse.content(query.single()) } } }
        val client = fixture.client()

        val answers = (1..5).map { async { client.get(fixture.uri("/n?$it")).payloadAsText() } }.awaitAll()
        assertEquals((1..5).map { "$it" }, answers)
        assertEquals("6", client.get(fixture.uri("/n?6")).payloadAsText())
        assertEquals(2, fixture.connections.connections().size, "one connection, two sides")
        val tokens = fixture.sent(CoapCode.GET).map { it.tokenHex }
        assertEquals(6, tokens.toSet().size, "§3.3: concurrent requests differ by token")
    }

    /** RFC 8323 §3, §5.5: an idle connection is released; the next request opens a new one. */
    @Test
    fun rfc8323Section5_5IdleConnectionsAreReleasedAndReopened() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("hello") { get { CoapResponse.content("Hello") } } }
        val client = fixture.client(endpointConfig = fixture.endpointConfig(ReliableTransportConfig(idleTimeout = 10.seconds)))

        client.get(fixture.uri("/hello"))
        delay(11.seconds)
        val release = fixture.sent(CoapCode.RELEASE).single()
        assertEquals("idle", release.payloadAsText())
        assertTrue(fixture.connections.connections().all { it.isClosed }, "the server closes on the Release")
        assertTrue(fixture.events.any { it is EndpointEvent.ReleaseReceived && it.diagnostic == "idle" })

        assertEquals("Hello", client.get(fixture.uri("/hello")).payloadAsText())
        assertEquals(4, fixture.connections.connections().size)
    }

    /** RFC 8323 §3.3: either side may send requests; one without a server MUST answer with an error (5.01). */
    @Test
    fun rfc8323Section3_3RequestsGoBothWaysAndAClientAnswers501() = runTest {
        val fixture = ReliableFixture(this)
        var peer: Endpoint? = null
        val server = fixture.server { resource("hello") { get { CoapResponse.content("Hello").also { peer = this.peer } } } }
        val client = fixture.client()
        client.get(fixture.uri("/hello"))

        val reverse = CoapClient(server.endpoint)
        val answer = reverse.request(CoapRequest(CoapCode.GET, CoapUri.parse("coap+tcp://127.0.0.1:40000/anything")))
        assertEquals(Endpoint.Tcp("127.0.0.1", 40000), peer)
        assertEquals(CoapCode.NOT_IMPLEMENTED, answer.code)
        assertEquals(2, fixture.connections.connections().size, "over the connection the client opened")
    }

    /** On a reliable transport a large body goes in one message when the peer's Max-Message-Size allows. */
    @Test
    fun rfc8323Section6LargeBodiesNeedNoBlocksWhenTheyFit() = runTest {
        val fixture = ReliableFixture(this)
        var stored = ByteArray(0)
        fixture.server {
            resource("store") {
                put {
                    stored = payload
                    CoapResponse.changed()
                }
                get { CoapResponse.content(stored) }
            }
        }
        val client = fixture.client()
        val payload = body(6000)
        assertEquals(CoapCode.CHANGED, client.put(fixture.uri("/store"), payload).code)
        assertContentEquals(payload, client.get(fixture.uri("/store")).payload)
        assertTrue(fixture.wire.none { it.message.options.block1 != null || it.message.options.block2 != null })
    }

    /** RFC 8323 §5.3.1: block sizes follow the peer's Max-Message-Size; 1152 means 1024-byte blocks. */
    @Test
    fun rfc8323Section5_3_1BlocksFitThePeersMaxMessageSize() = runTest {
        val fixture = ReliableFixture(this)
        var stored = ByteArray(0)
        val small = fixture.endpointConfig(ReliableTransportConfig(maxMessageSize = 1152))
        fixture.server(endpointConfig = small) {
            resource("store") {
                put {
                    stored = payload
                    CoapResponse.changed()
                }
                get { CoapResponse.content(stored) }
            }
        }
        val client = fixture.client(endpointConfig = small)
        val payload = body(3000)
        assertEquals(CoapCode.CHANGED, client.put(fixture.uri("/store"), payload).code)
        assertEquals(listOf(0L, 1L, 2L), fixture.sent(CoapCode.PUT).mapNotNull { it.options.block1?.number })
        assertTrue(fixture.sent(CoapCode.PUT).all { it.options.block1!!.size == 1024 && !it.options.block1!!.isBert })
        assertContentEquals(payload, client.get(fixture.uri("/store")).payload)
        assertTrue(fixture.wire.all { ReliableMessageCodec.Tcp.encode(it.message).size <= 1152 })
    }

    /** RFC 8323 §6 Figures 13 and 14: BERT blocks carry several kibibytes and number in 1024-byte units. */
    @Test
    fun rfc8323Section6BertUploadsAndDownloads() = runTest {
        val fixture = ReliableFixture(this)
        var stored = ByteArray(0)
        // A server that takes BERT blocks of 3072 bytes, as in Figure 13's first block.
        fixture.server(endpointConfig = fixture.endpointConfig(ReliableTransportConfig(maxMessageSize = 3072 + 256))) {
            resource("status") {
                put {
                    stored = payload
                    CoapResponse.changed()
                }
                get { CoapResponse.content(stored) }
            }
        }
        val client = fixture.client(endpointConfig = fixture.endpointConfig(ReliableTransportConfig(maxMessageSize = 5120 + 256)))
        val payload = body(12903)

        assertEquals(CoapCode.CHANGED, client.put(fixture.uri("/status"), payload).code)
        val uploads = fixture.sent(CoapCode.PUT).map { it.options.block1!! to it.payloadSize }
        assertTrue(uploads.all { it.first.isBert }, "$uploads")
        assertEquals(listOf(0L, 3L, 6L, 9L, 12L), uploads.map { it.first.number })
        assertEquals(listOf(3072, 3072, 3072, 3072, 615), uploads.map { it.second })
        val continues = fixture.sent(CoapCode.CONTINUE).map { it.options.block1!! }
        assertTrue(continues.all { it.isBert }, "§6: the server accepts BERT and says so")

        fixture.wire.clear()
        assertContentEquals(payload, client.get(fixture.uri("/status")).payload)
        val downloads = fixture.sent(CoapCode.CONTENT).map { it.options.block2!! to it.payloadSize }
        assertEquals(listOf(0L, 5L, 10L, 12L), downloads.map { it.first.number }, "the client takes 5 KiB per message")
        // The final BERT block stays within 1024 bytes, which libcoap 4.3.5 needs.
        assertEquals(listOf(5120, 5120, 2048, 615), downloads.map { it.second })
        val followUps = fixture.sent(CoapCode.GET).mapNotNull { it.options.block2 }
        assertTrue(followUps.all { it.isBert && !it.more }, "follow-ups ask for BERT: $followUps")
    }

    @Test
    fun aConfiguredBlockSizeTurnsBertOff() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server(endpointConfig = fixture.endpointConfig(ReliableTransportConfig(maxMessageSize = 2048))) {
            resource("store") { put { CoapResponse.changed() } }
        }
        val client = fixture.client(CoapClientConfig { preferredBlockSize = 512 })
        assertEquals(CoapCode.CHANGED, client.put(fixture.uri("/store"), body(3000)).code)
        assertTrue(fixture.sent(CoapCode.PUT).all { it.options.block1!!.size == 512 && !it.options.block1!!.isBert })
    }

    /** RFC 8323 §6: a server that prefers plain blocks answers BERT with SZX 6, and the client follows. */
    @Test
    fun rfc8323Section6AServerMayAnswerBertWithPlainBlocks() = runTest {
        val fixture = ReliableFixture(this)
        var received = 0
        fixture.server(config = CoapServerConfig(maxBlockSize = 512), endpointConfig = fixture.endpointConfig(ReliableTransportConfig(maxMessageSize = 4096))) {
            resource("store") {
                put {
                    received = payloadSize
                    CoapResponse.changed()
                }
            }
        }
        val client = fixture.client()
        assertEquals(CoapCode.CHANGED, client.put(fixture.uri("/store"), body(9000)).code)
        assertEquals(9000, received)
        val blocks = fixture.sent(CoapCode.PUT).map { it.options.block1!! }
        assertTrue(blocks.first().isBert)
        assertTrue(blocks.drop(1).all { !it.isBert && it.size == 512 }, "$blocks")
    }

    // ---------------------------------------------------------------- Observe

    /** RFC 8323 §7: notifications are plain responses; the client deregisters explicitly (§7.4). */
    @Test
    fun rfc8323Section7ObserveOverAConnection() = runTest {
        val fixture = ReliableFixture(this)
        val counter = Counter()
        fixture.server { add(counter) }
        // FORGET does not apply on reliable transports: there is no Reset to cancel with.
        val client = fixture.client(CoapClientConfig { observeCancellation = ObserveCancellation.FORGET })

        val collected = async { client.observe(fixture.uri("/counter")).take(3).toList() }
        runCurrent()
        counter.change()
        runCurrent()
        counter.change()
        assertEquals(listOf("0", "1", "2"), collected.await().map { it.payloadAsText() })
        runCurrent()
        assertEquals(0, counter.observerCount, "§7.4: deregistered by GET with Observe 1")
        val requests = fixture.sent(CoapCode.GET)
        assertEquals(listOf(0L, 1L), requests.map { it.options.observe })
        assertEquals(requests[0].tokenHex, requests[1].tokenHex)
    }

    /** A server-side interceptor that adds option 9999, critical and unknown to the client, to the responses [matching] selects. */
    private fun unknownCriticalOptionOn(matching: (CoapMessage) -> Boolean) = object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = if (message.isResponse && matching(message)) {
            message.toBuilder().options { add(CoapOption(9999, byteArrayOf(1))) }.build()
        } else {
            message
        }
    }

    /**
     * RFC 8323 §7.4: a connection has no Reset, so after the client rejects a notification with an unknown
     * critical option (RFC 7252 §5.4.1) it MUST deregister, or the server would notify it for good.
     */
    @Test
    fun rfc8323Section7_4ARejectedNotificationIsFollowedByADeregistration() = runTest {
        val fixture = ReliableFixture(this)
        val counter = Counter()
        val serverConfig = fixture.endpointConfig(interceptors = listOf(unknownCriticalOptionOn { it.options.observe != null && it.payloadAsText() == "1" }))
        fixture.server(endpointConfig = serverConfig) { add(counter) }
        val client = fixture.client()
        val received = mutableListOf<String>()
        val observing = async { runCatching { client.observe(fixture.uri("/counter")).collect { received += it.payloadAsText() } } }
        runCurrent()
        assertEquals(1, counter.observerCount)

        counter.change()
        val error = assertIs<CoapException>(observing.await().exceptionOrNull())
        assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason)
        assertEquals(listOf("0"), received)
        runCurrent()
        val requests = fixture.sent(CoapCode.GET, byServer = false)
        assertEquals(listOf(0L, 1L), requests.map { it.options.observe }, "§7.4: deregistered by GET with Observe 1")
        assertEquals(requests[0].tokenHex, requests[1].tokenHex)
        assertEquals(0, counter.observerCount)

        counter.change()
        runCurrent()
        val notifications = fixture.sent(CoapCode.CONTENT, byServer = true).map { it.payloadAsText() }
        assertEquals(listOf("0", "1", "1"), notifications, "the registration, the rejected notification, the deregistration's answer")
        assertEquals(0, client.endpoint.exchangeCount())
        assertEquals(0, client.observationCount())
    }

    /** RFC 8323 §7.4: a rejected registration response may have registered the client too, so it deregisters. */
    @Test
    fun rfc8323Section7_4ARejectedRegistrationResponseIsFollowedByADeregistration() = runTest {
        val fixture = ReliableFixture(this)
        val counter = Counter()
        val serverConfig = fixture.endpointConfig(interceptors = listOf(unknownCriticalOptionOn { it.options.observe != null }))
        fixture.server(endpointConfig = serverConfig) { add(counter) }
        val client = fixture.client()

        val error = assertFailsWith<CoapException> { client.observe(fixture.uri("/counter")).toList() }
        assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason)
        runCurrent()
        val requests = fixture.sent(CoapCode.GET, byServer = false)
        assertEquals(listOf(0L, 1L), requests.map { it.options.observe })
        assertEquals(requests[0].tokenHex, requests[1].tokenHex)
        assertEquals(0, counter.observerCount)
        assertEquals(0, client.endpoint.exchangeCount())
    }

    /** RFC 8323 §7.1: the Observe value MUST be ignored on reception; arrival order is what counts. */
    @Test
    fun rfc8323Section7_1ObserveValuesAreIgnored() = runTest {
        val fixture = ReliableFixture(this)
        val counter = Counter()
        fixture.server { add(counter) }
        val client = fixture.client()
        val collected = async { client.observe(fixture.uri("/counter")).take(2).toList() }
        runCurrent()

        // A notification whose Observe value is older than the registration's, by RFC 7641 §3.4.
        val clientSide = fixture.connections.connections().first() as InMemoryByteStream
        val token = fixture.sent(CoapCode.GET).single().token
        val stale = CoapMessage.build {
            code = CoapCode.CONTENT
            this.token = token
            options { observe(0xFFFFFF) }
            payload("stale but newest")
        }
        clientSide.inject(ReliableMessageCodec.Tcp.encode(stale))
        assertEquals(listOf("0", "stale but newest"), collected.await().map { it.payloadAsText() })
    }

    /**
     * RFC 8323 §7.1: no notification overtakes the registration response, since the client takes arrival
     * order as freshness. Here the state changes while the registration response is still being protected.
     */
    @Test
    fun rfc8323Section7_1TheRegistrationResponseComesFirst() = runTest {
        val fixture = ReliableFixture(this)
        val counter = Counter()
        var protections = 0
        val slowProtection = object : ServerMessageSecurity {
            override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest = object : UnprotectedRequest {
                override val message: CoapMessage = request
                override val identity: SecurityIdentity = object : SecurityIdentity {
                    override val description: String = "test"
                }

                override suspend fun protectResponse(response: CoapMessage): CoapMessage {
                    if (protections++ == 0) {
                        counter.change()
                        delay(1.seconds)
                    }
                    return response
                }
            }
        }
        fixture.server(config = CoapServerConfig(security = slowProtection)) { add(counter) }
        val client = fixture.client()
        val notifications = client.observe(fixture.uri("/counter")).take(2).toList()
        assertEquals(listOf("0", "1"), notifications.map { it.payloadAsText() })
        val responses = fixture.sent(CoapCode.CONTENT, byServer = true)
        assertEquals(listOf("0", "1"), responses.take(2).map { it.payloadAsText() })
    }

    /** RFC 8323 §3.4, §7.4: when the connection is gone, exchanges fail and observers are removed. */
    @Test
    fun rfc8323Section7_4ConnectionLossEndsObservationsAndExchanges() = runTest {
        val fixture = ReliableFixture(this)
        val counter = Counter()
        fixture.server {
            add(counter)
            resource("never") {
                get {
                    delay(1000.seconds)
                    CoapResponse.content("late")
                }
            }
        }
        val client = fixture.client()
        val observation = async { runCatching { client.observe(fixture.uri("/counter")).toList() } }
        val pending = async { runCatching { client.get(fixture.uri("/never")) } }
        runCurrent()
        assertEquals(1, counter.observerCount)

        (fixture.connections.connections().first() as InMemoryConnection).reset()
        runCurrent()
        val failure = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, failure.reason)
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, (observation.await().exceptionOrNull() as CoapException).reason)
        assertEquals(0, counter.observerCount)
        val removed = fixture.events.filterIsInstance<EndpointEvent.ObserverRemoved>().single()
        assertEquals(EndpointEvent.ObserverRemoved.Reason.SESSION_ENDED, removed.reason, "RFC 8323 §7.4")
        assertEquals(2, fixture.events.count { it is EndpointEvent.ConnectionClosed })
    }

    // ---------------------------------------------------------------- signaling

    /** RFC 8323 §5.4: a Ping is answered by a Pong; with Custody too. */
    @Test
    fun rfc8323Section5_4PingAndPong() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("hello") { get { CoapResponse.content("Hello") } } }
        val client = fixture.client()
        assertTrue(client.ping(fixture.uri()))
        assertTrue(client.endpoint.ping(Endpoint.Tcp("127.0.0.1", 5683), custody = true))
        val pongs = fixture.sent(CoapCode.PONG, byServer = true)
        assertEquals(2, pongs.size)
        assertTrue(2 in pongs[1].options, "Custody")
        assertFalse(client.ping("coap+tcp://127.0.0.1:9"), "nobody listens")
    }

    @Test
    fun aPingWithoutPongTimesOut() = runTest {
        val fixture = ReliableFixture(this)
        val listener = fixture.listen()
        launch {
            val connection = listener.accept() as InMemoryByteStream
            connection.write(ReliableMessageCodec.Tcp.encode(Signal.Csm(null, false).toMessage()))
        }
        val endpoint = fixture.endpoint(fixture.endpointConfig(ReliableTransportConfig(pingTimeout = 3.seconds)))
        assertFalse(endpoint.ping(Endpoint.Tcp("127.0.0.1", 5683)))
        assertEquals(3.seconds, fixture.now)
    }

    /** RFC 8323 §5.4.1: a request that is deliberately not answered (No-Response) still counts as processed. */
    @Test
    fun rfc8323Section5_4_1NoResponseRequestsAreProcessedForCustody() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("quiet") { post { CoapResponse.changed() } } }
        val client = fixture.client(CoapClientConfig { requestTimeout = 5.seconds })
        assertFailsWith<CoapException> { client.post(fixture.uri("/quiet"), body(4)) { options { noResponse(0x02) } } }
        assertTrue(client.endpoint.ping(Endpoint.Tcp("127.0.0.1", 5683), custody = true))
        assertTrue(fixture.sent(CoapCode.CHANGED).isEmpty())
    }

    /** RFC 8323 §5.5: Hold-Off keeps a client from reconnecting; Alternative-Address is only reported. */
    @Test
    fun rfc8323Section5_5HoldOffDelaysReconnecting() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("hello") { get { CoapResponse.content("Hello") } } }
        val client = fixture.client()
        client.get(fixture.uri("/hello"))
        val clientSide = fixture.connections.connections().first() as InMemoryByteStream
        val release = Signal.Release(listOf("192.0.2.9:5683"), 60.seconds, "moving").toMessage()
        clientSide.inject(ReliableMessageCodec.Tcp.encode(release))
        runCurrent()
        assertTrue(clientSide.isClosed, "nothing pending: the client closes at once")
        val event = fixture.events.filterIsInstance<EndpointEvent.ReleaseReceived>().single()
        assertEquals(listOf("192.0.2.9:5683"), event.alternativeAddresses)

        val refused = assertFailsWith<CoapException> { client.get(fixture.uri("/hello")) }
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, refused.reason)
        assertTrue("Hold-Off" in refused.message!!)
        delay(61.seconds)
        assertEquals("Hello", client.get(fixture.uri("/hello")).payloadAsText())
    }

    /** RFC 8323 §5.5: after a Release, requests in progress are answered before the connection closes. */
    @Test
    fun rfc8323Section5_5AReleasedConnectionDrains() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server {
            resource("slow") {
                get {
                    delay(3.seconds)
                    CoapResponse.content("done")
                }
            }
        }
        val client = fixture.client()
        val answer = async { client.get(fixture.uri("/slow")) }
        runCurrent()
        val clientSide = fixture.connections.connections().first() as InMemoryByteStream
        clientSide.inject(ReliableMessageCodec.Tcp.encode(Signal.Release().toMessage()))
        runCurrent()
        assertFalse(clientSide.isClosed, "the client's own request still waits")
        assertEquals("done", answer.await().payloadAsText())
        runCurrent()
        assertTrue(clientSide.isClosed)
    }

    /** RFC 8323 §5.6: an Abort from the peer fails the exchanges on the connection. */
    @Test
    fun rfc8323Section5_6AbortFailsTheExchanges() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("never") { get { delay(1000.seconds); CoapResponse.content("late") } } }
        val client = fixture.client()
        val pending = async { runCatching { client.get(fixture.uri("/never")) } }
        runCurrent()
        val clientSide = fixture.connections.connections().first() as InMemoryByteStream
        clientSide.inject(ReliableMessageCodec.Tcp.encode(Signal.Abort(diagnostic = "overloaded").toMessage()))
        val failure = pending.await().exceptionOrNull() as CoapException
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, failure.reason)
        assertTrue("overloaded" in failure.message!!)
        assertEquals("overloaded", fixture.events.filterIsInstance<EndpointEvent.AbortReceived>().single().diagnostic)
    }

    /** RFC 8323 §3.3: a peer whose first message is not a CSM is aborted, and requests to it fail. */
    @Test
    fun rfc8323Section3_3APeerWithoutCsmIsAborted() = runTest {
        val fixture = ReliableFixture(this)
        val listener = fixture.listen()
        val accepted = async { listener.accept() as InMemoryByteStream }
        val endpoint = fixture.endpoint()
        val client = CoapClient(endpoint)
        val request = async { runCatching { client.get(fixture.uri("/x")) } }
        val peer = accepted.await()
        peer.write(ReliableMessageCodec.Tcp.encode(CoapMessage.request(CoapCode.GET)))
        val failure = request.await().exceptionOrNull() as CoapException
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, failure.reason)
        val abort = fixture.sent(CoapCode.ABORT).single()
        assertTrue("Expected a CSM" in abort.payloadAsText())
    }

    /** RFC 8323 §3.3: a CSM that never comes is missing too. */
    @Test
    fun rfc8323Section3_3ACsmThatNeverComesFailsTheRequest() = runTest {
        val fixture = ReliableFixture(this)
        fixture.listen()
        val client = fixture.client(endpointConfig = fixture.endpointConfig(ReliableTransportConfig(csmTimeout = 4.seconds)))
        val failure = assertFailsWith<CoapException> { client.get(fixture.uri("/x")) }
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, failure.reason)
        assertEquals(4.seconds, fixture.now)
    }

    /** RFC 8323 §5.3.1: a message above the announced Max-Message-Size aborts the connection. */
    @Test
    fun rfc8323Section5_3_1AnOversizeMessageAborts() = runTest {
        val fixture = ReliableFixture(this)
        val small = fixture.endpointConfig(ReliableTransportConfig(maxMessageSize = 1152))
        fixture.server(endpointConfig = small) { resource("store") { put { CoapResponse.changed() } } }
        val client = fixture.client()
        client.ping(fixture.uri())
        // Ignore the server's 1152: claim it allowed more, and send a larger message.
        val clientSide = fixture.connections.connections()[1] as InMemoryByteStream
        clientSide.inject(ReliableMessageCodec.Tcp.encode(CoapMessage.request(CoapCode.PUT) { payload = body(2000) }))
        runCurrent()
        val abort = fixture.sent(CoapCode.ABORT, byServer = true).single()
        assertTrue("exceeds the limit of 1152" in abort.payloadAsText(), abort.payloadAsText())
    }

    // ---------------------------------------------------------------- connection management

    @Test
    fun failuresToConnectAndMissingConnectorsAreReported() = runTest {
        val fixture = ReliableFixture(this)
        val client = fixture.client()
        val refused = assertFailsWith<CoapException> { client.get("coap+tcp://127.0.0.1:9/x") }
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, refused.reason)
        assertTrue("refused" in refused.message!!)
        // A transport the endpoint does not have fails at once, naming the scheme, and sends nothing.
        val noDatagrams = assertFailsWith<CoapException> { client.get("coap://127.0.0.1/x") }
        assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, noDatagrams.reason)
        assertTrue("coap needs a DatagramTransport" in noDatagrams.message!!)
        assertEquals(
            CoapException.Reason.TRANSPORT_UNAVAILABLE,
            assertFailsWith<CoapException> { client.ping("coap://127.0.0.1") }.reason,
        )

        val nothing = CoapEndpoint(backgroundScope)
        val noConnector = assertFailsWith<CoapException> { CoapClient(nothing).get("coap+ws://127.0.0.1/x") }
        assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, noConnector.reason)
        assertTrue("coap+ws needs a Connector" in noConnector.message!!)
        assertEquals("CoapEndpoint(no datagram transport)", nothing.toString())
        nothing.close()
    }

    /** Closing the endpoint releases its connections (RFC 8323 §5.5) and fails what is open. */
    @Test
    fun closingTheEndpointReleasesItsConnections() = runTest {
        val fixture = ReliableFixture(this)
        val server = fixture.server { resource("never") { get { delay(1000.seconds); CoapResponse.content("late") } } }
        val client = fixture.client()
        val pending = async { runCatching { client.get(fixture.uri("/never")) } }
        runCurrent()
        server.close()
        runCurrent()
        assertEquals("endpoint closed", fixture.sent(CoapCode.RELEASE, byServer = true).single().payloadAsText())
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, (pending.await().exceptionOrNull() as CoapException).reason)
        assertFailsWith<CoapException> { client.get(fixture.uri("/never")) }

        client.endpoint.close()
        assertTrue(client.endpoint.isClosed)
    }

    /** RFC 8323 §9: what TLS authenticated reaches the resource as the peer's identity. */
    @Test
    fun theConnectionsIdentityReachesTheResource() = runTest {
        val fixture = ReliableFixture(this)
        val identity = PeerIdentity.PreSharedKey("device-1".encodeToByteArray())
        var seen: PeerIdentity? = null
        fixture.server(port = 5684, scheme = CoapScheme.COAPS_TCP, clientIdentity = identity) {
            resource("whoami") {
                get {
                    seen = peerIdentity
                    CoapResponse.content("ok")
                }
            }
        }
        val client = fixture.client()
        client.get("coaps+tcp://127.0.0.1/whoami")
        assertEquals(identity, seen)
        assertNull(fixture.events.firstOrNull { it is EndpointEvent.AbortSent })
    }

    /** One server on TCP and WebSockets at once, with one resource tree. */
    @Test
    fun aServerListensOnSeveralTransports() = runTest {
        val fixture = ReliableFixture(this)
        val tcp = fixture.listen(5683)
        val ws = fixture.listen(80, CoapScheme.COAP_WS)
        CoapServerOn(listOf(tcp, ws), fixture)
        val client = fixture.client()
        assertEquals("Hello", client.get("coap+tcp://127.0.0.1/hello").payloadAsText())
        assertEquals("Hello", client.get("coap+ws://127.0.0.1/hello").payloadAsText())
    }

    private fun CoapServerOn(listeners: List<dev.kcoap.transport.ConnectionListener>, fixture: ReliableFixture) =
        dev.kcoap.coap.server.CoapServer(listeners, fixture.test.backgroundScope, endpointConfig = fixture.endpointConfig()) {
            resource("hello") { get { CoapResponse.content("Hello") } }
        }
}
