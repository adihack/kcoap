package dev.kcoap.transport.ktor

import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Connection
import dev.kcoap.transport.Connector
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.IpLiteral
import dev.kcoap.transport.PeerIdentity
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.tls.CIOCipherSuites
import io.ktor.network.tls.tls
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Opens TLS connections for `coaps+tcp` (RFC 8323 §8.2, §9.1) on ktor-network-tls, on the JVM and Android. The
 * server is authenticated by certificate (the Certificate mode of RFC 8323 §9.1): its chain must be valid under
 * [TlsClientConfig.trust], the platform trust store by default, and name the host of the URI (RFC 9525 §6), and
 * then pass [TlsClientConfig.pinner] if one is set; otherwise the handshake fails and nothing is sent. The
 * connection reports that chain as its [Connection.peerIdentity], a [PeerIdentity.Certificate].
 *
 * ```
 * val client = CoapClient(listOf(KtorTcpConnector(), KtorTlsConnector()), scope)
 * client.get("coaps+tcp://coap.example.org/sensors/temp")
 * ```
 *
 * What ktor-network-tls 3.6.1 offers, and so what this connector does, is narrower than RFC 8323 §9.1 allows:
 * - TLS 1.2 only, in ktor's own implementation of it, not the JDK's; no TLS 1.3, no session resumption.
 * - Only the ECDHE suites with AES-GCM (`TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256` and `..._256_GCM_SHA384`,
 *   the same with RSA), as RFC 9325 §4.2 recommends; ktor's RSA key transport and CBC suites are left out. RFC
 *   7925's `TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8` is not among ktor's suites.
 * - No ALPN: RFC 8323 §8.2 has the client offer `coap` on every port but 5684, so a port other than 5684 is
 *   refused unless [TlsClientConfig.allowsAnyPortWithoutAlpn] is set.
 * - Certificates only: no PreSharedKey or RawPublicKey mode, and no client certificate.
 * - On the JVM only. ktor-network-tls has no TLS on Linux native or Apple platforms, where its handshake throws
 *   "TLS sessions are not supported on Native platform" (docs/platforms.md).
 *
 * Server Name Indication carries the host when it is a name, never an IP literal (RFC 6066 §3).
 *
 * @property config the trust, the pinning hook and the ALPN exception.
 * @param selector the ktor selector to use; when null, the connector creates one on `Dispatchers.IO` and closes
 *   it in [close].
 */
public class KtorTlsConnector(
    public val config: TlsClientConfig = TlsClientConfig.Default,
    selector: SelectorManager? = null,
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) : Connector {
    private val log = loggerFactory.logger(LogTags.TRANSPORT)
    private val owned = if (selector == null) SelectorManager(Dispatchers.IO) else null
    private val selector: SelectorManager = selector ?: owned!!

    /** Loaded on the first connection: the platform store is read from disk. */
    private val trust: X509TrustManager by lazy { config.trust.trustManager() }

    /** The platform's host-aware check of [trust], on Android; null on the JVM. */
    private val hostAware: HostAwareTrust? by lazy { HostAwareTrust.find(trust) }

    override val schemes: Set<CoapScheme> = setOf(CoapScheme.COAPS_TCP)

    /**
     * Connects to [peer], runs the TLS handshake and returns the secure connection.
     *
     * @throws IllegalArgumentException for anything but a `coaps+tcp` [Endpoint.Tcp], and for a port other than
     *   5684 unless [TlsClientConfig.allowsAnyPortWithoutAlpn] is set; nothing is sent then.
     * @throws SSLHandshakeException when the server's certificate is refused: not trusted, not naming the host, or
     *   refused by the pinner, which its cause says. Other handshake failures throw ktor's `TLSException`, also an
     *   `SSLException`, and a failure to connect the transport's own `IOException`.
     */
    override suspend fun connect(peer: Endpoint): Connection {
        require(peer is Endpoint.Tcp && peer.usesTls) { "A TLS connector reaches only coaps+tcp peers, not $peer" }
        require(peer.port == CoapScheme.COAPS_TCP.defaultPort || config.allowsAnyPortWithoutAlpn) {
            "coaps+tcp on port ${peer.port} needs ALPN `${CoapScheme.ALPN}` (RFC 8323 §8.2), which ktor-network-tls " +
                "cannot offer; only port ${CoapScheme.COAPS_TCP.defaultPort} implies coaps+tcp without it. Set " +
                "TlsClientConfig.allowsAnyPortWithoutAlpn for a server that accepts clients without ALPN"
        }
        val check = ServerCertificateCheck(trust, peer.host, config.pinner, hostAware)
        val socket = aSocket(selector).tcp().connect(peer.host, peer.port) { noDelay = true }
        val secure = try {
            // The TLS socket's own coroutines must not belong to the caller, whose job ends with this call.
            socket.tls(Dispatchers.IO) {
                trustManager = check
                serverName = peer.host.takeIf { IpLiteral.parse(it) == null }
                cipherSuites = TLS_CIPHER_SUITES
            }
        } catch (e: CertificateException) {
            // ktor passes the trust manager's verdict on as it is; callers get one exception type for a handshake.
            socket.close()
            log.debug { "Refused the certificate of $peer: ${e.message}" }
            throw SSLHandshakeException("The certificate of $peer was refused: ${e.message}").apply { initCause(e) }
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
        val chain = check.validatedChain ?: run {
            secure.close()
            throw SSLPeerUnverifiedException("The TLS handshake with $peer ended without a validated certificate")
        }
        log.debug { "TLS to $peer established, server ${chain[0].subjectX500Principal}" }
        return KtorTcpConnection(secure, peer, PeerIdentity.Certificate(chain.map { it.encoded }))
    }

    override suspend fun close() {
        owned?.close()
    }

    override fun toString(): String = "KtorTlsConnector"
}

/**
 * The suites a [KtorTlsConnector] offers: RFC 9325 §4.2's recommended TLS 1.2 suites among ktor's (ECDHE with
 * AES-GCM), AES-128 first, those ktor finds the JDK can run.
 */
internal val TLS_CIPHER_SUITES = listOf(
    CIOCipherSuites.ECDHE_ECDSA_AES128_SHA256,
    CIOCipherSuites.ECDHE_RSA_AES128_SHA256,
    CIOCipherSuites.ECDHE_ECDSA_AES256_SHA384,
    CIOCipherSuites.ECDHE_RSA_AES256_SHA384,
).filter { it in CIOCipherSuites.SupportedSuites }
