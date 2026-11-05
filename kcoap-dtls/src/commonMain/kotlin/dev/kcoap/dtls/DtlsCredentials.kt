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
    /** RawPublicKey and Certificate mode: this endpoint's own key, public key and certificates. */
    public val localCredential: DtlsLocalCredential? = null,
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

public fun interface RawPublicKeyTrust {
    /**
     * Whether [peer] may authenticate with this DER `SubjectPublicKeyInfo` (RFC 7250 §3), which
     * [dev.kcoap.crypto.x509.SubjectPublicKeyInfo.decode] turns into a key type and raw key.
     */
    public suspend fun isTrusted(peer: Endpoint, subjectPublicKeyInfo: ByteArray): Boolean
}
