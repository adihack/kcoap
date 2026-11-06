package dev.kcoap.edhoc.oscore.interop

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.AuthenticationCredential
import dev.kcoap.edhoc.CredentialResolver
import dev.kcoap.edhoc.EdhocInitiatorConfig
import dev.kcoap.edhoc.EdhocPeerErrorException
import dev.kcoap.edhoc.TrustedCredentials
import dev.kcoap.edhoc.oscore.EdhocClientHandshake
import dev.kcoap.edhoc.oscore.EdhocIdentity
import dev.kcoap.edhoc.oscore.Peers
import dev.kcoap.edhoc.oscore.PeerSecurity
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.FreshnessPolicy
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.OscoreIdentity
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import java.io.File
import java.net.DatagramSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo

/**
 * The browser topology end to end (docs/target-architecture.md, WP-I1): a kcoap client that has only WebSockets,
 * as in a browser, reaches a kcoap device that has only UDP through libcoap v4.3.5b's `coap-server` as a CoAP
 * forward-proxy (RFC 7252 §5.7.2) that takes `coap+ws` in (RFC 8323 §4) and sends `coap` out ([LibcoapProxy]).
 * OSCORE and EDHOC run end to end between the two kcoap sides; the proxy sees only the outer messages.
 *
 * Each request names the device by Proxy-Scheme and Uri-Host ([ProxyRoute]), so under OSCORE the proxy reads its
 * target from Class U options and the path stays encrypted (RFC 8613 §4.1.3.3). The device authorizes on who the
 * client is ([UdpDevice]): the [OscoreIdentity] of a provisioned context, or the [EdhocIdentity] EDHOC
 * established, never on the peer address, which is the proxy's for every client.
 *
 * libcoap 4.3.5b's direct proxy opens `coap://localhost:5683` whatever a request names, so the device listens on
 * 127.0.0.1:5683 (docs/adr/0009-browser-topology-interop.md). Its deviations that kcoap sees are pinned by the
 * `upstream…` tests, which fail, and are to be flipped, once libcoap is fixed. One is not: it reads the later blocks
 * of a request body it forwards from memory it has freed, which no test can expect a stable outcome of, so request
 * bodies here stay within one message on each side of the proxy, as OSCORE's inner blocks do. Skipped when docker or
 * the image is missing.
 */
class BrowserTopologyInteropTest {
    private val crypto = DefaultCryptoBackend()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanUps = mutableListOf<suspend () -> Unit>()
    private var testName = "test"

    /** The device as the browser names it: `coap://127.0.0.1`, the default port. */
    private val device = CoapUri(CoapScheme.COAP, "127.0.0.1", UdpDevice.PORT)

    @BeforeEach
    fun recordName(info: TestInfo) {
        testName = info.testMethod.map { it.name }.orElse("test")
    }

    @AfterTest
    fun cleanUp() = runBlocking {
        cleanUps.asReversed().forEach { runCatching { it() } }
        scope.cancel()
    }

