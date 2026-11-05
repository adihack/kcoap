package dev.kcoap.oscore

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.testkit.DatagramFate
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Q-Block (RFC 9177) under OSCORE (RFC 8613) between kcoap's client and server on the in-memory network and
 * virtual time: what each side's `withMessageSecurity` chooses, bodies both ways with their Q-Block options
 * inside OSCORE, a replayed block response, unprotected responses injected on a body's tokens, the protected
 * capability kept apart on a shared endpoint, a restarted server's freshness challenge, 10 % loss over many
 * seeds, and protected observations whose notification bodies come in Q-Block2 (RFC 9177 §4.5), under loss and
 * reordering and against replayed and stale bodies. On the JVM only: elsewhere the crypto backend suspends on the platform (WebCrypto), and virtual time
 * would run ahead of it. The rules themselves are tested on every target in [OscoreQBlockTest].
 */
class OscoreQBlockEndToEndTest {
    private val crypto = DefaultCryptoBackend()
    private val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
    private val parameters = TransmissionParameters.Default
    private val qBlockOptions = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)
    private val blockOptions = setOf(CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number)

    private fun body(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun CoapMessage.has(numbers: Set<Int>): Boolean = options.any { it.number in numbers }

    /** An observable resource whose representation is a body of 3000 bytes of its current version. */
    private class Observed : ObservableResource("obs") {
        var version = 0
            private set

        override val notificationType: CoapMessageType get() = CoapMessageType.NON

        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content(body(version), ContentFormat.OCTET_STREAM)

        fun change() {
            version++
            notifyObservers()
        }

        companion object {
            fun body(version: Int): ByteArray = ByteArray(3000) { (it * 7 + version).toByte() }
        }
    }

    /** A datagram on the network, decoded, and what the network did with it. */
    private class Wire(val from: Endpoint, val message: CoapMessage?, val fate: DatagramFate)

    /**
     * An OSCORE client and server with the stack on one in-memory network. [inner] holds the requests as the
     * client hands them to OSCORE, [rejected] the responses its OSCORE layer refused, [wire] every datagram.
     */
    private class Setup(
        val network: InMemoryNetwork,
        val server: CoapServer,
        val serverTransport: InMemoryTransport,
        val client: CoapClient,
        val clientTransport: InMemoryTransport,
        val stored: MutableList<ByteArray>,
        val observed: Observed,
        val inner: MutableList<CoapMessage>,
        val verified: MutableList<CoapMessage>,
        val unverified: MutableList<CoapMessage>,
        val rejected: MutableList<String>,
        val wire: MutableList<Wire>,
        val events: MutableList<EndpointEvent>,
    ) {
        fun uri(path: String): String = "coap://${serverTransport.localEndpoint.host}:${serverTransport.localEndpoint.port}$path"

        fun fromServer(): List<CoapMessage> = wire.filter { it.from == serverTransport.localEndpoint }.mapNotNull { it.message }

        suspend fun close() {
            client.endpoint.close()
            server.endpoint.close()
        }
    }

    private suspend fun TestScope.setup(
        client: QBlockClientConfig = QBlockClientConfig(QBlockMode.PROBE, withMessageSecurity = true),
        server: QBlockServerConfig = QBlockServerConfig(isEnabled = true, withMessageSecurity = true),
        seed: Int = 1,
        restartedServer: Boolean = false,
        defaultAccess: AccessPolicy = AccessPolicy.MessageSecurityRequired,
    ): Setup {
        val network = InMemoryNetwork(backgroundScope, Random(seed))
        val wire = Collections.synchronizedList(mutableListOf<Wire>())
        network.tap = { datagram, fate -> wire += Wire(datagram.from, runCatching { UdpMessageCodec.decode(datagram.bytes) }.getOrNull(), fate) }
        val events = Collections.synchronizedList(mutableListOf<EndpointEvent>())
        fun endpointConfig(offset: Int) = CoapEndpointConfig(
            parameters = parameters,
            timeSource = testScheduler.timeSource,
            random = Random(seed * 10 + offset),
            eventListener = { events += it },
        )
        val clientContext = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b)), crypto)
        val serverParameters = SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a))
        val serverContext = if (restartedServer) restoredContext(serverParameters, crypto) else ephemeralContext(serverParameters, crypto)

        val stored = Collections.synchronizedList(mutableListOf<ByteArray>())
        val observed = Observed()
        val big = body(3000, seed)
        val serverTransport = network.attach()
        val coapServer = CoapServer(
            CoapEndpoint(serverTransport, backgroundScope, endpointConfig(1)),
            CoapServerConfig(security = OscoreServer(InMemoryContextRepository().apply { add(serverContext) }), qBlock = server, defaultAccess = defaultAccess),
        ) {
            resource("big") { get { CoapResponse.content(big, ContentFormat.OCTET_STREAM) } }
            add(observed)
            resource("store") {
                put {
                    stored += payload
                    CoapResponse.changed()
                }
                // Answers with the body turned around, so an upload gets a body back (RFC 9177 §4.7).
                post {
                    stored += payload
                    CoapResponse(CoapCode.CHANGED, payload = payload.reversedArray())
                }
            }
        }
        val recording = RecordingClientSecurity(OscoreClient(clientContext))
        val clientTransport = network.attach()
        val coapClient = CoapClient(
            CoapEndpoint(clientTransport, backgroundScope, endpointConfig(2)),
            CoapClientConfig(security = recording, blockSize = 64, qBlock = client),
        )
        return Setup(
            network, coapServer, serverTransport, coapClient, clientTransport, stored, observed,
            recording.requests, recording.responses, recording.unverified, recording.rejected, wire, events,
        )
    }

    /**
     * Every Q-Block option went inside OSCORE (RFC 9177 §4, Table 2): no datagram shows one, and every request
     * and response on the wire is protected.
     */
    private fun assertProtectedOnTheWire(setup: Setup) {
        val messages = setup.wire.mapNotNull { it.message }
        assertTrue(messages.none { it.has(qBlockOptions) || it.has(blockOptions) }, "no Outer Q-Block or Block option")
        val withCode = messages.filter { it.code != CoapCode.EMPTY }
        assertTrue(withCode.all { it.options.oscore != null }, "every request and response is protected")
    }

    @Test
    fun rfc8613Section4_1_3_4_1ProtectedRequestsUseInnerBlockOptionsByDefault() = runTest {
        // The server would take Q-Block under OSCORE, but the client keeps its default.
        val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))
        val big = body(3000, 1)

        val got = setup.client.get(setup.uri("/big")) { nonConfirmable() }
        val put = setup.client.put(setup.uri("/store"), big.reversedArray()) { nonConfirmable() }

        assertContentEquals(big, got.payload)
        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big.reversedArray(), setup.stored.single())
        assertTrue(setup.inner.none { it.has(qBlockOptions) }, "no Q-Block option inside OSCORE")
        assertTrue(setup.inner.any { it.options.block2 != null }, "RFC 7959 carried the response body")
        assertTrue(setup.inner.any { it.options.block1 != null }, "RFC 7959 carried the request body")
        assertTrue(setup.events.none { it is EndpointEvent.QBlockFallback }, "nothing was tried and rejected")
        assertProtectedOnTheWire(setup)
    }

    @Test
    fun rfc9177Section4_1BodiesGoInQBlockInsideOscoreWhenBothSidesOptIn() = runTest {
        val setup = setup()
        val big = body(3000, 1)

        val got = setup.client.get(setup.uri("/big")) { nonConfirmable() }
        val put = setup.client.put(setup.uri("/store"), big.reversedArray()) { nonConfirmable() }
        val post = setup.client.post(setup.uri("/store"), big) { nonConfirmable() }

        assertContentEquals(big, got.payload)
        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big.reversedArray(), setup.stored[0])
        assertContentEquals(big.reversedArray(), post.payload, "the upload was answered with a Q-Block2 body (§4.7)")
        val probe = setup.inner.first()
        assertEquals("/.well-known/core", probe.options.path, "the probe went protected too")
        assertTrue(setup.inner.count { it.options.qBlock1 != null } >= 2 * 47, "both uploads went in Q-Block1")
        assertTrue(setup.inner.none { it.has(blockOptions) }, "no RFC 7959 Block option")
        val responses = setup.fromServer().filter { it.code != CoapCode.EMPTY }
        assertTrue(responses.size >= 2 * 47, "two Q-Block2 bodies of 47 blocks: ${responses.size} responses")
        assertEquals(emptyList(), setup.rejected.toList(), "OSCORE took every response of every body")
        assertTrue(setup.events.none { it is EndpointEvent.QBlockFallback })
        assertProtectedOnTheWire(setup)
        setup.close()
    }

    @Test
    fun rfc9177Section7_1ConfirmableBodiesGoInLockStepUnderOscore() = runTest {
        val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true))
        val big = body(3000, 1)

        val put = setup.client.put(setup.uri("/store"), big)
        val got = setup.client.get(setup.uri("/big"))

        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big, setup.stored.single())
        assertContentEquals(big, got.payload)
        val blocks = setup.inner.filter { it.options.qBlock1 != null }
        assertEquals(47, blocks.size, "every block once")
        assertTrue(setup.fromServer().filter { it.code == CoapCode.CHANGED || it.code == CoapCode.CONTENT }.all { it.type != CoapMessageType.NON })
        assertEquals(emptyList(), setup.rejected.toList())
        assertProtectedOnTheWire(setup)
        setup.close()
    }

    @Test
    fun rfc9177Section4_1AServerThatDoesNotOptInMakesTheClientUseRfc7959() = runTest {
        val setup = setup(server = QBlockServerConfig(isEnabled = true))
        val big = body(3000, 1)

        val got = setup.client.get(setup.uri("/big")) { nonConfirmable() }

        assertContentEquals(big, got.payload)
        val probe = setup.inner.single { it.has(qBlockOptions) }
        assertEquals("/.well-known/core", probe.options.path, "only the probe carried Q-Block")
        val fallback = setup.events.filterIsInstance<EndpointEvent.QBlockFallback>().single()
        assertTrue("4.02" in fallback.reason, "the protected probe got an inner 4.02: ${fallback.reason}")
        assertTrue(setup.inner.any { it.options.block2 != null }, "RFC 7959 carried the body")
        // The 4.02 went inside OSCORE: on the wire the answer to the probe is a protected 2.04 (RFC 8613 §4.2).
        val probeOnTheWire = setup.wire.first { it.from == setup.clientTransport.localEndpoint && it.message?.code?.isRequest == true }
        val answer = setup.fromServer().single { it.code.isResponse && it.token.contentEquals(probeOnTheWire.message!!.token) }
        assertTrue(answer.options.oscore != null, "the 4.02 is protected")
        assertEquals(CoapCode.CHANGED, answer.code, "its outer code is 2.04, not 4.02")
        assertProtectedOnTheWire(setup)
        setup.close()
    }

    @Test
    fun rfc9177Section4_1ARejectedNonConfirmableBodyIsSentAgainWithRfc7959() = runTest {
        // Without a probe the server's Reset to the first Non-confirmable block is the rejection.
        val setup = setup(
            client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true),
            server = QBlockServerConfig(isEnabled = true),
        )
        val big = body(3000, 1)

        val put = setup.client.put(setup.uri("/store"), big) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big, setup.stored.single())
        assertTrue(setup.inner.any { it.options.qBlock1 != null } && setup.inner.any { it.options.block1 != null })
        assertTrue(setup.fromServer().any { it.type == CoapMessageType.RST }, "the Q-Block1 block was rejected")
        assertProtectedOnTheWire(setup)
        setup.close()
    }

    @Test
    fun rfc9177Section4_1AProtectedFallbackLeavesQBlockToTheUnprotectedRequestsOfTheEndpoint() = runTest {
        // A server that takes Q-Block from unprotected requests only, and one endpoint with two clients: the
        // setup's OSCORE client and a plain one, which share the endpoint's capability cache. The server serves
        // both, so its resources are Public (ADR 0008: by default it would refuse the plain client).
        val setup = setup(server = QBlockServerConfig(isEnabled = true), defaultAccess = AccessPolicy.Public)
        val plain = CoapClient(setup.client.endpoint, CoapClientConfig(blockSize = 64, qBlock = QBlockClientConfig(QBlockMode.PROBE)))
        val big = body(3000, 1)

        assertContentEquals(big, plain.get(setup.uri("/big")) { nonConfirmable() }.payload)
        assertContentEquals(big, setup.client.get(setup.uri("/big")) { nonConfirmable() }.payload)
        assertContentEquals(big, plain.get(setup.uri("/big")) { nonConfirmable() }.payload)
        assertContentEquals(big, setup.client.get(setup.uri("/big")) { nonConfirmable() }.payload)

        val unprotected = setup.wire.filter { it.from == setup.clientTransport.localEndpoint }
            .mapNotNull { it.message }.filter { it.code.isRequest && it.options.oscore == null }
        assertEquals(1, unprotected.count { it.options.path == "/.well-known/core" }, "one unprotected probe")
        val plainGets = unprotected.filter { it.options.path == "/big" }
        assertTrue(plainGets.size >= 2 && plainGets.all { it.options.qBlock2 != null }, "both plain GETs went in Q-Block2")
        assertTrue(unprotected.none { it.has(blockOptions) }, "the protected fallback left the plain client on Q-Block")
        assertEquals(1, setup.inner.count { it.options.path == "/.well-known/core" }, "one protected probe, apart from the plain one")
        val protectedGets = setup.inner.filter { it.options.path == "/big" }
        assertTrue(protectedGets.none { it.has(qBlockOptions) }, "the OSCORE client took the 4.02 for its later requests")
        assertTrue(protectedGets.any { it.options.block2 != null }, "Inner Block2 carried the protected bodies")
        assertEquals(1, setup.events.count { it is EndpointEvent.QBlockFallback }, "only the protected probe fell back")
        setup.close()
    }

    // ---------------------------------------------------------------- notification bodies (RFC 9177 §4.5)

    /** Observes `/obs` Non-confirmably in the background; every notification goes to the returned list. */
    private fun TestScope.observe(setup: Setup): Pair<Job, MutableList<CoapResponse>> {
        val received = Collections.synchronizedList(mutableListOf<CoapResponse>())
        val job = backgroundScope.launch { setup.client.observe(setup.uri("/obs")) { nonConfirmable() }.collect { received += it } }
        return job to received
    }

    /** The verified notification blocks of each body, by ETag, in the order OSCORE took them. */
    private fun Setup.notificationBlocks(): Map<List<Byte>, List<Long>> =
        verified.toList().filter { it.options.observe != null && it.options.qBlock2 != null }
            .groupBy({ it.options.etags.single().toList() }, { it.options.qBlock2!!.number })

    /**
     * RFC 9177 §4.5 under RFC 8613: where both sides opt in, a protected observation registers with Inner Q-Block2,
     * and every notification comes as a Q-Block2 body inside OSCORE, each block a notification with a Partial IV of
     * its own (§4.1.3.5.2). The bodies arrive whole, one emission each, OSCORE refuses none of their blocks, and no
     * RFC 7959 Block option is used.
     */
    @Test
    fun rfc9177Section4_5AProtectedObservationTakesQBlock2NotificationBodiesWhenBothSidesOptIn() = runTest {
        val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true))
        val (observation, received) = observe(setup)
        delay(10.seconds)
        setup.observed.change()
        delay(10.seconds)
        setup.observed.change()
        delay(10.seconds)

        assertEquals((0..2).map { Observed.body(it).toList() }, received.map { it.payload.toList() })
        val registration = setup.inner.first { it.options.observe == 0L }
        assertEquals(BlockOption(0, true, 2), registration.options.qBlock2, "the registration asks for Q-Block2 inside OSCORE")
        assertTrue(setup.inner.none { it.has(blockOptions) }, "no RFC 7959 Block option")
        val bodies = setup.notificationBlocks()
        assertEquals(3, bodies.size, "three notification bodies")
        assertTrue(bodies.values.all { it.toSet() == (0L..46L).toSet() }, "every block of every body came as a notification")
        val pivs = setup.fromServer().filter { it.options.observe != null }.mapNotNull { OscoreOptionValue.decode(it.options.oscore!!).partialIv?.toList() }
        assertEquals(pivs.distinct(), pivs, "every block took a Partial IV of its own")
        assertEquals(emptyList(), setup.rejected.toList(), "OSCORE took every notification")
        assertEquals(1, setup.observed.observerCount)
        assertProtectedOnTheWire(setup)
        observation.cancel()
        setup.close()
    }

    /**
     * RFC 9177 §4.1 for a protected observation: the client opts in to Q-Block under OSCORE without a probe, and the
     * server takes Q-Block only from unprotected requests. The registration with Inner Q-Block2 is rejected before
     * it is processed, with an inner 4.02 when Confirmable and a Reset when Non-confirmable, and the observation
     * registers again with RFC 7959: the bodies arrive whole, the server holds one observer, OSCORE refuses
     * nothing, and nothing after the rejection carries Q-Block.
     */
    @Test
    fun rfc9177Section4_1AProtectedObservationFallsBackToInnerBlock2WhenTheServerDoesNotOptIn() = runTest {
        for (confirmable in listOf(false, true)) {
            val run = if (confirmable) "CON" else "NON"
            val setup = setup(
                client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true),
                server = QBlockServerConfig(isEnabled = true),
            )
            val received = Collections.synchronizedList(mutableListOf<CoapResponse>())
            val observation = backgroundScope.launch {
                setup.client.observe(setup.uri("/obs")) { if (!confirmable) nonConfirmable() }.collect { received += it }
            }
            delay(10.seconds)
            setup.observed.change()
            delay(10.seconds)

            assertEquals((0..1).map { Observed.body(it).toList() }, received.map { it.payload.toList() }, "$run: two bodies, whole")
            val registrations = setup.inner.filter { it.options.observe == 0L }
            assertEquals(2, registrations.size, "$run: the Q-Block registration and the RFC 7959 one")
            assertEquals(BlockOption(0, true, 2), registrations[0].options.qBlock2, "$run: Inner Q-Block2 first")
            assertTrue(setup.inner.drop(setup.inner.indexOf(registrations[1])).none { it.has(qBlockOptions) }, "$run: no Q-Block after it")
            assertTrue(setup.inner.any { it.options.block2 != null }, "$run: Inner Block2 carried the bodies")
            val rejection = setup.fromServer().first()
            if (confirmable) {
                assertEquals(CoapCode.CHANGED, rejection.code, "$run: the 4.02 went inside OSCORE, a protected 2.04 on the wire")
            } else {
                assertEquals(CoapMessageType.RST, rejection.type, "$run: the Non-confirmable registration got a Reset")
            }
            assertTrue(setup.events.any { it is EndpointEvent.QBlockFallback }, "$run")
            assertEquals(1, setup.observed.observerCount, "$run")
            assertEquals(emptyList(), setup.rejected.toList(), "$run: OSCORE took every response")
            assertProtectedOnTheWire(setup)
            observation.cancel()
            setup.close()
        }
    }

    /**
     * What kept protected observations on RFC 7959 until OSCORE took a notification body's blocks by a window per
     * registration: 10 % loss each way and 20 to 50 ms of latency, which reorders the blocks of a set. Over
     * [OBSERVE_SEEDS] runs every delivered notification is a whole body, once, in order, the latest state arrives,
     * blocks did arrive out of order, and OSCORE refused no genuine block (before, about 130 per run).
     */
    @Test
    fun rfc8613Section7_4_1ProtectedNotificationBodiesArriveWholeUnderLossAndReordering() = runTest(timeout = 5.minutes) {
        var reordered = 0
        var recoveries = 0
        for (seed in 1..OBSERVE_SEEDS) {
            val run = "seed $seed"
            val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true), seed = seed)
            val (observation, received) = observe(setup)
            delay(5.seconds)
            setup.network.conditions = LinkConditions(lossRate = 0.1, latency = 20.milliseconds, jitter = 30.milliseconds)
            repeat(5) {
                setup.observed.change()
                delay(15.seconds)
            }
            setup.network.conditions = LinkConditions.Perfect
            setup.observed.change()
            delay(20.seconds)

            val versions = (0..setup.observed.version).associateBy { Observed.body(it).toList() }
            val delivered = received.map { versions[it.payload.toList()] ?: fail("$run: a notification that is no whole body") }
            assertEquals(delivered.sorted(), delivered, "$run: in order")
            val etags = received.map { it.options.etags.single().toList() }
            assertEquals(etags.distinct(), etags, "$run: one emission per body")
            assertEquals(setup.observed.version, delivered.last(), "$run: the latest state arrives")
            assertTrue(delivered.size >= 4, "$run: ${delivered.size} bodies of ${setup.observed.version + 1} versions")
            assertEquals(emptyList(), setup.rejected.toList(), "$run: OSCORE took every genuine block")
            reordered += setup.notificationBlocks().values.count { it.zipWithNext().any { (a, b) -> b < a } }
            recoveries += setup.events.count { it is EndpointEvent.QBlockRecovery }
            observation.cancel()
            setup.close()
        }
        assertTrue(reordered > 0, "blocks of a body arrived out of order")
        assertTrue(recoveries > 0, "lost blocks were asked for")
    }

    /**
     * A replayed or stale notification body never reaches the application (RFC 8613 §7.4.1, §4.1.3.5.2; RFC 7641
     * §3.4): someone on the path holds back every block of the second body, records the third, then delivers the
     * second after the third arrived, and sends the third again with fresh Message IDs, past deduplication. OSCORE
     * refuses every one of those blocks, and the observation goes on.
     */
    @Test
    fun rfc8613Section7_4_1AReplayedOrStaleNotificationBodyIsNeverDelivered() = runTest {
        val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true))
        val heldBack = Collections.synchronizedList(mutableListOf<ByteArray>())
        val recorded = Collections.synchronizedList(mutableListOf<ByteArray>())
        var phase = 0
        setup.network.filter = { datagram ->
            // The Outer Observe option marks a notification (RFC 8613 §4.1.3.5.2); answers for missing blocks have none.
            val isNotification = datagram.from == setup.serverTransport.localEndpoint &&
                runCatching { UdpMessageCodec.decode(datagram.bytes) }.getOrNull()?.options?.observe != null
            when {
                isNotification && phase == 1 -> {
                    heldBack += datagram.bytes.copyOf()
                    false
                }
                isNotification && phase == 2 -> {
                    recorded += datagram.bytes.copyOf()
                    true
                }
                else -> true
            }
        }
        val (observation, received) = observe(setup)
        delay(10.seconds)
        phase = 1
        setup.observed.change()
        delay(30.seconds)
        phase = 2
        setup.observed.change()
        delay(30.seconds)
        phase = 3
        assertEquals(listOf(0, 2).map { Observed.body(it).toList() }, received.map { it.payload.toList() }, "the second body was held back")

        fun inject(bytes: ByteArray, messageId: Int) {
            val message = UdpMessageCodec.decode(bytes).toBuilder().apply { this.messageId = messageId }.build()
            setup.clientTransport.inject(DatagramEvent.Received(setup.serverTransport.localEndpoint, UdpMessageCodec.encode(message)))
        }
        heldBack.forEachIndexed { index, bytes -> inject(bytes, 0x5000 + index) }
        recorded.forEachIndexed { index, bytes -> inject(bytes, 0x6000 + index) }
        delay(10.seconds)
        setup.observed.change()
        delay(10.seconds)

        assertEquals(listOf(0, 2, 3).map { Observed.body(it).toList() }, received.map { it.payload.toList() }, "neither came through")
        assertTrue(heldBack.size >= 47 && recorded.size >= 47, "a whole body each: ${heldBack.size} held back, ${recorded.size} recorded")
        val expected = setOf("Replayed or stale notification", "Stale notification of an older body")
        assertEquals(heldBack.size + recorded.size, setup.rejected.size, "every injected block was refused")
        assertTrue(setup.rejected.all { it in expected }, "${setup.rejected}")
        observation.cancel()
        setup.close()
    }

    // ---------------------------------------------------------------- unprotected responses injected (RFC 9177 §11)

    /**
     * Sends [forged] to the client as if from the server, on the token of the client's request that [isTarget]
     * picks among those on the wire, once. The token is visible on the wire, so anyone on the path can do this.
     */
    private fun Setup.inject(forged: (token: ByteArray) -> CoapMessage, isTarget: (request: CoapMessage, index: Int) -> Boolean) {
        var requests = 0
        var injected = false
        network.filter = { datagram ->
            val message = if (datagram.from == clientTransport.localEndpoint) runCatching { UdpMessageCodec.decode(datagram.bytes) }.getOrNull() else null
            if (!injected && message != null && message.code.isRequest && isTarget(message, requests++)) {
                injected = true
                val bytes = UdpMessageCodec.encode(forged(message.token))
                clientTransport.inject(DatagramEvent.Received(serverTransport.localEndpoint, bytes))
            }
            true
        }
    }

    @Test
    fun rfc9177Section11AnUnprotected402ToAQBlockRequestDoesNotTurnQBlockOff() = runTest {
        val setup = setup()
        val big = body(3000, 1)
        // A 4.02 before the server answers the GET's first request, which a Q-Block client takes as a rejection.
        setup.inject(
            forged = { token ->
                CoapMessage.build {
                    type = CoapMessageType.NON
                    code = CoapCode.BAD_OPTION
                    messageId = 0x4242
                    this.token = token
                }
            },
            isTarget = { _, _ -> setup.inner.any { it.options.path == "/big" } },
        )

        // ADR 0008: the forged 4.02 is unverified, so it ends the GET as a distinct failure, not a timeout, and
        // steers nothing: no fallback to RFC 7959, the capability stays.
        val failure = assertFailsWith<CoapException> { setup.client.get(setup.uri("/big")) { nonConfirmable() } }
        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, failure.reason)
        assertEquals(CoapCode.BAD_OPTION, failure.response?.code)
        assertContentEquals(big, setup.client.get(setup.uri("/big")) { nonConfirmable() }.payload)

        assertTrue(setup.events.none { it is EndpointEvent.QBlockFallback }, "the capability stayed SUPPORTED")
        assertTrue(setup.inner.none { it.has(blockOptions) }, "RFC 7959 never took over")
        assertEquals(1, setup.inner.count { it.options.path == "/.well-known/core" }, "one probe for both GETs")
        val gets = setup.inner.filter { it.options.path == "/big" }
        assertTrue(gets.all { it.options.qBlock2 != null }, "both GETs went in Q-Block2")
        assertEquals(listOf(CoapCode.BAD_OPTION), setup.unverified.map { it.code })
        assertEquals(emptyList(), setup.rejected.toList())
        setup.close()
    }

    @Test
    fun rfc9177Section11AnUnprotected408DuringAnUploadHasNoBlockSentAgain() = runTest {
        val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true))
        val big = body(3000, 1)
        // A 4.08 listing blocks 0-9 on the token of block 9, the last of set 0, where the server's 4.08 would come.
        setup.inject(
            forged = { token ->
                CoapMessage.build {
                    type = CoapMessageType.NON
                    code = CoapCode.REQUEST_ENTITY_INCOMPLETE
                    messageId = 0x4243
                    this.token = token
                    options { contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ) }
                    payload = ByteArray(10) { it.toByte() }
                }
            },
            isTarget = { _, index -> index == 9 },
        )

        // ADR 0008: the forged 4.08 is unverified; the upload ends with it instead of sending blocks again.
        val failure = assertFailsWith<CoapException> { setup.client.put(setup.uri("/store"), big) { nonConfirmable() } }

        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, failure.reason)
        assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, failure.response?.code)
        val sent = setup.inner.mapNotNull { it.options.qBlock1?.number }
        assertEquals(sent.distinct(), sent, "no block sent twice")
        assertEquals((0L until sent.size).toList(), sent, "in order")
        assertEquals(listOf(CoapCode.REQUEST_ENTITY_INCOMPLETE), setup.unverified.map { it.code })
        assertEquals(emptyList(), setup.rejected.toList())
        assertTrue(setup.events.none { it is EndpointEvent.QBlockRecovery }, "no recovery was asked for")
        setup.close()
    }

    /** An unprotected 4.01 with Echo on [token], as anyone on the path can send (it is an OSCORE error's shape). */
    private fun forged401(token: ByteArray): CoapMessage = CoapMessage.build {
        type = CoapMessageType.NON
        code = CoapCode.UNAUTHORIZED
        messageId = 0x4244
        this.token = token
        options { echo("0102030405060708".hexToBytes()) }
    }

    /**
     * ADR 0008, the attack of SEC-4: an unprotected 4.01 with Echo answers a protected POST before the server's
     * protected 2.04 does. Were it acted on, the POST would go again under a fresh Partial IV, which OSCORE's replay
     * protection cannot catch, and run twice. It is unverified, so the POST ends with it and runs once.
     */
    @Test
    fun adr0008AForged401WithEchoDoesNotMakeAProtectedPostRunTwice() = runTest {
        for (confirmable in listOf(true, false)) {
            val setup = setup(client = QBlockClientConfig())
            setup.inject(forged = ::forged401, isTarget = { _, index -> index == 0 })

            val failure = assertFailsWith<CoapException> {
                setup.client.post(setup.uri("/store"), "10 EUR".encodeToByteArray()) { if (!confirmable) nonConfirmable() }
            }
            delay(10.seconds)

            assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, failure.reason, "confirmable=$confirmable")
            assertEquals(CoapCode.UNAUTHORIZED, failure.response?.code)
            assertEquals(1, setup.inner.count { it.code == CoapCode.POST }, "confirmable=$confirmable: one POST")
            assertTrue(setup.inner.none { it.options.echo != null }, "the Echo value was never taken")
            assertEquals(1, setup.stored.size, "confirmable=$confirmable: the server ran it once")
            setup.close()
        }
    }

    @Test
    fun adr0008AForged401WithEchoToAProtectedQBlockUploadHasNothingSentAgain() = runTest {
        val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true))
        setup.inject(forged = ::forged401, isTarget = { _, index -> index == 3 })

        val failure = assertFailsWith<CoapException> { setup.client.put(setup.uri("/store"), body(3000, 1)) { nonConfirmable() } }

        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, failure.reason)
        assertTrue(setup.inner.none { it.options.echo != null }, "no block went again with the Echo value")
        val sent = setup.inner.mapNotNull { it.options.qBlock1?.number }
        assertEquals(sent.distinct(), sent, "no block sent twice")
        setup.close()
    }

    @Test
    fun adr0008AForgedUnprotectedErrorDoesNotEndAProtectedObservation() = runTest {
        val setup = setup(client = QBlockClientConfig())
        val (observation, received) = observe(setup)
        delay(10.seconds)
        val token = setup.wire.mapNotNull { it.message }.first { it.code.isRequest && it.options.oscore != null }.token

        // RFC 7641 §3.2: a 4.04 without Observe would end the observation, if it were verified.
        val forged = CoapMessage.build {
            type = CoapMessageType.NON
            code = CoapCode.NOT_FOUND
            messageId = 0x4245
            this.token = token
        }
        setup.clientTransport.inject(DatagramEvent.Received(setup.serverTransport.localEndpoint, UdpMessageCodec.encode(forged)))
        delay(10.seconds)
        setup.observed.change()
        delay(10.seconds)

        assertEquals((0..1).map { Observed.body(it).toList() }, received.map { it.payload.toList() }, "the next notification arrived")
        assertTrue(observation.isActive, "the observation goes on")
        assertEquals(1, setup.observed.observerCount)
        assertEquals(listOf(CoapCode.NOT_FOUND), setup.unverified.map { it.code })
        observation.cancel()
        setup.close()
    }

    @Test
    fun rfc8613Section7_4AReplayedBlockResponseIsRejected() = runTest {
        val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true))
        val big = body(3000, 1)
        // An attacker records the third block response of the first set, which carries a Partial IV, and sends
        // it again with a fresh Message ID, past deduplication, while the body is still arriving.
        var seen = 0
        var recorded: ByteArray? = null
        setup.network.filter = { datagram ->
            if (datagram.from == setup.serverTransport.localEndpoint) {
                seen++
                if (seen == 3) recorded = datagram.bytes.copyOf()
                if (seen == 8) {
                    val original = UdpMessageCodec.decode(checkNotNull(recorded))
                    val replay = original.toBuilder().apply { messageId = (original.messageId + 1000) and 0xFFFF }.build()
                    setup.clientTransport.inject(DatagramEvent.Received(datagram.from, UdpMessageCodec.encode(replay)))
                }
            }
            true
        }

        val got = setup.client.get(setup.uri("/big")) { nonConfirmable() }

        assertContentEquals(big, got.payload, "the body is whole and has every block once")
        val replayed = UdpMessageCodec.decode(checkNotNull(recorded))
        assertTrue(OscoreOptionValue.decode(replayed.options.oscore!!).partialIv != null, "the recorded block had a Partial IV")
        assertEquals(listOf("Replayed or too old response"), setup.rejected.toList())
        setup.close()
    }

    @Test
    fun rfc8613AppendixB1_2ARestartedServerChallengesTheFirstBlocksAndTheBodiesComplete() = runTest {
        val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true), restartedServer = true)
        val big = body(3000, 1)

        val put = setup.client.put(setup.uri("/store"), big) { nonConfirmable() }
        val got = setup.client.get(setup.uri("/big")) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big, setup.stored.single())
        assertContentEquals(big, got.payload)
        assertTrue(setup.inner.any { it.options.echo != null }, "the client returned the server's Echo value")
        assertEquals(emptyList(), setup.rejected.toList())
        setup.close()
    }

    private enum class Direction { UPLOAD, DOWNLOAD }

    /**
     * [SEEDS] runs of one body under 10 % loss in both directions, each on a network of its own: every run ends
     * with the whole body or a clean [CoapException], OSCORE refuses no genuine response, and most complete.
     */
    private suspend fun TestScope.lossy(direction: Direction) {
        var completed = 0
        var failed = 0
        var lost = 0
        var recoveries = 0
        for (seed in 1..SEEDS) {
            val setup = setup(client = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true), seed = seed)
            val big = body(3000, seed)
            setup.network.conditions = LinkConditions(lossRate = 0.1)
            val run = "seed $seed, $direction"
            val result = runCatching {
                when (direction) {
                    Direction.UPLOAD -> setup.client.put(setup.uri("/store"), big) { nonConfirmable() }
                    Direction.DOWNLOAD -> setup.client.get(setup.uri("/big")) { nonConfirmable() }
                }
            }
            val response = result.getOrNull()
            if (response != null) {
                when (direction) {
                    Direction.UPLOAD -> {
                        assertEquals(CoapCode.CHANGED, response.code, run)
                        assertContentEquals(big, setup.stored.last(), run)
                    }
                    Direction.DOWNLOAD -> assertContentEquals(big, response.payload, run)
                }
                completed++
            } else {
                val error = result.exceptionOrNull()
                if (error !is CoapException || error.reason !in CLEAN_FAILURES) fail("$run ended with $error")
                failed++
            }
            assertTrue(setup.stored.all { it.contentEquals(big) }, "$run: only whole bodies reach the handler")
            assertTrue(setup.inner.none { it.has(blockOptions) }, "$run: Q-Block only")
            assertEquals(emptyList(), setup.rejected.toList(), "$run: OSCORE took every genuine response")
            lost += setup.wire.count { it.fate == DatagramFate.LOST }
            recoveries += setup.events.count { it is EndpointEvent.QBlockRecovery }
            // Whatever the server still had to say is said within NON_PARTIAL_TIMEOUT.
            delay(parameters.nonPartialTimeout + 1.seconds)
            setup.close()
        }
        assertTrue(completed >= failed * 4, "most runs complete: $completed completed, $failed failed")
        assertTrue(lost > 0 && recoveries > 0, "datagrams were lost ($lost) and blocks recovered ($recoveries)")
    }

    @Test
    fun rfc9177Section7_2QBlock1PutCompletesUnderOscoreWith10PercentLoss() = runTest(timeout = 5.minutes) { lossy(Direction.UPLOAD) }

    @Test
    fun rfc9177Section7_2QBlock2GetCompletesUnderOscoreWith10PercentLoss() = runTest(timeout = 5.minutes) { lossy(Direction.DOWNLOAD) }

    private companion object {
        const val SEEDS = 10

        /** Runs of the protected observation under loss and reordering, each with six changes. */
        const val OBSERVE_SEEDS = 5

        /** The ways a run may fail cleanly under loss. */
        val CLEAN_FAILURES = setOf(
            CoapException.Reason.RESPONSE_TIMEOUT,
            CoapException.Reason.BLOCKWISE_FAILED,
            CoapException.Reason.TRANSMISSION_TIMEOUT,
        )
    }
}
