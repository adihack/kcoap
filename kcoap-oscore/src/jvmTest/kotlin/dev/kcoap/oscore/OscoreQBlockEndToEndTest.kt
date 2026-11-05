package dev.kcoap.oscore

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
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.QBlockServerConfig
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
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Q-Block (RFC 9177) under OSCORE (RFC 8613) between kcoap's client and server on the in-memory network and
 * virtual time: what each side's `withMessageSecurity` chooses, bodies both ways with their Q-Block options
 * inside OSCORE, a replayed block response, unprotected responses injected on a body's tokens, a restarted
 * server's freshness challenge, and 10 % loss over many seeds. On the JVM only: elsewhere the crypto backend
 * suspends on the platform (WebCrypto), and virtual time would run ahead of it. The rules themselves are tested
 * on every target in [OscoreQBlockTest].
 */
class OscoreQBlockEndToEndTest {
    private val crypto = DefaultCryptoBackend()
    private val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
    private val parameters = TransmissionParameters.Default
    private val qBlockOptions = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)
    private val blockOptions = setOf(CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number)

    private fun body(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun CoapMessage.has(numbers: Set<Int>): Boolean = options.any { it.number in numbers }

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
        val inner: MutableList<CoapMessage>,
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
        val big = body(3000, seed)
        val serverTransport = network.attach()
        val coapServer = CoapServer(
            CoapEndpoint(serverTransport, backgroundScope, endpointConfig(1)),
            CoapServerConfig(security = OscoreServer(InMemoryContextRepository().apply { add(serverContext) }), qBlock = server),
        ) {
            resource("big") { get { CoapResponse.content(big, ContentFormat.OCTET_STREAM) } }
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
        return Setup(network, coapServer, serverTransport, coapClient, clientTransport, stored, recording.requests, recording.rejected, wire, events)
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
        setup.close()
    }

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

        assertContentEquals(big, setup.client.get(setup.uri("/big")) { nonConfirmable() }.payload)
        assertContentEquals(big, setup.client.get(setup.uri("/big")) { nonConfirmable() }.payload)

        assertTrue(setup.events.none { it is EndpointEvent.QBlockFallback }, "the capability stayed SUPPORTED")
        assertTrue(setup.inner.none { it.has(blockOptions) }, "RFC 7959 never took over")
        assertEquals(1, setup.inner.count { it.options.path == "/.well-known/core" }, "one probe for both GETs")
        val gets = setup.inner.filter { it.options.path == "/big" }
        assertTrue(gets.all { it.options.qBlock2 != null }, "both GETs went in Q-Block2")
        assertEquals(listOf("Unprotected 4.02 Bad Option to a Q-Block request"), setup.rejected.toList())
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

        val put = setup.client.put(setup.uri("/store"), big) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big, setup.stored.single())
        val sent = setup.inner.mapNotNull { it.options.qBlock1?.number }
        assertEquals((0L until 47L).toList(), sent, "every block once, in order")
        assertEquals(listOf("Unprotected 4.08 Request Entity Incomplete to a Q-Block request"), setup.rejected.toList())
        assertTrue(setup.events.none { it is EndpointEvent.QBlockRecovery }, "no recovery was asked for")
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

        /** The ways a run may fail cleanly under loss. */
        val CLEAN_FAILURES = setOf(
            CoapException.Reason.RESPONSE_TIMEOUT,
            CoapException.Reason.BLOCKWISE_FAILED,
            CoapException.Reason.TRANSMISSION_TIMEOUT,
        )
    }
}