    @Test
    fun oscoreGetThroughTheProxyIsAuthorizedByTheClientsOscoreIdentity() = runBlocking {
        assumeProxy()
        val peers = Peers.create(crypto)
        // The browser's context and another client's, both provisioned on the device; only the browser's identity
        // is admitted to /lights. The browser's context is restored, as after a restart of the device, so its first
        // request meets the Appendix B.1.2 freshness challenge, through the proxy.
        val browserIdentity = identity(BROWSER_ID)
        val start = startDevice(
            peers,
            provisioned = listOf(
                deviceContext(sender = DEVICE_ID, recipient = BROWSER_ID, FreshnessPolicy.REQUIRE_PROOF),
                deviceContext(sender = DEVICE_ID_FOR_OTHER, recipient = OTHER_ID),
            ),
            admitted = setOf(browserIdentity),
        )
        val proxy = startProxy()
        val route = ProxyRoute(proxy.webSocketUri)
        val recording = RecordingClientSecurity(OscoreClient(clientContext(sender = BROWSER_ID, recipient = DEVICE_ID)))
        // Q-Block on (PROBE), as an application might set it: over WebSockets and under OSCORE the client still keeps
        // it off by default, so protected bodies go in inner RFC 7959 blocks, which an OSCORE-unaware proxy passes on.
        val qBlock = QBlockClientConfig(QBlockMode.PROBE)
        val browser = webSocketClient(CoapClientConfig(security = recording, qBlock = qBlock))

        withTimeout(60.seconds) {
            awaitProxy(browser, proxy)
            val lights = browser.request(route.viaProxyScheme(get("lights")))
            assertEquals(CoapCode.CONTENT, lights.code, lights.payloadAsText())
            assertEquals("on", lights.payloadAsText())

            // Appendix B.1.2 through the proxy: a protected 4.01 with Echo, and the request repeated with that Echo.
            val (challenged, repeated) = recording.exchanges.take(2)
            val challenge = assertIs<ResponseVerification.Verified>(challenged.response).message
            assertEquals(CoapCode.UNAUTHORIZED, challenge.code)
            val echo = assertNotNull(challenge.options.echo, "the challenge carries Echo")
            assertNull(challenged.request.options.echo)
            assertContentEquals(echo, repeated.request.options.echo, "the repeat carries the challenge's Echo")

            // The device admitted the browser by its OSCORE identity; the request came from the proxy, over UDP.
            val visit = start.lights.single()
            assertEquals(browserIdentity, visit.identity)
            val proxyPeer = assertIs<Endpoint.Udp>(visit.peer, "the device sees the proxy, not the browser")
            assertEquals("127.0.0.1", proxyPeer.host)
            assertNotEquals(proxy.webSocketPort, proxyPeer.port)

            // What the proxy saw: the target in Class U options and no path, over WebSockets in and UDP out; the
            // outer code of both requests is POST (RFC 8613 §4.2), and of both responses 2.04.
            val messages = proxy.awaitMessages { all -> all.count { it.isWebSocket && it.code == "2.04" } == 2 }
            val received = messages.filter { it.isWebSocket && it.code == "POST" }
            assertEquals(2, received.size, "the request and its repeat reached the proxy as outer POSTs: $messages")
            assertTrue(received.all { it.has("Proxy-Scheme") && it.has("Uri-Host") && it.has("Oscore") }, "$received")
            assertTrue(received.none { it.has("Uri-Path") }, "the path stays encrypted: $received")
            val forwarded = messages.filter { !it.isWebSocket && it.code == "POST" }
            assertEquals(2, forwarded.size, "both went on over UDP: $messages")
            assertTrue(
                forwarded.all { it.has("Oscore") && !it.has("Proxy-Scheme") && !it.has("Uri-Path") },
                "$forwarded",
            )

            // Q-Block stays off: the 3000-byte body comes in inner Block2 blocks, one protected exchange each, and goes
            // back in inner Block1 blocks. Every outer message fits one message on both sides, so the proxy never
            // reassembles a body (docs/adr/0009: libcoap 4.3.5b's proxy corrupts request bodies of several blocks).
            val firmware = browser.request(route.viaProxyScheme(get("firmware")))
            assertContentEquals(UdpDevice.firmware, firmware.payload)
            val upload = route.viaProxyScheme(
                CoapRequest.Builder(CoapCode.PUT, get("firmware").uri).apply { payload = UdpDevice.firmware }.build(),
            )
            assertEquals(CoapCode.CHANGED, browser.request(upload).code)
            assertContentEquals(UdpDevice.firmware, start.uploads.single())
            val firmwareRequests = recording.exchanges.map { it.request }.filter { it.options.path == "/firmware" }
            val (downloads, uploads) = firmwareRequests.partition { it.code == CoapCode.GET }
            assertTrue(downloads.size > 1, "Block2 inside: $downloads")
            assertTrue(downloads.drop(1).all { it.options.block2 != null }, "Block2 inside: $downloads")
            assertTrue(uploads.size > 1 && uploads.all { it.options.block1 != null }, "Block1 inside: $uploads")
            assertTrue(firmwareRequests.none { it.options.qBlock1 != null || it.options.qBlock2 != null })
            assertTrue(recording.exchanges.none { it.request.options.path == "/.well-known/core" }, "no Q-Block probe")
            val blockOptions = listOf("Block1", "Block2", "Q-Block1", "Q-Block2")
            assertTrue(proxy.messages().none { message -> blockOptions.any { message.has(it) } }, "no outer blocks")

            // Another client that OSCORE authenticates, but whose identity is not admitted: a protected 4.03.
            val otherContext = clientContext(sender = OTHER_ID, recipient = DEVICE_ID_FOR_OTHER)
            val other = webSocketClient(CoapClientConfig(security = OscoreClient(otherContext)))
            assertEquals(CoapCode.FORBIDDEN, other.request(route.viaProxyScheme(get("lights"))).code)
            // A client without OSCORE: the unprotected 4.01 of a server with message security (ADR 0008).
            val plain = webSocketClient(CoapClientConfig())
            assertEquals(CoapCode.UNAUTHORIZED, plain.request(route.viaProxyScheme(get("lights"))).code)
            assertEquals(1, start.lights.size, "the handler ran for the admitted client only")
        }
    }

