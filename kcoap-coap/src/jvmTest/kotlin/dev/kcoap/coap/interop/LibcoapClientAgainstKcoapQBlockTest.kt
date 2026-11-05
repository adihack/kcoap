package dev.kcoap.coap.interop

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorTcpListener
import dev.kcoap.transport.ktor.KtorUdpTransport
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * libcoap's `coap-client -L 7` (Q-Block on, RFC 9177) against a kcoap server with Q-Block on, over real UDP,
 * TCP and WebSockets: the cases L1 to L5, L7, L8, L10 and L12 of docs/plan-qblock-and-platforms.md §1.5.
 * Every case compares the bytes of the body, since libcoap's client can exit 0 with a truncated one. Loss on
 * kcoap's side comes from a [DroppingTransport], on libcoap's side from its `-l`. Skipped when docker or the
 * `kcoap/libcoap` image is missing.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibcoapClientAgainstKcoapQBlockTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val directory: File = Files.createTempDirectory("kcoap-qblock").toFile()

    /** `/data/big3000.txt`: 3000 bytes, 47 blocks of 64. */
    private val big: ByteArray = ByteArray(3000) { ('a' + (it * 7 + it / 64) % 26).code.toByte() }

    @Volatile
    private var stored: ByteArray = ByteArray(0)

    private val qBlock = QBlockServerConfig(isEnabled = true)

    /** UDP without address verification (L1 to L8), UDP with the default policy (L12), TCP and WebSockets (L10). */
    private lateinit var open: CoapServer
    private lateinit var verifying: CoapServer
    private lateinit var reliable: CoapServer
    private lateinit var loss: DroppingTransport
    private lateinit var uri: String
    private lateinit var verifyingUri: String
    private lateinit var tcp: String
    private lateinit var ws: String

    private class Sent(val at: TimeSource.Monotonic.ValueTimeMark, val peer: Endpoint, val message: CoapMessage)

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private val received: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<EndpointEvent> = Collections.synchronizedList(mutableListOf())
    private val recorder = object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { sent += Sent(TimeSource.Monotonic.markNow(), to, it) }

        override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage =
            message.also { received += Sent(TimeSource.Monotonic.markNow(), from, it) }
    }

    private fun endpointConfig() = CoapEndpointConfig(interceptors = listOf(recorder), eventListener = { events += it })

    private fun dev.kcoap.coap.server.ResourceBuilder.routes() {
        resource("big") { get { CoapResponse.content(big, ContentFormat.TEXT_PLAIN) } }
        resource("store") {
            get { CoapResponse.content(stored, ContentFormat.TEXT_PLAIN) }
            put {
                stored = payload
                CoapResponse.changed()
            }
            // An upload that gets a body back (RFC 9177 §4.7): the body turned around.
            post {
                stored = payload
                CoapResponse(CoapCode.CHANGED, payload = payload.reversedArray())
            }
        }
    }

    @BeforeAll
    fun startServers() {
        assumeTrue(Libcoap.isAvailable, "docker or ${Libcoap.IMAGE} not available")
        File(directory, "big3000.txt").writeBytes(big)
        runBlocking {
            val udp = KtorUdpTransport.bind("127.0.0.1")
            openPort = udp.localEndpoint.port
            loss = DroppingTransport(udp)
            open = CoapServer(
                loss, scope, CoapServerConfig(amplification = AmplificationPolicy.OFF, qBlock = qBlock), endpointConfig(),
            ) { routes() }
            val verifyingTransport = KtorUdpTransport.bind("127.0.0.1")
            verifying = CoapServer(verifyingTransport, scope, CoapServerConfig(qBlock = qBlock), endpointConfig()) { routes() }
            val tcpListener = KtorTcpListener.bind("127.0.0.1")
            val wsListener = KtorWebSocketListener.start("127.0.0.1", 0)
            reliable = CoapServer(listOf(tcpListener, wsListener), scope, CoapServerConfig(qBlock = qBlock), endpointConfig()) { routes() }
            uri = "coap://127.0.0.1:$openPort"
            verifyingUri = "coap://127.0.0.1:${verifyingTransport.localEndpoint.port}"
            tcp = "coap+tcp://127.0.0.1:${tcpListener.localEndpoint.port}"
            ws = wsListener.localEndpoint!!.uri.replace("ws://", "coap+ws://").removeSuffix("/.well-known/coap")
        }
    }

    private var openPort = 0

    @AfterAll
    fun stopServers() {
        runBlocking {
            if (::open.isInitialized) open.close()
            if (::verifying.isInitialized) verifying.close()
            if (::reliable.isInitialized) reliable.close()
        }
        scope.cancel()
        directory.deleteRecursively()
    }

    @BeforeEach
    fun clear() {
        sent.clear()
        received.clear()
        events.clear()
        stored = ByteArray(0)
        if (::loss.isInitialized) loss.reset()
        File(directory, "out.bin").delete()
    }

    private fun client(vararg arguments: String, timeoutSeconds: Long = 60): Libcoap.Result =
        Libcoap.client(*arguments, mount = directory, timeoutSeconds = timeoutSeconds)

    private fun output(result: Libcoap.Result): ByteArray {
        val file = File(directory, "out.bin")
        assertTrue(file.exists(), "libcoap wrote no body: $result")
        return file.readBytes()
    }

    /**
     * What kcoap sent to the clients of this test: a body of an earlier test may still send its later sets to
     * a client that has exited, and every `docker run` sends from a new port.
     */
    private fun sentHere(): List<Sent> {
        val peers = received.map { it.peer }.toSet()
        return sent.filter { it.peer in peers }
    }

    /** What kcoap sent, without its answers to libcoap's probes of `/.well-known/core`. */
    private fun sentMessages(): List<CoapMessage> {
        val probes = received.map { it.message }.filter { it.options.path == "/.well-known/core" }
        return sentHere().map { it.message }.filter { message -> probes.none { message.isResponse && message.hasToken(it.token) } }
    }

    /** What kcoap received, without libcoap's probes. */
    private fun receivedMessages(): List<CoapMessage> = received.map { it.message }.filter { it.options.path != "/.well-known/core" }

    /** The Q-Block2 blocks kcoap sent, in order. */
    private fun blocksSent(): List<CoapMessage> = sentMessages().filter { it.isResponse && it.options.qBlock2 != null }

    /** How long kcoap took from the first request with Q-Block to the last message it sent. */
    private fun span(): Duration {
        val first = received.first { CoapOptions.Q_BLOCK1.number in it.message.options || it.message.options.qBlock2 != null && it.message.options.path != "/.well-known/core" }
        return sentHere().last().at - first.at
    }

    // ---------------------------------------------------------------- L1, L2, L3: Q-Block2

    /** L1: libcoap's probe of `/.well-known/core` with Q-Block2 0/_/16 gets block 0 with Q-Block2, ETag and Size2. */
    @Test
    fun l1TheProbeFindsQBlockSupport() {
        val result = client("-L", "7", "-v", "7", "-B", "10", "$uri/")

        assertTrue("Q-Block support available" in result.stdout, "$result")
        val probe = received.map { it.message }.first { it.options.path == "/.well-known/core" }
        assertEquals(CoapMessageType.CON, probe.type)
        assertEquals(BlockOption(0, false, 0), probe.options.qBlock2)
        val answer = sentHere().map { it.message }.first { it.hasToken(probe.token) }
        assertEquals(CoapMessageType.ACK, answer.type, "piggybacked")
        assertEquals(CoapCode.CONTENT, answer.code)
        val size2 = assertNotNull(answer.options.size2)
        assertEquals(BlockOption(0, size2 > 16, 0), answer.options.qBlock2, "block 0 only, as asked")
        assertEquals(1, answer.options.etags.size)
        val seen = result.wire.first { !it.isSent && it.type == "ACK" }
        assertEquals(listOf(if (size2 > 16) "0/M/16" else "0/_/16"), seen.option("Q-Block2"), "$result")
    }

    /** L2: a 3000-byte body in sets of 10 with ETag and Size2; libcoap's Continues 10 to 40 on the original token. */
    @Test
    fun l2ANonConfirmableBodyGoesInSetsAndContinues() {
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$uri/big")

        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(big, output(result), "$result")
        val blocks = blocksSent()
        assertEquals((0L..46L).toList(), blocks.map { it.options.qBlock2!!.number }, "$result")
        val etag = blocks.first().options.etags.single()
        assertTrue(blocks.all { it.options.etags.single().contentEquals(etag) && it.options.size2 == 3000L })
        val request = receivedMessages().first { it.options.path == "/big" }
        val continues = receivedMessages().filter { it.options.path == "/big" && it !== request }
        assertEquals(listOf(10L, 20L, 30L, 40L), continues.map { it.options.qBlock2!!.number }, "$result")
        assertTrue(continues.all { it.options.qBlock2!!.more && CoapOptions.OBSERVE.number !in it.options })
        assertTrue(blocks.all { it.hasToken(request.token) }, "every set on the original token")
        assertTrue(span() < 1500.milliseconds, "the Continues skip the pauses: ${span()}")
    }

    /** L2 variants: `-L 15` (missing blocks asked for with the M bit) and no `-b` (kcoap's own block size). */
    @Test
    fun l2VariantsWithTheMBitAndWithoutABlockSize() {
        val withMBit = client("-L", "15", "-N", "-b", "64", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$uri/big")
        assertEquals(0, withMBit.exitCode, "$withMBit")
        assertContentEquals(big, output(withMBit))

        File(directory, "out.bin").delete()
        sent.clear()
        received.clear()
        val ownSize = client("-L", "7", "-N", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$uri/big")
        assertEquals(0, ownSize.exitCode, "$ownSize")
        assertContentEquals(big, output(ownSize), "$ownSize")
        // Without -b libcoap 4.3.5 asks without Q-Block2, so kcoap, which keeps no per-client Q-Block state
        // (ADR 0006), answers with RFC 7959 Block2, which libcoap follows to the end.
        val request = receivedMessages().first { it.options.path == "/big" }
        assertEquals(null, request.options.qBlock2, "$ownSize")
        assertTrue(blocksSent().isEmpty())
        assertTrue(sentMessages().filter { it.isResponse }.all { it.options.block2 != null }, "$ownSize")
    }

    /** L3: kcoap loses blocks 2 and 7; libcoap asks for both in one request when block 10 arrives. */
    @Test
    fun l3MissingBlocksAreAskedForAndSentOnTheirRequest() {
        loss.drop = { message, copy -> message.isResponse && message.options.qBlock2?.number in setOf(2L, 7L) && copy == 1 }
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$uri/big")

        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(big, output(result), "$result")
        val requests = receivedMessages().filter { it.options.path == "/big" }
        val missing = requests.single { it.options.qBlock2List.size > 1 }
        assertEquals(listOf(BlockOption(2, false, 2), BlockOption(7, false, 2)), missing.options.qBlock2List)
        val answers = sentMessages().filter { it.hasToken(missing.token) }
        assertEquals(listOf(2L, 7L), answers.map { it.options.qBlock2!!.number })
        assertTrue(answers.all { it.options.etags.size == 1 && it.options.size2 == 3000L && CoapOptions.OBSERVE.number !in it.options })
        assertEquals(2, loss.dropped.size)
    }

    /** L3 with `-L 15`: kcoap loses blocks 3 to 9; libcoap asks with Q-Block2 3/M and gets 3 to 9 only. */
    @Test
    fun l3TheRestOfASetIsAskedForWithTheMBit() {
        loss.drop = { message, copy -> message.isResponse && message.options.qBlock2?.number in 3L..9L && copy == 1 }
        val result = client("-L", "15", "-N", "-b", "64", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$uri/big")

        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(big, output(result), "$result")
        val missing = receivedMessages().first { it.options.path == "/big" && it.options.qBlock2?.let { block -> block.number in 1L..9L } == true }
        assertEquals(listOf(BlockOption(3, true, 2)), missing.options.qBlock2List, "$result")
        assertEquals((3L..9L).toList(), sentMessages().filter { it.hasToken(missing.token) }.map { it.options.qBlock2!!.number })
    }

    // ---------------------------------------------------------------- L4, L5: Q-Block1

    /** L4: a 3000-byte upload: nothing for blocks in the middle, 2.31 with Q-Block1 9, 19, 29, 39, then 2.04. */
    @Test
    fun l4ANonConfirmableUploadGets231PerSet() {
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-B", "30", "-m", "put", "-f", "/data/big3000.txt", "$uri/store")

        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(big, stored, "$result")
        val blocks = receivedMessages().filter { it.options.qBlock1 != null }
        assertEquals((0L..46L).toList(), blocks.map { it.options.qBlock1!!.number }, "$result")
        assertEquals(1, blocks.map { it.options.requestTags.single().toList() }.distinct().size, "one Request-Tag")
        assertTrue(blocks.all { it.options.size1 == 3000L }, "Size1 on every block")
        val responses = sentMessages().filter { it.isResponse && it.code != CoapCode.CONTENT }
        assertEquals(List(4) { CoapCode.CONTINUE } + CoapCode.CHANGED, responses.map { it.code }, "$result")
        assertEquals(listOf(9L, 19L, 29L, 39L), responses.dropLast(1).map { it.options.qBlock1!!.number })
        // The request whose block completed the body: kcoap handles the blocks of a burst concurrently, so on
        // Dispatchers.IO that is the last or one of the last to arrive.
        assertTrue(blocks.takeLast(10).any { responses.last().hasToken(it.token) }, "$result")
        assertTrue(span() < 1500.milliseconds, "${span()}")
    }

    /** L5: libcoap loses blocks 2 and 7 (its datagrams 4 and 9): one 4.08 `02 07` at once when block 10 arrives. */
    @Test
    fun l5MissingBlocksOfAnUploadAreListedInA408() {
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-B", "30", "-l", "4,9", "-m", "put", "-f", "/data/big3000.txt", "$uri/store")

        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(big, stored, "$result")
        val incomplete = sentMessages().filter { it.code == CoapCode.REQUEST_ENTITY_INCOMPLETE }
        assertEquals(1, incomplete.size, "$result")
        assertEquals(CoapMessageType.NON, incomplete.single().type)
        assertEquals(ContentFormat.MISSING_BLOCKS_CBOR_SEQ, incomplete.single().options.contentFormat)
        assertContentEquals(byteArrayOf(0x02, 0x07), incomplete.single().payload)
        val trigger = receivedMessages().first { incomplete.single().hasToken(it.token) }
        assertTrue(trigger.options.qBlock1!!.number in 10L..19L, "on the token of a block of the later set, which showed the gaps: $trigger")
    }

    /** L5 with `-l 48`: libcoap loses its last block; 4 s later a 4.08 `18 2e` asks for block 46. */
    @Test
    fun l5ALostLastBlockIsAskedForAfterNonReceiveTimeout() {
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-B", "30", "-l", "48", "-m", "put", "-f", "/data/big3000.txt", "$uri/store")

        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(big, stored, "$result")
        val incomplete = sentHere().single { it.message.code == CoapCode.REQUEST_ENTITY_INCOMPLETE }
        assertContentEquals(byteArrayOf(0x18, 0x2e), incomplete.message.payload)
        assertEquals(CoapMessageType.NON, incomplete.message.type)
        val block45 = received.first { it.message.options.qBlock1?.number == 45L }
        val wait = incomplete.at - block45.at
        assertTrue(wait in 3900.milliseconds..5.seconds, "NON_RECEIVE_TIMEOUT after the last block that came: $wait")
    }

    // ---------------------------------------------------------------- L7: Confirmable

    /** L7: without `-N`, an Empty ACK per Q-Block1 block, the final response piggybacked; block 0 piggybacked, then CON blocks. */
    @Test
    fun l7ConfirmableUploadAndBody() {
        val upload = client("-L", "7", "-b", "64", "-v", "7", "-B", "30", "-m", "put", "-f", "/data/big3000.txt", "$uri/store")
        assertEquals(0, upload.exitCode, "$upload")
        assertContentEquals(big, stored, "$upload")
        val acks = sentMessages().filter { it.type == CoapMessageType.ACK }
        assertTrue(acks.dropLast(1).filter { it.messageId in receivedMessages().filter { r -> r.options.qBlock1 != null }.map { r -> r.messageId } }.all { it.isEmpty })
        assertEquals(CoapCode.CHANGED, acks.last().code, "$upload")
        assertTrue(sentMessages().none { it.code == CoapCode.CONTINUE }, "no 2.31 for Confirmable blocks")

        sent.clear()
        received.clear()
        val body = client("-L", "7", "-b", "64", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$uri/big")
        assertEquals(0, body.exitCode, "$body")
        assertContentEquals(big, output(body), "$body")
        val blocks = blocksSent()
        assertEquals(CoapMessageType.ACK, blocks.first().type)
        assertTrue(blocks.drop(1).all { it.type == CoapMessageType.CON }, "$body")
        assertEquals((0L..46L).toList(), blocks.map { it.options.qBlock2!!.number })
    }

    // ---------------------------------------------------------------- L8: the strict rules

    /** L8: raw datagrams that break RFC 9177's rules get 4.00 or 4.02. */
    @Test
    fun l8RequestsThatBreakTheRulesAreRefused() = runBlocking {
        val raw = KtorUdpTransport.bind("127.0.0.1")
        try {
            val server = Endpoint.Udp("127.0.0.1", openPort)
            suspend fun ask(request: CoapMessage): CoapMessage {
                raw.send(server, UdpMessageCodec.encode(request))
                val event = withTimeout(5.seconds) { raw.receive() } as DatagramEvent.Received
                return UdpMessageCodec.decode(event.datagram)
            }
            var id = 0x2000
            fun request(code: CoapCode = CoapCode.GET, block: CoapMessage.Builder.() -> Unit) = CoapMessage.request(code) {
                type = CoapMessageType.CON
                messageId = id++
                token = byteArrayOf(id.toByte())
                block()
            }

            val descending = ask(request { options { uriPath("big").addQBlock2(BlockOption(7, false, 2)).addQBlock2(BlockOption(2, false, 2)) } })
            assertEquals(CoapCode.BAD_REQUEST, descending.code, descending.payloadAsText())
            val noTag = ask(
                request(CoapCode.PUT) {
                    payload = ByteArray(64)
                    options { uriPath("store").size1(3000).qBlock1(BlockOption(0, true, 2)) }
                },
            )
            assertEquals(CoapCode.BAD_REQUEST, noTag.code)
            assertEquals("Q-Block1 without Request-Tag", noTag.payloadAsText())
            val noSize1 = ask(
                request(CoapCode.PUT) {
                    payload = ByteArray(64)
                    options { uriPath("store").requestTag(byteArrayOf(1)).qBlock1(BlockOption(0, true, 2)) }
                },
            )
            assertEquals("Q-Block1 without Size1", noSize1.payloadAsText())
            val mixed = ask(request { options { uriPath("big").qBlock2(BlockOption(0, true, 2)).block2(BlockOption(0, false, 2)) } })
            assertEquals(CoapCode.BAD_OPTION, mixed.code)
        } finally {
            raw.close()
        }
    }

    /**
     * L8, pinned: libcoap 4.3.5's client asks for the rest of a Q-Block2 response to its Q-Block1 upload with a
     * Continue that repeats `Q-Block1 0/M` and Size1 without a payload, where RFC 9177 §4.7 wants neither. kcoap
     * answers it 4.00, since Q-Block1 with M set must fill its block (RFC 7959 §2.3; ADR 0006, strict), and
     * libcoap then gives the body up after its first set, prints the 4.00 and writes nothing.
     */
    @Test
    fun upstreamLibcoapRepeatsQBlock1WithoutPayloadInFollowUpsOfAnUploadAndGets400() {
        val result = client(
            "-L", "7", "-N", "-b", "64", "-v", "7", "-B", "30", "-m", "post", "-f", "/data/big3000.txt", "-o", "/data/out.bin", "$uri/store",
        )

        assertContentEquals(big, stored, "the upload itself completes: $result")
        val body = sentMessages().filter { it.code == CoapCode.CHANGED }
        assertEquals((0L..9L).toList(), body.map { it.options.qBlock2!!.number }.take(10), "the first set: $result")
        val continued = receivedMessages().single { it.options.qBlock2 != null }
        assertEquals(BlockOption(10, true, 2), continued.options.qBlock2)
        assertEquals(BlockOption(0, true, 2), continued.options.qBlock1, "the deviation")
        assertEquals(0, continued.payloadSize)
        val answer = sentMessages().single { it.hasToken(continued.token) }
        assertEquals(CoapCode.BAD_REQUEST, answer.code)
        assertEquals("Q-Block1 payload of 0 bytes does not fill block 0", answer.payloadAsText())
        assertTrue("4.00 Q-Block1 payload of 0 bytes does not fill block 0" in result.stderr, "$result")
        val output = File(directory, "out.bin")
        assertTrue(!output.exists() || output.length() == 0L, "libcoap gives the body up: $result")
    }

    // ---------------------------------------------------------------- L10: TCP and WebSockets

    /** L10: over `coap+tcp` and `coap+ws` the blocks go back to back, only the final response counts. */
    @Test
    fun l10OverConnectionsBlocksGoBackToBack() {
        for ((base, name) in listOf(tcp to "tcp", ws to "ws")) {
            sent.clear()
            received.clear()
            stored = ByteArray(0)
            val upload = client("-L", "7", "-b", "64", "-v", "7", "-B", "30", "-m", "put", "-f", "/data/big3000.txt", "$base/store")
            assertEquals(0, upload.exitCode, "$name: $upload")
            assertContentEquals(big, stored, "$name: $upload")
            val responses = sentMessages().filter { it.isResponse }
            assertEquals(listOf(CoapCode.CHANGED), responses.map { it.code }, "$name: only the final response")
            assertTrue(receivedMessages().count { it.options.qBlock1 != null } == 47, "$name")

            File(directory, "out.bin").delete()
            sent.clear()
            val body = client("-L", "7", "-b", "64", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$base/big")
            assertEquals(0, body.exitCode, "$name: $body")
            assertContentEquals(big, output(body), "$name: $body")
            assertEquals((0L..46L).toList(), blocksSent().map { it.options.qBlock2!!.number }, "$name")
            assertTrue(receivedMessages().none { it.options.qBlock2?.number?.let { n -> n > 0 } == true }, "no Continue on a connection")
        }
        runBlocking { delay(500.milliseconds) }
        assertTrue(runBlocking { reliable.qBlock.state().partialBodies == 0 })
        assertTrue(events.none { it is EndpointEvent.AbortSent }, "$events")
    }

    // ---------------------------------------------------------------- L12: address verification

    /** L12: with the default policy, the first burst request gets a Non-confirmable 4.01 with Echo. */
    @Test
    fun l12TheFirstBurstWaitsForEcho() {
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$verifyingUri/big")

        val challenge = sentMessages().single { it.code == CoapCode.UNAUTHORIZED }
        assertEquals(CoapMessageType.NON, challenge.type)
        val echo = assertNotNull(challenge.options.echo)
        val repeat = receivedMessages().firstOrNull { it.options.echo?.contentEquals(echo) == true }
        assertNotNull(repeat, "libcoap repeats the request with the Echo value: $result")
        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(big, output(result), "$result")
    }
}
