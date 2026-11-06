package dev.kcoap.coap.interop

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorTcpConnector
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
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
 * The kcoap client against libcoap's `coap-server` over CoAP over TCP and WebSockets (RFC 8323), every
 * test on both transports:
 *
 * - `coap-server -p <port> -w <ws port> -d 10 -X 1152 -v 4` for the common tests. libcoap 4.3.5 reads
 *   WebSocket frames into a 1472-byte buffer whatever its CSM announces, and a larger frame kills it, so
 *   its Max-Message-Size is held at 1152 and large bodies go in 1024-byte blocks.
 * - `coap-server -p <port> -d 10 -X 4352 -v 4` for BERT over TCP: 4 KiB blocks plus 256 bytes.
 *
 * Skipped when docker or the image is missing.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KcoapClientAgainstLibcoapReliableTest {
    private lateinit var server: Libcoap.Server
    private lateinit var bertServer: Libcoap.Server
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Every message the client saw, for assertions on the wire. */
    private val wire: MutableList<Pair<String, CoapMessage>> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<EndpointEvent> = Collections.synchronizedList(mutableListOf())
    private val recorder = object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { wire += "out" to it }
        override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage = message.also { wire += "in" to it }
    }

    @BeforeAll
    fun startServers() {
        assumeTrue(Libcoap.isAvailable, "docker or ${Libcoap.IMAGE} not available")
        server = Libcoap.startServer("-d", "10", "-X", "1152", "-v", "4", webSockets = true)
        bertServer = Libcoap.startServer("-d", "10", "-X", "4352", "-v", "4")
        runBlocking {
            val probe = CoapClient(listOf(KtorTcpConnector(), KtorWebSocketConnector()), scope)
            val ready = (1..40).any {
                val up = probe.ping(server.tcpUri) && probe.ping(server.webSocketUri) && probe.ping(bertServer.tcpUri)
                if (!up) delay(250.milliseconds)
                up
            }
            probe.close()
            check(ready) { "coap-server did not answer over TCP and WebSockets: ${server.logs()}" }
        }
    }

    @AfterAll
    fun stopServers() {
        if (::server.isInitialized) server.close()
        if (::bertServer.isInitialized) bertServer.close()
        scope.cancel()
    }

    private fun base(transport: String) = if (transport == "tcp") server.tcpUri else server.webSocketUri

    private fun withClient(block: suspend (CoapClient) -> Unit) = runBlocking {
        wire.clear()
        events.clear()
        val client = CoapClient(
            listOf(KtorTcpConnector(), KtorWebSocketConnector()), scope,
            endpointConfig = CoapEndpointConfig {
                interceptors = listOf(recorder)
                eventListener = EndpointEventListener { events += it }
            },
        )
        try {
            withTimeout(60.seconds) { block(client) }
        } finally {
            client.close()
        }
        assertTrue(events.none { it is EndpointEvent.AbortSent || it is EndpointEvent.AbortReceived }, "no Abort: $events")
    }

    private fun incoming(code: CoapCode) = wire.filter { it.first == "in" && it.second.code == code }.map { it.second }

    private fun outgoing(code: CoapCode) = wire.filter { it.first == "out" && it.second.code == code }.map { it.second }

    /** RFC 8323 §3.3, §5.3: both CSMs, libcoap's with its Max-Message-Size and Block-Wise-Transfer. */
    private fun exchangesCsmsAndGetsTheRootResource(transport: String) = withClient { client ->
        val response = client.get("${base(transport)}/")
        assertEquals(CoapCode.CONTENT, response.code)
        assertTrue("libcoap" in response.payloadAsText())
        assertEquals(CoapCode.CSM, wire.first { it.first == "out" }.second.code, "kcoap's CSM goes first")
        val csm = events.filterIsInstance<EndpointEvent.CsmReceived>().single()
        assertEquals(1152, csm.maxMessageSize)
        assertTrue(csm.blockWiseTransfer)
    }

    private fun putsGetsAndDeletes(transport: String) = withClient { client ->
        val uri = base(transport)
        val put = client.put("$uri/example_data", "kcoap over $transport".encodeToByteArray(), ContentFormat.TEXT_PLAIN)
        assertTrue(put.code == CoapCode.CREATED || put.code == CoapCode.CHANGED, "PUT answered ${put.code}")
        assertEquals("kcoap over $transport", client.get("$uri/example_data").payloadAsText())

        assertEquals(CoapCode.CREATED, client.put("$uri/kcoap-$transport", "dynamic".encodeToByteArray()).code)
        assertEquals("dynamic", client.get("$uri/kcoap-$transport").payloadAsText())
        assertEquals(CoapCode.DELETED, client.delete("$uri/kcoap-$transport").code)
        assertEquals(CoapCode.NOT_FOUND, client.get("$uri/kcoap-$transport").code)
    }

    /**
     * RFC 8323 §5.3.1: each side sizes messages by the other's Max-Message-Size. The upload goes in
     * 1024-byte blocks to fit libcoap's 1152; the download comes whole, as kcoap announces 8704.
     */
    private fun sizesMessagesByThePeersMaxMessageSize(transport: String) = withClient { client ->
        val random = Random(2)
        val body = ByteArray(3000) { ('a' + random.nextInt(26)).code.toByte() }
        assertTrue(client.put("${base(transport)}/example_data", body, ContentFormat.TEXT_PLAIN).isSuccess)
        val uploads = outgoing(CoapCode.PUT).map { it.options.block1!! }
        assertEquals(listOf(0L, 1L, 2L), uploads.map { it.number })
        assertTrue(uploads.all { it.size == 1024 && !it.isBert })
        assertContentEquals(body, client.get("${base(transport)}/example_data").payload)
        val download = incoming(CoapCode.CONTENT).single()
        assertEquals(3000, download.payloadSize)
        assertEquals(null, download.options.block2)
    }

    /** RFC 8323 §7: notifications over the connection, ended by an explicit deregistration (§7.4). */
    private fun observesTheClockAndDeregisters(transport: String) = withClient { client ->
        val notifications = client.observe("${base(transport)}/time").take(3).toList()
        assertEquals(3, notifications.size)
        assertTrue(notifications.all { it.code == CoapCode.CONTENT && it.payloadSize > 0 })
        delay(1.seconds)
        val requests = wire.filter { it.first == "out" && it.second.isRequest }.map { it.second }
        assertEquals(listOf(0L, 1L), requests.map { it.options.observe })
        assertEquals(requests[0].tokenHex, requests[1].tokenHex)
    }

    /** RFC 8323 §5.4: a Ping is answered by a Pong (libcoap 4.3.5 sends it with Custody and without the token). */
    private fun pingsOverTheConnection(transport: String) = withClient { client ->
        assertTrue(client.ping(base(transport)))
        val peer = if (transport == "tcp") Endpoint.Tcp("127.0.0.1", server.port) else Endpoint.WebSocket.of("127.0.0.1", server.webSocketPort!!)
        assertTrue(client.endpoint.ping(peer, custody = true))
        assertEquals(2, incoming(CoapCode.PONG).size)
    }

    /** RFC 8323 §6: a body above libcoap's Max-Message-Size goes up and comes down in BERT blocks. */
    @Test
    fun overTcpUploadsAndDownloadsWithBert() = withClient { client ->
        val random = Random(8)
        val body = ByteArray(20_000) { ('a' + random.nextInt(26)).code.toByte() }
        val put = client.put("${bertServer.tcpUri}/example_data", body, ContentFormat.TEXT_PLAIN)
        assertTrue(put.isSuccess, "PUT answered ${put.code}")
        val uploads = outgoing(CoapCode.PUT).map { it.options.block1!! }
        assertTrue(uploads.all { it.isBert }, "BERT uploads: $uploads")
        assertEquals(listOf(0L, 4L, 8L, 12L, 16L, 19L), uploads.map { it.number }, "4 KiB per message, the last within 1 KiB")
        assertTrue(incoming(CoapCode.CONTINUE).all { it.options.block1!!.isBert }, "libcoap accepts BERT")

        wire.clear()
        assertContentEquals(body, client.get("${bertServer.tcpUri}/example_data").payload)
        val downloads = incoming(CoapCode.CONTENT).mapNotNull { it.options.block2 }
        assertTrue(downloads.isNotEmpty() && downloads.all { it.isBert }, "BERT downloads: $downloads")
    }

    // ---------------------------------------------------------------- one test per transport

    @Test
    fun overTcpExchangesCsmsAndGetsTheRootResource() = exchangesCsmsAndGetsTheRootResource("tcp")

    @Test
    fun overWebSocketsExchangesCsmsAndGetsTheRootResource() = exchangesCsmsAndGetsTheRootResource("ws")

    @Test
    fun overTcpPutsGetsAndDeletes() = putsGetsAndDeletes("tcp")

    @Test
    fun overWebSocketsPutsGetsAndDeletes() = putsGetsAndDeletes("ws")

    @Test
    fun overTcpSizesMessagesByThePeersMaxMessageSize() = sizesMessagesByThePeersMaxMessageSize("tcp")

    @Test
    fun overWebSocketsSizesMessagesByThePeersMaxMessageSize() = sizesMessagesByThePeersMaxMessageSize("ws")

    @Test
    fun overTcpObservesTheClockAndDeregisters() = observesTheClockAndDeregisters("tcp")

    @Test
    fun overWebSocketsObservesTheClockAndDeregisters() = observesTheClockAndDeregisters("ws")

    @Test
    fun overTcpPingsOverTheConnection() = pingsOverTheConnection("tcp")

    @Test
    fun overWebSocketsPingsOverTheConnection() = pingsOverTheConnection("ws")
}