    @Test
    fun edhocHandshakeThroughTheProxyThenOscoreWithTheDerivedContext() = runBlocking {
        assumeProxy()
        val peers = Peers.create(crypto)
        val start = startDevice(peers, admittedCredentials = setOf(peers.client.credential))
        val proxy = startProxy()
        val route = ProxyRoute(proxy.webSocketUri)
        val security = PeerSecurity()
        val browser = webSocketClient(CoapClientConfig(security = security))

        withTimeout(60.seconds) {
            awaitProxy(browser, proxy)
            // The handshake states its requests for the device; the route sends them to the proxy.
            val handshake = handshakeThrough(route, browser, peers.initiatorConfig())
            val session = handshake.run()
            // Through a proxy the client's peer is the proxy: the protection is installed for its endpoint.
            val proxyEndpoint = security.install(browser, proxy.webSocketUri, OscoreClient(session.oscore))
            assertEquals(Endpoint.WebSocket.of("127.0.0.1", proxy.webSocketPort), proxyEndpoint)
            val lights = browser.request(route.viaProxyScheme(get("lights")))
            assertEquals("on", lights.payloadAsText())

            val identity = assertIs<EdhocIdentity>(start.lights.single().identity)
            assertEquals(peers.client.credential, identity.peerCredential)
            assertEquals(OscoreIdentity(session.edhoc.peerConnectionIdentifier.bytes, null), identity.oscore)
            assertEquals(peers.client.credential, start.established.single().peerCredential)

            // message_1 and message_3 went through the proxy in the clear, from the proxy's UDP side to the device.
            val edhocArrivals = start.security.arrivals.filter { it.arrived.options.path == "/.well-known/edhoc" }
            assertEquals(2, edhocArrivals.size, "message_1 and message_3")
            assertTrue(edhocArrivals.all { it.peer is Endpoint.Udp && it.verified == null })
            val messages = proxy.awaitMessages { all -> all.count { it.isWebSocket && it.code == "2.04" } == 3 }
            val received = messages.filter { it.isWebSocket && it.code == "POST" }
            assertEquals(3, received.size, "message_1, message_3 and the OSCORE request: $messages")
            val (message1, message3, protected) = received
            for (message in listOf(message1, message3)) {
                assertTrue(message.has("Proxy-Scheme"), "$message")
                assertTrue("Uri-Path:.well-known, Uri-Path:edhoc" in message.line, "$message")
                assertTrue("Content-Format:65" in message.line, "application/cid-edhoc+cbor-seq: $message")
            }
            assertTrue(protected.has("Oscore") && !protected.has("Uri-Path"), "$protected")
            val answers = messages.filter { it.isWebSocket && it.code == "2.04" }
            assertTrue("Content-Format:64" in answers.first().line, "message_2 as application/edhoc+cbor-seq: $answers")
        }
    }

