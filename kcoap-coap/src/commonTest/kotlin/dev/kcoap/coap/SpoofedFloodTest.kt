package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RFC 7252 §11.3, §11.4: a UDP source address proves nothing, so a flood of Confirmable requests from any number
 * of forged addresses ([dev.kcoap.testkit.InMemoryNetwork.spoof]) must leave every structure it fills bounded, in
 * total and per peer, while a client at a real address is still served. One test per bound: deduplication
 * (§4.5), Message IDs (§4.4), exchanges in progress, and observers (RFC 7641 §4.1, §4.5); and one per way a
 * request refused before anything acted on it may be processed again, with OSCORE requests (RFC 8613 §7.4) as
 * the ones that never may.
 */
class SpoofedFloodTest {
    private companion object {
        /** Kids of [ToyOscore]: one with a security context, one without, one whose repository fails. */
        const val KID: Byte = 1
        const val NO_CONTEXT: Byte = 2
        const val REPOSITORY_FAILS: Byte = 3

        /** [inner] in an outer message of [outerCode] with the OSCORE option [oscore], and Outer Observe (§4.1.3.5). */
        fun seal(inner: CoapMessage, outerCode: CoapCode, oscore: ByteArray): CoapMessage = CoapMessage.build {
            val plaintext = CoapMessage(CoapMessageType.CON, inner.code, 0, ByteArray(0), inner.options, inner.payload)
            type = inner.type
            messageId = inner.messageId
            token = inner.token
            code = outerCode
            options {
                oscore(oscore)
                inner.options.observe?.let { observe(it) }
            }
            payload = UdpMessageCodec.encode(plaintext)
        }
    }

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

    /** Observes [uri] with [client] and sends the payload of every notification here. */
    private suspend fun Channel<String>.collect(client: CoapClient, uri: String) {
        client.observe(uri).collect { send(it.payloadAsText()) }
    }

    /** Sends [message] from [peer] to [to] and returns the answer, or null when none comes within a second. */
    private suspend fun answerTo(peer: InMemoryTransport, to: Endpoint, message: CoapMessage): CoapMessage? {
        peer.send(to, UdpMessageCodec.encode(message))
        val event = withTimeoutOrNull(1.seconds) { peer.receive() } ?: return null
        return UdpMessageCodec.decode((event as DatagramEvent.Received).datagram)
    }

    /** The identity of the one security context [ToyOscore] has. */
    private object Kid1 : SecurityIdentity {
        override val description: String get() = "kid 1"
    }

    /**
     * A toy OSCORE (RFC 8613): the inner message is encoded into the payload of the outer one, whose OSCORE option
     * holds a kid and a Partial IV. Only [KID] has a security context: [NO_CONTEXT] fails verification (4.01), and
     * [REPOSITORY_FAILS] makes the context repository behind it throw (5.00). Verifying records the option in a
     * replay window (§7.4), so a request verified twice is refused as a replay.
     */
    private class ToyOscore : ServerMessageSecurity {
        private val window = HashSet<List<Byte>>()
        var replays = 0

