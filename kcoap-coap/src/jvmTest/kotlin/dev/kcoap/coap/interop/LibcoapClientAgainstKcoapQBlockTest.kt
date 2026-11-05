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
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.RequestContext
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * libcoap's `coap-client -L 7` (Q-Block on, RFC 9177) against a kcoap server with Q-Block on, over real UDP,
 * TCP and WebSockets: the cases L1 to L12 of docs/plan-qblock-and-platforms.md §1.5 (L11, under OSCORE, is in
 * kcoap-oscore), with L6 also under the default amplification policy and L9 as a soak ([Soak]).
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

    /** `/obs`: 1500 bytes for its [version], which [change] turns and notifies (L6). */
    private class Changing : ObservableResource("obs") {
        @Volatile
        var version = 0

        override val notificationType: CoapMessageType get() = CoapMessageType.NON

        override suspend fun handleGet(context: RequestContext): CoapResponse =
            CoapResponse.content(ByteArray(1500) { ('a' + (it + version) % 26).code.toByte() }, ContentFormat.TEXT_PLAIN)

        fun change() {
            version++
            notifyObservers()
        }
    }

    private fun dev.kcoap.coap.server.ResourceBuilder.routes() {
        add(Changing())
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

    // ---------------------------------------------------------------- L6: Observe

    /**
     * L6: libcoap observes kcoap's `/obs`, 1500 bytes that change every 3 s, with Q-Block2 (`-s 10`): every
     * block of every body carries Observe and one ETag of its own body, the Continues continue on the
     * registration's token, and libcoap's deregistration with Observe 1 removes the observer.
     */
    @Test
    fun l6NotificationBodiesCarryObserveOnEveryBlockAndContinueOnTheRegistrationToken() {
        val obs = open.root.child("obs") as Changing
        val changes = scope.launch {
            repeat(4) {
                delay(3.seconds)
                obs.change()
            }
        }
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-s", "10", "-B", "15", "$uri/obs")
        runBlocking { changes.cancelAndJoin() }

        assertEquals(0, result.exitCode, "$result")
        val registration = receivedMessages().first { it.options.path == "/obs" && it.options.observe == 0L }
        // libcoap deregisters on the registration's token, and that GET's answer, the last body, has no Observe.
        val onRegistration = blocksSent().filter { it.hasToken(registration.token) }
        val all = onRegistration.groupBy { it.options.etags.single().toList() }.values.toList()
        val bodies = all.dropLast(1)
        assertTrue(all.last().none { CoapOptions.OBSERVE.number in it.options }, "the deregistration's answer: $result")
        assertTrue(bodies.size >= 3, "a body per change: ${bodies.size} $result")
        assertTrue(bodies.flatten().all { it.options.observe != null }, "RFC 9177 §4.5: Observe on every block of a notification")
        val observes = bodies.map { body -> body.map { it.options.observe!! }.distinct().single() }
        assertEquals(observes.sorted(), observes, "one Observe value per body, rising")
        assertTrue(bodies.all { body -> body.map { it.options.qBlock2!!.number }.toSet() == (0L..23L).toSet() }, "every body whole")
        val continues = receivedMessages().filter { it.options.path == "/obs" && it.options.qBlock2?.let { block -> block.more && block.number > 0 } == true }
        assertTrue(continues.isNotEmpty() && continues.all { CoapOptions.OBSERVE.number !in it.options }, "$result")
        assertTrue(continues.none { continued -> sentMessages().any { it.hasToken(continued.token) } }, "nothing on a Continue's own token")
        val deregistration = receivedMessages().firstOrNull { it.options.path == "/obs" && it.options.observe == 1L }
        assertNotNull(deregistration, "$result")
        assertTrue(sentMessages().any { it.hasToken(deregistration.token) && CoapOptions.OBSERVE.number !in it.options }, "answered")
        assertEquals(0, obs.observerCount)
        // libcoap writes no -o file for an observation; its log shows every block of every notification arrived.
        val got = result.wire.filter { line ->
            !line.isSent && line.code == "2.05" && " Observe:" in line.line && line.option("Q-Block2").singleOrNull()?.endsWith("/64") == true
        }.groupBy { it.option("ETag").single() }
        assertEquals(bodies.size, got.size, "$result")
        assertTrue(got.values.all { blocks -> blocks.mapNotNull { it.number("Q-Block2") }.toSet() == (0L..23L).toSet() }, "$result")
    }

    /**
     * L6 under the default [AmplificationPolicy.Q_BLOCK_BURSTS] (L12 for an observation): a Q-Block registration
     * from an address nothing verified gets a Non-confirmable 4.01 with Echo first, whatever the size of its
     * first response, since its notification bodies are bursts no request asks for (RFC 9175 §2.4 item 3, RFC
     * 7641 §7). libcoap repeats the registration with the Echo value (RFC 9175 §2.3), and every notification body
     * then arrives whole, with Observe on every block.
     */
    @Test
    fun l6UnderTheDefaultPolicyTheRegistrationIsRepeatedWithEchoAndTheBodiesFollow() {
        val obs = verifying.root.child("obs") as Changing
        val changes = scope.launch {
            repeat(3) {
                delay(3.seconds)
                obs.change()
            }
        }
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-s", "10", "-B", "15", "$verifyingUri/obs")
        runBlocking { changes.cancelAndJoin() }

        assertEquals(0, result.exitCode, "$result")
        val challenge = sentMessages().first { it.code == CoapCode.UNAUTHORIZED }
        assertEquals(CoapMessageType.NON, challenge.type, "Non-confirmable, never a separate Confirmable response")
        val echo = assertNotNull(challenge.options.echo)
        val registrations = receivedMessages().filter { it.options.path == "/obs" && it.options.observe == 0L }
        assertTrue(challenge.hasToken(registrations.first().token), "the registration itself is challenged: $result")
        val repeat = registrations.firstOrNull { it.options.echo?.contentEquals(echo) == true }
        assertNotNull(repeat, "libcoap repeats the registration with the Echo value: $result")
        assertEquals(BlockOption(0, true, 2), repeat.options.qBlock2, "and with Q-Block2")
        val bodies = blocksSent().filter { it.hasToken(repeat.token) && it.options.observe != null }
            .groupBy { it.options.etags.single().toList() }.values.toList()
        assertTrue(bodies.size >= 3, "the registration's body and one per change: ${bodies.size} $result")
        assertTrue(bodies.all { body -> body.map { it.options.qBlock2!!.number }.toSet() == (0L..23L).toSet() }, "every body whole")
        assertTrue(bodies.flatten().none { it.code == CoapCode.UNAUTHORIZED })
        val got = result.wire.filter { line ->
            !line.isSent && line.code == "2.05" && " Observe:" in line.line && line.option("Q-Block2").singleOrNull()?.endsWith("/64") == true
        }.groupBy { it.option("ETag").single() }
        assertTrue(got.values.count { blocks -> blocks.mapNotNull { it.number("Q-Block2") }.toSet() == (0L..23L).toSet() } >= 3, "$result")
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

    /** The Q-Block2 numbers of the 2.05 blocks of 64 bytes that reached libcoap, from its log at `-v 7`. */
    private fun blocksArrived(result: Libcoap.Result): Set<Long> =
        result.wire.filter { !it.isSent && it.code == "2.05" && it.option("Q-Block2").singleOrNull()?.endsWith("/64") == true }
            .mapNotNull { it.number("Q-Block2") }.toSet()

    /**
     * Pinned, found by L9: when the gaps of a body span two sets, libcoap 4.3.5's client loses the body although
     * every block reaches it. kcoap loses the first copies of blocks 3, 11 and 14; libcoap asks for block 3 alone
     * when block 10 arrives, sends Resets to blocks of set 1 on the original token, asks for the rest of set 1 with
     * 11/M and for the later sets, gets every block 0 to 46, and then exits 0 having written one block of 64 bytes
     * instead of the 3000-byte body. kcoap answers every request as RFC 9177 §4.4 asks.
     */
    @Test
    fun upstreamLibcoapWritesOneBlockForABodyWhoseGapsSpanTwoSetsL3() {
        val lost = Collections.synchronizedSet(mutableSetOf<Long>())
        loss.drop = { message, _ ->
            val num = message.options.qBlock2?.number
            message.isResponse && message.options.size2 == 3000L && num in setOf(3L, 11L, 14L) && lost.add(num!!)
        }
        val result = client("-L", "7", "-N", "-b", "64", "-v", "7", "-B", "30", "-o", "/data/out.bin", "$uri/big")

        assertEquals(setOf(3L, 11L, 14L), lost.toSet())
        assertEquals((0L..46L).toSet(), blocksArrived(result), "every block reached libcoap: $result")
        val missing = receivedMessages().first { it.options.path == "/big" && it.options.qBlock2?.number == 3L }
        assertEquals(listOf(BlockOption(3, false, 2)), missing.options.qBlock2List)
        assertTrue(result.wire.any { it.isSent && it.type == "RST" }, "libcoap resets blocks of the body it still assembles: $result")
        assertEquals(0, result.exitCode, "$result")
        val written = File(directory, "out.bin").readBytes()
        assertTrue(isOneBlock(written), "the deviation: one block of the body written for the body (${written.size} bytes)")
    }

    /** Whether [written] is one block of 64 bytes of `/big`, what libcoap writes for the body in the deviation above. */
    private fun isOneBlock(written: ByteArray): Boolean =
        written.size == 64 && (0 until 46).any { big.copyOfRange(it * 64, it * 64 + 64).contentEquals(written) }

    // ---------------------------------------------------------------- L9: soak

    /**
     * L9: L4 and L2 with 10 % loss on both sides (`-l 10%` on libcoap's, a [DroppingTransport] on kcoap's), in
     * [Soak.runs] runs each: 3 here, 20 in the nightly soak. Every upload stores the whole body or nothing, since
     * kcoap hands only whole bodies to its handler. Every download gives libcoap the whole body, ends with libcoap
     * giving up (an error or nothing written), or meets the deviation pinned in
     * [upstreamLibcoapWritesOneBlockForABodyWhoseGapsSpanTwoSetsL3] (one block written for the body); never does
     * libcoap write bytes that are not the body's. In the nightly soak most runs complete. Afterwards kcoap holds
     * no partial body.
     */
    @Test
    fun l9UploadsAndBodiesAt10PercentLossBothWays() {
        val random = java.util.Random(9)
        loss.drop = { _, _ -> synchronized(random) { random.nextDouble() < 0.1 } }
        var uploads = 0
        var downloads = 0
        var libcoapLostTheBody = 0
        val failures = mutableListOf<String>()
        repeat(Soak.runs) { run ->
            stored = ByteArray(0)
            val upload = client(
                "-L", "7", "-N", "-b", "64", "-v", "6", "-B", "90", "-l", "10%", "-m", "put", "-f", "/data/big3000.txt", "$uri/store",
                timeoutSeconds = 150,
            )
            assertTrue(stored.isEmpty() || stored.contentEquals(big), "run $run: a partial upload reached the handler")
            if (upload.exitCode == 0 && stored.contentEquals(big)) uploads++ else failures += "run $run upload: exit ${upload.exitCode}"

            File(directory, "out.bin").delete()
            val download = client(
                "-L", "7", "-N", "-b", "64", "-v", "7", "-B", "90", "-l", "10%", "-o", "/data/out.bin", "$uri/big",
                timeoutSeconds = 150,
            )
            val file = File(directory, "out.bin")
            val got = if (file.exists()) file.readBytes() else ByteArray(0)
            when {
                download.exitCode == 0 && got.contentEquals(big) -> downloads++
                download.exitCode != 0 || got.isEmpty() -> {
                    val requests = download.wire.filter { it.isSent && it.code == "GET" }.map { it.option("Q-Block2") }
                    val missing = (0L..46L) - blocksArrived(download)
                    failures += "run $run download: libcoap gave up (exit ${download.exitCode}, ${got.size} bytes; asked $requests; never got $missing)"
                }
                else -> {
                    assertTrue(isOneBlock(got), "run $run: libcoap wrote ${got.size} bytes that are neither the body nor one of its blocks\n$download")
                    libcoapLostTheBody++
                    failures += "run $run download: libcoap wrote one block for the body (${blocksArrived(download).size} blocks arrived)"
                }
            }
        }
        println("L9, ${Soak.runs} runs: $uploads uploads and $downloads bodies complete; $failures")
        assertTrue(loss.dropped.isNotEmpty(), "kcoap lost datagrams")
        if (Soak.isNightly) {
            // Over 20 runs (14 uploads and 18 downloads reached the end at the first nightly run); 3 runs say too little.
            val reached = downloads + libcoapLostTheBody
            assertTrue(uploads * 2 >= Soak.runs && reached * 2 >= Soak.runs, "most runs complete: $uploads uploads, $downloads bodies, $failures")
        }
        // An upload libcoap gave up is discarded at the end of the 4.08 ladder (124 s, RFC 9177 §7.2).
        val drained = runBlocking {
            withTimeoutOrNull(140.seconds) {
                while (open.qBlock.state().partialBodies > 0) delay(500.milliseconds)
            }
        }
        assertNotNull(drained, "no partial body is left: ${runBlocking { open.qBlock.state() }}")
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
