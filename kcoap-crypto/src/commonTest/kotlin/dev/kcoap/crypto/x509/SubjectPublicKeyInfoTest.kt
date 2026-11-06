package dev.kcoap.crypto.x509

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.EcPoints
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.x509.TestCertificates.algorithm
import dev.kcoap.crypto.x509.TestCertificates.sequence
import dev.kcoap.crypto.x509.TestCertificates.subjectPublicKeyInfo
import dev.kcoap.crypto.x509.TestCertificates.tlv
import dev.kcoap.testkit.ByteMutator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SubjectPublicKeyInfo (RFC 5280 §4.1.2.7) on its own, as DTLS RawPublicKey mode carries it (RFC 7250 §3). */
class SubjectPublicKeyInfoTest {
    private val p256 = algorithm(TestCertificates.EC_PUBLIC_KEY, TestCertificates.P_256)

    /** RFC 7250 §3: the raw public key is the DER SubjectPublicKeyInfo; RFC 5480 §2.2: an ECPoint. */
    @Test
    fun rfc7250Section3RawPublicKeysDecode() {
        val der = subjectPublicKeyInfo(p256, EcPoints.RFC9529_PK_R_UNCOMPRESSED.hexToBytes())
        val spki = SubjectPublicKeyInfo.decode(der)
        assertEquals(KeyType.P256, spki.keyType)
        assertEquals(EcPoints.RFC9529_PK_R_UNCOMPRESSED, spki.publicKey.toHex())
        assertEquals(der.toHex(), spki.encoded.toHex())
        assertEquals("SubjectPublicKeyInfo(P256)", spki.toString())

        val ed25519 = SubjectPublicKeyInfo.decode(subjectPublicKeyInfo(algorithm("2b6570"), Rfc9529Certificates.PK_I.hexToBytes()))
        assertEquals(KeyType.ED25519, ed25519.keyType)
        assertEquals(Rfc9529Certificates.PK_I, ed25519.publicKey.toHex())
    }

    @Test
    fun compressedPointsAreExpanded() {
        val compressed = subjectPublicKeyInfo(p256, EcPoints.RFC9529_G_X_COMPRESSED.hexToBytes())
        assertEquals(EcPoints.RFC9529_G_X_UNCOMPRESSED, SubjectPublicKeyInfo.decode(compressed).publicKey.toHex())
        val p384 = algorithm(TestCertificates.EC_PUBLIC_KEY, TestCertificates.P_384)
        val compressed384 = subjectPublicKeyInfo(p384, EcPoints.P384_3G_COMPRESSED.hexToBytes())
        assertEquals(EcPoints.P384_3G_UNCOMPRESSED, SubjectPublicKeyInfo.decode(compressed384).publicKey.toHex())
    }

    @Test
    fun equalityIsByEncodingAndCopiesAreIndependent() {
        val der = subjectPublicKeyInfo(p256, EcPoints.P256_G_UNCOMPRESSED.hexToBytes())
        val spki = SubjectPublicKeyInfo.decode(der)
        val again = SubjectPublicKeyInfo.decode(der.copyOf())
        assertEquals(spki, again)
        assertEquals(spki.hashCode(), again.hashCode())
        val compressed = SubjectPublicKeyInfo.decode(subjectPublicKeyInfo(p256, EcPoints.P256_G_COMPRESSED.hexToBytes()))
        assertEquals(spki.publicKey.toHex(), compressed.publicKey.toHex())
        assertNotEquals(spki, compressed, "the same key in another encoding is another SubjectPublicKeyInfo")
        assertNotEquals<Any>(spki, "key")
        der.fill(0)
        spki.encoded.fill(0)
        spki.publicKey.fill(0)
        assertEquals(EcPoints.P256_G_UNCOMPRESSED, spki.publicKey.toHex())
        assertEquals(again.encoded.toHex(), spki.encoded.toHex())
    }

    @Test
    fun malformedOrUnsupportedKeysAreRejected() {
        val x25519 = algorithm(TestCertificates.X25519)
        val valid = subjectPublicKeyInfo(x25519, ByteArray(32))
        val invalid = listOf(
            valid + byteArrayOf(0), // trailing bytes
            valid.copyOf(valid.size - 1),
            sequence(x25519, tlv(0x03, ByteArray(0))), // empty BIT STRING
            sequence(x25519, tlv(0x03, byteArrayOf(1) + ByteArray(32))), // unused bits
            sequence(x25519, tlv(0x03, byteArrayOf(0) + ByteArray(32)), tlv(0x05, ByteArray(0))), // a third field
            sequence(x25519, tlv(0x04, byteArrayOf(0) + ByteArray(32))), // OCTET STRING instead of BIT STRING
            sequence(tlv(0x03, byteArrayOf(0) + ByteArray(32))), // no algorithm
            subjectPublicKeyInfo(algorithm("2b6571"), ByteArray(57)), // Ed448
            subjectPublicKeyInfo(algorithm(TestCertificates.EC_PUBLIC_KEY, "2b8104000a"), ByteArray(65)), // secp256k1
            subjectPublicKeyInfo(p256, ("05" + EcPoints.P256_G_UNCOMPRESSED.substring(2)).hexToBytes()), // bad prefix
            subjectPublicKeyInfo(p256, ("02" + "00".repeat(31) + "01").hexToBytes()), // x not on the curve
            subjectPublicKeyInfo(p256, EcPoints.P384_G_COMPRESSED.hexToBytes()), // a P-384 point
            subjectPublicKeyInfo(algorithm(TestCertificates.ML_DSA_44), ByteArray(1311)),
            ByteArray(0),
            "0500".hexToBytes(),
        )
        for (der in invalid) {
            assertFailsWith<CertificateFormatException>(der.toHex()) { SubjectPublicKeyInfo.decode(der) }
        }
        val curve = assertFailsWith<CertificateFormatException> {
            SubjectPublicKeyInfo.decode(subjectPublicKeyInfo(algorithm(TestCertificates.EC_PUBLIC_KEY, "2b8104000a"), ByteArray(65)))
        }
        assertEquals("Unsupported EC curve OID 2b8104000a", curve.message)
    }

    /** What a DTLS peer sends as its raw public key fails only with [CertificateFormatException]. */
    @Test
    fun mutatedKeysFailOnlyWithCertificateFormatException() {
        val seeds = listOf(
            subjectPublicKeyInfo(p256, EcPoints.P256_3G_UNCOMPRESSED.hexToBytes()),
            subjectPublicKeyInfo(p256, EcPoints.P256_3G_COMPRESSED.hexToBytes()),
            subjectPublicKeyInfo(algorithm(TestCertificates.EC_PUBLIC_KEY, TestCertificates.P_384), EcPoints.P384_2G_COMPRESSED.hexToBytes()),
            subjectPublicKeyInfo(algorithm(TestCertificates.X25519), ByteArray(32) { 9 }),
        )
        val random = Random(7250)
        var decoded = 0
        repeat(4_000) {
            try {
                SubjectPublicKeyInfo.decode(ByteMutator.mutate(seeds[it % seeds.size], random))
                decoded++
            } catch (_: CertificateFormatException) {
            }
        }
        assertTrue(decoded in 1 until 4_000, "some mutations still decode, most do not: $decoded")
    }
}