    @Test
    fun combinedRequestOfRfc9668ThroughTheProxy() = runBlocking {
        assumeProxy()
        // Method 3 (static Diffie-Hellman) here, method 0 in the sequential test.
        val peers = Peers.staticDiffieHellman(crypto)
        val start = startDevice(peers, admittedCredentials = setOf(peers.client.credential))
        val proxy = startProxy()
        val route = ProxyRoute(proxy.webSocketUri)
        val security = PeerSecurity()
        val browser = webSocketClient(CoapClientConfig(security = security))

        withTimeout(60.seconds) {
            awaitProxy(browser, proxy)
            val handshake = handshakeThrough(route, browser, peers.initiatorConfig())
            val (session, protection) = handshake.runCombined()
            security.install(browser, proxy.webSocketUri, protection)
            val lights = browser.request(route.viaProxyScheme(get("lights")))
            assertEquals("on", lights.payloadAsText())
            assertFalse(protection.isMessage3Pending())

            // Only message_1 went to the EDHOC resource; message_3 rode on the first OSCORE request (RFC 9668 §3.2),
            // whose EDHOC option the proxy passed on, outside, like the OSCORE option.
            assertEquals(1, start.security.arrivals.count { it.arrived.options.path == "/.well-known/edhoc" })
            val combined = start.security.arrivals.single { it.arrived.options.edhoc }
            assertNotNull(combined.arrived.options.oscore)
            assertTrue(combined.arrived.options.uriPath.isEmpty(), "the path stays encrypted")
            assertIs<Endpoint.Udp>(combined.peer)
            assertNotNull(combined.verified, "the device completed EDHOC and verified the request")

            val identity = assertIs<EdhocIdentity>(start.lights.single().identity)
            assertEquals(peers.client.credential, identity.peerCredential)
            assertEquals(OscoreIdentity(session.edhoc.peerConnectionIdentifier.bytes, null), identity.oscore)
            assertEquals(peers.client.credential, start.established.single().peerCredential)
            // libcoap 4.3.5b does not know the EDHOC option and prints it by its number; it is Safe-to-Forward (RFC
            // 9668 §3.1), so the proxy took the request and passed the option on.
            val messages = proxy.awaitMessages { all -> all.count { it.isWebSocket && it.code == "2.04" } == 2 }
            val combinedIn = messages.single { it.isWebSocket && it.code == "POST" && it.has("Oscore") }
            assertTrue(combinedIn.has("21") && combinedIn.has("Proxy-Scheme"), "$combinedIn")
            val combinedOut = messages.single { !it.isWebSocket && it.code == "POST" && it.has("Oscore") }
            assertTrue(combinedOut.has("21") && !combinedOut.has("Proxy-Scheme"), "$combinedOut")
        }
    }

    @Test
    fun edhocErrorOfTheDeviceReachesTheBrowserThroughTheProxy() = runBlocking {
        assumeProxy()
        val peers = Peers.create(crypto)
        // A device that trusts no client credential: it refuses message_3 with an EDHOC error message in a 4.00.
        val start = startDevice(peers, responderTrusts = TrustedCredentials(crypto, emptyList()))
        val proxy = startProxy()
        val route = ProxyRoute(proxy.webSocketUri)
        val browser = webSocketClient(CoapClientConfig(security = PeerSecurity()))

        withTimeout(60.seconds) {
            awaitProxy(browser, proxy)
            val handshake = handshakeThrough(route, browser, peers.initiatorConfig())
            assertFailsWith<EdhocPeerErrorException> { handshake.run() }
            assertTrue(start.established.isEmpty())
            // The 4.00 with the EDHOC error message (RFC 9528 Appendix A.2.3), passed on with its Content-Format.
            val messages = proxy.awaitMessages { all -> all.any { it.isWebSocket && it.code == "4.00" } }
            val error = messages.single { it.isWebSocket && it.code == "4.00" }
            assertTrue("Content-Format:64" in error.line, "$error")
        }
    }

    /**
     * libcoap 4.3.5b's direct proxy (`-P ,<names>`) opens `coap://localhost:5683` for every request, whatever it
     * names: `coap_proxy_get_session` reads the target from the request only for a proxy with a next hop
     * (`COAP_PROXY_FORWARD`), and `coap-server` leaves the direct proxy's target zeroed. Building the forwarded
     * request, it puts in the zeroed target's Uri-Port, empty (0); a request that names a port of its own then has
     * two, and libcoap drops the second ("Option number 7 is not defined as repeatable") together with every option
     * after it, here Uri-Path. Pinned: this fails once libcoap sends the request where it names.
     */
    @Test
    fun upstreamLibcoapDirectProxyForwardsToPort5683WhateverTheRequestNames() = runBlocking {
        assumeProxy()
        val peers = Peers.create(crypto)
        val start = startDevice(peers)
        val namedPort = DatagramSocket(0).use { it.localPort }
        val named = startDevice(peers, port = namedPort, greeting = "named")
        val proxy = startProxy()
        val route = ProxyRoute(proxy.webSocketUri)
        val browser = webSocketClient(CoapClientConfig())

        withTimeout(60.seconds) {
            awaitProxy(browser, proxy)
            val target = CoapUri(CoapScheme.COAP, "127.0.0.1", namedPort, listOf("hello"))
            val request = route.viaProxyScheme(CoapRequest.Builder(CoapCode.GET, target).build())
            assertEquals(namedPort, request.options.uriPort, "the request names its port")
            // The device on 5683 got it, without its path: GET / of a server with message security, so 4.01.
            assertEquals(CoapCode.UNAUTHORIZED, browser.request(request).code)
            assertTrue(named.security.arrivals.isEmpty(), "nothing reached port $namedPort")
            val arrived = start.security.arrivals.single().arrived
            assertEquals(0, arrived.options.uriPort, "the zeroed target's Uri-Port")
            assertEquals(emptyList(), arrived.options.uriPath, "Uri-Path dropped after the repeated Uri-Port")
            assertTrue(start.helloRequests.isEmpty())
            assertTrue("Option number 7 is not defined as repeatable" in proxy.logs())
        }
    }

