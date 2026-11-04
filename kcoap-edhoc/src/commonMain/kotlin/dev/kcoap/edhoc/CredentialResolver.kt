package dev.kcoap.edhoc

import dev.kcoap.crypto.CertificateValidationException
import dev.kcoap.crypto.CertificateValidator
import dev.kcoap.crypto.CryptoBackend

/**
 * Turns a received ID_CRED_x into the peer's authentication credential (RFC 9528 §5.3.3 step 5, §5.4.3
 * step 5). This is where the application decides whom it trusts: return only credentials it is willing
 * to authenticate (RFC 9528 Appendix D).
 *
 * The result is a list because 'kid' values need not be unique: RFC 9528 §3.5.3 says applications MUST
 * NOT assume they are, so EDHOC tries every candidate until one verifies. An empty list means no trusted
 * credential: EDHOC answers with error code 3 for a reference it cannot resolve ('kid', 'x5t') and with
 * error code 1 otherwise, since code 3 must not be used for unsupported identifier types (RFC 9528 §6.4).
 * Throw an [EdhocException] to answer with a specific error.
 */
public fun interface CredentialResolver {
    /** The trusted credentials [identifier] may refer to, most likely first. */
    public suspend fun resolve(identifier: CredentialIdentifier): List<AuthenticationCredential>
}

/**
 * A [CredentialResolver] over a fixed set of trusted credentials:
 *
 * - `kid` matches credentials whose [AuthenticationCredential.kid] equals it;
 * - `x5t` matches X.509 credentials whose thumbprint equals it;
 * - `kccs` is accepted when an identical CCS is trusted (raw public keys are their own trust anchors,
 *   RFC 9528 Appendix D.3);
 * - `x5chain` is accepted when its end-entity certificate is trusted, or else when [certificateValidator]
 *   accepts the chain.
 *
 * Identity checks beyond the pinned set (subject names, policies) belong in a custom resolver.
 */
public class TrustedCredentials(
    private val backend: CryptoBackend,
    credentials: List<AuthenticationCredential>,
    private val certificateValidator: CertificateValidator? = null,
) : CredentialResolver {
    private val trusted = credentials.toList()

    override suspend fun resolve(identifier: CredentialIdentifier): List<AuthenticationCredential> =
        when (identifier) {
            is CredentialIdentifier.KeyId -> trusted.filter { it.hasKid(identifier.kid) }
            is CredentialIdentifier.X509Thumbprint -> trusted.filter { credential ->
                credential.certificate?.let { identifier.matches(backend, it.encoded) } == true
            }
            is CredentialIdentifier.Kccs -> {
                val offered = identifier.credential()
                trusted.filter { it.encoded.contentEquals(offered.encoded) }
            }
            is CredentialIdentifier.X509Chain -> resolveChain(identifier)
            is CredentialIdentifier.Other -> emptyList()
        }

    private suspend fun resolveChain(chain: CredentialIdentifier.X509Chain): List<AuthenticationCredential> {
        val endEntity = chain.endEntityCredential()
        val pinned = trusted.filter { it.certificate != null && it.encoded.contentEquals(endEntity.encoded) }
        if (pinned.isNotEmpty() || certificateValidator == null) return pinned
        try {
            certificateValidator.validate(chain.certificates)
        } catch (e: CertificateValidationException) {
            throw EdhocException.unspecified("Certificate chain not trusted", e)
        }
        return listOf(endEntity)
    }
}
