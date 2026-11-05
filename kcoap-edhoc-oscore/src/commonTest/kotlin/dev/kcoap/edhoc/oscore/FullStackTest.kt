package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.oscore.InMemoryContextRepository
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        val lightsSeenBy = mutableListOf<SecurityIdentity?>()
        var uploaded = ByteArray(0)
        val server = CoapServer(serverTransport, backgroundScope, CoapServerConfig(security = CombinedRequestServer(edhoc, OscoreServer(contexts))), endpointConfig) {
            resource(".well-known") { add(EdhocResource(edhoc)) }
            // The default policy of a server with message security (ADR 0008): only OSCORE reaches it.
            resource("lights") {
                get {
                    lightsSeenBy += securityIdentity
                    CoapResponse.content("on")
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

        // Without a protection installed, PeerSecurity sends nothing rather than plaintext; a plain client is refused.
        assertFailsWith<MessageSecurityException>("no OSCORE yet") { client.get("$base/lights") }
        val plain = CoapClient(network.attach(), backgroundScope, endpointConfig = endpointConfig)
        assertEquals(CoapCode.UNAUTHORIZED, plain.get("$base/lights").code)
        assertEquals(emptyList(), lightsSeenBy, "the handler never ran for it")
        // /.well-known/core shows a plain client only the EDHOC resource, which is Public.
        assertEquals(listOf("/.well-known/edhoc"), plain.discover(base).map { it.target })
        val store = RecordingStore()
        val handshake =
            EdhocClientHandshake.over(client, serverUri, peers.initiatorConfig(), crypto, sequenceStore = store)
        val (session, protection) = handshake.runCombined()
        assertEquals(setOf(session.oscore.name), store.names, "the context was derived with the given store")
        // Installed by URI: the client's own resolver finds the endpoint its requests go to.
        assertEquals(address, security.install(client, serverUri, protection))
        assertTrue(security.isProtected(address))
        val unresolvable = CoapUri.parse("coap://edhoc.example")
        assertFailsWith<IllegalArgumentException>("IpLiterals knows no DNS") { security.install(client, unresolvable, protection) }

        val lights = client.get("$base/lights")
        assertEquals("on", lights.payloadAsText())
        // The handler sees who EDHOC authenticated, not only the C_R it allocated.
        val identity = assertIs<EdhocIdentity>(lightsSeenBy.single())
        assertEquals(peers.client.credential, identity.peerCredential)
        assertContentEquals(session.edhoc.peerConnectionIdentifier.bytes, identity.oscore.recipientId)
        assertTrue(client.discover(base).any { it.target == "/lights" && it.has("osc") }, "an OSCORE client sees it, marked osc")
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

    /**
     * A client that probes for Q-Block under message security (RFC 9177 §4.1) asks before every request whose
     * server it knows nothing about, the EDHOC request included. Before the protection is installed,
     * `PeerSecurity` will not send the probe (ADR 0008), so the EDHOC request goes in the clear with RFC 7959 and
     * nothing is kept; the first request after the install probes, protected, and Q-Block carries the bodies.
     */
    @Test
    fun edhocRunsOnAClientThatProbesForQBlockUnderMessageSecurity() = runTest {
        val network = InMemoryNetwork(backgroundScope, Random(11))
        val endpointConfig = CoapEndpointConfig(timeSource = testScheduler.timeSource)
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val edhoc = EdhocServer(peers.responderConfig(), crypto, contexts)
        val serverTransport = network.attach()
        val bigBody = ByteArray(3000) { (it % 251).toByte() }
        val serverConfig = CoapServerConfig(
            security = CombinedRequestServer(edhoc, OscoreServer(contexts)),
            qBlock = QBlockServerConfig(isEnabled = true, withMessageSecurity = true),
        )
        val server = CoapServer(serverTransport, backgroundScope, serverConfig, endpointConfig) {
            resource(".well-known") { add(EdhocResource(edhoc)) }
            resource("firmware") { get { CoapResponse.content(bigBody, ContentFormat.OCTET_STREAM) } }
        }

        // Every request the client hands to message security, inner as it is, and whether it went protected.
        val security = PeerSecurity()
        val handed = mutableListOf<Pair<CoapMessage, Boolean>>()
        val recording = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
                val protected = try {
                    security.protectRequest(request, peer)
                } catch (e: MessageSecurityException) {
                    handed += request to false
                    throw e
                }
                handed += request to (protected != null)
                return protected
            }
        }
        val qBlock = QBlockClientConfig(QBlockMode.PROBE, withMessageSecurity = true)
        val clientConfig = CoapClientConfig(security = recording, qBlock = qBlock)
        val client = CoapClient(network.attach(), backgroundScope, clientConfig, endpointConfig)
        val address = serverTransport.localEndpoint
        val base = "coap://${address.host}:${address.port}"

        val handshake = EdhocClientHandshake.over(client, CoapUri.parse(base), peers.initiatorConfig(), crypto)
        val (_, protection) = handshake.runCombined()
        val beforeInstall = handed.toList()
        security.install(client, CoapUri.parse(base), protection)
        assertContentEquals(bigBody, client.get("$base/firmware").payload)

        val edhocRequests = beforeInstall.filter { it.first.options.path == "/.well-known/edhoc" }
        assertTrue(edhocRequests.isNotEmpty() && edhocRequests.none { it.second }, "message_1 went in the clear")
        assertTrue(edhocRequests.none { it.first.options.qBlock2 != null }, "with RFC 7959")
        val refused = beforeInstall.filter { it.first.options.path == "/.well-known/core" }
        assertTrue(refused.isNotEmpty() && refused.none { it.second }, "the probe was not sent before the install")
        val afterInstall = handed.drop(beforeInstall.size)
        assertTrue(afterInstall.all { it.second }, "everything after the install is protected")
        val probe = afterInstall.first().first
        assertEquals("/.well-known/core", probe.options.path, "the first request after the install probes")
        val gets = afterInstall.drop(1).map { it.first }
        assertTrue(gets.isNotEmpty(), "the GET went after the probe")
        assertTrue(gets.all { it.options.path == "/firmware" && it.options.qBlock2 != null }, "Q-Block2 inside OSCORE")
        client.close()
        server.close()
    }
}
