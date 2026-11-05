package dev.kcoap.crypto

/**
 * Validates an X.509 chain against trust anchors: path, signatures, validity, usage. Shared by EDHOC
 * (`x5chain`, `x5t`) and DTLS/TLS certificate mode. Name checks against the request belong to the caller.
 * Parsing is not its job: [dev.kcoap.crypto.x509.X509Certificate] reads the parts the protocols need.
 *
 * kcoap ships no validator yet. Until it does, trusting an `x5chain` means pinning its end-entity
 * certificate (kcoap-edhoc's `TrustedCredentials`) or passing a validator the application supplies. The
 * planned default is a common-code validator for a constrained profile (RFC 7925 §4.4: explicit trust
 * anchors, the signatures through [CryptoBackend.verify], validity on an injected clock), so it behaves
 * the same on every platform, browsers included; validators on the operating system's trust store (JVM
 * `CertPathValidator`, Apple `SecTrust`) may follow as optional adapters, with this same interface.
 *
 * Returning normally means trusted, so an implementation throws for every chain it cannot validate; a
 * stub such as `CertificateValidator { }` trusts every chain, self-signed ones included.
 *
 * Validation authenticates, it does not authorize: every chain that leads to a trust anchor passes,
 * whoever its end entity is. Decide which peers may connect afterwards, in EDHOC on the session's peer
 * credential or in a credential resolver that wraps kcoap-edhoc's `TrustedCredentials`. Without a
 * validator, `TrustedCredentials` trusts only the certificates pinned in it and rejects every other chain.
 */
public fun interface CertificateValidator {
    /**
     * @param chain DER certificates, end entity first.
     * @throws CertificateValidationException when the chain is not trusted.
     */
    public suspend fun validate(chain: List<ByteArray>)
}

/** A chain that a [CertificateValidator] does not trust. */
public class CertificateValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)
