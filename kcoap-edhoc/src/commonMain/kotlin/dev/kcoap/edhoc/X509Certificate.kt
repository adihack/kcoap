package dev.kcoap.edhoc

import dev.kcoap.common.toHex
import dev.kcoap.crypto.KeyType

/**
 * The parts of an X.509 certificate (RFC 5280) that EDHOC needs: the authentication key from the
 * SubjectPublicKeyInfo and the subject's common name for identity checks (RFC 9528 Appendix D.2).
 *
 * This is not a validator. Chains are validated by a [dev.kcoap.crypto.CertificateValidator]; the parser
 * only walks the DER structure and recognizes these subject public key algorithms:
 * Ed25519 and X25519 (RFC 8410), X448, P-256 and P-384 (RFC 5480), ML-DSA-44/65/87
 * (draft-ietf-lamps-dilithium-certificates).
 */
public class X509Certificate private constructor(
    der: ByteArray,
    public val keyType: KeyType,
    publicKey: ByteArray,
    /** The subject's commonName (OID 2.5.4.3), when it is a UTF8, printable or IA5 string. */
    public val subjectCommonName: String?,
) {
    private val encodedBytes = der.copyOf()
    private val publicKeyBytes = publicKey.copyOf()

    /** The DER encoding, which as a CBOR byte string is CRED_x (RFC 9528 §3.5.2). */
    public val encoded: ByteArray get() = encodedBytes.copyOf()

    /**
     * The subject public key in the raw encoding of [keyType]: a SEC1 point for P-256 and P-384 (possibly
     * compressed), the plain key bytes otherwise.
     */
    public val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is X509Certificate && encodedBytes.contentEquals(other.encodedBytes)

    override fun hashCode(): Int = encodedBytes.contentHashCode()

    override fun toString(): String = "X509Certificate($keyType, CN=$subjectCommonName)"

    public companion object {
        /**
         * Parses a DER certificate.
         *
         * @throws IllegalArgumentException for malformed DER or an unsupported public key algorithm.
         */
        public fun parse(der: ByteArray): X509Certificate {
            val outer = DerReader(der)
            val certificate = outer.read(DerReader.SEQUENCE).reader()
            require(outer.isAtEnd) { "Trailing bytes after the certificate" }
            val tbs = certificate.read(DerReader.SEQUENCE).reader()
            if (tbs.peekTag() == DerReader.CONTEXT_0) tbs.read() // version
            tbs.read(DerReader.INTEGER) // serialNumber
            tbs.read(DerReader.SEQUENCE) // signature
            tbs.read(DerReader.SEQUENCE) // issuer
            tbs.read(DerReader.SEQUENCE) // validity
            val subject = tbs.read(DerReader.SEQUENCE)
            val (keyType, publicKey) = subjectPublicKey(tbs.read(DerReader.SEQUENCE).reader())
            certificate.read(DerReader.SEQUENCE) // signatureAlgorithm
            certificate.read(DerReader.BIT_STRING) // signatureValue
            require(certificate.isAtEnd) { "Trailing fields after the certificate signature" }
            return X509Certificate(der, keyType, publicKey, commonName(subject.reader()))
        }

        private fun subjectPublicKey(spki: DerReader): Pair<KeyType, ByteArray> {
            val algorithm = spki.read(DerReader.SEQUENCE).reader()
            val oid = algorithm.read(DerReader.OBJECT_IDENTIFIER).content().toHex()
            val bits = spki.read(DerReader.BIT_STRING).content()
            require(bits.isNotEmpty() && bits[0].toInt() == 0) { "Subject public key must be whole bytes" }
            val key = bits.copyOfRange(1, bits.size)
            val type = if (oid == OID_EC_PUBLIC_KEY) {
                val curve = algorithm.read(DerReader.OBJECT_IDENTIFIER).content().toHex()
                EC_CURVES[curve] ?: throw IllegalArgumentException("Unsupported EC curve OID $curve")
            } else {
                KEY_ALGORITHMS[oid] ?: throw IllegalArgumentException("Unsupported public key algorithm OID $oid")
            }
            val validLength = when (type) {
                // Uncompressed 04 || x || y, or compressed 02/03 || x.
                KeyType.P256, KeyType.P384 ->
                    key.size == type.publicKeyLength || key.size == (type.publicKeyLength + 1) / 2
                else -> key.size == type.publicKeyLength
            }
            require(validLength) { "Invalid $type public key length ${key.size}" }
            return type to key
        }

        /** Name ::= SEQUENCE OF RelativeDistinguishedName (SET OF AttributeTypeAndValue). */
        private fun commonName(name: DerReader): String? {
            while (!name.isAtEnd) {
                val rdn = name.read(DerReader.SET).reader()
                while (!rdn.isAtEnd) {
                    val attribute = rdn.read(DerReader.SEQUENCE).reader()
                    val type = attribute.read(DerReader.OBJECT_IDENTIFIER).content().toHex()
                    val value = attribute.read()
                    if (type == OID_COMMON_NAME && value.tag in TEXT_TAGS) return value.content().decodeToString()
                }
            }
            return null
        }

        private const val OID_EC_PUBLIC_KEY = "2a8648ce3d0201"
        private const val OID_COMMON_NAME = "550403"
        private val TEXT_TAGS = setOf(DerReader.UTF8_STRING, DerReader.PRINTABLE_STRING, DerReader.IA5_STRING)

        private val KEY_ALGORITHMS = mapOf(
            "2b6570" to KeyType.ED25519, // 1.3.101.112
            "2b656e" to KeyType.X25519, // 1.3.101.110
            "2b656f" to KeyType.X448, // 1.3.101.111
            "608648016503040311" to KeyType.ML_DSA_44, // 2.16.840.1.101.3.4.3.17
            "608648016503040312" to KeyType.ML_DSA_65, // 2.16.840.1.101.3.4.3.18
            "608648016503040313" to KeyType.ML_DSA_87, // 2.16.840.1.101.3.4.3.19
        )

        private val EC_CURVES = mapOf(
            "2a8648ce3d030107" to KeyType.P256, // prime256v1
            "2b81040022" to KeyType.P384, // secp384r1
        )
    }
}
