package dev.kcoap.transport.ktor

import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The name check of ktor's CIO client, whose TLS is ktor-network-tls, against a JSSE `wss` server whose certificate
 * comes from an authority the client trusts. ktor checks a host name only against the certificate's dNSName
 * entries and accepts a certificate that has none, and CIO checks an IP literal by the name its reverse DNS lookup
 * gives. That is why `KtorWebSocketConnector()` does not serve `coaps+ws` on the JVM and Android, and why
 * [KtorTlsConnector] checks names itself (RFC 9525 §6), which the same certificates fail.
 *
 * When an update makes an `upstream…` test fail, ktor checks names fully: the default connector may then serve
 * `coaps+ws` on the JVM (`defaultClientAuthenticatesServers`), and docs/platforms.md changes.
 */
class CioWebSocketNameCheckTest {
    private fun real(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(20.seconds) { block() } }

    private val authority = TestCertificates.authority("kcoap test CA")
    private val trust = TlsTrust.Anchors(listOf(authority.certificate)).trustManager()

    /** The request lines the WebSocket server received, each after a completed TLS handshake. */
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Answers a WebSocket opening handshake (RFC 6455 §4.2.2) with the subprotocol `coap`, then waits for the end. */
    private fun acceptWebSocket(input: InputStream, output: OutputStream) {
        val reader = input.bufferedReader(Charsets.ISO_8859_1)
        val head = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        requests += head.first()
        val key = head.first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
        )
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: coap\r\n\r\n").toByteArray(),
        )
        output.flush()
        while (reader.read() >= 0) Unit
    }

    private fun webSocketServerFor(issued: TestCertificates.Issued) =
        TlsEchoServer(listOf(issued.certificate), issued.keys.private, conversation = ::acceptWebSocket)

    /** A connector on a CIO client that trusts the test authority, as an application would pass it. */
    private suspend fun withCioConnector(block: suspend (KtorWebSocketConnector) -> Unit) {
        val http = HttpClient(CIO) {
            engine { https { trustManager = trust } }
            install(WebSockets)
        }
        val connector = KtorWebSocketConnector(http)
        try {
            block(connector)
        } finally {
            connector.close()
            http.close()
        }
    }

    /** The certificate names no host at all, only 127.0.0.1, and still opens `wss://localhost`. */
    @Test
    fun upstreamKtorCioAcceptsACertificateWithoutDnsNamesForAnyHost() = real {
        val leaf = TestCertificates.issue(authority, "server", ipAddresses = listOf("127.0.0.1"))
        assertFalse(ServerIdentity.isNamedBy(leaf.certificate, "localhost"), "RFC 9525 §6: it does not name localhost")
        webSocketServerFor(leaf).use { server ->
            withCioConnector { connector ->
                connector.connect(Endpoint.WebSocket.of("localhost", server.port, usesTls = true)).close()
            }
            assertEquals(listOf("GET /.well-known/coap HTTP/1.1"), requests, "the request went out over the handshake")
            assertEquals(listOf<String?>("localhost"), server.serverNames)

            val tls = KtorTlsConnector(TlsClientConfig(TlsTrust.Anchors(listOf(authority.certificate)), allowsAnyPortWithoutAlpn = true))
            try {
                val error = assertFailsWith<SSLException> { tls.connect(Endpoint.Tcp("localhost", server.port, usesTls = true)) }
                assertTrue(error.causes().any { "does not name localhost" in it.message.orEmpty() }, "${error.causes()}")
            } finally {
                tls.close()
            }
        }
    }

    /**
     * CIO never checks an IP literal against the certificate's IP addresses: it hands ktor-network-tls the name a
     * reverse DNS lookup gives for it (here, as a rule, `localhost`, also sent as SNI), and ktor-network-tls itself
     * accepts a certificate without IP addresses for an IP literal. A certificate that names a host, but not
     * 127.0.0.1, opens `wss://127.0.0.1` either way.
     */
    @Test
    fun upstreamKtorCioAcceptsACertificateWithoutTheIpAddressOfAnIpLiteral() = real {
        val reverse = InetAddress.getByName("127.0.0.1").hostName
        val leaf = TestCertificates.issue(authority, "server", dnsNames = listOf(if (reverse == "127.0.0.1") "coap.example.org" else reverse))
        assertFalse(ServerIdentity.isNamedBy(leaf.certificate, "127.0.0.1"), "RFC 9525 §6.4: it does not name 127.0.0.1")
        webSocketServerFor(leaf).use { server ->
            withCioConnector { connector ->
                connector.connect(Endpoint.WebSocket.of("127.0.0.1", server.port, usesTls = true)).close()
            }
            assertEquals(listOf("GET /.well-known/coap HTTP/1.1"), requests)
        }
    }

    /** What ktor does check: a host name against dNSName entries, when the certificate has some. */
    @Test
    fun ktorCioRefusesACertificateWhoseDnsNamesNameAnotherHost() = real {
        val leaf = TestCertificates.issue(authority, "server", dnsNames = listOf("coap.example.org"))
        webSocketServerFor(leaf).use { server ->
            withCioConnector { connector ->
                val error = assertFailsWith<SSLException> {
                    connector.connect(Endpoint.WebSocket.of("localhost", server.port, usesTls = true))
                }
                assertTrue(error.causes().any { "No server host: localhost" in it.message.orEmpty() }, "${error.causes()}")
            }
            assertEquals(emptyList(), requests, "no request went out")
        }
    }

    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()
}
