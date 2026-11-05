package dev.kcoap.crypto.x509

import dev.kcoap.common.toHex
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.Sec1

/**
 * A decoded SubjectPublicKeyInfo (RFC 5280 §4.1.2.7): the public key of an X.509 certificate, and on its
 * own the raw public key of DTLS RawPublicKey mode (RFC 7250 §3). [keyType] and [publicKey] are what
 * [dev.kcoap.crypto.CryptoBackend.verify] and `keyAgreement` take.
 *
 * Recognized algorithms: Ed25519 and X25519 (RFC 8410), X448, P-256 and P-384 (RFC 5480), ML-DSA-44/65/87
 * (draft-ietf-lamps-dilithium-certificates). Algorithm parameters other than the named curve are not
 * checked.
 */
public class SubjectPublicKeyInfo private constructor(
    der: ByteArray,
    /** The type of the public key. */
    public val keyType: KeyType,
    publicKey: ByteArray,
) {
    private val encodedBytes = der.copyOf()
    private val publicKeyBytes = publicKey.copyOf()

    /** The DER encoding this was decoded from. */
    public val encoded: ByteArray get() = encodedBytes.copyOf()

    /**
     * The public key in the raw encoding of [keyType]: an uncompressed SEC1 point `04 || x || y` for P-256
     * and P-384 (a compressed one is expanded with [Sec1]), the plain key bytes otherwise.
     */
    public val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is SubjectPublicKeyInfo && encodedBytes.contentEquals(other.encodedBytes)

    override fun hashCode(): Int = encodedBytes.contentHashCode()

    override fun toString(): String = "SubjectPublicKeyInfo($keyType)"

    public companion object {
        /**
         * Decodes a DER SubjectPublicKeyInfo, with no bytes after it.
         *
         * @throws CertificateFormatException for malformed DER, an unsupported algorithm or curve, a key of
         *   the wrong length, or a compressed point that is not on its curve.
         */
        public fun decode(der: ByteArray): SubjectPublicKeyInfo {
            val outer = DerReader(der)
            val spki = outer.read(DerReader.SEQUENCE).reader()
            outer.expectEnd("the SubjectPublicKeyInfo")
            val (keyType, publicKey) = read(spki)
            return SubjectPublicKeyInfo(der, keyType, publicKey)
        }

        /**
         * The key type and raw public key from the content of a SubjectPublicKeyInfo SEQUENCE:
         * `SEQUENCE { algorithm AlgorithmIdentifier, subjectPublicKey BIT STRING }`.
         */
        internal fun read(spki: DerReader): Pair<KeyType, ByteArray> {
            val algorithm = spki.read(DerReader.SEQUENCE).reader()
            val oid = algorithm.read(DerReader.OBJECT_IDENTIFIER).content().toHex()
            val bits = spki.read(DerReader.BIT_STRING).content()
            spki.expectEnd("the subject public key")
            if (bits.isEmpty() || bits[0].toInt() != 0) {
                throw CertificateFormatException("The subject public key must be whole bytes")
            }
            val key = bits.copyOfRange(1, bits.size)
            val type = if (oid == OID_EC_PUBLIC_KEY) {
                val curve = algorithm.read(DerReader.OBJECT_IDENTIFIER).content().toHex()
                EC_CURVES[curve] ?: throw CertificateFormatException("Unsupported EC curve OID $curve")
            } else {
                KEY_ALGORITHMS[oid] ?: throw CertificateFormatException("Unsupported public key algorithm OID $oid")
            }
            return type to rawKey(type, key)
        }

        /** [key] checked against the length of [type], with compressed P-256 and P-384 points expanded. */
        private fun rawKey(type: KeyType, key: ByteArray): ByteArray {
            val compressedLength = (type.publicKeyLength + 1) / 2
            val isPoint = type == KeyType.P256 || type == KeyType.P384
            if (isPoint && key.size == compressedLength && Sec1.isCompressed(key)) {
                return try {
                    Sec1.decompress(type, key)
                } catch (e: IllegalArgumentException) {
                    throw CertificateFormatException("Invalid compressed $type public key", e)
                }
            }
            if (key.size != type.publicKeyLength || (isPoint && key[0].toInt() != UNCOMPRESSED)) {
                throw CertificateFormatException("Invalid $type public key of ${key.size} bytes")
            }
            return key
        }

        private const val UNCOMPRESSED = 0x04
        private const val OID_EC_PUBLIC_KEY = "2a8648ce3d0201"

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
