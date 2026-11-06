package dev.kcoap.transport.ktor

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Which certificate authorities a [KtorTlsConnector] trusts to vouch for a server (RFC 8323 §9.1, Certificate
 * mode). Either way the chain is validated by the platform's PKIX trust manager (signatures, validity, basic
 * constraints, key usage), and the server must then be named by its certificate.
 */
public sealed interface TlsTrust {
    /**
     * The platform trust store: the JVM's default `TrustManagerFactory` with its default key store, which is the
     * JDK's `cacerts` unless `javax.net.ssl.trustStore` names another; on Android, the system's trusted CAs as the
     * app's network security configuration admits them.
     */
    public data object Platform : TlsTrust

    /**
     * Only [certificates] as trust anchors, instead of the platform store: a private CA, or a device's self-signed
     * certificate trusted as itself.
     *
     * @throws IllegalArgumentException when [certificates] is empty, which would trust nothing.
     */
    public class Anchors(certificates: List<X509Certificate>) : TlsTrust {
        /** The trust anchors, in the order given. */
        public val certificates: List<X509Certificate> = certificates.toList()

        init {
            require(this.certificates.isNotEmpty()) { "At least one trust anchor is needed" }
        }

        override fun toString(): String = "Anchors(${certificates.size} certificates)"
    }
}

/** The platform's PKIX trust manager over this trust's anchors. */
internal fun TlsTrust.trustManager(): X509TrustManager {
    val keyStore = when (this) {
        TlsTrust.Platform -> null
        is TlsTrust.Anchors -> KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate -> setCertificateEntry("anchor-$index", certificate) }
        }
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
