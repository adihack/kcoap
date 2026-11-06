package dev.kcoap.coap.interop

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorTcpListener
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import io.ktor.server.cio.CIO
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
 * libcoap's `coap-client` against a kcoap server over `coap+tcp://` and `coap+ws://` (RFC 8323): GET,
 * PUT with `-e`, DELETE, Block1 and Block2, BERT, Observe with `-s`. Over WebSockets `coap-client` runs
 * with `-X 1152`, because libcoap 4.3.5 reads WebSocket frames into a 1472-byte buffer whatever it
 * announces, and dies on larger ones. Skipped when docker or the `kcoap/libcoap` image is missing.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibcoapClientAgainstKcoapReliableTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var server: CoapServer
    private lateinit var tcp: String
    private lateinit var ws: String
    private val directory: File = Files.createTempDirectory("kcoap-interop").toFile()

    @Volatile
    private var stored: ByteArray = ByteArray(0)

    /** Every message the kcoap server received, and the endpoint events. */
    private val received: MutableList<CoapMessage> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<EndpointEvent> = Collections.synchronizedList(mutableListOf())
    private val recorder = object : MessageInterceptor {
        override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage = message.also { received += it }
    }
    private val large: ByteArray = Random(6).let { random -> ByteArray(20_000) { ('a' + random.nextInt(26)).code.toByte() } }

    private val counter = object : ObservableResource("counter") {
        @Volatile
        var value = 0
        override suspend fun handleGet(context: RequestContext) = CoapResponse.content("$value")
    }

    @BeforeAll
    fun startServer() {
        assumeTrue(Libcoap.isAvailable, "docker or ${Libcoap.IMAGE} not available")
        runBlocking {
            val tcpListener = KtorTcpListener.bind("127.0.0.1")
            val wsListener = KtorWebSocketListener.start(CIO, "127.0.0.1", 0)
            server = CoapServer(
                listOf(tcpListener, wsListener), scope,
                endpointConfig = CoapEndpointConfig {
                    interceptors = listOf(recorder)
                    eventListener = EndpointEventListener { events += it }
                },
            ) {
                resource("hello") { get { CoapResponse.content("Hello from kcoap over ${peer::class.simpleName}") } }
                resource("store") {
                    get { CoapResponse.content(stored, ContentFormat.TEXT_PLAIN) }
                    put {
                        stored = payload
                        CoapResponse.changed()
                    }
                    delete {
                        stored = ByteArray(0)
                        CoapResponse.deleted()
                    }
                }
                resource("large") { get { CoapResponse.content(large, ContentFormat.TEXT_PLAIN) } }
                add(counter)
            }
            tcp = "coap+tcp://127.0.0.1:${tcpListener.localEndpoint.port}"
            ws = wsListener.localEndpoint!!.uri.replace("ws://", "coap+ws://").removeSuffix("/.well-known/coap")
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
        received.clear()
        events.clear()
        return Libcoap.client("-B", "10", "-v", "6", "-w", *arguments, mount = directory).also {
            assertTrue(events.none { event -> event is EndpointEvent.AbortSent }, "kcoap aborted: $events\n$it")
        }
    }

    /** The payloads coap-client printed, one per line, without its message log. */
    private val Libcoap.Result.payloads: List<String> get() = stdout.lines().filter { it.isNotBlank() && !it.startsWith("v:") }

    /** RFC 8323 §3.3: libcoap's CSM first, then the request; kcoap answers on the same connection. */
    @Test
    fun getsOverTcpAndWebSockets() {
        val overTcp = client("$tcp/hello")
        assertEquals(0, overTcp.exitCode, "$overTcp")
        assertEquals(listOf("Hello from kcoap over Tcp"), overTcp.payloads, "$overTcp")
        assertEquals(CoapCode.CSM, received.first().code)
        assertTrue(events.any { it is EndpointEvent.CsmReceived })

        val overWebSockets = client("-X", "1152", "$ws/hello")
        assertEquals(0, overWebSockets.exitCode, "$overWebSockets")
        assertEquals(listOf("Hello from kcoap over WebSocket"), overWebSockets.payloads, "$overWebSockets")
    }

    @Test
    fun putsAndDeletesOverBoth() {
        for ((base, extra) in listOf(tcp to emptyList(), ws to listOf("-X", "1152"))) {
            val put = client(*extra.toTypedArray(), "-m", "put", "-e", "from libcoap", "$base/store")
            assertEquals(0, put.exitCode, "$put")
            assertEquals("from libcoap", stored.decodeToString())
            assertEquals(listOf("from libcoap"), client(*extra.toTypedArray(), "$base/store").payloads)
            assertEquals(0, client(*extra.toTypedArray(), "-m", "delete", "$base/store").exitCode)
            assertEquals(0, stored.size)
        }
    }

    /** RFC 8323 §6: libcoap uploads in BERT blocks to kcoap's Max-Message-Size of 8704, the last one larger than 1024 bytes. */
    @Test
    fun acceptsABertUploadOverTcp() {
        val file = File(directory, "upload.txt")
        file.writeBytes(large)
        val result = client("-m", "put", "-f", "/data/upload.txt", "$tcp/store")
        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(large, stored)
        val blocks = received.filter { it.code == CoapCode.PUT }.map { it.options.block1!! to it.payloadSize }
        assertTrue(blocks.size > 1 && blocks.all { it.first.isBert }, "BERT blocks: $blocks")
        assertTrue(blocks.last().second > 1024, "libcoap's final block holds several kibibytes: $blocks")
    }

    /** RFC 8323 §6: to libcoap's `-X 4352`, kcoap serves BERT blocks of 4 KiB. */
    @Test
    fun servesBertBlocksOverTcp() {
        val result = client("-X", "4352", "-o", "/data/download.txt", "$tcp/large")
        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(large, File(directory, "download.txt").readBytes().let { it.copyOf(minOf(it.size, large.size)) })
        val follows = received.filter { it.code == CoapCode.GET }.mapNotNull { it.options.block2 }
        assertTrue(follows.isNotEmpty() && follows.all { it.isBert }, "libcoap asks for further BERT blocks: $follows")
    }

    /** Over WebSockets with `-X 1152`, the same body comes in plain 1024-byte Block2 blocks. */
    @Test
    fun servesPlainBlocksOverWebSockets() {
        val result = client("-X", "1152", "-o", "/data/ws.txt", "$ws/large")
        assertEquals(0, result.exitCode, "$result")
        assertContentEquals(large, File(directory, "ws.txt").readBytes().let { it.copyOf(minOf(it.size, large.size)) })
        val follows = received.filter { it.code == CoapCode.GET }.mapNotNull { it.options.block2 }
        assertTrue(follows.size >= 19 && follows.none { it.isBert }, "$follows")
    }

    /** RFC 8323 §7: libcoap observes for 3 seconds and deregisters; kcoap notifies on the connection. */
    @Test
    fun observesOverBoth() {
        for ((base, extra) in listOf(tcp to emptyList(), ws to listOf("-X", "1152"))) {
            val result = client(*extra.toTypedArray(), "-s", "3", "$base/counter")
            assertEquals(0, result.exitCode, "$result")
            val values = result.payloads.map { it.toInt() }
            assertTrue(values.size >= 3, "notifications: $values\n$result")
            assertEquals(values.sorted(), values)
            assertTrue(counter.observerCount == 0, "the observer is gone once libcoap closed the connection")
        }
    }
}
