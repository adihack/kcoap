package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreIdentity
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole stack, as an application uses it: a CoAP server with an EDHOC resource and protected
 * resources, a CoAP client that runs EDHOC and then talks OSCORE, over a simulated network.
 */
class FullStackTest {
    // Real cryptography under CoAP timers: on WebCrypto it must not let virtual time run.
    private val crypto = InstantCrypto(DefaultCryptoBackend())

    @Test
    fun edhocThenOscoreThroughClientAndServer() = runTest {
        val network = InMemoryNetwork(backgroundScope, Random(7))
        network.conditions = LinkConditions(lossRate = 0.1, duplicationRate = 0.1)
        val endpointConfig = CoapEndpointConfig(timeSource = testScheduler.timeSource)
        val peers = Peers.create(crypto)

        // Server: EDHOC resource, OSCORE (with the combined request) for everything else.
        val contexts = InMemoryContextRepository()
        val edhoc = EdhocServer(peers.responderConfig(), crypto, contexts)
        val serverTransport = network.attach()
        val wire = mutableListOf<CoapMessage>()
        val clientEndpointConfig = CoapEndpointConfig(
            timeSource = testScheduler.timeSource,
            interceptors = listOf(object : MessageInterceptor {
                override fun onSend(to: Endpoint, message: CoapMessage) = message.also { wire += it }
                override fun onReceive(from: Endpoint, message: CoapMessage) = message
            }),
        )
        val bigBody = ByteArray(3000) { (it % 251).toByte() }
        var uploaded = ByteArray(0)
        val server = CoapServer(serverTransport, backgroundScope, CoapServerConfig(security = CombinedRequestServer(edhoc, OscoreServer(contexts))), endpointConfig) {
            resource(".well-known") { add(EdhocResource(edhoc)) }
            resource("lights") {
                get {
                    // Only OSCORE-authenticated clients may read.
                    if (securityIdentity !is OscoreIdentity) CoapResponse.error(CoapCode.UNAUTHORIZED) else CoapResponse.content("on")
                }
            }
            resource("firmware") {
                get { CoapResponse.content(bigBody, ContentFormat.OCTET_STREAM) }
                put {
                    uploaded = payload
                    CoapResponse.changed()
                }
            }
            resource("counter") {
                observable()
                get { CoapResponse.content("tick") }
            }
        }

        // Client: EDHOC first, then the combined request carries message_3.
        val security = PeerSecurity()
        val client = CoapClient(network.attach(), backgroundScope, CoapClientConfig(security = security), clientEndpointConfig)
        val address = serverTransport.localEndpoint
        val base = "coap://${address.host}:${address.port}"
        val serverUri = CoapUri.parse(base)

        assertEquals(CoapCode.UNAUTHORIZED, client.get("$base/lights").code, "no OSCORE yet")
        val store = RecordingStore()
        val handshake =
            EdhocClientHandshake.over(client, serverUri, peers.initiatorConfig(), crypto, sequenceStore = store)
        val (session, protection) = handshake.runCombined()
        assertEquals(setOf(session.oscore.name), store.names, "the context was derived with the given store")
        security.install(address, protection)
        assertTrue(security.isProtected(address))

        val lights = client.get("$base/lights")
        assertEquals("on", lights.payloadAsText())
        assertTrue(wire.any { it.options.edhoc }, "message_3 went with the first OSCORE request")
        assertFalse(protection.isMessage3Pending())
        assertEquals(session.oscore.recipientId.toList(), session.edhoc.ownConnectionIdentifier.bytes.toList())

        // Block-wise over OSCORE in both directions (inner blocks, RFC 8613 §4.1.3.4.1).
        assertContentEquals(bigBody, client.get("$base/firmware").payload)
        assertEquals(CoapCode.CHANGED, client.put("$base/firmware", bigBody, ContentFormat.OCTET_STREAM).code)
        assertContentEquals(bigBody, uploaded)
        assertTrue(wire.filter { it.options.oscore != null }.none { it.options.uriPath.isNotEmpty() }, "paths stay encrypted")

        // Observe over OSCORE: notifications carry their own Partial IVs.
        val counter = server.root.child("counter") as dev.kcoap.coap.server.ObservableResource
        val notifications = backgroundScope.launch {
            val received = client.observe("$base/counter").take(3).toList()
            assertEquals(3, received.size)
        }
        repeat(5) {
            delay(1_000)
            counter.notifyObservers()
        }
        notifications.join()
        client.close()
        server.close()
    }
}
