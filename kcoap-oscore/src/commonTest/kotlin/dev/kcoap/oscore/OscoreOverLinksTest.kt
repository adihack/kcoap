package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.EndpointResolver
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.testkit.InMemoryLink
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * OSCORE (RFC 8613) through kcoap's client and server behaves the same on every link: each test runs once over
 * datagrams ([OscoreOverDatagramsTest]) and once on connections ([OscoreOverConnectionsTest]), on
 * kcoap-testkit's [InMemoryNetwork] and [InMemoryConnections] and virtual time, on every target. OSCORE sits
 * above the link (RFC 8613 Figure 1; ADR 0005), so a protected request, inner Block-wise both ways, protected
 * notifications, the Echo freshness challenge of a restarted server, the default access policy and an
 * unprotected error all go through the same code on both.
 */
abstract class OscoreOverLinksTest {
    /** The link under test, on [network] or on [connections]. */
    protected abstract fun link(network: InMemoryNetwork, connections: InMemoryConnections): InMemoryLink

    // Real cryptography under CoAP timers: on WebCrypto it must not let virtual time run.
    private val crypto = InstantCrypto(DefaultCryptoBackend())
    private val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
    private val clientParameters = SecurityContextParameters(secret, senderId = byteArrayOf(0x0a), recipientId = byteArrayOf(0x0b))
    private val serverParameters = SecurityContextParameters(secret, senderId = byteArrayOf(0x0b), recipientId = byteArrayOf(0x0a))

    private class Counter : ObservableResource("counter") {
        var value = 0
            private set

        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content("$value")

        fun change() {
            value++
            notifyObservers()
        }
    }

    /** A protected server and a client on one link; [wire] holds the messages the client sent and received, as on the link. */
    private class Setup(
        val link: InMemoryLink,
        val server: CoapServer,
        val side: InMemoryLink.Side,
        val endpointConfig: CoapEndpointConfig,
        val counter: Counter,
        val seen: List<SecurityIdentity?>,
        val stored: List<ByteArray>,
    ) {
        val wire = mutableListOf<CoapMessage>()

        fun uri(path: String): String = link.uri + path

        /** A client on a new endpoint with [security]. */
        suspend fun client(scope: CoroutineScope, security: OscoreClient?): CoapClient {
            val clientSide = link.client()
            val recorder = object : MessageInterceptor {
                override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { wire += it }

                override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage = message.also { wire += it }
            }
            val config = CoapEndpointConfig(timeSource = endpointConfig.timeSource, random = Random(2), interceptors = listOf(recorder))
            val endpoint = CoapEndpoint(scope, config, clientSide.transport, clientSide.connectors, clientSide.listeners)
            val resolver = EndpointResolver { uri -> clientSide.peer(EndpointResolver.Default.resolve(uri)) }
            return CoapClient(endpoint, CoapClientConfig(security = security, resolver = resolver))
        }
    }

    private suspend fun TestScope.setup(restarted: Boolean = false): Setup {
        val link = link(InMemoryNetwork(backgroundScope, Random(1)), InMemoryConnections(chunkSize = 7))
        val serverContext = if (restarted) restoredContext(serverParameters, crypto) else ephemeralContext(serverParameters, crypto)
        val repository = InMemoryContextRepository().apply { add(serverContext) }
        val config = CoapEndpointConfig(timeSource = testScheduler.timeSource, random = Random(1))
        val side = link.server()
        val counter = Counter()
        val seen = mutableListOf<SecurityIdentity?>()
        val stored = mutableListOf<ByteArray>()
        val endpoint = CoapEndpoint(backgroundScope, config, side.transport, side.connectors, side.listeners)
        val server = CoapServer(endpoint, CoapServerConfig(security = OscoreServer(repository))) {
            add(counter)
            resource("lights") {
                get {
                    seen += securityIdentity
                    CoapResponse.content("on")
                }
            }
            resource("big") {
                get { CoapResponse.content(body(BIG), ContentFormat.OCTET_STREAM) }
                put {
                    stored += payload
                    CoapResponse.changed()
                }
            }
        }
        return Setup(link, server, side, config, counter, seen, stored)
    }

    private suspend fun TestScope.protectedClient(setup: Setup): CoapClient =
        setup.client(backgroundScope, OscoreClient(ephemeralContext(clientParameters, crypto)))

    private fun body(size: Int): ByteArray = ByteArray(size) { (it * 7 + it / 253).toByte() }

