package dev.kcoap.crypto

/**
 * Validates an X.509 chain against trust anchors: path, signatures, validity, usage. Shared by EDHOC
 * (`x5chain`, `x5t`) and DTLS/TLS certificate mode. Name checks against the request belong to the caller.
 *
 * No implementation ships with kcoap yet: a common-code validator and thin platform adapters (JVM
 * `CertPathValidator`, Apple `SecTrust`) are on the roadmap. Returning normally means trusted, so an
 * implementation throws for every chain it cannot validate; a stub such as `CertificateValidator { }`
 * trusts every chain, self-signed ones included.
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

public class CertificateValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)