    /**
     * libcoap 4.3.5b's proxy gives a forwarded response that has a payload but no Content-Format a Content-Format of
     * text/plain (`coap_proxy_forward_response` defaults it to 0). A protected response (RFC 8613 §4.2) has none
     * outside, so it reaches the browser with an outer Content-Format, a Class E option that OSCORE drops when it
     * verifies the response (§8.4), and the response verifies. Pinned: this fails once libcoap passes the response on
     * as it came.
     */
    @Test
    fun upstreamLibcoapProxyAddsContentFormatToAProtectedResponse() = runBlocking {
        assumeProxy()
        val peers = Peers.create(crypto)
        startDevice(
            peers,
            provisioned = listOf(deviceContext(sender = DEVICE_ID, recipient = BROWSER_ID)),
            admitted = setOf(identity(BROWSER_ID)),
        )
        val proxy = startProxy()
        val route = ProxyRoute(proxy.webSocketUri)
        val recording = RecordingClientSecurity(OscoreClient(clientContext(sender = BROWSER_ID, recipient = DEVICE_ID)))
        val browser = webSocketClient(CoapClientConfig(security = recording))

        withTimeout(60.seconds) {
            awaitProxy(browser, proxy)
            assertContentEquals(UdpDevice.firmware, browser.request(route.viaProxyScheme(get("firmware"))).payload)
            val blocks = recording.exchanges.size
            val messages = proxy.awaitMessages { all -> all.count { it.isWebSocket && it.code == "2.04" } == blocks }
            val fromDevice = messages.filter { !it.isWebSocket && it.code == "2.04" }
            assertEquals(blocks, fromDevice.size, "$messages")
            assertTrue(fromDevice.none { it.has("Content-Format") }, "the device sent none outside: $fromDevice")
            for (exchange in recording.exchanges) {
                assertEquals(ContentFormat.TEXT_PLAIN, assertNotNull(exchange.arrived).options.contentFormat, "added")
                val verified = assertIs<ResponseVerification.Verified>(exchange.response).message
                assertEquals(ContentFormat.OCTET_STREAM, verified.options.contentFormat, "the device's own, inside")
            }
        }
    }