    @Test
    fun rfc8613Section8AProtectedRequestReachesItsResourceWithTheContextsIdentity() = runTest {
        val setup = setup()
        val client = protectedClient(setup)

        assertEquals("on", client.get(setup.uri("/lights")).payloadAsText())

        assertEquals(OscoreIdentity(byteArrayOf(0x0a), null), setup.seen.single())
        val messages = setup.wire.filter { it.isRequest || it.isResponse }
        assertTrue(messages.isNotEmpty() && messages.all { it.options.oscore != null }, "every request and response is protected")
        assertTrue(messages.none { it.options.uriPath.isNotEmpty() }, "Uri-Path is inner (Class E)")
    }

    /** RFC 8613 §4.1.3.4.1: inner Block-wise, so OSCORE protects each block, both ways and on every link. */
    @Test
    fun rfc8613Section4_1_3_4InnerBlockwiseCarriesBodiesBothWays() = runTest {
        val setup = setup()
        val client = protectedClient(setup)

        assertContentEquals(body(BIG), client.get(setup.uri("/big")).payload)
        assertEquals(CoapCode.CHANGED, client.put(setup.uri("/big"), body(BIG)).code)

        assertContentEquals(body(BIG), setup.stored.single())
        val messages = setup.wire.filter { it.isRequest || it.isResponse }
        assertTrue(messages.size > 4, "in blocks: ${messages.size} messages")
        assertTrue(messages.none { it.options.block1 != null || it.options.block2 != null }, "no outer Block options")
    }

    /** RFC 8613 §8.3: notifications are protected with the registration's context, and arrive in order. */
    @Test
    fun rfc8613Section8_3ProtectedNotificationsFollowTheState() = runTest {
        val setup = setup()
        val client = protectedClient(setup)
        val values = async { client.observe(setup.uri("/counter")).take(3).toList().map { it.payloadAsText() } }
        delay(1.seconds)
        assertEquals(1, setup.counter.observerCount)

        setup.counter.change()
        delay(1.seconds)
        setup.counter.change()

        assertEquals(listOf("0", "1", "2"), values.await())
        delay(1.seconds)
        assertEquals(0, setup.counter.observerCount, "deregistered")
    }

    /**
     * RFC 8613 Appendix B.1.2: a server whose context was restored from a persisted sequence number asks for a
     * freshness proof, a 4.01 with Echo, and the client repeats its request once with that value.
     */
    @Test
    fun rfc8613AppendixB1_2ARestartedServerChallengesOnceWithEcho() = runTest {
        val setup = setup(restarted = true)
        val client = protectedClient(setup)

        assertEquals("on", client.get(setup.uri("/lights")).payloadAsText())

        val requests = setup.wire.filter { it.isRequest }
        assertEquals(2, requests.size, "the request and its repeat")
        assertEquals(1, setup.seen.size, "the resource ran once")
    }

    /** ADR 0008: a server with OSCORE refuses an unprotected request by default, before the resource runs. */
    @Test
    fun adr0008AnUnprotectedRequestIsRefused() = runTest {
        val setup = setup()
        val plain = setup.client(backgroundScope, security = null)

        assertEquals(CoapCode.UNAUTHORIZED, plain.get(setup.uri("/lights")).code)
        assertTrue(setup.seen.isEmpty())
    }

    /**
     * RFC 8613 §8.2 and ADR 0008: a context the server does not know gets an unprotected 4.01, which the client
     * cannot verify, so the request fails with [CoapException.Reason.UNVERIFIED_RESPONSE] and is not repeated.
     */
    @Test
    fun rfc8613Section8_2AnUnprotectedErrorFailsTheRequest() = runTest {
        val setup = setup()
        val stranger = SecurityContextParameters(secret, senderId = byteArrayOf(0x0c), recipientId = byteArrayOf(0x0b))
        val client = setup.client(backgroundScope, OscoreClient(ephemeralContext(stranger, crypto)))

        val failure = assertIs<CoapException>(runCatching { client.get(setup.uri("/lights")) }.exceptionOrNull())

        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, failure.reason)
        assertEquals(CoapCode.UNAUTHORIZED, assertNotNull(failure.response).code)
        assertNull(failure.response?.options?.oscore)
        assertEquals(1, setup.wire.count { it.isRequest }, "not repeated")
        assertTrue(setup.seen.isEmpty())
    }

    private companion object {
        /** Larger than one message on either link, so it goes in blocks: datagrams of 1152, BERT blocks of 8 KiB. */
        const val BIG = 20_000
    }
}
