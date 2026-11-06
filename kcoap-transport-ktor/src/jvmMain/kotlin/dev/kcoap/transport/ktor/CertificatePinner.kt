package dev.kcoap.transport.ktor

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * A certificate-pinning hook for [KtorTlsConnector]: a check of the server's chain on top of [TlsTrust], never
 * instead of it. It runs during the TLS handshake, after the chain was validated against the trust anchors and
 * found to name the server, and before any CoAP message is sent; refusing ends the handshake.
 *
 * ```
 * val pins = CertificatePinner.publicKeySha256(listOf(Base64.decode("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")))
 * val connector = KtorTlsConnector(TlsClientConfig(pinner = pins))
 * ```
 */
public fun interface CertificatePinner {
    /**
     * Whether to accept [chain] for [host]. [host] is the peer's host as the request named it, an IP literal in
     * canonical form or a lower-case name. [chain] is the validated path, end-entity certificate first, then each
     * issuer up to and including the trust anchor that vouched for it. Throwing refuses the connection as well.
     */
    public fun accepts(host: String, chain: List<X509Certificate>): Boolean

    public companion object {
        /**
         * Accepts a chain in which some certificate, the trust anchor included, has a public key whose SHA-256
         * digest is one of [pins]: the digest of the DER-encoded SubjectPublicKeyInfo, as RFC 7469 §2.4 defines
         * it (`pin-sha256`, which is its Base64 form). Pinning a key rather than a certificate survives the
         * certificate's renewal with the same key; list a backup key too, as §4.3 advises.
         *
         * @throws IllegalArgumentException when [pins] is empty or a pin is not 32 bytes long.
         */
        public fun publicKeySha256(pins: Collection<ByteArray>): CertificatePinner {
            require(pins.isNotEmpty()) { "At least one pin is needed" }
            require(pins.all { it.size == SHA256_LENGTH }) { "A SHA-256 pin is $SHA256_LENGTH bytes long" }
            val copies = pins.map { it.copyOf() }
            return CertificatePinner { _, chain ->
                chain.any { certificate ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
                    copies.any { MessageDigest.isEqual(it, digest) }
                }
            }
        }

        private const val SHA256_LENGTH = 32
    }
}