    /**
     * RFC 8613 §4.1.3.3 has an OSCORE client turn a Proxy-Uri into an outer Proxy-Uri with the scheme and authority,
     * as kcoap-oscore does. libcoap 4.3.5b's proxy leaves an OSCORE request to be forwarded only when it carries
     * Proxy-Scheme and a Uri-Host that is not one of its names (`coap_dispatch`); with Proxy-Uri it tries to decrypt
     * the request itself, has no OSCORE context ("OSCORE: Not enabled"), and drops it without an answer. Pinned: this
     * fails once libcoap forwards it. The same request with Proxy-Scheme gets through.
     */
    @Test
    fun upstreamLibcoapProxyDropsAnOscoreRequestThatNamesItsTargetInProxyUri() = runBlocking {
        assumeProxy()
        val peers = Peers.create(crypto)
        val start = startDevice(
            peers,
            provisioned = listOf(deviceContext(sender = DEVICE_ID, recipient = BROWSER_ID)),
            admitted = setOf(identity(BROWSER_ID)),
        )
        val proxy = startProxy()
        val route = ProxyRoute(proxy.webSocketUri)
        val recording = RecordingClientSecurity(OscoreClient(clientContext(sender = BROWSER_ID, recipient = DEVICE_ID)))
        val browser = webSocketClient(CoapClientConfig(security = recording, requestTimeout = 3.seconds))

        withTimeout(60.seconds) {
            awaitProxy(browser, proxy)
            val dropped = assertFailsWith<CoapException> { browser.request(route.viaProxyUri(get("lights"))) }
            assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, dropped.reason)
            val sent = recording.exchanges.single().sent
            assertEquals("coap://127.0.0.1", sent.options.proxyUri, "the outer Proxy-Uri keeps scheme and authority")
            assertTrue(sent.options.uriPath.isEmpty(), "the path went inside")
            assertTrue("OSCORE: Not enabled" in proxy.logs(), "the proxy tried to decrypt it")
            assertTrue(start.security.arrivals.isEmpty(), "nothing reached the device")

            assertEquals("on", browser.request(route.viaProxyScheme(get("lights"))).payloadAsText())
        }
    }

    // ------------------------------------------------------------------------------------------- fixtures

    /** A GET of [path] on the device, as the browser states it before [ProxyRoute] sends it to the proxy. */
    private fun get(path: String): CoapRequest {
        val segments = path.split('/').filter { it.isNotEmpty() }
        return CoapRequest.Builder(CoapCode.GET, CoapUri(device.scheme, device.host, device.port, segments)).build()
    }

    /** EDHOC with the device, whose requests [client] sends through the proxy that [route] names. */
    private fun handshakeThrough(route: ProxyRoute, client: CoapClient, config: EdhocInitiatorConfig) =
        EdhocClientHandshake(config, crypto, device) { client.request(route.viaProxyScheme(it)) }

    private suspend fun startDevice(
        peers: Peers,
        provisioned: List<SecurityContext> = emptyList(),
        admitted: Set<OscoreIdentity> = emptySet(),
        admittedCredentials: Set<AuthenticationCredential> = emptySet(),
        responderTrusts: CredentialResolver = peers.trustingClient(),
        port: Int = UdpDevice.PORT,
        greeting: String = "device",
    ): UdpDevice {
        val responder = peers.responderConfig(credentialResolver = responderTrusts)
        return UdpDevice.start(scope, crypto, responder, provisioned, admitted, admittedCredentials, port, greeting)
            .also { cleanUps += { it.close() } }
    }

    /** A proxy whose log is kept in `build/interop-logs/<test>-proxy.log` when its container goes. */
    private fun startProxy(): LibcoapProxy = LibcoapProxy.start().also { proxy ->
        cleanUps += {
            File("build/interop-logs").apply { mkdirs() }.resolve("$testName-proxy.log").writeText(proxy.logs())
            proxy.close()
        }
    }

    private fun webSocketClient(config: CoapClientConfig): CoapClient =
        CoapClient(listOf(KtorWebSocketConnector()), scope, config).also { cleanUps += { it.close() } }

    /** The proxy's WebSocket listener accepts a CoAP ping (RFC 8323 §5.4) once it is up. */
    private suspend fun awaitProxy(client: CoapClient, proxy: LibcoapProxy) {
        repeat(50) {
            val pong = withTimeoutOrNull(2.seconds) { runCatching { client.ping(proxy.webSocketUri) }.getOrNull() }
            if (pong == true) return
            delay(200.milliseconds)
        }
        error("The libcoap proxy did not come up")
    }

    private suspend fun clientContext(sender: Byte, recipient: Byte): SecurityContext =
        SecurityContext.derive(parameters(sender, recipient), crypto, EphemeralSequenceNumberStore())

    private suspend fun deviceContext(
        sender: Byte,
        recipient: Byte,
        freshness: FreshnessPolicy = FreshnessPolicy.AUTO,
    ): SecurityContext {
        val store = EphemeralSequenceNumberStore()
        return SecurityContext.derive(parameters(sender, recipient), crypto, store, freshness = freshness)
    }

    /** One Master Secret per pair of IDs, so the other client's keys are not the browser's. */
    private fun parameters(sender: Byte, recipient: Byte): SecurityContextParameters {
        val pair = minOf(sender, recipient)
        return SecurityContextParameters(
            masterSecret = ByteArray(16) { (it + pair).toByte() },
            senderId = byteArrayOf(sender),
            recipientId = byteArrayOf(recipient),
            masterSalt = byteArrayOf(0x9e.toByte(), 0x7c, 0xa9.toByte(), 0x22, 0x23, 0x78, 0x63, 0x40),
        )
    }

    private fun assumeProxy() {
        assumeTrue(LibcoapProxy.isAvailable, "Docker with ${LibcoapProxy.IMAGE} is needed for this interop test")
    }

    private companion object {
        const val BROWSER_ID: Byte = 0x01
        const val DEVICE_ID: Byte = 0x02
        const val OTHER_ID: Byte = 0x03
        const val DEVICE_ID_FOR_OTHER: Byte = 0x04

        fun identity(id: Byte): OscoreIdentity = OscoreIdentity(byteArrayOf(id), null)
    }
}
