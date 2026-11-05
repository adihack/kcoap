package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.NotificationOrder
import dev.kcoap.coap.client.ObserveCancellation
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.testkit.DatagramFate
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ObserveTest {
    /** An observable counter whose state the test changes. */
    private class Counter(var value: Int = 0, var code: CoapCode = CoapCode.CONTENT) : ObservableResource("counter") {
        override suspend fun handleGet(context: RequestContext): CoapResponse =
            if (code.isSuccess) CoapResponse.content("$value").withOptions { maxAge(30) } else CoapResponse(code)

        fun change(newValue: Int) {
            value = newValue
            notifyObservers()
        }
    }

    private class NonCounter : ObservableResource("non") {
        var value = 0
        override val notificationType: CoapMessageType get() = CoapMessageType.NON
        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("$value")
    }

    @Test
    fun rfc7641Section3NotificationsArriveUntilTheCollectorStops() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter()
        val server = fixture.server { add(counter) }
        val client = fixture.client()

        val received = async { client.observe(fixture.uri(server, "/counter")).take(4).toList() }
        runCurrent()
        delay(1.seconds)
        assertEquals(1, counter.observerCount)
        for (value in 1..3) {
            counter.change(value)
            delay(5.seconds)
        }
        assertEquals(listOf("0", "1", "2", "3"), received.await().map { it.payloadAsText() })
        delay(5.seconds)
        assertEquals(0, counter.observerCount, "deregistered (RFC 7641 §3.6)")

        val requests = fixture.traffic.mapNotNull { it.message }.filter { it.isRequest }
        assertEquals(listOf(0L, 1L), requests.map { it.options.observe })
        assertEquals(requests[0].tokenHex, requests[1].tokenHex, "deregistration uses the observation's token")
        val notifications = fixture.traffic.mapNotNull { it.message }.filter { it.isResponse && it.options.observe != null }
        val values = notifications.map { it.options.observe!! }
        assertEquals(values.sorted(), values, "strictly increasing sequence numbers (§4.4)")
        assertEquals(values.toSet().size, values.size)
        assertTrue(notifications.drop(1).all { it.type == CoapMessageType.CON })
        assertTrue(fixture.events.any { it is EndpointEvent.ObserverAdded })
        assertTrue(fixture.events.any { it is EndpointEvent.ObserverRemoved })
    }

    @Test
    fun rfc7641Section3_6ForgettingAnObservationResetsTheNextNotification() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter()
        val server = fixture.server { add(counter) }
        val client = fixture.client(CoapClientConfig(observeCancellation = ObserveCancellation.FORGET))

        assertEquals("0", client.observe(fixture.uri(server, "/counter")).first().payloadAsText())
        delay(1.seconds)
        assertEquals(1, counter.observerCount)
        counter.change(1)
        delay(5.seconds)
        assertEquals(0, counter.observerCount, "the client's RST removed the observer (§3.5)")
        assertTrue(fixture.traffic.any { it.message?.type == CoapMessageType.RST })
    }

    @Test
    fun rfc7641Section3_1CollectorsOfOneResourceShareOneRegistration() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter()
        val server = fixture.server { add(counter) }
        val client = fixture.client()
        val uri = fixture.uri(server, "/counter")

        val first = async { client.observe(uri).take(2).toList() }
        val second = async { client.observe(uri).take(2).toList() }
        delay(1.seconds)
        counter.change(1)
        delay(1.seconds)
        assertEquals(listOf("0", "1"), first.await().map { it.payloadAsText() })
        assertEquals(listOf("0", "1"), second.await().map { it.payloadAsText() })
        assertEquals(1, fixture.traffic.count { it.message?.options?.observe == 0L && it.message.isRequest })
    }

    @Test
    fun rfc7641Section2ANonObservableResourceAnswersOnce() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("plain") { get { CoapResponse.content("once") } } }
        val client = fixture.client()

        val responses = client.observe(fixture.uri(server, "/plain")).toList()
        assertEquals(listOf("once"), responses.map { it.payloadAsText() })
        assertNull(fixture.traffic.mapNotNull { it.message }.last().options.observe)
    }

    @Test
    fun rfc7641Section3_2AnErrorNotificationEndsTheObservation() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter()
        val server = fixture.server { add(counter) }
        val client = fixture.client()

        val responses = async { client.observe(fixture.uri(server, "/counter")).toList() }
        delay(1.seconds)
        counter.code = CoapCode.SERVICE_UNAVAILABLE
        counter.notifyObservers()
        assertEquals(listOf(CoapCode.CONTENT, CoapCode.SERVICE_UNAVAILABLE), responses.await().map { it.code })
        assertEquals(0, counter.observerCount)
        val error = fixture.traffic.mapNotNull { it.message }.last { it.code == CoapCode.SERVICE_UNAVAILABLE }
        assertNull(error.options.observe, "non-2.xx notifications have no Observe")
    }

    @Test
    fun rfc7641Section3_2RemovingTheResourceSends404() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("gone") { observable(); get { CoapResponse.content("here") } } }
        val client = fixture.client()

        val responses = async { client.observe(fixture.uri(server, "/gone")).toList() }
        delay(1.seconds)
        server.root.remove("gone")
        assertEquals(listOf(CoapCode.CONTENT, CoapCode.NOT_FOUND), responses.await().map { it.code })
    }

    @Test
    fun rfc7641Section4_1ErrorResponsesDoNotRegister() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter(code = CoapCode.FORBIDDEN)
        val server = fixture.server { add(counter) }
        val client = fixture.client()

        assertEquals(listOf(CoapCode.FORBIDDEN), client.observe(fixture.uri(server, "/counter")).toList().map { it.code })
        assertEquals(0, counter.observerCount)
    }

    @Test
    fun rfc7641Section4_5AnUnacknowledgedNotificationRemovesTheObserver() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter()
        val server = fixture.server { add(counter) }
        val client = fixture.client()

        val received = Channel<CoapResponse>(Channel.UNLIMITED)
        val job = launch { client.observe(fixture.uri(server, "/counter")).collect { received.send(it) } }
        received.receive()
        // The client goes away without a word.
        fixture.network.filter = { it.from != fixture.address(client.endpoint) }
        counter.change(1)
        delay(100.seconds)
        assertEquals(0, counter.observerCount)
        assertTrue(fixture.events.any { it is EndpointEvent.TransmissionTimedOut })
        job.cancel()
    }

    @Test
    fun rfc7641Section4_5_2AChangeSupersedesTheOutstandingNotification() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter()
        val server = fixture.server { add(counter) }
        val client = fixture.client()

        val received = Channel<String>(Channel.UNLIMITED)
        val job = launch { client.observe(fixture.uri(server, "/counter")).collect { received.send(it.payloadAsText()) } }
        assertEquals("0", received.receive())
        // The client's ACKs are lost for a while, so notification 1 stays outstanding.
        fixture.network.filter = { it.from != fixture.address(client.endpoint) }
        counter.change(1)
        delay(1.seconds)
        counter.change(2)
        counter.change(3)
        delay(1.seconds)
        fixture.network.filter = { true }
        delay(30.seconds)
        assertEquals("1", received.receive())
        assertEquals("3", received.receive(), "state 2 was skipped, the latest arrived")
        val fromServer = fixture.traffic.filter { it.from == fixture.address(server.endpoint) }.mapNotNull { it.message }
        val superseding = fromServer.last { it.payloadAsText() == "3" }
        assertTrue(fromServer.any { it.payloadAsText() == "1" && it.messageId != superseding.messageId }, "a new Message ID")
        assertTrue(fromServer.none { it.payloadAsText() == "2" })
        assertEquals(1, counter.observerCount)
        job.cancel()
    }

    @Test
    fun rfc7641Section4_5NonConfirmableNotificationsAreRateLimitedAndConfirmableDaily() = runTest {
        val fixture = StackFixture(this)
        val resource = NonCounter()
        val server = fixture.server(CoapServerConfig(confirmableNotificationInterval = 1.hours)) { add(resource) }
        val client = fixture.client()

        val received = Channel<String>(Channel.UNLIMITED)
        val job = launch { client.observe(fixture.uri(server, "/non")).collect { received.send(it.payloadAsText()) } }
        assertEquals("0", received.receive())
        resource.value = 1
        resource.notifyObservers()
        assertEquals("1", received.receive())
        // Two more changes within the 3-second interval: only the latest goes out, after it (§4.5.1).
        resource.value = 2
        resource.notifyObservers()
        resource.value = 3
        resource.notifyObservers()
        assertEquals("3", received.receive())
        val types = fixture.traffic.filter { it.from == fixture.address(server.endpoint) }.mapNotNull { it.message?.type }
        assertEquals(listOf(CoapMessageType.ACK, CoapMessageType.NON, CoapMessageType.NON), types)

        delay(2.hours)
        resource.value = 4
        resource.notifyObservers()
        assertEquals("4", received.receive())
        val last = fixture.traffic.last { it.from == fixture.address(server.endpoint) }.message!!
        assertEquals(CoapMessageType.CON, last.type, "a Confirmable notification after the interval")
        job.cancel()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.notificationTypes(security: ServerMessageSecurity?): List<CoapMessageType> {
        val fixture = StackFixture(this)
        val resource = NonCounter()
        val config = CoapServerConfig(security = security, nonConfirmableNotificationInterval = kotlin.time.Duration.ZERO)
        val server = fixture.server(config) { add(resource) }
        val client = fixture.client()
        val received = Channel<String>(Channel.UNLIMITED)
        val job = launch { client.observe(fixture.uri(server, "/non")).collect { received.send(it.payloadAsText()) } }
        assertEquals("0", received.receive())
        for (value in 1..10) {
            resource.value = value
            resource.notifyObservers()
            assertEquals("$value", received.receive())
        }
        job.cancel()
        return fixture.traffic.filter { it.from == fixture.address(server.endpoint) }.mapNotNull { it.message?.type }.drop(1)
    }

    @Test
    fun rfc7641Section7NonConfirmableNotificationsToAnUnauthenticatedClientAreInterspersedWithConfirmableOnes() = runTest {
        assertEquals(List(8) { CoapMessageType.NON } + CoapMessageType.CON + CoapMessageType.NON, notificationTypes(security = null))
    }

    @Test
    fun rfc7641Section7AnAuthenticatedClientGetsNonConfirmableNotificationsOnly() = runTest {
        val identified = object : ServerMessageSecurity {
            override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint) = object : UnprotectedRequest {
                override val message: CoapMessage = request
                override val identity = object : SecurityIdentity {
                    override val description: String = "authenticated"
                }

                override suspend fun protectResponse(response: CoapMessage): CoapMessage = response
            }
        }
        assertEquals(List(10) { CoapMessageType.NON }, notificationTypes(identified))
    }

    @Test
    fun rfc7959Section2_6LargeNotificationsAreCompletedWithBlock2() = runTest {
        val fixture = StackFixture(this)
        var version = 'a'
        val server = fixture.server {
            resource("large") {
                observable()
                get { CoapResponse.content(ByteArray(2500) { version.code.toByte() }) }
            }
        }
        val resource = server.root.child("large") as ObservableResource
        val client = fixture.client()

        val received = async { client.observe(fixture.uri(server, "/large")).take(2).toList() }
        delay(2.seconds)
        version = 'b'
        resource.notifyObservers()
        val bodies = received.await()
        assertEquals(listOf(2500, 2500), bodies.map { it.payloadSize })
        assertTrue(bodies[1].payload.all { it == 'b'.code.toByte() })
        val notifications = fixture.traffic.mapNotNull { it.message }.filter { it.options.observe != null && it.isResponse }
        assertTrue(notifications.all { it.options.block2?.number == 0L }, "only the first block is notified")
        val followUps = fixture.traffic.mapNotNull { it.message }.filter { it.isRequest && (it.options.block2?.number ?: 0) > 0 }
        assertTrue(followUps.all { it.options.observe == null })
    }

    @Test
    fun rfc7641Section3_4StaleNotificationsAreDropped() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val client = fixture.client()
        val clientAddress = fixture.address(client.endpoint)

        val received = Channel<String>(Channel.UNLIMITED)
        val job = launch {
            client.observe("coap://${transport.localEndpoint.host}:${transport.localEndpoint.port}/x").collect {
                received.send(it.payloadAsText())
            }
        }
        val registration = UdpMessageCodec.decode((transport.receive() as DatagramEvent.Received).datagram)
        suspend fun notify(type: CoapMessageType, mid: Int, observe: Long?, text: String) {
            val message = CoapMessage.response(registration, CoapCode.CONTENT) {
                this.type = type
                messageId = mid
                options { observe?.let { observe(it) } }
                payload(text)
            }
            transport.send(clientAddress, UdpMessageCodec.encode(message))
        }
        notify(CoapMessageType.ACK, registration.messageId, 100, "first")
        assertEquals("first", received.receive())
        notify(CoapMessageType.NON, 1, 99, "older")
        notify(CoapMessageType.NON, 2, 101, "newer")
        assertEquals("newer", received.receive(), "the older one was dropped")
        delay(200.seconds)
        notify(CoapMessageType.NON, 3, 50, "after silence")
        assertEquals("after silence", received.receive(), "128 s without notifications accept any value")
        notify(CoapMessageType.CON, 4, null, "final")
        assertEquals("final", received.receive())
        job.join()
    }

    @Test
    fun aResetRegistrationFailsTheFlow() = runTest {
        val fixture = StackFixture(this)
        // A client alone on the endpoint: it receives, but resets requests, having no server.
        val plain = fixture.client().endpoint
        val client = fixture.client()
        val address = fixture.address(plain)

        val error = assertFailsWith<CoapException> { client.observe("coap://${address.host}:${address.port}/x").toList() }
        assertEquals(CoapException.Reason.RESET, error.reason)
        assertFailsWith<IllegalArgumentException> {
            client.observe(CoapRequest(CoapCode.POST, CoapUri.parse("coap://${address.host}:${address.port}/x"))).toList()
        }
    }

    @Test
    fun rfc7252Section9_1_1AnEndedSessionEndsItsObservations() = runTest {
        val fixture = StackFixture(this)
        val counter = Counter()
        val server = fixture.server { add(counter) }
        val raw = fixture.transport()
        val session = Endpoint.Secure(SessionId(7), 1, raw.localEndpoint, PeerIdentity.PreSharedKey(byteArrayOf(1)))
        val serverTransport = server.endpoint.transport as dev.kcoap.testkit.InMemoryTransport

        val registration = CoapMessage.request(CoapCode.GET) {
            messageId = 1
            token = byteArrayOf(1)
            options {
                observe(0)
                uriPath("counter")
            }
        }
        serverTransport.inject(DatagramEvent.Received(session, UdpMessageCodec.encode(registration)))
        delay(1.seconds)
        assertEquals(1, counter.observerCount)
        serverTransport.inject(DatagramEvent.SessionEnded(session, SessionEndReason.TIMEOUT))
        delay(1.seconds)
        assertEquals(0, counter.observerCount)
    }

    @Test
    fun theRequestContextCarriesTheChannelIdentity() = runTest {
        val fixture = StackFixture(this)
        val identities = Channel<PeerIdentity>(1)
        val server = fixture.server {
            resource("who") {
                get {
                    identities.send(peerIdentity)
                    assertFalse(isNotification)
                    CoapResponse.content("x")
                }
            }
        }
        val serverTransport = server.endpoint.transport as dev.kcoap.testkit.InMemoryTransport
        val identity = PeerIdentity.PreSharedKey("client-1".encodeToByteArray())
        val session = Endpoint.Secure(SessionId(3), 0, Endpoint.Udp("127.0.0.1", 9), identity)
        val request = CoapMessage.request(CoapCode.GET) {
            messageId = 1
            options { uriPath("who") }
        }
        serverTransport.inject(DatagramEvent.Received(session, UdpMessageCodec.encode(request)))
        assertEquals(identity, identities.receive())
        delay(1.seconds)
        // The response went to the session, which the in-memory network cannot reach.
        assertEquals(DatagramFate.UNREACHABLE, fixture.traffic.last().fate)
    }

    @Test
    fun rfc7641Section3_4SerialNumberArithmetic() {
        assertTrue(NotificationOrder.isNewer(1, 2))
        assertFalse(NotificationOrder.isNewer(2, 1))
        assertFalse(NotificationOrder.isNewer(5, 5))
        assertTrue(NotificationOrder.isNewer(0xFFFFFF, 0), "wraps around 2^24")
        assertFalse(NotificationOrder.isNewer(0, 0xFFFFFF))
        assertFalse(NotificationOrder.isNewer(0, 1L shl 23), "exactly 2^23 apart is not newer")
        val order = NotificationOrder()
        assertTrue(order.accept(10, 0.seconds))
        assertFalse(order.accept(9, 1.seconds))
        assertTrue(order.accept(11, 2.seconds))
        assertFalse(order.accept(11, 3.seconds))
        assertTrue(order.accept(3, 200.seconds))
    }

    @Test
    fun observingAFetchIsAllowed() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("query") {
                observable()
                fetch { CoapResponse.content("for ${payloadAsText()}") }
            }
        }
        val client = fixture.client()
        val request = CoapRequest.Builder(CoapCode.FETCH, CoapUri.parse(fixture.uri(server, "/query"))).payload("q").build()
        val first = client.observe(request).first()
        assertEquals("for q", first.payloadAsText())
        assertIs<ObservableResource>(server.root.child("query"))
    }
}
