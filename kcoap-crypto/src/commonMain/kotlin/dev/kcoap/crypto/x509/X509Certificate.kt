package dev.kcoap.crypto.x509

import dev.kcoap.common.toHex
import dev.kcoap.crypto.KeyType

/**
 * The parts of an X.509 certificate (RFC 5280) that kcoap's protocols need: the subject public key from
 * the SubjectPublicKeyInfo, and the subject's common name for identity checks. EDHOC takes certificates
 * as CRED_x and in `x5chain` (RFC 9528 §3.5.2, RFC 9360, Appendix D.2); DTLS certificate mode will take
 * them in the same way.
 *
 * This is not a validator. Chains are validated by a [dev.kcoap.crypto.CertificateValidator], of which
 * kcoap ships none yet; the parser only walks the DER structure, checks that it is one certificate with
 * the fields of RFC 5280 §4.1, and recognizes the subject public key algorithms of [SubjectPublicKeyInfo].
 */
public class X509Certificate private constructor(
    der: ByteArray,
    /** The type of the subject public key. */
    public val keyType: KeyType,
    publicKey: ByteArray,
    /** The subject's commonName (OID 2.5.4.3), when it is a UTF8, printable or IA5 string. */
    public val subjectCommonName: String?,
) {
    private val encodedBytes = der.copyOf()
    private val publicKeyBytes = publicKey.copyOf()

    /** The DER encoding, which as a CBOR byte string is an EDHOC CRED_x (RFC 9528 §3.5.2). */
    public val encoded: ByteArray get() = encodedBytes.copyOf()

    /**
     * The subject public key in the raw encoding of [keyType]: an uncompressed SEC1 point `04 || x || y`
     * for P-256 and P-384, also when the certificate carries it compressed, and the plain key bytes
     * otherwise ([SubjectPublicKeyInfo.publicKey]).
     */
    public val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is X509Certificate && encodedBytes.contentEquals(other.encodedBytes)

    override fun hashCode(): Int = encodedBytes.contentHashCode()

    override fun toString(): String = "X509Certificate($keyType, CN=$subjectCommonName)"

    public companion object {
        /**
         * Parses a DER certificate, with no bytes after it (RFC 5280 §4.1).
         *
         * @throws CertificateFormatException for malformed DER, a structure that is not a certificate, or a
         *   subject public key [SubjectPublicKeyInfo.decode] rejects. Nothing else, whatever the input.
         */
        public fun parse(der: ByteArray): X509Certificate {
            val outer = DerReader(der)
            val certificate = outer.read(DerReader.SEQUENCE).reader()
            outer.expectEnd("the certificate")
            val tbs = certificate.read(DerReader.SEQUENCE).reader()
            if (tbs.peekTag() == DerReader.CONTEXT_0) tbs.read() // version
            tbs.read(DerReader.INTEGER) // serialNumber
            tbs.read(DerReader.SEQUENCE) // signature
            tbs.read(DerReader.SEQUENCE) // issuer
            tbs.read(DerReader.SEQUENCE) // validity
            val subject = tbs.read(DerReader.SEQUENCE)
            val (keyType, publicKey) = SubjectPublicKeyInfo.read(tbs.read(DerReader.SEQUENCE).reader())
            certificate.read(DerReader.SEQUENCE) // signatureAlgorithm
            certificate.read(DerReader.BIT_STRING) // signatureValue
            certificate.expectEnd("the certificate signature")
            return X509Certificate(der, keyType, publicKey, commonName(subject.reader()))
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

        private const val OID_COMMON_NAME = "550403"
        private val TEXT_TAGS = setOf(DerReader.UTF8_STRING, DerReader.PRINTABLE_STRING, DerReader.IA5_STRING)
    }
}
