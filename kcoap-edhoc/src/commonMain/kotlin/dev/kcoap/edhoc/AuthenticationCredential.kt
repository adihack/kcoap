package dev.kcoap.edhoc

import dev.kcoap.cbor.CborWriter
import dev.kcoap.common.toHex
import dev.kcoap.cose.CoseKey
import dev.kcoap.cose.CoseKeyType
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.x509.CertificateFormatException
import dev.kcoap.crypto.x509.X509Certificate

/**
 * An authentication credential, CRED_I or CRED_R (RFC 9528 §3.5.2): the exact CBOR item EDHOC hashes and
 * MACs, together with the authentication public key it carries.
 *
 * - X.509: CRED_x is the DER certificate as a CBOR byte string (RFC 9360), parsed by kcoap-crypto's
 *   [X509Certificate]. Use [x509].
 * - CCS: CRED_x is the untagged CWT Claims Set with a COSE_Key in its 'cnf' claim; a raw public key is a
 *   CCS too. Use [ccs]; the bytes are kept as given, never re-encoded (RFC 9528 §3.5.2).
 *
 * CWTs and C509 certificates are not supported.
 */
public class AuthenticationCredential private constructor(
    encoded: ByteArray,
    /** The type of the authentication key. */
    public val keyType: KeyType,
    publicKey: ByteArray,
    kid: ByteArray?,
    /** The certificate, for X.509 credentials. */
    public val certificate: X509Certificate?,
    /** The claims set, for CCS credentials. */
    public val claimsSet: CwtClaimsSet?,
) {
    private val encodedBytes = encoded.copyOf()
    private val publicKeyBytes = publicKey.copyOf()
    private val kidBytes = kid?.copyOf()

    /** CRED_x: the CBOR item that enters MAC_2/MAC_3, the signatures and TH_3/TH_4. */
    public val encoded: ByteArray get() = encodedBytes.copyOf()

    /** The authentication public key in the raw encoding of [keyType]. */
    public val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    /**
     * The key identifier this credential is known by, for ID_CRED_x = { 4 : kid } (RFC 9528 §3.5.3): the
     * COSE_Key 'kid' of a CCS, or the one given to [x509].
     */
    public val kid: ByteArray? get() = kidBytes?.copyOf()

    /** Whether the key can verify [algorithm] signatures. */
    internal fun canVerify(algorithm: SignatureAlgorithm): Boolean = keyType == algorithm.keyType

    internal fun hasKid(candidate: ByteArray): Boolean = kidBytes?.contentEquals(candidate) == true

    override fun equals(other: Any?): Boolean =
        other is AuthenticationCredential && encodedBytes.contentEquals(other.encodedBytes) &&
            kidBytes.contentEquals(other.kidBytes)

    override fun hashCode(): Int = encodedBytes.contentHashCode()

    override fun toString(): String {
        val kind = if (certificate != null) "X.509" else "CCS"
        return "AuthenticationCredential($kind, $keyType${kidBytes?.let { ", kid=${it.toHex()}" } ?: ""})"
    }

    public companion object {
        /**
         * An X.509 credential from its DER encoding.
         *
         * @param kid the identifier to match ID_CRED_x = { 4 : kid } against, if the deployment uses one.
         * @throws CertificateFormatException when the certificate cannot be parsed or its key is not one
         *   kcoap supports, for a certificate of this endpoint as for one a peer sent in `x5chain`.
         */
        public fun x509(der: ByteArray, kid: ByteArray? = null): AuthenticationCredential {
            val certificate = X509Certificate.parse(der)
            val encoded = CborWriter().writeBytes(der).toByteArray()
            return AuthenticationCredential(encoded, certificate.keyType, certificate.publicKey, kid, certificate, null)
        }

        /**
         * A CCS credential. Its [CwtClaimsSet.encoded] bytes become CRED_x, so a claims set decoded from
         * received or provisioned bytes keeps exactly those bytes.
         *
         * @throws IllegalArgumentException when the COSE_Key is not an authentication key kcoap supports.
         */
        public fun ccs(claimsSet: CwtClaimsSet): AuthenticationCredential {
            val key = claimsSet.confirmationKey
            return AuthenticationCredential(
                claimsSet.encoded, keyTypeOf(key), key.rawPublicKey(), key.kid, null, claimsSet,
            )
        }

        /** A CCS credential from its encoding, which becomes CRED_x unchanged. */
        public fun ccs(encoded: ByteArray): AuthenticationCredential = ccs(CwtClaimsSet.decode(encoded))

        private fun keyTypeOf(key: CoseKey): KeyType = when (key.keyType) {
            CoseKeyType.OKP, CoseKeyType.EC2 -> key.curve!!.keyType
            CoseKeyType.AKP -> when (key.algorithm) {
                SignatureAlgorithm.ML_DSA_44.coseId -> KeyType.ML_DSA_44
                SignatureAlgorithm.ML_DSA_65.coseId -> KeyType.ML_DSA_65
                SignatureAlgorithm.ML_DSA_87.coseId -> KeyType.ML_DSA_87
                else -> throw IllegalArgumentException("Unsupported AKP algorithm ${key.algorithm}")
            }
        }
    }
}
