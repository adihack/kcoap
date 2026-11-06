package dev.kcoap.transport.ktor

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.Volatile

/**
 * The trust manager one [KtorTlsConnector] handshake runs, for the server at [host]: the chain is validated by
 * [trust] (the platform's PKIX trust manager), must name [host] (RFC 9525 §6, [ServerIdentity]) and pass
 * [pinner]; any failure throws [CertificateException], which ends the handshake. What passed is kept in
 * [validatedChain], end-entity certificate first and trust anchor last.
 */
internal class ServerCertificateCheck(
    private val trust: X509TrustManager,
    private val host: String,
    private val pinner: CertificatePinner?,
) : X509TrustManager {
    /** Set once a chain passed every check; read after the handshake. */
    @Volatile
    var validatedChain: List<X509Certificate>? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("The server sent no certificate")
        trust.checkServerTrusted(chain, authType)
        if (!ServerIdentity.isNamedBy(chain[0], host)) {
            throw CertificateException("The server certificate does not name $host (RFC 9525 §6)")
        }
        val path = pathOf(chain)
        val pinned = try {
            pinner?.accepts(host, path) ?: true
        } catch (e: Exception) {
            throw CertificateException("The certificate pinner failed for $host", e)
        }
        if (!pinned) throw CertificateException("The certificate pinner refused the chain of $host")
        validatedChain = path
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("A TLS client does not authenticate clients")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = trust.acceptedIssuers

    /**
     * The path [trust] validated, rebuilt from the chain the server sent: from the end-entity certificate, each
     * issuer that signed the one before, until a self-signed certificate or a trust anchor that signed the last.
     * TLS 1.2 servers send their chain in order (RFC 5246 §7.4.2) but may add or leave out certificates, so the
     * path is followed by signature rather than by position.
     */
    private fun pathOf(sent: Array<out X509Certificate>): List<X509Certificate> {
        val path = mutableListOf(sent[0])
        val unused = sent.drop(1).toMutableList()
        val anchors = trust.acceptedIssuers
        while (path.size <= MAX_PATH) {
            val last = path.last()
            if (isIssuer(last, last)) return path
            val anchor = anchors.firstOrNull { isIssuer(it, last) }
            if (anchor != null) return path.apply { add(anchor) }
            val next = unused.firstOrNull { isIssuer(it, last) } ?: return path
            unused.remove(next)
            path += next
        }
        return path
    }

    /** Whether [issuer] issued and signed [certificate]. */
    private fun isIssuer(issuer: X509Certificate, certificate: X509Certificate): Boolean =
        issuer.subjectX500Principal == certificate.issuerX500Principal &&
            try {
                certificate.verify(issuer.publicKey)
                true
            } catch (e: Exception) {
                false
            }

    private companion object {
        /** More certificates than any real path has; a bound for a chain built to loop. */
        const val MAX_PATH = 10
    }
}
