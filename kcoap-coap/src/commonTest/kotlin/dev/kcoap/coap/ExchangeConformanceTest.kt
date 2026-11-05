package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.EndpointResolver
import dev.kcoap.coap.client.ObserveCancellation
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.server.ResourceBuilder
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.common.toHex
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.testkit.InMemoryLink
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.testkit.InMemorySessions
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * The exchange layer behaves the same on every link (ADR 0005): each test runs once over datagrams
 * ([DatagramLinkExchangeTest]) and once on connections ([ConnectionLinkExchangeTest]), between kcoap's own
 * client and server on kcoap-testkit's [InMemoryLink], on virtual time, on every target. Block-wise both ways
 * and within the link's limits, Q-Block both ways, Observe, Echo, Request-Tag, the access policy and
 * unverified responses go through the same code on both; where RFC 7252 and RFC 8323 differ, the subclass
 * says what its link does: whether it carries BERT blocks (RFC 8323 §6), whether it supersedes notifications
 * and has Resets (RFC 7641 §3.6, §4.5.2), and what a lost link fails its exchanges with.
 */
abstract class ExchangeConformanceTest {
    /**
     * The link under test, on [network] or on [connections], carrying messages of at most [maxMessageSize] bytes
     * when it is given, whose channel security reports [identity] for the client.
     */
    protected abstract fun link(
        network: InMemoryNetwork,
        connections: InMemoryConnections,
        maxMessageSize: Int?,
        identity: PeerIdentity,
    ): InMemoryLink

    /** Whether one message carries several 1024-byte blocks where both sides indicate BERT (RFC 8323 §6). */
    protected abstract val carriesBert: Boolean

    /**
     * Whether a notification awaits an acknowledgement, so that a newer state supersedes it (RFC 7641 §4.5.2)
     * and states that change faster than the acknowledgements come are skipped; on a connection every
     * notification is written and done (RFC 8323 §7.2).
     */
    protected abstract val supersedes: Boolean

    /**
     * Whether the client answers a notification it does not want with a Reset (RFC 7641 §3.6); RFC 8323 has
     * none, so on a connection the client always deregisters (§7.4).
     */
    protected abstract val hasReset: Boolean

    /**
     * What a lost link fails the exchanges and observations on it with: an ended DTLS session over datagrams
     * (RFC 7252 §9.1.1), a closed connection (RFC 8323 §3.4, §7.4).
     */
    protected abstract val lostLinkReason: CoapException.Reason

    private val loggerFactory = LoggerFactory(
        LogConfig.build {
            level = LogLevel.TRACE
            sink = LogSink.None
            payloadDumps = true
        },
    )

    /**
     * A server and a client on one [link], whose datagrams go on [network]; [sent] and [received] are the
     * client's messages, [serverSent] the server's.
     */
    private class Setup(
        val link: InMemoryLink,
        val network: InMemoryNetwork,
        val server: CoapServer,
        val client: CoapClient,
        val side: InMemoryLink.Side,
        val sent: List<CoapMessage>,
        val received: List<CoapMessage>,
        val serverSent: List<CoapMessage>,
    ) {
        fun uri(path: String): String = link.uri + path

        /** The responses the server sent with an Observe option: the registration's, then the notifications. */
        fun notifications(): List<CoapMessage> = serverSent.filter { it.isResponse && it.options.observe != null }

        /** Over datagrams, loses what the client sends while [lost]; a connection loses nothing. */
        fun loseClientDatagrams(lost: Boolean) {
            val client = (side.transport as? InMemorySessions)?.localEndpoint ?: return
            network.filter = if (lost) { datagram -> datagram.from != client } else { _ -> true }
        }

        /** The endpoint the client reaches the server at. */
        suspend fun serverPeer(): Endpoint = side.peer(EndpointResolver.Default.resolve(CoapUri.parse(link.uri)))
    }

