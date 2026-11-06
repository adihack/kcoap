package dev.kcoap.coap.interop

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.transport.Connector
import dev.kcoap.transport.ktor.CertificatePinner
import dev.kcoap.transport.ktor.KtorTcpConnector
import dev.kcoap.transport.ktor.KtorTlsConnector
import dev.kcoap.transport.ktor.TlsClientConfig
import dev.kcoap.transport.ktor.TlsTrust
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The kcoap client against libcoap's `coap-server` over TLS (RFC 8323 §9): `coaps+tcp` through
 * `KtorTlsConnector` and `coaps+ws` through the WebSocket connector on a CIO client the test passes, both on
 * ktor-network-tls. The connector's own default client serves no `coaps+ws` on the JVM, since CIO's name check
 * accepts a certificate without DNS names for any host (`CioWebSocketNameCheckTest` in kcoap-transport-ktor).
 *
 * `coap-server -p <port> -c server.pem -j server.key -w ,<wss port> -v 4` serves `coaps+tcp` on the port after
 * its own, with OpenSSL 3. Its certificate names only `localhost` and comes from a certificate authority that
 * openssl makes in the container when it starts, so nothing outside the test trusts it: the client trusts it
 * through `TlsTrust.Anchors`, and the platform trust store refuses it. libcoap accepts clients without ALPN on
 * any port, so the connector is allowed past RFC 8323 §8.2's rule for ports other than 5684.
 *
 * Skipped when docker or the image is missing.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KcoapClientAgainstLibcoapTlsTest {
    private lateinit var server: Libcoap.Server
    private lateinit var authority: X509Certificate
    private lateinit var certificate: X509Certificate
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val secureTcpUri get() = "coaps+tcp://localhost:${server.tlsPort}"
    private val secureWebSocketUri get() = "coaps+ws://localhost:${server.webSocketPort}"

    @BeforeAll
    fun startServer() {
        assumeTrue(Libcoap.isAvailable, "docker or ${Libcoap.IMAGE} not available")
        server = Libcoap.startTlsServer("DNS:localhost", "-v", "4", secureWebSockets = true)
        awaitReady(server)
        authority = parse(server.read("/certs/ca.pem"))
        certificate = parse(server.read("/certs/server.pem"))
    }

    /** Waits until [server] answers on its plain port, which it does once the certificates exist and it runs. */
    private fun awaitReady(server: Libcoap.Server) = runBlocking {
        val probe = CoapClient(listOf(KtorTcpConnector()), scope)
        val ready = (1..40).any {
            val up = runCatching { probe.ping(server.tcpUri) }.getOrDefault(false)
            if (!up) delay(250.milliseconds)
            up
        }
        probe.close()
        check(ready) { "coap-server did not answer over TCP: ${server.logs()}" }
    }

    @AfterAll
    fun stopServer() {
        if (::server.isInitialized) server.close()
        scope.cancel()
    }

    private fun parse(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    private fun trusted(pinner: CertificatePinner? = null, anchor: X509Certificate = authority) =
        KtorTlsConnector(TlsClientConfig(TlsTrust.Anchors(listOf(anchor)), pinner, allowsAnyPortWithoutAlpn = true))

    /** A CIO WebSocket client, as an application passes one, trusting [anchor] or else the platform store. */
    private fun cioClient(anchor: X509Certificate?) = HttpClient(CIO) {
        if (anchor != null) {
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
                init(KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("ca", anchor) })
                trustManagers.filterIsInstance<X509TrustManager>().first()
            }
            engine { https { trustManager = trust } }
        }
        install(WebSockets)
    }

    private fun withClient(vararg connectors: Connector, block: suspend (CoapClient) -> Unit) = runBlocking {
        val client = CoapClient(connectors.toList(), scope)
        try {
            withTimeout(60.seconds) { block(client) }
        } finally {
            client.close()
        }
    }

    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    /** A connection that failed to open: CONNECTION_CLOSED, with the TLS failure among its causes. */
    private fun assertRefusedByTls(error: CoapException, reason: String) {
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, error.reason, "$error")
        val handshake = error.causes().filterIsInstance<SSLHandshakeException>().firstOrNull()
        assertTrue(handshake != null && error.causes().any { reason in it.message.orEmpty() }, "${error.causes()}")
    }

    /** RFC 8323 §9.1, Certificate mode: a trusted test authority's certificate, and CoAP over the connection. */
    @Test
    fun rfc8323Section9_1GetsPutsAndPingsOverTlsWithATrustedTestAuthority() = withClient(trusted()) { client ->
        val root = client.get("$secureTcpUri/")
        assertEquals(CoapCode.CONTENT, root.code)
        assertTrue("libcoap" in root.payloadAsText(), root.payloadAsText())

        val random = Random(9)
        val body = ByteArray(3000) { ('a' + random.nextInt(26)).code.toByte() }
        assertTrue(client.put("$secureTcpUri/example_data", body, ContentFormat.TEXT_PLAIN).isSuccess)
        assertContentEquals(body, client.get("$secureTcpUri/example_data").payload)
        assertTrue(client.ping(secureTcpUri))
        assertTrue(client.discover(secureTcpUri).any { it.target == "/time" })
    }

    /** The pinning hook: the server's key pinned lets the request through, any other pin refuses it. */
    @Test
    fun thePinningHookAcceptsTheServersKeyAndRefusesAnyOther() {
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        withClient(trusted(CertificatePinner.publicKeySha256(listOf(pin)))) { client ->
            assertEquals(CoapCode.CONTENT, client.get("$secureTcpUri/").code)
        }
        withClient(trusted(CertificatePinner.publicKeySha256(listOf(ByteArray(32))))) { client ->
            assertRefusedByTls(assertFailsWith<CoapException> { client.get("$secureTcpUri/") }, "pinner refused the chain of localhost")
        }
    }

    /** The platform trust store does not know the test authority, so the server is refused before any CoAP. */
    @Test
    fun aCertificateThePlatformDoesNotTrustIsRefused() = withClient(KtorTlsConnector(TlsClientConfig(allowsAnyPortWithoutAlpn = true))) { client ->
        assertRefusedByTls(assertFailsWith<CoapException> { client.get("$secureTcpUri/") }, "PKIX path building failed")
    }

    /** RFC 9525 §6.4: a certificate that names `localhost` does not name 127.0.0.1, though both reach the server. */
    @Test
    fun rfc9525Section6ACertificateForAnotherNameIsRefused() = withClient(trusted()) { client ->
        assertRefusedByTls(
            assertFailsWith<CoapException> { client.get("coaps+tcp://127.0.0.1:${server.tlsPort}/") },
            "does not name 127.0.0.1",
        )
    }

    /** RFC 8323 §9: without a transport that runs TLS, the client refuses the URI before it connects. */
    @Test
    fun rfc8323Section9APlainTcpConnectorDoesNotServeCoapsTcp() = withClient(KtorTcpConnector()) { client ->
        val error = assertFailsWith<CoapException> { client.get("$secureTcpUri/") }
        assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, error.reason)
        assertTrue("coaps+tcp needs TLS" in error.message.orEmpty(), error.message)
    }

    /**
     * RFC 8323 §8.4, §9.2: `coaps+ws` on the WebSocket client's own TLS, here that of a CIO client the test passes,
     * which trusts the test authority through its trust manager.
     */
    @Test
    fun rfc8323Section9_2GetsOverSecureWebSocketsOnTheEnginesTls() {
        val http = cioClient(authority)
        try {
            withClient(KtorWebSocketConnector(http)) { client ->
                val root = client.get("$secureWebSocketUri/")
                assertEquals(CoapCode.CONTENT, root.code)
                assertTrue("libcoap" in root.payloadAsText(), root.payloadAsText())
            }
        } finally {
            http.close()
        }
    }

    /**
     * The platform trust store of a CIO client refuses the test authority in the TLS handshake, and the
     * connector's default client serves no `coaps+ws` on the JVM, so its CoAP client refuses the URI before it
     * connects.
     */
    @Test
    fun aSecureWebSocketThePlatformDoesNotTrustIsRefused() {
        val http = cioClient(anchor = null)
        try {
            withClient(KtorWebSocketConnector(http)) { client ->
                val error = assertFailsWith<CoapException> { client.get("$secureWebSocketUri/") }
                assertEquals(CoapException.Reason.CONNECTION_CLOSED, error.reason, "${error.causes()}")
                assertTrue(error.causes().any { "PKIX path building failed" in it.message.orEmpty() }, "${error.causes()}")
            }
        } finally {
            http.close()
        }
        withClient(KtorWebSocketConnector()) { client ->
            val error = assertFailsWith<CoapException> { client.get("$secureWebSocketUri/") }
            assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, error.reason, "${error.causes()}")
            assertTrue("coaps+ws needs TLS" in error.message.orEmpty(), error.message)
        }
    }

    /**
     * RFC 9525 §6.3: a server whose certificate names only another host, coap.example.org, is refused for
     * `localhost` over both transports: by `KtorTlsConnector`'s own check, and by CIO's, which checks a host name
     * against DNS names when the certificate has some.
     */
    @Test
    fun rfc9525Section6_3ACertificateForAnotherHostNameIsRefusedOverBothTransports() {
        val other = Libcoap.startTlsServer("DNS:coap.example.org", "-v", "4", secureWebSockets = true)
        try {
            awaitReady(other)
            val otherAuthority = parse(other.read("/certs/ca.pem"))
            withClient(trusted(anchor = otherAuthority)) { client ->
                assertRefusedByTls(
                    assertFailsWith<CoapException> { client.get("coaps+tcp://localhost:${other.tlsPort}/") },
                    "does not name localhost",
                )
            }
            val http = cioClient(otherAuthority)
            try {
                withClient(KtorWebSocketConnector(http)) { client ->
                    val error = assertFailsWith<CoapException> { client.get("coaps+ws://localhost:${other.webSocketPort}/") }
                    assertEquals(CoapException.Reason.CONNECTION_CLOSED, error.reason, "${error.causes()}")
                    assertTrue(error.causes().any { "No server host: localhost" in it.message.orEmpty() }, "${error.causes()}")
                }
            } finally {
                http.close()
            }
        } finally {
            other.close()
        }
    }
}
