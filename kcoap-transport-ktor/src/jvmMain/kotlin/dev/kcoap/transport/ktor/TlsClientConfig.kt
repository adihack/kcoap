package dev.kcoap.transport.ktor

import dev.kcoap.transport.CoapScheme

/**
 * How a [KtorTlsConnector] runs TLS for `coaps+tcp` (RFC 8323 §8.2, §9.1).
 *
 * @property trust which certificate authorities vouch for a server; [TlsTrust.Platform] by default.
 * @property pinner an extra check of the validated chain, or null for none.
 * @property allowsAnyPortWithoutAlpn whether to connect to ports other than 5684 although the connector cannot
 *   offer ALPN. RFC 8323 §8.2 has a TLS client offer the ALPN protocol `coap` ([CoapScheme.ALPN]) on every port
 *   but 5684, where a server that negotiates no ALPN implies `coaps+tcp`, and ktor-network-tls cannot send the
 *   extension. So by default the connector refuses such a port before connecting; set this for a server known
 *   to accept clients without ALPN, as libcoap's does.
 */
public class TlsClientConfig(
    public val trust: TlsTrust = TlsTrust.Platform,
    public val pinner: CertificatePinner? = null,
    public val allowsAnyPortWithoutAlpn: Boolean = false,
) {
    override fun toString(): String =
        "TlsClientConfig(trust=$trust, pinner=${if (pinner == null) "none" else "set"}, " +
            "allowsAnyPortWithoutAlpn=$allowsAnyPortWithoutAlpn)"

    public companion object {
        /** The platform trust store, no pinning, port 5684 only. */
        public val Default: TlsClientConfig = TlsClientConfig()
    }
}
