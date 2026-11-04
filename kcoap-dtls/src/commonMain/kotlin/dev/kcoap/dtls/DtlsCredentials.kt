package dev.kcoap.dtls

import dev.kcoap.crypto.CertificateValidator
import dev.kcoap.transport.Endpoint

/**
 * Where DTLS gets keys and trust decisions. Lookups are suspend so they can reach a database, a secure
 * element or a remote authorization service. Which ones are required depends on the configured modes,
 * see [DtlsConfig].
 */
public class DtlsCredentials(
    /** PreSharedKey mode. */
    public val preSharedKeys: PskStore? = null,
    /** RawPublicKey and Certificate mode: this endpoint's own key pair. */
    public val signer: DtlsSigner? = null,
    /** RawPublicKey mode: which peer keys are accepted. */
    public val rawPublicKeyTrust: RawPublicKeyTrust? = null,
    /** Certificate mode: chain validation. RFC 7252 §9.1.3.3 name matching is done by DTLS itself. */
    public val certificateValidator: CertificateValidator? = null,
)

public interface PskStore {
    /** Server side: the key for [identity], or null to fail the handshake. */
    public suspend fun keyFor(identity: ByteArray): ByteArray?

    /** Client side: what to present to [peer], given the server's optional PSK identity hint. */
    public suspend fun credentialFor(peer: Endpoint, identityHint: ByteArray?): PskCredential?
}

public class PskCredential(identity: ByteArray, key: ByteArray) {
    private val identityBytes = identity.copyOf()
    private val keyBytes = key.copyOf()

    public fun identity(): ByteArray = identityBytes.copyOf()

    public fun key(): ByteArray = keyBytes.copyOf()

    override fun toString(): String = "PskCredential(${identityBytes.size}-byte identity)"
}

/**
 * This endpoint's P-256 key pair. The private key stays behind this interface, so it can live in the
 * Android KeyStore, the Secure Enclave or an HSM.
 */
public interface DtlsSigner {
    /** DER `SubjectPublicKeyInfo` of the P-256 public key. */
    public val subjectPublicKeyInfo: ByteArray

    /** DER certificates, end entity first; empty in RawPublicKey mode. */
    public val certificateChain: List<ByteArray>

    /** ECDSA with SHA-256 over [data], DER encoded as TLS 1.2 expects (COSE uses raw `r || s`). */
    public suspend fun sign(data: ByteArray): ByteArray
}

public fun interface RawPublicKeyTrust {
    /** Whether [peer] may authenticate with this DER `SubjectPublicKeyInfo`. */
    public suspend fun isTrusted(peer: Endpoint, subjectPublicKeyInfo: ByteArray): Boolean
}
