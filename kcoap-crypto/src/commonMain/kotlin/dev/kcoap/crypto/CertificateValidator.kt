package dev.kcoap.crypto

/**
 * Validates an X.509 chain against trust anchors: path, signatures, validity, usage. Shared by EDHOC
 * (`x5chain`, `x5t`) and DTLS/TLS certificate mode, and backed by the platform where one exists (JVM
 * `CertPathValidator`, Apple `SecTrust`). Name checks against the request belong to the caller.
 */
public fun interface CertificateValidator {
    /**
     * @param chain DER certificates, end entity first.
     * @throws CertificateValidationException when the chain is not trusted.
     */
    public suspend fun validate(chain: List<ByteArray>)
}

public class CertificateValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)
