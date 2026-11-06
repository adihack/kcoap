package dev.kcoap.coap.interop

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
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
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.QBlockCapabilities
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorTcpConnector
import dev.kcoap.transport.ktor.KtorUdpTransport
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The kcoap client with Q-Block (RFC 9177) against libcoap's `coap-server -L 7` (cases K1–K10 and K12
 * of docs/plan-qblock-and-platforms.md §1.5), over real UDP, TCP and WebSockets. Every case starts a server
 * of its own, since libcoap's `-l` loss list counts every datagram the server sends, and compares body
 * bytes. Loss on the kcoap side comes from [LossyTransport]. Skipped when docker or the image is missing.
 *
 * libcoap's `/example_data` holds 1500 bytes until it is PUT: groups of a letter and `123456789`.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KcoapClientAgainstLibcoapQBlockTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** A message the client sent or received, when, and whether [LossyTransport] dropped it. */
    class Wire(val outgoing: Boolean, val message: CoapMessage, val at: Duration, val dropped: Boolean = false) {
        override fun toString(): String = "${if (outgoing) "out" else "in"} $at ${if (dropped) "X " else ""}$message"
    }

    private val wire: MutableList<Wire> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<EndpointEvent> = Collections.synchronizedList(mutableListOf())
    private var start = System.nanoTime()
    private fun elapsed(): Duration = (System.nanoTime() - start).nanoseconds

    private val recorder = object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { wire += Wire(true, it, elapsed()) }
        override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage = message.also { wire += Wire(false, it, elapsed()) }
    }

    /**
     * Records every datagram on the wire, retransmissions and ACKs included, and is the kcoap-side loss:
     * drops the datagrams [dropOut] and [dropIn] pick by their message, so a test can lose exactly the blocks
     * the case names.
     */
    private inner class LossyTransport(
        private val inner: DatagramTransport,
        private val dropOut: (CoapMessage) -> Boolean,
        private val dropIn: (CoapMessage) -> Boolean,
    ) : DatagramTransport {
        override val maxDatagramSize: Int get() = inner.maxDatagramSize

        override suspend fun send(to: Endpoint, datagram: ByteArray) {
            val message = runCatching { UdpMessageCodec.decode(datagram) }.getOrNull()
            val drop = message != null && dropOut(message)
            message?.let { wire += Wire(true, it, elapsed(), dropped = drop) }
            if (!drop) inner.send(to, datagram)
        }

        override suspend fun receive(): DatagramEvent {
            while (true) {
                val event = inner.receive()
                val message = (event as? DatagramEvent.Received)?.let { runCatching { UdpMessageCodec.decode(it.datagram) }.getOrNull() }
                val drop = message != null && dropIn(message)
                message?.let { wire += Wire(false, it, elapsed(), dropped = drop) }
                if (!drop) return event
            }
        }

        override suspend fun close() = inner.close()
    }

    @BeforeAll
    fun requireLibcoap() {
        assumeTrue(Libcoap.isAvailable, "docker or ${Libcoap.IMAGE} not available")
    }

    @AfterAll
    fun stop() {
        scope.cancel()
    }

    // ---------------------------------------------------------------- harness

    /** Starts `coap-server` with [arguments], waits until it listens, runs [block], and removes it. */
    private fun withServer(vararg arguments: String, webSockets: Boolean = false, block: (Libcoap.Server) -> Unit) {
        val server = Libcoap.startServer(*arguments, webSockets = webSockets)
        try {
            val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
            while (System.nanoTime() < deadline && "created UDP" !in server.logs()) Thread.sleep(100)
            check("created UDP" in server.logs()) { "coap-server did not start: ${server.logs()}" }
            block(server)
        } catch (e: Throwable) {
            System.err.println("libcoap log:\n${server.logs().takeLast(20_000)}")
            System.err.println("kcoap wire:\n${wire.joinToString("\n")}")
            throw e
        } finally {
            server.close()
        }
    }

    private fun withClient(
        qBlock: QBlockClientConfig = QBlockClientConfig(QBlockMode.PROBE),
        blockSize: Int? = 64,
        dropOut: (CoapMessage) -> Boolean = { false },
        dropIn: (CoapMessage) -> Boolean = { false },
        timeout: Duration = 90.seconds,
        block: suspend (CoapClient) -> Unit,
    ) = runBlocking {
        wire.clear()
        events.clear()
        start = System.nanoTime()
        val transport = LossyTransport(KtorUdpTransport.bind("127.0.0.1"), dropOut, dropIn)
        val endpoint = CoapEndpoint(
            transport, scope,
            CoapEndpointConfig { eventListener = EndpointEventListener { events += it } },
        )
        val client = CoapClient(endpoint, CoapClientConfig { preferredBlockSize = blockSize; this.qBlock = qBlock })
        try {
            withTimeout(timeout) { block(client) }
            assertEquals(0, endpoint.exchangeCount(), "no exchange is left")
        } finally {
            endpoint.close()
        }
    }

    private fun withReliableClient(block: suspend (CoapClient) -> Unit) = runBlocking {
        wire.clear()
        start = System.nanoTime()
        val qBlock = QBlockClientConfig(QBlockMode.PROBE, onReliableTransports = true)
        val client = CoapClient(
            listOf(KtorTcpConnector(), KtorWebSocketConnector()), scope,
            CoapClientConfig { preferredBlockSize = 64; this.qBlock = qBlock },
            CoapEndpointConfig { interceptors = listOf(recorder) },
        )
        try {
            withTimeout(60.seconds) { block(client) }
        } finally {
            client.close()
        }
    }

    private fun sent(): List<Wire> = synchronized(wire) { wire.filter { it.outgoing && !it.dropped } }

    private fun received(): List<Wire> = synchronized(wire) { wire.filter { !it.outgoing && !it.dropped } }

    private fun requests(path: String): List<Wire> = sent().filter { it.message.isRequest && it.message.options.path == path }

    /** Blocks of 64 bytes, which leaves out the answer to the probe, block 0 of 16 bytes. */
    private fun blocks(): List<Wire> = received().filter { it.message.options.qBlock2?.size == 64 && it.message.code == CoapCode.CONTENT }

    private val CoapMessage.qBlock2: List<String> get() = options.qBlock2List.map { "${it.number}/${if (it.more) "M" else "_"}/${it.size}" }

    private val CoapMessage.qBlock1: String? get() = options.qBlock1?.let { "${it.number}/${if (it.more) "M" else "_"}/${it.size}" }

    private fun body(size: Int, seed: Int): ByteArray {
        val random = Random(seed)
        return ByteArray(size) { ('a' + random.nextInt(26)).code.toByte() }
    }

    /** libcoap's initial `/example_data`: 150 groups of a letter from a to z and `123456789`. */
    private val exampleData: ByteArray = buildString { repeat(150) { append('a' + it % 26).append("123456789") } }.encodeToByteArray()

    /** RFC 9177 §4.1: no message carries both a Block and a Q-Block option. */
    private fun assertNoMessageMixesBlockAndQBlock() {
        val block = setOf(CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number)
        val qBlock = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)
        val mixed = synchronized(wire) { wire.toList() }.filter { line ->
            line.message.options.any { it.number in block } && line.message.options.any { it.number in qBlock }
        }
        assertEquals(emptyList(), mixed)
    }

    // ---------------------------------------------------------------- K1

    @Test
    fun k1ANonGetFindsSupportWithTheProbeAndGetsTheBodyInSetsWithContinues() = withServer("-L", "7", "-v", "7") { server ->
        withClient { client ->
            val response = client.get("${server.uri}/example_data") { nonConfirmable() }

            assertEquals(CoapCode.CONTENT, response.code)
            assertContentEquals(exampleData, response.payload)
            assertEquals(ContentFormat.TEXT_PLAIN, response.contentFormat)
            val probe = sent().first().message
            assertEquals("/.well-known/core", probe.options.path)
            assertEquals(CoapMessageType.CON, probe.type)
            assertEquals(listOf("0/_/16"), probe.qBlock2)
            assertTrue(received().first().message.options.qBlock2 != null, "libcoap answers the probe with Q-Block2")
            assertEquals(QBlockCapabilities.Capability.SUPPORTED, client.qBlock!!.support.known(Endpoint.Udp("127.0.0.1", server.port), false))

            val gets = requests("/example_data").map { it.message }
            assertEquals(listOf("0/M/64", "10/M/64", "20/M/64"), gets.map { it.qBlock2.single() }, "exactly two Continues")
            assertTrue(gets.all { it.type == CoapMessageType.NON })
            assertTrue(gets.drop(1).all { it.options.observe == null && it.options.etags.isEmpty() })
            assertTrue(gets.all { it.options.requestTags.map(ByteArray::toList) == gets[0].options.requestTags.map(ByteArray::toList) })
            val blocks = blocks().map { it.message }
            assertEquals((0L..23L).toList(), blocks.map { it.options.qBlock2!!.number })
            assertTrue(blocks.all { it.options.etags.single().contentEquals(blocks[0].options.etags.single()) })
            assertTrue(blocks.all { it.options.size2 == 1500L && it.options.contentFormat == ContentFormat.TEXT_PLAIN })
            assertTrue(blocks.all { it.tokenHex == gets[0].tokenHex }, "libcoap continues on the original token")
            val took = blocks().last().at - requests("/example_data").first().at
            assertTrue(took < 1.5.seconds, "the body took $took")
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    // ---------------------------------------------------------------- K2

    @Test
    fun k2LostBlocksAreAskedForAtOnceWhenALaterSetArrives() = withServer("-L", "7", "-v", "7", "-l", "4,9") { server ->
        withClient { client ->
            val response = client.get("${server.uri}/example_data") { nonConfirmable() }

            assertContentEquals(exampleData, response.payload)
            val asked = requests("/example_data").filter { it.message.options.qBlock2List.size > 1 }
            assertEquals(listOf(listOf("2/_/64", "7/_/64")), asked.map { it.message.qBlock2 })
            val block10 = blocks().first { it.message.options.qBlock2!!.number == 10L }
            assertTrue(asked.single().at - block10.at < 500.milliseconds, "asked when block 10 arrived")
            assertTrue(events.filterIsInstance<EndpointEvent.QBlockRecovery>().any { !it.isRequestedByPeer && it.blocks == listOf(2L, 7L) })
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    @Test
    fun k2ALostBlock0IsAskedForWithTheWholeBodyNeverAlone() = withServer("-L", "7", "-v", "7", "-l", "2") { server ->
        withClient { client ->
            val response = client.get("${server.uri}/example_data") { nonConfirmable() }

            assertContentEquals(exampleData, response.payload)
            val options = requests("/example_data").flatMap { it.message.qBlock2 }
            assertEquals(2, options.count { it == "0/M/64" }, "the request and the request for block 0: $options")
            assertTrue("0/_/64" !in options, "libcoap runs the handler again for NUM 0")
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    @Test
    fun k2WithTheMBitAMissingTailOfASetIsAskedForWithOneOption() = withServer("-L", "7", "-v", "7", "-l", "5-25") { server ->
        withClient(QBlockClientConfig(QBlockMode.PROBE, missingBlockMBit = true)) { client ->
            val response = client.get("${server.uri}/example_data") { nonConfirmable() }

            assertContentEquals(exampleData, response.payload)
            val asked = requests("/example_data").first { it.message.qBlock2 == listOf("3/M/64") }
            // libcoap's -l counts every datagram it sends, so some of these are lost on purpose too.
            val answer = blocks().filter { it.message.tokenHex == asked.message.tokenHex }.map { it.message.options.qBlock2!!.number }
            assertTrue(answer.isNotEmpty() && answer.all { it in 3L..9L }, "the server answers 3/M with blocks 3 to 9: $answer")
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    // ---------------------------------------------------------------- K3

    @Test
    fun k3ANonPutGetsA231PerSetAndTheNextSetFollowsAtOnce() = withServer("-L", "7", "-v", "7") { server ->
        withClient { client ->
            val upload = body(3000, 3)

            val response = client.put("${server.uri}/example_data", upload, ContentFormat.TEXT_PLAIN) { nonConfirmable() }

            assertTrue(response.code == CoapCode.CHANGED || response.code == CoapCode.CREATED, "PUT answered ${response.code}")
            val puts = requests("/example_data")
            assertEquals((0..46).map { "$it/${if (it < 46) "M" else "_"}/64" }, puts.map { it.message.qBlock1 })
            assertEquals(1, puts.map { it.message.options.requestTags.single().toList() }.toSet().size)
            assertTrue(puts.all { it.message.options.size1 == 3000L })
            val continues = received().filter { it.message.code == CoapCode.CONTINUE }
            assertEquals(listOf(9L, 19L, 29L, 39L), continues.map { it.message.options.qBlock1!!.number })
            for (continued in continues) {
                val next = puts.first { it.message.options.qBlock1!!.number == continued.message.options.qBlock1!!.number + 1 }
                assertTrue(next.at - continued.at < 200.milliseconds, "the next set at once after a 2.31")
            }
            val final = received().last { it.message.code.isSuccess && it.message.code != CoapCode.CONTINUE }.message
            assertEquals(null, final.options.qBlock1, "libcoap's final response carries no Q-Block1")
            val took = received().last().at - puts.first().at
            assertTrue(took < 1.5.seconds, "the upload took $took")

            assertContentEquals(upload, client.get("${server.uri}/example_data") { nonConfirmable() }.payload)
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    // ---------------------------------------------------------------- K4

    @Test
    fun k4BlocksLostOnTheWayAreAskedForWithA408AfterTheLastBlockSent() = withServer("-L", "7", "-v", "7") { server ->
        val lost = mutableSetOf<Long>()
        withClient(dropOut = { message ->
            val num = message.options.qBlock1?.number
            num != null && num in setOf(2L, 7L) && lost.add(num)
        }) { client ->
            val upload = body(3000, 4)

            val response = client.put("${server.uri}/example_data", upload, ContentFormat.TEXT_PLAIN) { nonConfirmable() }

            assertTrue(response.code == CoapCode.CHANGED || response.code == CoapCode.CREATED, "PUT answered ${response.code}")
            assertTrue(received().none { it.message.code == CoapCode.CONTINUE }, "no 2.31 while set 0 has gaps")
            val puts = requests("/example_data")
            val setStarts = listOf(10L, 20L, 30L).map { num -> puts.first { it.message.options.qBlock1!!.number == num }.at }
            val firstSet = puts.first().at
            (listOf(firstSet) + setStarts).zipWithNext().forEach { (a, b) ->
                assertTrue(b - a in 1.9.seconds..3.5.seconds, "sets NON_TIMEOUT_RANDOM apart: ${b - a}")
            }
            // Four sets unconfirmed: set 4 waits for libcoap's 4.08, NON_RECEIVE_TIMEOUT after the last block it got.
            val missing = received().single { it.message.code == CoapCode.REQUEST_ENTITY_INCOMPLETE }
            assertEquals(ContentFormat.MISSING_BLOCKS_CBOR_SEQ, missing.message.options.contentFormat)
            assertContentEquals(byteArrayOf(0x02, 0x07), missing.message.payload)
            val lastSent = puts.last { it.message.options.qBlock1!!.number == 39L }
            assertEquals(lastSent.message.tokenHex, missing.message.tokenHex)
            assertTrue(missing.at - lastSent.at in 3.seconds..6.seconds, "4.08 ${missing.at - lastSent.at} after the last block sent")
            val after = puts.filter { it.at > missing.at }.map { it.message.options.qBlock1!!.number }
            assertEquals(listOf(2L, 7L) + (40L..46L), after, "the lost blocks, then the set that waited")
            assertContentEquals(upload, client.get("${server.uri}/example_data") { nonConfirmable() }.payload)
        }
    }

    @Test
    fun k4ALongUploadWithAnEarlyLossWaitsForTheFirst408WithBoundedTokens() = withServer("-L", "7", "-v", "7") { server ->
        val lost = mutableSetOf<Long>()
        withClient(dropOut = { message -> message.options.qBlock1?.number == 2L && lost.add(2L) }) { client ->
            // 300 blocks, and no 2.31 from libcoap while block 2 is missing: more than the 256 exchanges per peer.
            val upload = body(300 * 64, 13)
            var most = 0

            val response = coroutineScope {
                val sampler = launch {
                    while (true) {
                        most = maxOf(most, client.endpoint.exchangeCount())
                        delay(5.milliseconds)
                    }
                }
                client.put("${server.uri}/example_data", upload, ContentFormat.TEXT_PLAIN) { nonConfirmable() }.also { sampler.cancel() }
            }

            assertTrue(response.code == CoapCode.CHANGED || response.code == CoapCode.CREATED, "PUT answered ${response.code}")
            assertTrue(most <= 50, "unconfirmed requests at most four sets and a few: $most")
            val missing = received().first { it.message.code == CoapCode.REQUEST_ENTITY_INCOMPLETE }
            assertContentEquals(byteArrayOf(0x02), missing.message.payload)
            assertContentEquals(upload, client.get("${server.uri}/example_data") { nonConfirmable() }.payload)
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    @Test
    fun upstreamLibcoapGivesUpAnUploadWithThreeGapsAndItsPlain408IsReturnedK4() = withServer("-L", "7", "-v", "7") { server ->
        val lost = mutableSetOf<Long>()
        withClient(dropOut = { message ->
            val num = message.options.qBlock1?.number
            num != null && num in setOf(1L, 3L, 5L) && lost.add(num)
        }) { client ->
            val response = client.put("${server.uri}/example_data", body(3000, 5), ContentFormat.TEXT_PLAIN) { nonConfirmable() }

            assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, response.code, "libcoap's 3-range limit")
            assertEquals(null, response.contentFormat, "no list: the body is given up")
            assertTrue("missing" in response.payloadAsText().lowercase(), response.payloadAsText())
        }
    }

    // ---------------------------------------------------------------- K5

    @Test
    fun k5AServerWithoutQBlockAnswersTheProbe402AndRfc7959CarriesTheBodies() = withServer("-v", "7") { server ->
        withClient { client ->
            val upload = body(3000, 6)

            val got = client.get("${server.uri}/example_data") { nonConfirmable() }
            val put = client.put("${server.uri}/example_data", upload, ContentFormat.TEXT_PLAIN) { nonConfirmable() }
            val again = client.get("${server.uri}/example_data")

            assertContentEquals(exampleData, got.payload)
            assertTrue(put.isSuccess, "PUT answered ${put.code}")
            assertContentEquals(upload, again.payload)
            val probeAnswer = received().first().message
            assertEquals(CoapMessageType.ACK, probeAnswer.type)
            assertEquals(CoapCode.BAD_OPTION, probeAnswer.code)
            assertEquals(listOf("0/_/16"), probeAnswer.qBlock2, "upstream: libcoap repeats the option it rejects in its 4.02")
            assertEquals(QBlockCapabilities.Capability.UNSUPPORTED, client.qBlock!!.support.known(Endpoint.Udp("127.0.0.1", server.port), false))
            val qBlock = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)
            val withQBlock = synchronized(wire) { wire.toList() }.filter { line -> line.message.options.any { it.number in qBlock } }
            assertEquals(listOf(probeAnswer.tokenHex), withQBlock.map { it.message.tokenHex }.distinct(), "only the probe and its 4.02")
            assertTrue(requests("/example_data").any { it.message.options.block1 != null })
            assertTrue(received().any { it.message.options.block2 != null })
        }
    }

    /**
     * Pinned (K5): `coap-server` without `-L` answers kcoap's probe, a Confirmable GET with Q-Block2 0/_/16, with a
     * piggybacked 4.02 that repeats the Q-Block2 option it rejects. RFC 7252 §5.4.1 only says the option was not
     * recognized; kcoap's client checks the code alone, so the repeated option does no harm.
     */
    @Test
    fun upstreamLibcoapRepeatsTheQBlock2OptionItRejectsInIts402K5() = withServer("-v", "7") { server ->
        withClient { client ->
            client.get("${server.uri}/example_data") { nonConfirmable() }

            val probe = requests("/.well-known/core").single().message
            val answer = received().first { it.message.hasToken(probe.token) }.message
            assertEquals(CoapCode.BAD_OPTION, answer.code)
            assertEquals(listOf("0/_/16"), answer.qBlock2, "the deviation: the rejected option comes back")
            assertEquals(QBlockCapabilities.Capability.UNSUPPORTED, client.qBlock!!.support.known(Endpoint.Udp("127.0.0.1", server.port), false))
        }
    }

    /**
     * Pinned (per-session stickiness, plan §1.6): libcoap 4.3.5's server marks a session as using Q-Block once a
     * request on it carried Q-Block1 or Q-Block2, and from then on `coap_add_data_large_response` answers every
     * request from that address and port in Q-Block2, one asking for Block2 included, where RFC 9177 §4.1 has
     * Q-Block2 answer only a request that carries a Q-Block option. kcoap's client keeps one socket per endpoint
     * and sends RFC 7959 for a request that carries block options of its own, carries No-Response, or goes under
     * OSCORE without `withMessageSecurity`; such a request after a Q-Block body gets a Q-Block2 answer, which it
     * rejects as an unrecognized critical option (RFC 7252 §5.4.1), and fails.
     */
    @Test
    fun upstreamLibcoapAnswersABlock2RequestInQBlock2OnceItsSessionUsedQBlock() = withServer("-L", "7", "-v", "7") { server ->
        withClient { client ->
            assertContentEquals(exampleData, client.get("${server.uri}/example_data") { nonConfirmable() }.payload)

            val error = assertFailsWith<CoapException> {
                client.get("${server.uri}/example_data") { options { block2(BlockOption(0, false, 2)) } }
            }
            assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason, "$error")
            val request = requests("/example_data").last().message
            assertEquals(BlockOption(0, false, 2), request.options.block2)
            assertEquals(emptyList(), request.qBlock2, "an RFC 7959 request")
            val answer = received().first { it.message.hasToken(request.token) }.message
            assertEquals(CoapCode.CONTENT, answer.code)
            assertEquals(listOf("0/M/64"), answer.qBlock2, "the deviation: a Q-Block2 answer to Block2")
            assertEquals(null, answer.options.block2)
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    /**
     * Pinned (plan §1.6): libcoap 4.3.5's server answers a request that carries both Block2 and Q-Block2 with 4.00
     * "Both Block2 and Q-Block2 invalid", where RFC 9177 §4.1 asks for 4.02 (Bad Option). kcoap's client never sends
     * the mix ([assertNoMessageMixesBlockAndQBlock]), and kcoap's server answers it 4.02 (L8), so this is a raw
     * datagram.
     */
    @Test
    fun upstreamLibcoapAnswersBlock2MixedWithQBlock2With400() = withServer("-L", "7", "-v", "7") { server ->
        runBlocking {
            val raw = KtorUdpTransport.bind("127.0.0.1")
            try {
                val request = CoapMessage.request(CoapCode.GET) {
                    type = CoapMessageType.CON
                    messageId = 0x3000
                    token = byteArrayOf(0x31)
                    options { uriPath("example_data").block2(BlockOption(0, false, 2)).qBlock2(BlockOption(0, true, 2)) }
                }
                raw.send(Endpoint.Udp("127.0.0.1", server.port), UdpMessageCodec.encode(request))
                val answer = UdpMessageCodec.decode((withTimeout(5.seconds) { raw.receive() } as DatagramEvent.Received).datagram)

                assertEquals(CoapMessageType.ACK, answer.type)
                assertEquals(0x3000, answer.messageId)
                assertEquals(CoapCode.BAD_REQUEST, answer.code, "the deviation: 4.00, not 4.02")
                assertEquals("Both Block2 and Q-Block2 invalid", answer.payloadAsText())
            } finally {
                raw.close()
            }
        }
    }

    /**
     * Pinned (K6): libcoap 4.3.5's server leaves Observe out of the blocks of a notification body that it sends for
     * a Continue, where RFC 9177 §4.5 wants the body's Observe value on every payload, those after a Continue
     * included. kcoap's client takes such blocks by their ETag (ADR 0006), so the body still arrives whole.
     */
    @Test
    fun upstreamLibcoapLeavesObserveOutOfTheBlocksItSendsForAContinueK6() = withServer("-L", "7", "-N", "-v", "7") { server ->
        withClient { client ->
            val received = Channel<CoapResponse>(Channel.UNLIMITED)
            val observation = scope.launch {
                client.observe("${server.uri}/example_data") { nonConfirmable() }.collect { received.send(it) }
            }
            assertContentEquals(exampleData, withTimeout(10.seconds) { received.receive() }.payload)
            observation.cancel()
            delay(1.seconds)

            val registration = requests("/example_data").first().message
            val body = blocks().map { it.message }.filter { it.hasToken(registration.token) }
            assertEquals((0L..23L).toList(), body.map { it.options.qBlock2!!.number }.sorted())
            assertTrue(body.filter { it.options.qBlock2!!.number < 10 }.all { it.options.observe != null }, "the first set carries Observe")
            assertTrue(body.filter { it.options.qBlock2!!.number >= 10 }.all { it.options.observe == null }, "the deviation: none after a Continue")
        }
    }

    // ---------------------------------------------------------------- K6

    /**
     * K6: kcoap observes libcoap's `/example_data` with Q-Block (`coap-server -L 7 -N`, Non-confirmable
     * notifications), and libcoap's own client PUTs 3000 bytes there, which notifies. Both bodies arrive whole,
     * each emitted once, with Continues for their later sets; cancelling the observation deregisters with
     * Observe 1 on its token (RFC 7641 §3.6).
     */
    @Test
    fun k6NotificationBodiesArriveWholeOncePerBodyAndTheObservationDeregistersOnItsToken() = withServer("-L", "7", "-N", "-v", "7") { server ->
        val directory = Files.createTempDirectory("kcoap-k6").toFile()
        val upload = body(3000, 6)
        File(directory, "big3000.txt").writeBytes(upload)
        try {
            withClient { client ->
                val received = Channel<CoapResponse>(Channel.UNLIMITED)
                val observation = scope.launch {
                    client.observe("${server.uri}/example_data") { nonConfirmable() }.collect { received.send(it) }
                }
                val first = withTimeout(10.seconds) { received.receive() }
                assertContentEquals(exampleData, first.payload)

                val put = Libcoap.client("-L", "7", "-N", "-b", "64", "-m", "put", "-f", "/data/big3000.txt", "${server.uri}/example_data", mount = directory)
                assertEquals(0, put.exitCode, "$put")
                val second = withTimeout(20.seconds) { received.receive() }
                assertContentEquals(upload, second.payload)
                delay(3.seconds)
                assertTrue(received.isEmpty, "exactly one emission per body")

                observation.cancel()
                delay(2.seconds)
                val requests = requests("/example_data").map { it.message }
                val registration = requests.first()
                assertEquals(0L, registration.options.observe)
                assertEquals(listOf("0/M/64"), registration.qBlock2)
                val bodies = blocks().map { it.message }.filter { it.hasToken(registration.token) }.groupBy { it.options.etags.single().toList() }
                assertEquals(listOf(1500L, 3000L), bodies.values.map { body -> body.first().options.size2 }, "two bodies, one ETag each")
                assertEquals(listOf((0L..23L).toList(), (0L..46L).toList()), bodies.values.map { body -> body.map { it.options.qBlock2!!.number }.sorted() })
                val continues = requests.filter { request -> request.options.qBlock2List.singleOrNull()?.let { it.more && it.number > 0 } == true }
                assertEquals(listOf(10L, 20L, 10L, 20L, 30L, 40L), continues.map { it.options.qBlock2!!.number }, "Continues at 10 to 40")
                assertTrue(continues.all { it.options.observe == null })
                // libcoap leaves Observe out of the blocks it sends after a Continue; the client takes them by ETag.
                assertTrue(bodies.values.flatten().any { it.options.observe == null }, "upstream: blocks without Observe")
                val deregistration = requests.last()
                assertEquals(1L, deregistration.options.observe, "RFC 7641 §3.6: GET with Observe 1")
                assertEquals(registration.tokenHex, deregistration.tokenHex, "on the observation's token")
                assertNoMessageMixesBlockAndQBlock()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- K7

    @Test
    fun k7ConfirmableBodiesGoInLockStepWithoutContinuesOr231() = withServer("-L", "7", "-v", "7") { server ->
        withClient { client ->
            val got = client.get("${server.uri}/example_data")
            assertContentEquals(exampleData, got.payload)
            val gets = requests("/example_data")
            assertEquals(1, gets.size, "no Continue for Confirmable blocks")
            val conBlocks = blocks().filter { it.message.type == CoapMessageType.CON }
            assertTrue(conBlocks.isNotEmpty(), "separate Confirmable blocks: ${blocks().map { it.message.type }}")
            val acks = sent().filter { it.message.type == CoapMessageType.ACK }.map { it.message.messageId }
            assertTrue(conBlocks.all { it.message.messageId in acks }, "each Confirmable block acknowledged")

            wire.clear()
            val upload = body(3000, 7)
            val put = client.put("${server.uri}/example_data", upload, ContentFormat.TEXT_PLAIN)
            assertTrue(put.isSuccess, "PUT answered ${put.code}")
            val puts = requests("/example_data")
            assertTrue(puts.all { it.message.type == CoapMessageType.CON })
            assertEquals(47, puts.map { it.message.options.qBlock1!!.number }.toSet().size)
            assertTrue(received().none { it.message.code == CoapCode.CONTINUE }, "no 2.31 for Confirmable blocks")
            assertContentEquals(upload, client.get("${server.uri}/example_data").payload)
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    // ---------------------------------------------------------------- K8

    /**
     * Pinned (K8): libcoap 4.3.5's server keeps a body for 4 × NON_TIMEOUT (8 s) after it last sent the body's last
     * block; a request for a block after that runs its handler again and gets that block alone, without ETag and
     * Size2, where RFC 9177 §4.4 has every block of a body carry the body's ETag. The client loses every copy of
     * block 23, the last, until such an answer comes: libcoap answers its requests for block 23 from the cache
     * first, and one of them comes 8 s or more after libcoap last sent the block, since they back off
     * (NON_RECEIVE_TIMEOUT × 2^n: 4, 8, 16 s). The client takes a block without ETag and Size2 for a changed
     * representation and asks for the whole body again (`0/M`), which libcoap sends with a new ETag.
     */
    @Test
    fun upstreamLibcoapAnswersWithoutETagOnceItsCacheExpiredAndTheGetRestartsK8() = withServer("-L", "7", "-v", "7") { server ->
        fun isLastBlock(message: CoapMessage) = message.code == CoapCode.CONTENT && message.options.qBlock2?.number == 23L
        fun isUntagged(message: CoapMessage) = message.options.etags.isEmpty() && message.options.size2 == null
        fun answeredUntagged() = received().any { isLastBlock(it.message) && isUntagged(it.message) }
        withClient(dropIn = { message -> isLastBlock(message) && !isUntagged(message) && !answeredUntagged() }) { client ->
            val response = client.get("${server.uri}/example_data") { nonConfirmable() }

            assertContentEquals(exampleData, response.payload)
            val asked = requests("/example_data").filter { it.message.qBlock2 == listOf("23/_/64") }.map { it.message.tokenHex }
            val answers = synchronized(wire) { wire.filter { !it.outgoing && isLastBlock(it.message) && it.message.tokenHex in asked } }
            val untagged = answers.filter { isUntagged(it.message) }
            assertEquals(1, untagged.size, "libcoap answers a request for block 23 without ETag and Size2 once: $answers")
            val cached = answers.filter { it.at < untagged.single().at }
            assertTrue(cached.isNotEmpty() && cached.all { it.dropped && it.message.options.etags.size == 1 }, "from its cache before: $answers")
            val restarts = requests("/example_data").filter { it.message.qBlock2 == listOf("0/M/64") && it.at > untagged.single().at }
            assertEquals(1, restarts.size, "the GET starts again: ${requests("/example_data").map { it.message.qBlock2 }}")
            val etags = blocks().filter { !isUntagged(it.message) }.map { it.message.options.etags.single().toList() }.distinct()
            assertEquals(2, etags.size, "the body again, with a new ETag: $etags")
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    // ---------------------------------------------------------------- K9

    @Test
    fun k9aANonFetchUploadsItsBodyAndGetsTheResponseBodyInQBlock2() = withServer("-L", "7", "-v", "7") { server ->
        withClient { client ->
            val query = body(700, 9)

            val response = client.fetch("${server.uri}/example_data", query, ContentFormat.TEXT_PLAIN) { nonConfirmable() }

            val fetches = requests("/example_data").filter { it.message.options.qBlock1 != null }
            assertEquals(11, fetches.size)
            assertEquals(listOf(9L), received().filter { it.message.code == CoapCode.CONTINUE }.map { it.message.options.qBlock1!!.number })
            // libcoap's /example_data answers a FETCH like a GET, in blocks of its own 1024 bytes.
            assertEquals(CoapCode.CONTENT, response.code)
            assertContentEquals(exampleData, response.payload)
            val body = received().filter { it.message.options.qBlock2 != null && it.message.tokenHex != sent().first().message.tokenHex }
            assertEquals(listOf(fetches.last().message.tokenHex), body.map { it.message.tokenHex }.distinct(), "the body on the last Q-Block1 token")
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    /**
     * K9 (b). libcoap 4.3.5 sends only block 0 of a 2.01 or 2.04 body, here the echo of the PUT (`-e`); the
     * client asks for the rest when NON_RECEIVE_TIMEOUT has passed, with a PUT that carries only Q-Block2 and
     * the upload's Request-Tag (RFC 9177 §4.7), and libcoap answers it.
     */
    @Test
    fun upstreamLibcoapSendsOnlyBlock0OfAnEchoedPutBodyAndTheRestIsFetchedK9() = withServer("-L", "7", "-v", "7", "-e") { server ->
        withClient { client ->
            val upload = body(3000, 10)

            val response = client.put("${server.uri}/example_data", upload, ContentFormat.TEXT_PLAIN) { nonConfirmable() }

            assertContentEquals(upload, response.payload)
            val puts = requests("/example_data")
            val tag = puts.first().message.options.requestTags.single()
            val followUp = puts.single { it.message.options.qBlock1 == null }
            assertEquals(0, followUp.message.payloadSize)
            assertContentEquals(tag, followUp.message.options.requestTags.single())
            assertEquals(listOf("1/_/1024", "2/_/1024"), followUp.message.qBlock2)
            val beforeAsked = received().filter { it.message.options.qBlock2 != null && it.at < followUp.at }
            assertEquals(listOf(0L), beforeAsked.drop(1).map { it.message.options.qBlock2!!.number }, "upstream: block 0 alone")
            assertTrue(followUp.at - beforeAsked.last().at >= 3.seconds, "asked for after NON_RECEIVE_TIMEOUT")
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    // ---------------------------------------------------------------- K10

    @Test
    fun k10OnReliableTransportsBlocksGoBackToBackWithout231OrContinue() =
        withServer("-L", "7", "-X", "1152", "-v", "7", webSockets = true) { server ->
            for (base in listOf(server.tcpUri, server.webSocketUri)) {
                withReliableClient { client ->
                    // 320 blocks: more than a body's inbox holds on datagrams, and than exchanges per peer.
                    val upload = body(20 * 1024, 11)

                    val put = client.put("$base/example_data", upload, ContentFormat.TEXT_PLAIN)
                    val got = client.get("$base/example_data")

                    assertTrue(put.isSuccess, "$base: PUT answered ${put.code}")
                    assertContentEquals(upload, got.payload, base)
                    val puts = requests("/example_data").filter { it.message.options.qBlock1 != null }
                    assertEquals(320, puts.size, base)
                    assertTrue(received().none { it.message.code == CoapCode.CONTINUE }, "$base: no 2.31")
                    val gets = requests("/example_data").filter { it.message.code == CoapCode.GET }
                    assertEquals(listOf(listOf("0/M/64")), gets.map { it.message.qBlock2 }, "$base: no Continue")
                    assertEquals(320, blocks().size, base)
                    assertNoMessageMixesBlockAndQBlock()
                }
            }
        }

    // ---------------------------------------------------------------- K12

    @Test
    fun k12ALostProbeAnswerIsRecoveredByRetransmittingTheProbe() = withServer("-L", "7", "-v", "7", "-l", "1") { server ->
        withClient { client ->
            val response = client.get("${server.uri}/example_data") { nonConfirmable() }

            assertContentEquals(exampleData, response.payload)
            val probes = requests("/.well-known/core").map { it.message }
            assertEquals(2, probes.size, "the probe and its retransmission")
            assertEquals(1, probes.map { it.messageId }.toSet().size)
            assertEquals(QBlockCapabilities.Capability.SUPPORTED, client.qBlock!!.support.known(Endpoint.Udp("127.0.0.1", server.port), false))
            assertTrue(requests("/example_data").all { it.message.options.qBlock2 != null })
            assertNoMessageMixesBlockAndQBlock()
        }
    }

    // ---------------------------------------------------------------- K soak

    /**
     * The K soak, L9's counterpart: [Soak.runs] runs (3 here, 20 in the nightly soak) of a Non-confirmable PUT of
     * 3000 bytes and a GET of what libcoap then holds, with 10 % loss on both sides (`-l 10%` on libcoap's,
     * [LossyTransport] on kcoap's). Every run ends with the right body, a clean [CoapException], or libcoap's
     * plain 4.08 for an upload with three gaps or more (its limit, pinned in
     * [upstreamLibcoapGivesUpAnUploadWithThreeGapsAndItsPlain408IsReturnedK4]); libcoap holds the new body or
     * the one before, never a mix; no exchange is left after a run, and no message mixes Block and Q-Block. In
     * the nightly soak a quarter of the uploads and half the downloads complete at least.
     */
    @Test
    fun kSoakBodiesBothWaysAt10PercentLoss() = withServer("-L", "7", "-v", "7", "-l", "10%") { server ->
        val random = java.util.Random(11)
        fun lose(): Boolean = synchronized(random) { random.nextDouble() < 0.1 }
        var held = exampleData
        var uploads = 0
        var downloads = 0
        val outcomes = mutableListOf<String>()
        repeat(Soak.runs) { run ->
            withClient(dropOut = { lose() }, dropIn = { lose() }, timeout = 10.minutes) { client ->
                val upload = body(3000, 100 + run)
                val put = runCatching { client.put("${server.uri}/example_data", upload, ContentFormat.TEXT_PLAIN) { nonConfirmable() } }
                put.exceptionOrNull()?.let { assertCleanFailure(it, "run $run PUT") }
                val code = put.getOrNull()?.code
                outcomes += "PUT ${code ?: put.exceptionOrNull()?.let { (it as CoapException).reason }}"
                when (code) {
                    CoapCode.CHANGED, CoapCode.CREATED -> uploads++
                    null, CoapCode.REQUEST_ENTITY_INCOMPLETE -> Unit
                    else -> throw AssertionError("run $run: PUT answered $code")
                }

                val get = runCatching { client.get("${server.uri}/example_data") { nonConfirmable() } }
                get.exceptionOrNull()?.let { assertCleanFailure(it, "run $run GET") }
                val got = get.getOrNull()
                outcomes += "GET ${got?.code ?: get.exceptionOrNull()?.let { (it as CoapException).reason }}"
                if (got != null) {
                    assertEquals(CoapCode.CONTENT, got.code, "run $run")
                    val isNew = got.payload.contentEquals(upload)
                    assertTrue(isNew || got.payload.contentEquals(held), "run $run: libcoap holds the new body or the one before")
                    if (isNew) held = upload
                    if (code == CoapCode.CHANGED || code == CoapCode.CREATED) assertTrue(isNew, "run $run: the stored body is the upload")
                    downloads++
                }
                assertNoMessageMixesBlockAndQBlock()
            }
        }
        println("K soak, ${Soak.runs} runs: $outcomes")
        if (Soak.isNightly) {
            // libcoap gives up uploads with three gaps, so fewer uploads than downloads complete.
            assertTrue(uploads * 4 >= Soak.runs && downloads * 2 >= Soak.runs, "runs complete: $uploads uploads, $downloads bodies; $outcomes")
        }
    }

    /** A run of the soak may fail only the ways a lossy path explains. */
    private fun assertCleanFailure(error: Throwable, run: String) {
        val reasons = setOf(CoapException.Reason.RESPONSE_TIMEOUT, CoapException.Reason.BLOCKWISE_FAILED, CoapException.Reason.TRANSMISSION_TIMEOUT)
        assertTrue(error is CoapException && error.reason in reasons, "$run ended with $error")
    }
}