        override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
            val value = request.options.oscore ?: return null
            when (value.first()) {
                KID -> Unit
                REPOSITORY_FAILS -> error("The context repository failed")
                else -> throw MessageSecurityException("No context", CoapCode.UNAUTHORIZED, "No security context")
            }
            if (!window.add(value.toList())) {
                replays++
                throw MessageSecurityException("Replay", CoapCode.UNAUTHORIZED, "Replay detected")
            }
            val inner = UdpMessageCodec.decode(request.payload)
            return object : UnprotectedRequest {
                override val message = CoapMessage(
                    request.type, inner.code, request.messageId, request.token, inner.options, inner.payload,
                )
                override val identity: SecurityIdentity = Kid1

                override suspend fun protectResponse(response: CoapMessage): CoapMessage {
                    val outerCode = if (response.options.observe != null) CoapCode.CONTENT else CoapCode.CHANGED
                    return seal(response, outerCode, ByteArray(0))
                }
            }
        }
    }

    /** The inner message of an answer [ToyOscore] protected. */
    private fun open(outer: CoapMessage): CoapMessage = UdpMessageCodec.decode(outer.payload)

    /** A Confirmable request under [kid] with Partial IV [messageId]: an outer POST, or with Observe a FETCH. */
    private fun protectedRequest(
        code: CoapCode,
        messageId: Int,
        path: String,
        kid: Byte = KID,
        observe: Long? = null,
    ): CoapMessage {
        val inner = request(code, CoapMessageType.CON, messageId, path, observe)
        val outerCode = if (observe != null) CoapCode.FETCH else CoapCode.POST
        return seal(inner, outerCode, byteArrayOf(kid, (messageId shr 8).toByte(), messageId.toByte()))
    }

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
    fun rfc8613Section8_2AFloodOfPostsThatFailVerificationLeavesRoomForAProtectedPost() = runTest {
        val fixture = StackFixture(this)
        val bounded = fixture.bounded { maxDeduplicationEntries = 400 }
        val server = fixture.server(CoapServerConfig(security = ToyOscore()), bounded) {
            resource("store") { post { CoapResponse.changed() } }
        }
        // Half name no security context (4.01), half meet a failing context repository (5.00): nothing acted on either.
        fixture.flood(server, 2_000) {
            protectedRequest(CoapCode.POST, it, "store", kid = if (it % 2 == 0) NO_CONTEXT else REPOSITORY_FAILS)
        }
        delay(1.seconds)

        assertTrue(server.endpoint.messagingSizes()!!.deduplicationEntries <= 400)
        assertEquals(0, fixture.limits(Limit.DEDUPLICATION_ENTRIES))
        val legitimate = protectedRequest(CoapCode.POST, 7, "store")
        val answer = answerTo(fixture.transport(), fixture.address(server.endpoint), legitimate)
        assertEquals(CoapCode.CHANGED, open(assertNotNull(answer)).code)
    }

    @Test
    fun adr0008AFloodOfPostsTheAccessPolicyRefusesLeavesRoomForALegitimatePost() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.bounded { maxDeduplicationEntries = 400 }) {
            resource("admin") {
                access = AccessPolicy { false }
                post { CoapResponse.changed() }
            }
            resource("store") { post { CoapResponse.changed() } }
        }
        fixture.flood(server, 2_000) { request(CoapCode.POST, CoapMessageType.CON, it, "admin") }
        delay(1.seconds)

        assertEquals(0, fixture.limits(Limit.DEDUPLICATION_ENTRIES), "a 4.01 before any work may be processed again")
        assertEquals(CoapCode.CHANGED, fixture.client().post(fixture.uri(server, "/store"), byteArrayOf(1)).code)
    }

    private suspend fun TestScope.floodBeyondTheHandlers(type: CoapMessageType) {
        val fixture = StackFixture(this)
        val server = fixture.server(
            endpointConfig = fixture.bounded {
                maxDeduplicationEntries = 400
                maxConcurrentRequests = 2
            },
        ) {
            resource("slow") {
                post {
                    delay(2.seconds)
                    CoapResponse.changed()
                }
            }
        }
        // Two run; every other one is refused before its handler, with 5.03 if Confirmable, else dropped.
        fixture.flood(server, 2_000) { request(CoapCode.POST, type, it, "slow") }
        delay(3.seconds)

        assertEquals(1_998, fixture.limits(Limit.CONCURRENT_REQUESTS))
        assertEquals(0, fixture.limits(Limit.DEDUPLICATION_ENTRIES), "no handler ran, so they may be processed again")
        assertEquals(CoapCode.CHANGED, fixture.client().post(fixture.uri(server, "/slow"), byteArrayOf(1)).code)
    }

    @Test
    fun rfc7252Section5_9_3_4AFloodOfConfirmablePostsBeyondTheHandlersLeavesRoomForALegitimatePost() = runTest {
        floodBeyondTheHandlers(CoapMessageType.CON)
    }

    @Test
    fun rfc7252Section4_5AFloodOfNonConfirmablePostsBeyondTheHandlersLeavesRoomForALegitimatePost() = runTest {
        floodBeyondTheHandlers(CoapMessageType.NON)
    }

    /**
     * RFC 7252 §4.5 relaxes deduplication only for requests processed again with the same result, which an OSCORE
     * request is not (RFC 8613 §7.4): its retransmission after a lost ACK must get the answer it got, under any
     * pressure on the cache, and never reach the replay window again, for an Observe registration (an outer FETCH)
     * and for a request refused inside the protection (a 4.04) alike.
     */
    @Test
    fun rfc8613Section7_4AnOscoreRequestIsNeverForgottenForRoom() = runTest {
        val fixture = StackFixture(this)
        val security = ToyOscore()
        val gauge = Gauge("g")
        val bounded = fixture.bounded { maxDeduplicationEntries = 40 }
        val server = fixture.server(CoapServerConfig(security = security), bounded) {
            add(gauge)
            resource("r") { get { CoapResponse.content("ok") } }
        }
        val address = fixture.address(server.endpoint)
        val peer = fixture.transport()
        val registration = protectedRequest(CoapCode.GET, 1, "g", observe = 0)
        val missing = protectedRequest(CoapCode.POST, 2, "missing")
        // The client never sees these answers, as if the ACKs were lost.
        val registered = assertNotNull(answerTo(peer, address, registration))
        val refused = assertNotNull(answerTo(peer, address, missing))
        assertEquals(CoapCode.CONTENT, open(registered).code)
        assertEquals(CoapCode.NOT_FOUND, open(refused).code)

        // GETs from forged sources, each refused unprotected (4.01) and so repeatable, churn through the cache; in
        // bursts smaller than the cache, so it always holds answered ones to make room with.
        fixture.flood(server, 400, burst = 10) { request(CoapCode.GET, CoapMessageType.CON, it, "r") }
        delay(1.seconds)
        assertEquals(0, fixture.limits(Limit.DEDUPLICATION_ENTRIES))

        // Their retransmissions get the same ACKs from the deduplication cache.
        for ((request, answer) in listOf(registration to registered, missing to refused)) {
            val again = assertNotNull(answerTo(peer, address, request))
            assertContentEquals(UdpMessageCodec.encode(answer), UdpMessageCodec.encode(again))
        }
        assertEquals(0, security.replays)
        assertEquals(1, gauge.observerCount)
    }

    @Test
    fun rfc7252Section4_4AFloodOfNonConfirmableGetsLeavesMessageIdsForWhatTheEndpointMustSend() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge("g")
        val server = fixture.server(endpointConfig = fixture.bounded { maxReservedMessageIds = 300 }) {
            resource("r") { get { CoapResponse.content("ok") } }
            resource("slow") {
                get {
                    delay(3.seconds)
                    CoapResponse.content("done")
                }
            }
            add(gauge)
        }
        val client = fixture.client(CoapClientConfig { requestTimeout = 10.seconds })
        val notifications = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch { notifications.collect(client, fixture.uri(server, "/g")) }
        assertEquals("0", notifications.receive())

        // Every Non-confirmable request gets a Non-confirmable response, with a Message ID of its own: they take
        // three quarters of the IDs at most, since a client copes with losing them (RFC 7252 §5.2.3).
        fixture.flood(server, 1_000) { request(CoapCode.GET, CoapMessageType.NON, it, "r") }
        delay(1.seconds)
        assertEquals(225, server.endpoint.messagingSizes()!!.reservedMessageIds)
        assertEquals(775, fixture.limits(Limit.MESSAGE_IDS))

        // A piggybacked response needs no new Message ID, so a Confirmable GET is served.
        assertEquals("ok", client.get(fixture.uri(server, "/r")).payloadAsText())
        // The last quarter is left for what must go: a notification, the separate response to a slow request ...
        gauge.value = 1
        assertEquals("1", withTimeout(5.seconds) { notifications.receive() })
        assertEquals("done", client.get(fixture.uri(server, "/slow")).payloadAsText())
        // ... and a request of the endpoint's own, here of a client that shares the server's endpoint.
        val other = fixture.server { resource("x") { get { CoapResponse.content("x") } } }
        assertEquals("x", CoapClient(server.endpoint).get(fixture.uri(other, "/x")).payloadAsText())
        assertEquals(1, gauge.observerCount)
        assertEquals(775, fixture.limits(Limit.MESSAGE_IDS), "nothing else was refused")

        // §4.4 forbids reusing the IDs before EXCHANGE_LIFETIME; then Non-confirmable responses go out again.
        delay(server.endpoint.parameters.exchangeLifetime)
        assertEquals("ok", client.get(fixture.uri(server, "/r")) { nonConfirmable() }.payloadAsText())
    }

    @Test
    fun rfc7252Section4_4AFloodFromOneSourceKeepsItsMessageIdsBoundedAndServesOthers() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(endpointConfig = fixture.bounded { maxReservedMessageIdsPerPeer = 100 }) {
            resource("r") { get { CoapResponse.content("ok") } }
        }
        fixture.flood(server, 1, perSource = 400) { request(CoapCode.GET, CoapMessageType.NON, it, "r") }
        delay(1.seconds)

        assertEquals(75, server.endpoint.messagingSizes()!!.reservedMessageIds, "three quarters of the source's bound")
        assertEquals(325, fixture.limits(Limit.MESSAGE_IDS_PER_PEER))
        assertEquals(0, fixture.limits(Limit.MESSAGE_IDS))
        // Another peer has IDs of its own, also for a Non-confirmable response.
        assertEquals("ok", fixture.client().get(fixture.uri(server, "/r")) { nonConfirmable() }.payloadAsText())
    }

    @Test
    fun rfc7641Section4_5ANotificationAFullEndpointRefusesKeepsTheObserverAndTheLatestStateFollows() = runTest {
        val fixture = StackFixture(this)
        val gauge = Gauge("g")
        val server = fixture.server(endpointConfig = fixture.bounded { maxReservedMessageIds = 3 }) { add(gauge) }
        val notifications = Channel<String>(Channel.UNLIMITED)
        val client = fixture.client()
        backgroundScope.launch { notifications.collect(client, fixture.uri(server, "/g")) }
        assertEquals("0", notifications.receive())

        // Three notifications take the three Message IDs for EXCHANGE_LIFETIME; the fourth finds none.
        for (value in 1..4) {
            gauge.value = value
            delay(1.seconds)
        }
        assertEquals(listOf("1", "2", "3"), List(3) { notifications.receive() })
        assertTrue(fixture.limits(Limit.MESSAGE_IDS) >= 1)
        assertEquals(1, gauge.observerCount, "the refusal says nothing about the client")
        assertTrue(fixture.events.none { it is EndpointEvent.ObserverRemoved })
        assertNull(notifications.tryReceive().getOrNull())

        // RFC 7641 §4.5: once the IDs expired, the latest state goes out after the back-off, without another change.
        delay(server.endpoint.parameters.exchangeLifetime + 1.minutes)
        assertEquals("4", notifications.receive())
        assertEquals(1, gauge.observerCount)
        assertTrue(fixture.events.none { it is EndpointEvent.ObserverRemoved })
        // With IDs free again, the next change goes out at once.
        gauge.value = 5
        assertEquals("5", withTimeout(5.seconds) { notifications.receive() })
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

    /** An observable value; setting it notifies the observers. */
    private class Gauge(name: String) : ObservableResource(name) {
        var value = 0
            set(value) {
                field = value
                notifyObservers()
            }

        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("$value")
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
