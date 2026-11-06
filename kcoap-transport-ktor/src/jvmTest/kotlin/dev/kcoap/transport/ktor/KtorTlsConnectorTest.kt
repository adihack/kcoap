package dev.kcoap.transport.ktor

import dev.kcoap.transport.ByteStreamConnection
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.tls.tls
import java.net.BindException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * KtorTlsConnector (ktor-network-tls) against a JSSE server: what TLS authenticates, what reaches the server,
 * and every way a server is refused before a byte of CoAP goes out.
 */
class KtorTlsConnectorTest {
    private fun real(block: suspend () -> Unit) = runBlocking(Dispatchers.IO) { withTimeout(20.seconds) { block() } }

    private val authority = TestCertificates.authority("kcoap test CA")

    /** Reaches the server on its own port, which is not 5684, so ALPN's absence is allowed. */
    private fun anchoredTo(vararg anchors: X509Certificate, pinner: CertificatePinner? = null) =
        KtorTlsConnector(TlsClientConfig(TlsTrust.Anchors(anchors.toList()), pinner, allowsAnyPortWithoutAlpn = true))

    private fun serverFor(
        issued: TestCertificates.Issued,
        vararg intermediates: X509Certificate,
        protocols: Array<String> = arrayOf("TLSv1.2", "TLSv1.3"),
        atPort: Int = 0,
    ) = TlsEchoServer(listOf(issued.certificate) + intermediates, issued.keys.private, protocols, atPort)

