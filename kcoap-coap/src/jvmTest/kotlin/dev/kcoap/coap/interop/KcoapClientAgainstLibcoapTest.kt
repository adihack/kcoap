package dev.kcoap.coap.interop

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorUdpTransport
import java.util.Collections
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The kcoap client against libcoap's `coap-server` (`-d 10` allows dynamic resources), over real UDP.
 * Skipped when docker or the `kcoap/libcoap` image is missing.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KcoapClientAgainstLibcoapTest {
    private lateinit var server: Libcoap.Server
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Every message the client saw, for assertions on the wire. */
    private val wire: MutableList<Pair<String, CoapMessage>> = Collections.synchronizedList(mutableListOf())
    private val recorder = object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { wire += "out" to it }
        override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage = message.also { wire += "in" to it }
    }

    @BeforeAll
    fun startServer() {
        assumeTrue(Libcoap.isAvailable, "docker or ${Libcoap.IMAGE} not available")
        server = Libcoap.startServer("-d", "10", "-v", "4")
        // Wait until the server answers pings.
        runBlocking {
            val probe = CoapEndpoint(
                KtorUdpTransport.bind("127.0.0.1"), scope,
                CoapEndpointConfig { parameters = TransmissionParameters(ackTimeout = 500.milliseconds, maxRetransmit = 0) },
            )
            val ready = (1..40).any { probe.ping(Endpoint.Udp("127.0.0.1", server.port)) }
            probe.close()
            check(ready) { "coap-server did not answer: ${server.logs()}" }
        }
    }

    @AfterAll
    fun stopServer() {
        if (::server.isInitialized) server.close()
        scope.cancel()
    }

    private fun withClient(config: CoapClientConfig = CoapClientConfig.Default, block: suspend (CoapClient) -> Unit) = runBlocking {
        wire.clear()
        val client = CoapClient(
            KtorUdpTransport.bind("127.0.0.1"), scope, config,
            CoapEndpointConfig { interceptors = listOf(recorder) },
        )
        try {
            withTimeout(60.seconds) { block(client) }
        } finally {
            client.close()
        }
    }

    @Test
    fun discoversTheResourcesOfWellKnownCore() = withClient { client ->
        val links = client.discover(server.uri)
        val time = links.single { it.target == "/time" }
        assertEquals(listOf("ticks"), time.resourceTypes)
        assertEquals(listOf("clock"), time.interfaces)
        assertEquals("Internal Clock", time.title)
        assertEquals(listOf(ContentFormat.TEXT_PLAIN), time.contentFormats)
        assertTrue(time.isObservable)
        assertTrue(links.any { it.target == "/example_data" })

        // RFC 6690 §4.1 filtering, done by libcoap.
        assertEquals(listOf("/time"), client.discover(server.uri, "rt=ticks").map { it.target })
    }

    @Test
    fun getsTheRootResourceConfirmableAndNonConfirmable() = withClient { client ->
        val response = client.get("${server.uri}/")
        assertEquals(CoapCode.CONTENT, response.code)
        assertTrue("libcoap" in response.payloadAsText())

        val non = client.get("${server.uri}/") { nonConfirmable() }
        assertEquals(CoapCode.CONTENT, non.code)
        assertTrue(wire.any { (direction, message) -> direction == "in" && message.type == CoapMessageType.NON && message.isResponse })
    }

    @Test
    fun putsGetsPostsAndDeletes() = withClient { client ->
        val put = client.put("${server.uri}/example_data", "hello from kcoap".encodeToByteArray(), ContentFormat.TEXT_PLAIN)
        assertTrue(put.code == CoapCode.CREATED || put.code == CoapCode.CHANGED, "PUT answered ${put.code}")
        assertEquals("hello from kcoap", client.get("${server.uri}/example_data").payloadAsText())
        assertEquals(CoapCode.METHOD_NOT_ALLOWED, client.post("${server.uri}/example_data", "x".encodeToByteArray()).code)

        // A dynamic resource (coap-server -d): created by PUT, gone after DELETE.
        assertEquals(CoapCode.CREATED, client.put("${server.uri}/kcoap-dynamic", "dynamic".encodeToByteArray()).code)
        assertEquals("dynamic", client.get("${server.uri}/kcoap-dynamic").payloadAsText())
        assertEquals(CoapCode.DELETED, client.delete("${server.uri}/kcoap-dynamic").code)
        assertEquals(CoapCode.NOT_FOUND, client.get("${server.uri}/kcoap-dynamic").code)
    }

    @Test
    fun uploadsWithBlock1AndDownloadsWithBlock2() = withClient { client ->
        val random = Random(2)
        val body = ByteArray(3000) { ('a' + random.nextInt(26)).code.toByte() }
        val put = client.put("${server.uri}/example_data", body, ContentFormat.TEXT_PLAIN)
        assertTrue(put.isSuccess, "PUT answered ${put.code}")
        val uploads = wire.filter { it.first == "out" }.mapNotNull { it.second.options.block1 }
        assertEquals(listOf(0L, 1L, 2L), uploads.map { it.number })

        val download = client.get("${server.uri}/example_data")
        assertContentEquals(body, download.payload)
        val blocks = wire.filter { it.first == "in" }.mapNotNull { it.second.options.block2 }
        assertTrue(blocks.size >= 3, "downloaded in blocks: $blocks")
    }

    @Test
    fun negotiatesASmallBlockSizeEarly() = withClient(CoapClientConfig { preferredBlockSize = 64 }) { client ->
        val random = Random(3)
        val body = ByteArray(700) { ('a' + random.nextInt(26)).code.toByte() }
        assertTrue(client.put("${server.uri}/example_data", body).isSuccess)
        wire.clear()

        assertContentEquals(body, client.get("${server.uri}/example_data").payload)
        val blocks = wire.filter { it.first == "in" }.mapNotNull { it.second.options.block2 }
        assertEquals(11, blocks.size)
        assertTrue(blocks.all { it.size == 64 })
    }

    @Test
    fun observesTheClockAndDeregisters() = withClient { client ->
        val notifications = client.observe("${server.uri}/time").take(3).toList()
        assertEquals(3, notifications.size)
        assertTrue(notifications.all { it.code == CoapCode.CONTENT && it.payloadSize > 0 })
        delay(2.seconds)

        val observeValues = wire.filter { it.first == "in" }.mapNotNull { it.second.options.observe }
        assertEquals(observeValues.sorted(), observeValues)
        val requests = wire.filter { it.first == "out" && it.second.isRequest }.map { it.second }
        assertEquals(listOf(0L, 1L), requests.map { it.options.observe })
        assertEquals(requests[0].tokenHex, requests[1].tokenHex)
        val last = wire.last { it.first == "in" && it.second.isResponse }.second
        assertEquals(null, last.options.observe, "the deregistration is answered without Observe")
    }

    @Test
    fun waitsForASeparateResponse() = withClient { client ->
        val response = client.get("${server.uri}/async?1")
        assertEquals("done", response.payloadAsText())
        val incoming = wire.filter { it.first == "in" }.map { it.second }
        assertTrue(incoming.any { it.type == CoapMessageType.ACK && it.isEmpty }, "an Empty ACK first: $incoming")
        assertTrue(incoming.any { it.type == CoapMessageType.CON && it.code == CoapCode.CONTENT }, "then a separate response")
    }

    @Test
    fun pingsTheServer() = withClient { client ->
        assertTrue(client.ping(server.uri))
    }
}