    private suspend fun TestScope.setup(
        server: CoapServerConfig = CoapServerConfig(),
        maxMessageSize: Int? = null,
        identity: PeerIdentity = PeerIdentity.None,
        client: (EndpointResolver) -> CoapClientConfig = { CoapClientConfig(resolver = it) },
        routes: ResourceBuilder.() -> Unit,
    ): Setup {
        val network = InMemoryNetwork(backgroundScope, Random(1))
        val link = link(network, InMemoryConnections(chunkSize = 7), maxMessageSize, identity)
        val reliable = maxMessageSize?.let { ReliableTransportConfig(maxMessageSize = it.toLong()) } ?: ReliableTransportConfig()
        val sent = mutableListOf<CoapMessage>()
        val received = mutableListOf<CoapMessage>()
        val serverSent = mutableListOf<CoapMessage>()
        val recorder = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { sent += it }

            override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage = message.also { received += it }
        }
        val serverRecorder = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { serverSent += it }
        }
        fun config(seed: Int, interceptors: List<MessageInterceptor>) = CoapEndpointConfig(
            timeSource = testScheduler.timeSource,
            random = Random(seed),
            interceptors = interceptors,
            loggerFactory = loggerFactory,
            reliable = reliable,
        )
        val serverSide = link.server()
        val serverEndpoint = CoapEndpoint(backgroundScope, config(1, listOf(serverRecorder)), serverSide.transport, serverSide.connectors, serverSide.listeners)
        val clientSide = link.client()
        val clientEndpoint = CoapEndpoint(backgroundScope, config(2, listOf(recorder)), clientSide.transport, clientSide.connectors, clientSide.listeners)
        val resolver = EndpointResolver { uri -> clientSide.peer(EndpointResolver.Default.resolve(uri)) }
        return Setup(
            link, network, CoapServer(serverEndpoint, server, routes), CoapClient(clientEndpoint, client(resolver)), clientSide,
            sent, received, serverSent,
        )
    }

    private fun body(size: Int, seed: Int = 0): ByteArray = ByteArray(size) { (it * 31 + seed + it / 251).toByte() }

    /** A resource whose state is a number that [change] increases. */
    private class Counter : ObservableResource("counter") {
        var value = 0
            private set

        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("$value")

        fun change() {
            value++
            notifyObservers()
        }
    }

    // ---------------------------------------------------------------- requests and block-wise transfers

    @Test
    fun aRequestGetsItsResponse() = runTest {
        val setup = setup {
            resource("hello") {
                get { CoapResponse.content("Hello") }
                put { CoapResponse.changed() }
            }
        }

        assertEquals("Hello", setup.client.get(setup.uri("/hello")).payloadAsText())
        assertEquals(CoapCode.CHANGED, setup.client.put(setup.uri("/hello"), "x".encodeToByteArray()).code)
        assertEquals(CoapCode.NOT_FOUND, setup.client.get(setup.uri("/missing")).code)
        assertTrue(setup.client.ping(setup.uri("")))
    }

    /** RFC 7959 §2.4, and RFC 8323 §6 on connections: a body larger than one message comes in blocks. */
    @Test
    fun rfc7959Section2_4ABodyLargerThanAMessageIsDownloadedInBlocks() = runTest {
        val whole = body(20_000)
        val setup = setup { resource("big") { get { CoapResponse.content(whole, ContentFormat.OCTET_STREAM) } } }

        assertContentEquals(whole, setup.client.get(setup.uri("/big")).payload)
        assertTrue(setup.received.count { it.options.block2 != null } > 1, "in blocks")
    }

    /** RFC 7959 §2.5, and RFC 8323 §6 on connections: a body larger than one message goes up in blocks. */
    @Test
    fun rfc7959Section2_5ABodyLargerThanAMessageIsUploadedInBlocks() = runTest {
        val upload = body(20_000, seed = 1)
        var stored = ByteArray(0)
        val setup = setup {
            resource("store") {
                put {
                    stored = payload
                    CoapResponse.changed()
                }
            }
        }

        assertEquals(CoapCode.CHANGED, setup.client.put(setup.uri("/store"), upload).code)
        assertContentEquals(upload, stored)
        assertTrue(setup.sent.count { it.options.block1 != null } > 1, "in blocks")
    }

    /** RFC 8323 §6: BERT blocks only where both CSMs indicate BERT, never over datagrams (RFC 7959 §2.2). */
    @Test
    fun rfc8323Section6BertBlocksGoOnlyWhereBothSidesIndicateThem() = runTest {
        val whole = body(20_000)
        var stored = ByteArray(0)
        val setup = setup {
            resource("big") {
                get { CoapResponse.content(whole, ContentFormat.OCTET_STREAM) }
                put {
                    stored = payload
                    CoapResponse.changed()
                }
            }
        }

        assertContentEquals(whole, setup.client.get(setup.uri("/big")).payload)
        assertEquals(CoapCode.CHANGED, setup.client.put(setup.uri("/big"), whole).code)
        assertContentEquals(whole, stored)
        val blocks = setup.received.mapNotNull { it.options.block2 } + setup.sent.mapNotNull { it.options.block1 }
        assertEquals(carriesBert, blocks.any { it.isBert }, "BERT blocks: $blocks")
    }

    /**
     * RFC 7252 §4.6 and RFC 8323 §5.3.1: every message the client sends and receives fits what the link carries,
     * the datagram size or the peer's Max-Message-Size, so the block sizes follow it both ways.
     */
    @Test
    fun rfc7252Section4_6EveryMessageFitsTheLinksLimits() = runTest {
        val whole = body(3000)
        var stored = ByteArray(0)
        val setup = setup(maxMessageSize = 300) {
            resource("big") {
                get { CoapResponse.content(whole, ContentFormat.OCTET_STREAM) }
                put {
                    stored = payload
                    CoapResponse.changed()
                }
            }
        }

        assertContentEquals(whole, setup.client.get(setup.uri("/big")).payload)
        assertEquals(CoapCode.CHANGED, setup.client.put(setup.uri("/big"), whole).code)
        assertContentEquals(whole, stored)
        val limits = setup.client.endpoint.limits(setup.serverPeer())
        assertEquals(300, limits.maxMessageSize)
        val messages = (setup.sent + setup.received).filter { it.isRequest || it.isResponse }
        assertTrue(messages.size > 2 * 3000 / 256, "in blocks")
        assertTrue(messages.all { limits.size(it) <= 300 }, "the largest is ${messages.maxOf { limits.size(it) }} bytes")
    }

    // ---------------------------------------------------------------- Q-Block (RFC 9177)

    private val qBlockServer = CoapServerConfig(qBlock = QBlockServerConfig(isEnabled = true), amplification = AmplificationPolicy.OFF)

    private fun qBlockClient(resolver: EndpointResolver) = CoapClientConfig(
        resolver = resolver,
        blockSize = 64,
        qBlock = QBlockClientConfig(QBlockMode.PROBE, onReliableTransports = true),
    )

    /** RFC 9177 §4.4, and Appendix B on connections: a response body in Q-Block2 blocks. */
    @Test
    fun rfc9177Section4_4AResponseBodyComesInQBlock2Blocks() = runTest {
        val whole = body(3000)
        val setup = setup(qBlockServer, client = ::qBlockClient) {
            resource("big") { get { CoapResponse.content(whole, ContentFormat.OCTET_STREAM) } }
        }

        assertContentEquals(whole, setup.client.get(setup.uri("/big")).payload)
        assertTrue(setup.received.count { it.options.qBlock2 != null } > 3000 / 64, "in Q-Block2 blocks")
        assertTrue(setup.received.none { it.options.block2 != null }, "not in Block2 blocks")
    }

    /** RFC 9177 §4.3, and Appendix B on connections: a request body in Q-Block1 blocks. */
    @Test
    fun rfc9177Section4_3ARequestBodyGoesInQBlock1Blocks() = runTest {
        val upload = body(3000, seed = 2)
        var stored = ByteArray(0)
        val setup = setup(qBlockServer, client = ::qBlockClient) {
            resource("store") {
                put {
                    stored = payload
                    CoapResponse.changed()
                }
            }
        }

        assertEquals(CoapCode.CHANGED, setup.client.put(setup.uri("/store"), upload).code)
        assertContentEquals(upload, stored)
        assertTrue(setup.sent.count { it.options.qBlock1 != null } >= 3000 / 64, "in Q-Block1 blocks")
        assertTrue(setup.sent.none { it.options.block1 != null }, "not in Block1 blocks")
    }

    // ---------------------------------------------------------------- Observe (RFC 7641)

    /**
     * RFC 7641 §4.5: when changes come faster than notifications are acknowledged, the server keeps only the
     * latest state, superseding the outstanding notification over datagrams (§4.5.2) and sending the latest once
     * its ACK came, so states may be skipped but never arrive out of order, and the latest one always arrives.
     * A connection acknowledges nothing (RFC 8323 §7.2), so there every state goes out, in order.
     */
    @Test
    fun rfc7641Section4_5ChangesThatOutpaceTheLinkSkipStatesButNeverReorderThem() = runTest {
        val counter = Counter()
        val setup = setup { add(counter) }
        val seen = async {
            setup.client.observe(setup.uri("/counter"))
                .map { it.payloadAsText().toInt() }
                .transformWhile {
                    emit(it)
                    it < 50
                }
                .toList()
        }
        delay(1.seconds)
        assertEquals(1, counter.observerCount)
        // Over datagrams an ACK comes 200 ms after its notification went out, while the state changes every 50 ms.
        setup.network.conditions = LinkConditions(latency = 100.milliseconds)

        repeat(50) {
            counter.change()
            delay(50.milliseconds)
        }

        val values = withTimeout(10.minutes) { seen.await() }
        assertEquals(50, values.last(), "the latest state arrived")
        assertTrue(values.zipWithNext().all { (older, newer) -> older < newer }, "never an older state after a newer one: $values")
        val notifications = setup.notifications().drop(1).map { it.payloadAsText().toInt() }
        if (supersedes) {
            assertTrue(notifications.size in 2..<50, "states were skipped, more than once: $notifications")
        } else {
            assertEquals((0..50).toList(), values, "every state, in order")
            assertEquals((1..50).toList(), notifications)
        }
    }

    /**
     * RFC 7641 §4.5.2: a change while a Confirmable notification still awaits its ACK replaces it at its next
     * retransmission, with a new Message ID, instead of waiting behind it (NSTART 1), so a state superseded in
     * between never goes out, the newest one does, and the observer stays. A connection has no
     * acknowledgements and nothing to supersede: every state is written in order (RFC 8323 §7.2).
     */
    @Test
    fun rfc7641Section4_5_2AChangeSupersedesTheNotificationAwaitingItsAcknowledgement() = runTest {
        val counter = Counter()
        val setup = setup { add(counter) }
        val received = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch { setup.client.observe(setup.uri("/counter")).collect { received.send(it.payloadAsText()) } }
        assertEquals("0", received.receive())

        // Over datagrams the client's ACKs are lost for a while, so notification 1 stays outstanding.
        setup.loseClientDatagrams(true)
        counter.change()
        delay(1.seconds)
        counter.change()
        delay(100.milliseconds)
        counter.change()
        // Notification 1's first timeout, 2 to 3 s after it went out, has passed; no ACK came yet.
        delay(4.seconds)
        val beforeAnyAck = setup.notifications().drop(1).map { it.payloadAsText() }
        setup.loseClientDatagrams(false)
        delay(30.seconds)

        val states = generateSequence { received.tryReceive().getOrNull() }.toList()
        val notifications = setup.notifications().drop(1)
        if (supersedes) {
            assertEquals(listOf("1", "3"), beforeAnyAck, "the retransmission of 1 carried the newest state")
            assertEquals(listOf("1", "3"), states, "state 2 was skipped, the latest arrived")
            assertEquals(listOf("1", "3"), notifications.map { it.payloadAsText() }, "state 2 never went out")
            assertTrue(notifications.all { it.type == CoapMessageType.CON })
            assertNotEquals(notifications[0].messageId, notifications[1].messageId, "the replacement has a new Message ID")
        } else {
            assertEquals(listOf("1", "2", "3"), beforeAnyAck, "nothing waits for an acknowledgement")
            assertEquals(listOf("1", "2", "3"), states, "every state, in order")
            assertEquals(listOf("1", "2", "3"), notifications.map { it.payloadAsText() })
        }
        assertEquals(1, counter.observerCount, "the observer stays")
    }

    /**
     * RFC 7641 §3.6: an observation the client stops is deregistered, on connections with Observe 1 because
     * RFC 8323 has no Reset (§7.4), and the server forgets the observer.
     */
    @Test
    fun rfc7641Section3_6AStoppedObservationIsDeregistered() = runTest {
        val counter = Counter()
        val setup = setup { add(counter) }
        val two = async { setup.client.observe(setup.uri("/counter")).take(2).toList() }
        delay(1.seconds)
        assertEquals(1, counter.observerCount)

        counter.change()

        assertEquals(listOf("0", "1"), two.await().map { it.payloadAsText() })
        delay(1.seconds)
        assertEquals(0, counter.observerCount, "the server forgot the observer")
        assertTrue(setup.sent.any { it.isRequest && it.options.observe == 1L }, "a deregistration")
    }

    /**
     * RFC 7641 §3.6 with [ObserveCancellation.FORGET]: over datagrams the client only forgets the token and
     * answers the next notification with a Reset, which ends the observation on the server. A connection has no
     * Reset, so there the client deregisters with Observe 1 all the same (RFC 8323 §7.4).
     */
    @Test
    fun rfc7641Section3_6AForgottenObservationEndsOnTheServerOnEveryLink() = runTest {
        val counter = Counter()
        val forgetting = { resolver: EndpointResolver -> CoapClientConfig(resolver = resolver, observeCancellation = ObserveCancellation.FORGET) }
        val setup = setup(client = forgetting) { add(counter) }
        val two = async { setup.client.observe(setup.uri("/counter")).take(2).toList() }
        delay(1.seconds)
        counter.change()
        assertEquals(listOf("0", "1"), two.await().map { it.payloadAsText() })
        delay(1.seconds)

        val deregistrations = setup.sent.filter { it.isRequest && it.options.observe == 1L }
        if (hasReset) {
            assertEquals(emptyList(), deregistrations, "the client only forgot the token")
            assertEquals(1, counter.observerCount, "the server does not know yet")
            counter.change()
            delay(1.seconds)
            assertTrue(setup.sent.any { it.type == CoapMessageType.RST }, "the next notification was answered with a Reset")
        } else {
            assertEquals(1, deregistrations.size, "a deregistration all the same: $deregistrations")
        }
        assertEquals(0, counter.observerCount, "the server forgot the observer")
    }

    /**
     * A lost link fails what was on it: an ended DTLS session (RFC 7252 §9.1.1) or a closed connection
     * (RFC 8323 §3.4, §7.4) ends the observation and the exchange in progress for the client, and the server
     * drops its observer.
     */
    @Test
    fun aLostLinkEndsTheObservationsAndExchangesOnIt() = runTest {
        val counter = Counter()
        val gate = CompletableDeferred<Unit>()
        val setup = setup {
            add(counter)
            resource("slow") {
                get {
                    gate.await()
                    CoapResponse.content("late")
                }
            }
        }
        val observation = async { runCatching { setup.client.observe(setup.uri("/counter")).toList() } }
        delay(1.seconds)
        assertEquals(1, counter.observerCount)
        val pending = async { runCatching { setup.client.get(setup.uri("/slow")) } }
        delay(2.seconds)

        setup.link.cut()

        assertEquals(lostLinkReason, assertIs<CoapException>(observation.await().exceptionOrNull()).reason)
        assertEquals(lostLinkReason, assertIs<CoapException>(pending.await().exceptionOrNull()).reason)
        delay(1.seconds)
        assertEquals(0, counter.observerCount, "the server dropped its observer with the link")
        gate.complete(Unit)
        // A new session or connection serves the next request.
        assertEquals("0", setup.client.get(setup.uri("/counter")).payloadAsText())
    }

    // ---------------------------------------------------------------- Echo and Request-Tag (RFC 9175)

    /** RFC 9175 §2.3: a 4.01 with Echo is answered once, with that Echo value. */
    @Test
    fun rfc9175Section2_3AFreshnessChallengeIsAnsweredOnceWithItsEcho() = runTest {
        val value = byteArrayOf(1, 2, 3, 4)
        var requests = 0
        val setup = setup {
            resource("fresh") {
                get {
                    requests++
                    if (options.echo?.contentEquals(value) == true) {
                        CoapResponse.content("fresh")
                    } else {
                        CoapResponse(CoapCode.UNAUTHORIZED, OptionSet.build { echo(value) })
                    }
                }
            }
        }

        assertEquals("fresh", setup.client.get(setup.uri("/fresh")).payloadAsText())
        assertEquals(2, requests)
    }

    /** RFC 9175 §3.5.2: uploads to one resource at once get Request-Tags of their own, so their blocks never mix. */
    @Test
    fun rfc9175Section3_5ConcurrentUploadsKeepTheirBodiesApart() = runTest {
        val first = body(20_000, seed = 3)
        val second = body(20_000, seed = 4)
        val stored = mutableListOf<ByteArray>()
        val setup = setup {
            resource("store") {
                put {
                    stored += payload
                    CoapResponse.changed()
                }
            }
        }

        val a = async { setup.client.put(setup.uri("/store"), first) }
        val b = async { setup.client.put(setup.uri("/store"), second) }

        assertEquals(CoapCode.CHANGED, a.await().code)
        assertEquals(CoapCode.CHANGED, b.await().code)
        assertEquals(2, stored.size)
        assertTrue(stored.any { it.contentEquals(first) } && stored.any { it.contentEquals(second) }, "both bodies whole")
        val tags = setup.sent.filter { it.options.block1 != null }.map { message -> message.options.requestTags.joinToString { it.toHex() } }
        assertEquals(2, tags.toSet().size, "one Request-Tag per upload: ${tags.toSet()}")
    }

    // ---------------------------------------------------------------- access (ADR 0008)

    /**
     * ADR 0008: the access policy decides before any work for a request, and sees what the link's channel
     * security authenticated: the DTLS session, or TLS on the connection.
     */
    @Test
    fun adr0008TheAccessPolicyDecidesBeforeTheHandlerRuns() = runTest {
        var seenBy: PeerIdentity? = null
        val routes: ResourceBuilder.() -> Unit = {
            resource("private") {
                access = AccessPolicy.Authenticated
                get {
                    seenBy = peerIdentity
                    CoapResponse.content("secret")
                }
            }
        }
        val anonymous = setup(routes = routes)

        assertEquals(CoapCode.UNAUTHORIZED, anonymous.client.get(anonymous.uri("/private")).code)
        assertEquals(null, seenBy, "the handler never ran")

        val identity = PeerIdentity.PreSharedKey("device-1".encodeToByteArray())
        val authenticated = setup(identity = identity, routes = routes)
        assertEquals("secret", authenticated.client.get(authenticated.uri("/private")).payloadAsText())
        assertEquals(identity, seenBy)
    }

    /**
     * Message security on the client: one that cannot verify the server's only answer, an unprotected error
     * (RFC 8613 §8.2), fails the request with [CoapException.Reason.UNVERIFIED_RESPONSE], and the observation
     * too, and acts on nothing in it (ADR 0008).
     */
    @Test
    fun adr0008AnUnverifiedErrorFailsTheRequestAndActsOnNothing() = runTest {
        var ran = false
        val counter = Counter()
        val setup = setup(server = CoapServerConfig(security = RefusingServer), client = { CoapClientConfig(security = MarkingClient, resolver = it) }) {
            add(counter)
            resource("lights") {
                get {
                    ran = true
                    CoapResponse.content("on")
                }
            }
        }

        val request = assertIs<CoapException>(runCatching { setup.client.get(setup.uri("/lights")) }.exceptionOrNull())
        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, request.reason)
        assertEquals(CoapCode.UNAUTHORIZED, request.response?.code)
        val observation = assertIs<CoapException>(runCatching { setup.client.observe(setup.uri("/counter")).toList() }.exceptionOrNull())
        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, observation.reason)
        assertFalse(ran)
        assertEquals(0, counter.observerCount)
    }

    /** Client message security that marks each request with an empty OSCORE option and verifies only marked answers. */
    private object MarkingClient : ClientMessageSecurity {
        override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest = object : ProtectedRequest {
            override val message: CoapMessage = request.toBuilder().options { oscore(ByteArray(0)) }.build()

            override suspend fun unprotectResponse(response: CoapMessage): ResponseVerification {
                if (response.options.oscore != null) {
                    return ResponseVerification.Verified(response.toBuilder().options { remove(CoapOptions.OSCORE.number) }.build())
                }
                if (response.code.isClientError || response.code.isServerError) return ResponseVerification.Unverified(response, "unprotected")
                throw MessageSecurityException("Unprotected ${response.code}")
            }
        }
    }

    /** Server message security that cannot verify any request, so it answers each one unprotected (RFC 8613 §8.2). */
    private object RefusingServer : ServerMessageSecurity {
        override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? =
            throw MessageSecurityException("no context", CoapCode.UNAUTHORIZED, "Security context not found")
    }
}
