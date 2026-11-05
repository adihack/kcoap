package dev.kcoap.coap.interop

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.ResourceAttributes
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorUdpTransport
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * libcoap's `coap-client` against a kcoap server over real UDP: GET, PUT with `-e`, POST, DELETE,
 * Block1 uploads with `-b`, Block2 downloads, Observe with `-s` and discovery. Skipped when docker or the
 * `kcoap/libcoap` image is missing.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibcoapClientAgainstKcoapTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var server: CoapServer
    private lateinit var uri: String
    private val directory: File = Files.createTempDirectory("kcoap-interop").toFile()

    @Volatile
    private var stored: ByteArray = ByteArray(0)

    /** Every message the kcoap server sent. */
    private val sent: MutableList<CoapMessage> = Collections.synchronizedList(mutableListOf())
    private val recorder = object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { sent += it }
    }

    /** Every message the kcoap server received, with the monotonic time it arrived. */
    private val received: MutableList<Pair<TimeSource.Monotonic.ValueTimeMark, CoapMessage>> =
        Collections.synchronizedList(mutableListOf())
    private val arrivals = object : MessageInterceptor {
        override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage =
            message.also { received += TimeSource.Monotonic.markNow() to it }
    }
    private val large: String = Random(5).let { random -> String(CharArray(3000) { 'a' + random.nextInt(26) }) }

    private val counter = object : ObservableResource("counter") {
        @Volatile
        var value = 0
        override val attributes = ResourceAttributes(resourceTypes = listOf("count"), title = "Counter")
        override suspend fun handleGet(context: dev.kcoap.coap.server.RequestContext) =
            CoapResponse.content("$value").withOptions { maxAge(1) }
    }

    @BeforeAll
    fun startServer() {
        assumeTrue(Libcoap.isAvailable, "docker or ${Libcoap.IMAGE} not available")
        runBlocking {
            server = CoapServer(
                KtorUdpTransport.bind("127.0.0.1"), scope,
                endpointConfig = CoapEndpointConfig(interceptors = listOf(recorder, arrivals)),
            ) {
                resource("hello") { get { CoapResponse.content("Hello from kcoap") } }
                resource("store") {
                    get { CoapResponse.content(stored, ContentFormat.TEXT_PLAIN) }
                    put {
                        stored = payload
                        CoapResponse.changed()
                    }
                    post {
                        stored = payload
                        CoapResponse.created("store")
                    }
                    delete {
                        stored = ByteArray(0)
                        CoapResponse.deleted()
                    }
                }
                resource("large") {
                    attributes = ResourceAttributes(maximumSize = 3000)
                    get { CoapResponse.content(large) }
                }
                add(counter)
            }
            uri = "coap://127.0.0.1:${(server.endpoint.transport as KtorUdpTransport).localEndpoint.port}"
        }
        scope.launch {
            while (isActive) {
                delay(500.milliseconds)
                counter.value++
                counter.notifyObservers()
            }
        }
    }

    @AfterAll
    fun stopServer() {
        if (::server.isInitialized) runBlocking { server.close() }
        scope.cancel()
        directory.deleteRecursively()
    }

    /** Runs coap-client with message logging; `-w` ends every payload with a line feed. */
    private fun client(vararg arguments: String): Libcoap.Result {
        sent.clear()
        return Libcoap.client("-B", "10", "-v", "6", "-w", *arguments, mount = directory)
    }

    /** The payloads coap-client printed, one per line, without its message log. */
    private val Libcoap.Result.payloads: List<String> get() = stdout.lines().filter { it.isNotBlank() && !it.startsWith("v:1 ") }

    /** The response codes libcoap logged for messages it received, such as `2.05`. */
    private fun Libcoap.Result.receivedCodes(): List<String> =
        messages.filter { " t:ACK " in it || " t:CON c:2" in it || " t:NON c:" in it }
            .mapNotNull { Regex(""" c:(\d\.\d\d) """).find(it)?.groupValues?.get(1) }

    @Test
    fun getsAResource() {
        val result = client("$uri/hello")
        assertEquals(0, result.exitCode, "$result")
        assertEquals(listOf("Hello from kcoap"), result.payloads, "$result")
        assertTrue("2.05" in result.receivedCodes(), "$result")
    }

    @Test
    fun getsNonConfirmable() {
        val result = client("-N", "$uri/hello")
        assertTrue("Hello from kcoap" in result.stdout, "$result")
        assertTrue(result.messages.any { it.startsWith("v:1 t:NON c:2.05") }, "$result")
    }

    @Test
    fun putsPostsAndDeletesText() {
        val put = client("-m", "put", "-e", "written by libcoap", "$uri/store")
        assertTrue("2.04" in put.receivedCodes(), "$put")
        assertEquals("written by libcoap", stored.decodeToString())
        assertEquals(listOf("written by libcoap"), client("$uri/store").payloads)

        val post = client("-m", "post", "-e", "posted", "$uri/store")
        assertTrue("2.01" in post.receivedCodes(), "$post")
        assertEquals("posted", stored.decodeToString())

        val delete = client("-m", "delete", "$uri/store")
        assertTrue("2.02" in delete.receivedCodes(), "$delete")
        assertEquals(0, stored.size)
    }

    @Test
    fun receivesABlock1Upload() {
        File(directory, "upload.txt").writeText(large)
        val result = client("-m", "put", "-b", "64", "-f", "/data/upload.txt", "$uri/store")
        assertEquals(0, result.exitCode, "$result")
        assertEquals(large, stored.decodeToString())
        val responses = sent.filter { it.isResponse }
        assertEquals(List(3000 / 64) { CoapCode.CONTINUE } + CoapCode.CHANGED, responses.map { it.code })
        assertEquals(BlockOption(46, false, 2), responses.last().options.block1)
    }

    @Test
    fun servesABlock2Download() {
        // coap-client writes each block as it arrives, so its stdout mixes them with the log; -o does not.
        val output = File(directory, "download.txt")
        val result = client("-b", "128", "-o", "/data/download.txt", "$uri/large")
        assertEquals(0, result.exitCode, "$result")
        assertEquals(large, output.readText().removeSuffix("\n"), "-w appends one line feed")
        val blocks = sent.mapNotNull { it.options.block2 }
        assertEquals(24, blocks.size, "3000 bytes in blocks of 128")
        assertTrue(blocks.all { it.size == 128 })

        output.delete()
        client("-o", "/data/download.txt", "$uri/large")
        assertEquals(large, output.readText().removeSuffix("\n"), "-w appends one line feed")
        assertTrue(sent.mapNotNull { it.options.block2 }.all { it.size == 1024 }, "the server's own block size")
    }

    @Test
    fun isObserved() {
        val before = counter.observerCount
        val result = client("-s", "3", "$uri/counter")
        assertEquals(0, result.exitCode, "$result")
        val values = result.payloads.map { it.trim().toInt() }
        assertTrue(values.size >= 4, "notifications every 500 ms: $result")
        assertEquals(values.sorted(), values)
        val notifications = result.messages.filter { it.contains("Observe:") && it.contains(" c:2.05") }
        assertTrue(notifications.size >= 4, "$result")
        runBlocking { delay(1000.milliseconds) }
        assertEquals(before, counter.observerCount, "libcoap deregistered or reset the observation")
    }

    @Test
    fun discoversTheResources() {
        val result = client("$uri/.well-known/core")
        assertEquals(0, result.exitCode, "$result")
        val document = result.payloads.single()
        assertTrue("</hello>" in document, document)
        assertTrue("</counter>;title=\"Counter\";rt=\"count\";obs" in document, document)
        assertTrue("</large>;sz=3000" in document, document)
        val filtered = client("$uri/.well-known/core?rt=count").payloads.single()
        assertEquals("</counter>;title=\"Counter\";rt=\"count\";obs", filtered)
    }

    /**
     * RFC 9177 §4.1 with Q-Block off on kcoap's side (the default): libcoap 4.3.5 turns Q-Block on with
     * `-L 7` (COAP_BLOCK_USE_LIBCOAP + COAP_BLOCK_SINGLE_BODY + COAP_BLOCK_TRY_Q_BLOCK; it has no `-Q`), probes
     * with a Confirmable request carrying Q-Block2, gets 4.02, and sends its real request without Q-Block.
     */
    @Test
    fun fallsBackFromQBlockWhileItIsOff() {
        sent.clear()
        received.clear()
        val result = Libcoap.client("-L", "7", "-v", "7", "-B", "10", "-w", "$uri/hello", mount = directory)

        assertEquals(0, result.exitCode, "$result")
        // At -v 7 the debug log shares stdout with the payload.
        assertTrue("Hello from kcoap" in result.stdout.lines(), "$result")
        assertTrue("Q-Block support not available" in result.stdout + result.stderr, "$result")
        assertTrue(result.messages.any { it.startsWith("v:1 t:CON c:GET ") && "Q-Block2:0/_/16" in it }, "libcoap's probe: $result")
        val requests = received.filter { it.second.isRequest }
        val probe = requests.first { CoapOptions.Q_BLOCK2.number in it.second.options }
        assertEquals(CoapMessageType.CON, probe.second.type, "RFC 9177 §4.1: the probe is Confirmable")
        val refusal = sent.single { it.hasToken(probe.second.token) }
        assertEquals(CoapCode.BAD_OPTION, refusal.code, "$refusal")
        val real = requests.last()
        assertEquals("/hello", real.second.options.path)
        assertTrue(real.second.options.none { it.number == CoapOptions.Q_BLOCK1.number || it.number == CoapOptions.Q_BLOCK2.number })
        val gap = real.first - probe.first
        assertTrue(gap < 1.seconds, "the real request follows the 4.02 within 1 s, not after a timeout: $gap")
        assertEquals(CoapCode.CONTENT, sent.last().code)
    }

    @Test
    fun answersUnknownPathsAndMethods() {
        assertTrue("4.04" in client("$uri/missing").receivedCodes())
        assertTrue("4.05" in client("-m", "post", "-e", "x", "$uri/hello").receivedCodes())
        assertEquals(CoapCode.NOT_FOUND.toString().take(4), "4.04")
    }
}