    private suspend fun ByteStreamConnection.readExactly(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(buffer, filled, count - filled)
            check(read > 0) { "end of stream after $filled bytes" }
            filled += read
        }
        return buffer
    }

    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    private fun Throwable.mentions(text: String): Boolean = causes().any { text in it.message.orEmpty() }

    /** RFC 8323 §9.1, Certificate mode: bytes travel, and the connection reports the validated path. */
    @Test
    fun rfc8323Section9_1BytesTravelOverTlsAndThePeerIsTheValidatedChain() = real {
        val leaf = TestCertificates.issue(authority, "server", ipAddresses = listOf("127.0.0.1"))
        serverFor(leaf).use { server ->
            val connector = anchoredTo(authority.certificate)
            try {
                val peer = Endpoint.Tcp("127.0.0.1", server.port, usesTls = true)
                val connection = assertIs<ByteStreamConnection>(connector.connect(peer))
                assertEquals(peer, connection.remote)
                assertEquals(PeerIdentity.Certificate(listOf(leaf.certificate.encoded, authority.certificate.encoded)), connection.peerIdentity)

                connection.write(ByteArray(5000) { it.toByte() })
                assertContentEquals(ByteArray(5000) { it.toByte() }, connection.readExactly(5000))
                connection.close()
                connection.close()
                assertEquals(listOf<String?>(null), server.serverNames, "RFC 6066 §3: no IP literal as server name")
                assertTrue(server.cipherSuites.single() in TLS_CIPHER_SUITES.map { it.name }, "RFC 9325 §4.2: ${server.cipherSuites}")
            } finally {
                connector.close()
            }
        }
    }

    /** RFC 6066 §3 and RFC 9525 §6.3: a host name goes out as SNI and is matched against the DNS names. */
    @Test
    fun aHostNameIsSentAsServerNameAndMatchedAgainstTheDnsNames() = real {
        val leaf = TestCertificates.issue(authority, "server", dnsNames = listOf("localhost"))
        serverFor(leaf).use { server ->
            val selector = SelectorManager(Dispatchers.IO)
            val connector = KtorTlsConnector(
                TlsClientConfig(TlsTrust.Anchors(listOf(authority.certificate)), allowsAnyPortWithoutAlpn = true),
                selector,
            )
            try {
                connector.connect(Endpoint.Tcp("LocalHost", server.port, usesTls = true)).close()
                assertEquals(listOf<String?>("localhost"), server.serverNames)
            } finally {
                connector.close()
                selector.close()
            }
        }
    }

    /** The server sends its intermediate; the path runs through it to the trusted root. */
    @Test
    fun theChainRunsThroughTheIntermediateTheServerSends() = real {
        val intermediate = TestCertificates.issue(authority, "kcoap test intermediate", isAuthority = true)
        val leaf = TestCertificates.issue(intermediate, "server", ipAddresses = listOf("127.0.0.1"))
        serverFor(leaf, intermediate.certificate).use { server ->
            val chains = mutableListOf<List<X509Certificate>>()
            val connector = anchoredTo(authority.certificate, pinner = { host, chain ->
                assertEquals("127.0.0.1", host)
                chains += chain
                true
            })
            try {
                val connection = connector.connect(Endpoint.Tcp("127.0.0.1", server.port, usesTls = true))
                val expected = listOf(leaf.certificate, intermediate.certificate, authority.certificate)
                assertEquals(listOf(expected), chains)
                assertEquals(PeerIdentity.Certificate(expected.map { it.encoded }), connection.peerIdentity)
                connection.close()
            } finally {
                connector.close()
            }
        }
    }

    /** A trust anchor need not be a root: an intermediate, or a device's self-signed certificate, trusted as itself. */
    @Test
    fun anIntermediateOrASelfSignedCertificateCanBeTheTrustAnchor() = real {
        val intermediate = TestCertificates.issue(authority, "kcoap test intermediate", isAuthority = true)
        val leaf = TestCertificates.issue(intermediate, "server", ipAddresses = listOf("127.0.0.1"))
        serverFor(leaf, intermediate.certificate).use { server ->
            val connector = anchoredTo(intermediate.certificate)
            try {
                val connection = connector.connect(Endpoint.Tcp("127.0.0.1", server.port, usesTls = true))
                assertEquals(PeerIdentity.Certificate(listOf(leaf.certificate.encoded, intermediate.certificate.encoded)), connection.peerIdentity)
                connection.close()
            } finally {
                connector.close()
            }
        }
        val device = TestCertificates.selfSigned("device", ipAddresses = listOf("127.0.0.1"))
        serverFor(device).use { server ->
            val connector = anchoredTo(device.certificate)
            try {
                val connection = connector.connect(Endpoint.Tcp("127.0.0.1", server.port, usesTls = true))
                assertEquals(PeerIdentity.Certificate(listOf(device.certificate.encoded)), connection.peerIdentity)
                connection.close()
            } finally {
                connector.close()
            }
        }
    }

    /** A certificate no trusted authority issued is refused, by the platform store and by other anchors. */
    @Test
    fun aCertificateFromAnUntrustedAuthorityIsRefused() = real {
        val leaf = TestCertificates.issue(authority, "server", ipAddresses = listOf("127.0.0.1"))
        serverFor(leaf).use { server ->
            val peer = Endpoint.Tcp("127.0.0.1", server.port, usesTls = true)
            val platform = KtorTlsConnector(TlsClientConfig(allowsAnyPortWithoutAlpn = true))
            val other = anchoredTo(TestCertificates.authority("another CA").certificate)
            try {
                assertFailsWith<SSLException> { platform.connect(peer) }
                assertFailsWith<SSLException> { other.connect(peer) }
            } finally {
                platform.close()
                other.close()
            }
        }
    }

    /** RFC 9525 §6.4 and §2: an IP literal must be an iPAddress entry; neither a DNS name nor the Common Name will do. */
    @Test
    fun rfc9525Section6ACertificateThatDoesNotNameTheHostIsRefused() = real {
        val cases = listOf(
            TestCertificates.issue(authority, "server", dnsNames = listOf("coap.example.org")),
            TestCertificates.issue(authority, "127.0.0.1"),
            TestCertificates.issue(authority, "server", dnsNames = listOf("127.0.0.1")),
            TestCertificates.issue(authority, "server", ipAddresses = listOf("127.0.0.2")),
        )
        for (leaf in cases) {
            serverFor(leaf).use { server ->
                val connector = anchoredTo(authority.certificate)
                try {
                    val error = assertFailsWith<SSLException> { connector.connect(Endpoint.Tcp("127.0.0.1", server.port, usesTls = true)) }
                    assertTrue(error.mentions("does not name 127.0.0.1"), "$leaf: ${error.causes()}")
                } finally {
                    connector.close()
                }
            }
        }
    }

    /** RFC 7469 §2.4 pins: the hook sees the validated path, and a refusal or a failure ends the handshake. */
    @Test
    fun thePinnerCanAcceptOrRefuseTheValidatedChain() = real {
        val leaf = TestCertificates.issue(authority, "server", ipAddresses = listOf("127.0.0.1"))
        fun pin(certificate: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        serverFor(leaf).use { server ->
            val peer = Endpoint.Tcp("127.0.0.1", server.port, usesTls = true)
            suspend fun connectWith(pinner: CertificatePinner): Result<Unit> {
                val connector = anchoredTo(authority.certificate, pinner = pinner)
                return try {
                    runCatching { connector.connect(peer).close() }
                } finally {
                    connector.close()
                }
            }
            assertTrue(connectWith(CertificatePinner.publicKeySha256(listOf(pin(leaf.certificate)))).isSuccess)
            assertTrue(connectWith(CertificatePinner.publicKeySha256(listOf(ByteArray(32), pin(authority.certificate)))).isSuccess)

            val wrong = connectWith(CertificatePinner.publicKeySha256(listOf(ByteArray(32)))).exceptionOrNull()
            assertIs<SSLException>(wrong)
            assertTrue(wrong.mentions("pinner refused the chain of 127.0.0.1"), "${wrong.causes()}")

            val failing = connectWith { _, _ -> error("pin store unavailable") }.exceptionOrNull()
            assertIs<SSLException>(failing)
            assertTrue(failing.mentions("pinner failed for 127.0.0.1") && failing.mentions("pin store unavailable"), "${failing.causes()}")
        }
    }

    /** RFC 8323 §8.2: without ALPN only port 5684 implies coaps+tcp, so other ports are refused before connecting. */
    @Test
    fun rfc8323Section8_2APortOtherThan5684IsRefusedWithoutAlpnUnlessAllowed() = real {
        assertTrue(TlsClientConfig.Default.admitsWithoutAlpn(5684))
        assertFalse(TlsClientConfig.Default.admitsWithoutAlpn(5683))
        assertTrue(TlsClientConfig(allowsAnyPortWithoutAlpn = true).admitsWithoutAlpn(443))
        val leaf = TestCertificates.issue(authority, "server", ipAddresses = listOf("127.0.0.1"))
        val connector = KtorTlsConnector(TlsClientConfig(TlsTrust.Anchors(listOf(authority.certificate))))
        try {
            serverFor(leaf).use { server ->
                val error = assertFailsWith<IllegalArgumentException> { connector.connect(Endpoint.Tcp("127.0.0.1", server.port, usesTls = true)) }
                assertTrue("needs ALPN `coap` (RFC 8323 §8.2)" in error.message.orEmpty(), error.message)
                assertEquals(0, server.accepted, "nothing was sent")
            }
            // Port 5684 needs no opt-in. Only checked over the network when this test can listen there itself,
            // so that nothing else on the machine answers for it.
            val atDefaultPort = try {
                serverFor(leaf, atPort = CoapScheme.COAPS_TCP.defaultPort)
            } catch (e: BindException) {
                null
            }
            atDefaultPort?.use { server ->
                connector.connect(Endpoint.Tcp("127.0.0.1", CoapScheme.COAPS_TCP.defaultPort, usesTls = true)).close()
                assertEquals(1, server.accepted)
            }
        } finally {
            connector.close()
        }
    }

    /** ktor-network-tls speaks TLS 1.2 only, so a server that insists on TLS 1.3 refuses the handshake. */
    @Test
    fun upstreamKtorNetworkTlsSpeaksOnlyTls12() = real {
        val leaf = TestCertificates.issue(authority, "server", ipAddresses = listOf("127.0.0.1"))
        serverFor(leaf, protocols = arrayOf("TLSv1.3")).use { server ->
            val connector = anchoredTo(authority.certificate)
            try {
                val error = assertFailsWith<SSLException> { connector.connect(Endpoint.Tcp("127.0.0.1", server.port, usesTls = true)) }
                assertTrue(error.mentions("Received alert during handshake") && error.mentions("ProtocolVersion"), "${error.causes()}")
            } finally {
                connector.close()
            }
        }
    }

    @Test
    fun onlyCoapsTcpPeersAreReached() = real {
        val connector = KtorTlsConnector()
        try {
            assertEquals(setOf(CoapScheme.COAPS_TCP), connector.schemes)
            assertEquals(TlsClientConfig.Default, connector.config)
            assertFailsWith<IllegalArgumentException> { connector.connect(Endpoint.Tcp("127.0.0.1", 5684)) }
            assertFailsWith<IllegalArgumentException> { connector.connect(Endpoint.WebSocket.of("127.0.0.1", 443, usesTls = true)) }
            assertEquals("KtorTlsConnector", connector.toString())
        } finally {
            connector.close()
        }
    }

    @Test
    fun theConfigurationChecksWhatItIsGiven() {
        assertFailsWith<IllegalArgumentException> { TlsTrust.Anchors(emptyList()) }
        assertFailsWith<IllegalArgumentException> { CertificatePinner.publicKeySha256(emptyList()) }
        assertFailsWith<IllegalArgumentException> { CertificatePinner.publicKeySha256(listOf(ByteArray(20))) }
        val anchors = mutableListOf(authority.certificate)
        val trust = TlsTrust.Anchors(anchors)
        anchors.clear()
        assertEquals(listOf(authority.certificate), trust.certificates, "the list is copied")
        assertEquals("Anchors(1 certificates)", trust.toString())
        assertEquals("TlsClientConfig(trust=Platform, pinner=none, allowsAnyPortWithoutAlpn=false)", TlsClientConfig.Default.toString())
        assertEquals(
            "TlsClientConfig(trust=Anchors(1 certificates), pinner=set, allowsAnyPortWithoutAlpn=true)",
            TlsClientConfig(trust, { _, _ -> true }, allowsAnyPortWithoutAlpn = true).toString(),
        )
        assertNull(TlsClientConfig().pinner)
    }

    /**
     * RFC 9325 §4.2: the ClientHello offers forward secrecy and AEAD only. ktor's own default list, which a plain
     * ktor-network-tls client offers, adds RSA key transport and CBC suites, which the connector leaves out.
     */
    @Test
    fun rfc9325Section4_2OnlyEcdheSuitesWithAesGcmAreOffered() = real {
        val ecdheWithAesGcm = listOf(0xC02B, 0xC02F, 0xC02C, 0xC030)
        ClientHelloRecorder().use { recorder ->
            val connector = anchoredTo(authority.certificate)
            try {
                assertFails { connector.connect(Endpoint.Tcp("127.0.0.1", recorder.port, usesTls = true)) }
            } finally {
                connector.close()
            }
            assertEquals(ecdheWithAesGcm, assertNotNull(recorder.hello).cipherSuites)
        }
        assertEquals(
            listOf(
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            ),
            TLS_CIPHER_SUITES.map { it.name },
        )
        ClientHelloRecorder().use { recorder ->
            val selector = SelectorManager(Dispatchers.IO)
            try {
                val socket = aSocket(selector).tcp().connect("127.0.0.1", recorder.port)
                assertFails { socket.tls(Dispatchers.IO) }
                socket.close()
            } finally {
                selector.close()
            }
            val offered = assertNotNull(recorder.hello).cipherSuites
            // TLS_RSA_WITH_AES_128_GCM_SHA256, TLS_RSA_WITH_AES_256_CBC_SHA, TLS_RSA_WITH_AES_128_CBC_SHA
            assertTrue(offered.containsAll(listOf(0x009C, 0x0035, 0x002F)), "ktor's default suites: $offered")
        }
    }

    /**
     * What ktor-network-tls 3.6.1 leaves out of its ClientHello, recorded as deviations in docs/platforms.md: ALPN
     * (RFC 7301, which RFC 8323 §8.2 wants on ports other than 5684), the extended master secret (RFC 7627) and
     * the renegotiation indication (RFC 5746, as extension or signaling suite), both of which RFC 9325 §3.5
     * requires for TLS 1.2. When an update makes this test fail, ktor sends one of them, and the docs change.
     */
    @Test
    fun upstreamKtorNetworkTlsOffersNoAlpnExtendedMasterSecretOrRenegotiationInfo() = real {
        ClientHelloRecorder().use { recorder ->
            val connector = anchoredTo(authority.certificate)
            try {
                assertFails { connector.connect(Endpoint.Tcp("localhost", recorder.port, usesTls = true)) }
            } finally {
                connector.close()
            }
            val hello = assertNotNull(recorder.hello)
            assertTrue(SERVER_NAME in hello.extensions, "RFC 6066 §3: SNI for a host name, ${hello.extensions}")
            for (missing in listOf(ALPN, EXTENDED_MASTER_SECRET, RENEGOTIATION_INFO)) {
                assertFalse(missing in hello.extensions, "extension $missing in ${hello.extensions}")
            }
            assertFalse(EMPTY_RENEGOTIATION_INFO_SCSV in hello.cipherSuites, "${hello.cipherSuites}")
        }
    }

    private companion object {
        const val SERVER_NAME = 0
        const val ALPN = 16
        const val EXTENDED_MASTER_SECRET = 23
        const val RENEGOTIATION_INFO = 0xFF01
        const val EMPTY_RENEGOTIATION_INFO_SCSV = 0x00FF
    }
}
