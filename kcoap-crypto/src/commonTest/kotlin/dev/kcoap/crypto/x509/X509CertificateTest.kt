package dev.kcoap.crypto.x509

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.EcPoints
import dev.kcoap.crypto.x509.TestCertificates.algorithm
import dev.kcoap.crypto.x509.TestCertificates.certificate
import dev.kcoap.testkit.ByteMutator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The X.509 parser (RFC 5280 §4.1): the parts the protocols need, and nothing but its own exception. */
class X509CertificateTest {
    private fun ecAlgorithm(curveOid: String): ByteArray = algorithm(TestCertificates.EC_PUBLIC_KEY, curveOid)

    private fun attribute(oid: String, tag: Int, value: String): ByteArray =
        TestCertificates.sequence(TestCertificates.oid(oid), TestCertificates.tlv(tag, value.encodeToByteArray()))

    /** RFC 9529 §2.2 and §2.3: the Ed25519 certificates of EDHOC trace 1. */
    @Test
    fun rfc9529Trace1CertificatesParse() {
        val initiator = X509Certificate.parse(Rfc9529Certificates.CRED_I.hexToBytes())
        assertEquals(KeyType.ED25519, initiator.keyType)
        assertEquals(Rfc9529Certificates.PK_I, initiator.publicKey.toHex())
        assertEquals("EDHOC Initiator Ed25519", initiator.subjectCommonName)
        assertEquals(Rfc9529Certificates.CRED_I, initiator.encoded.toHex())
        assertEquals("X509Certificate(ED25519, CN=EDHOC Initiator Ed25519)", initiator.toString())
        assertEquals(initiator, X509Certificate.parse(Rfc9529Certificates.CRED_I.hexToBytes()))
        assertEquals(initiator.hashCode(), X509Certificate.parse(Rfc9529Certificates.CRED_I.hexToBytes()).hashCode())
        val responder = X509Certificate.parse(Rfc9529Certificates.CRED_R.hexToBytes())
        assertEquals(Rfc9529Certificates.PK_R, responder.publicKey.toHex())
        assertEquals("EDHOC Responder Ed25519", responder.subjectCommonName)
        assertNotEquals(initiator, responder)
        assertNotEquals<Any>(initiator, "certificate")
    }

    @Test
    fun theParsedCertificateKeepsItsOwnCopies() {
        val der = Rfc9529Certificates.CRED_I.hexToBytes()
        val certificate = X509Certificate.parse(der)
        der.fill(0)
        certificate.encoded.fill(0)
        certificate.publicKey.fill(0)
        assertEquals(Rfc9529Certificates.CRED_I, certificate.encoded.toHex())
        assertEquals(Rfc9529Certificates.PK_I, certificate.publicKey.toHex())
    }

    @Test
    fun subjectPublicKeyAlgorithms() {
        val point = EcPoints.P256_2G_UNCOMPRESSED
        val p384 = EcPoints.P384_2G_UNCOMPRESSED
        val cases = listOf(
            ecAlgorithm(TestCertificates.P_256) to point to KeyType.P256,
            ecAlgorithm(TestCertificates.P_384) to p384 to KeyType.P384,
            algorithm(TestCertificates.X25519) to "44".repeat(32) to KeyType.X25519,
            algorithm("2b656f") to "55".repeat(56) to KeyType.X448,
            algorithm("2b6570") to "66".repeat(32) to KeyType.ED25519,
            algorithm(TestCertificates.ML_DSA_44) to "77".repeat(1312) to KeyType.ML_DSA_44,
            algorithm("608648016503040312") to "88".repeat(1952) to KeyType.ML_DSA_65,
            algorithm(TestCertificates.ML_DSA_87) to "99".repeat(2592) to KeyType.ML_DSA_87,
        )
        for ((input, type) in cases) {
            val (algorithmIdentifier, keyHex) = input
            val parsed = X509Certificate.parse(certificate(algorithmIdentifier, keyHex.hexToBytes(), "subject"))
            assertEquals(type, parsed.keyType)
            assertEquals(keyHex, parsed.publicKey.toHex())
            assertEquals("subject", parsed.subjectCommonName)
        }
        // Without a version field, and with a subject that has no commonName.
        val organization = attribute("55040a", 0x0C, "org")
        val numericCn = attribute("550403", 0x12, "42")
        val noCn = TestCertificates.sequence(TestCertificates.tlv(0x31, organization + numericCn))
        val bare = certificate(algorithm(TestCertificates.X25519), ByteArray(32), "x", version = false, subject = noCn)
        assertEquals(KeyType.X25519, X509Certificate.parse(bare).keyType)
        assertNull(X509Certificate.parse(bare).subjectCommonName, "only UTF8, printable and IA5 names are read")
    }

    /**
     * RFC 5480 §2.2 allows compressed points; [X509Certificate.publicKey] is uncompressed whatever the
     * certificate carries, as KeyType documents and every backend takes.
     */
    @Test
    fun compressedKeysComeOutUncompressed() {
        for ((curve, compressed, uncompressed) in listOf(
            Triple(TestCertificates.P_256, EcPoints.P256_2G_COMPRESSED, EcPoints.P256_2G_UNCOMPRESSED),
            Triple(TestCertificates.P_256, EcPoints.P256_3G_COMPRESSED, EcPoints.P256_3G_UNCOMPRESSED),
            Triple(TestCertificates.P_384, EcPoints.P384_2G_COMPRESSED, EcPoints.P384_2G_UNCOMPRESSED),
        )) {
            val parsed = X509Certificate.parse(certificate(ecAlgorithm(curve), compressed.hexToBytes(), "ec"))
            assertEquals(uncompressed, parsed.publicKey.toHex())
        }
        val offTheCurve = "02" + "00".repeat(31) + "01" // x = 1 has no square root on P-256
        val failure = assertFailsWith<CertificateFormatException> {
            X509Certificate.parse(certificate(ecAlgorithm(TestCertificates.P_256), offTheCurve.hexToBytes(), "ec"))
        }
        assertTrue(failure.cause is IllegalArgumentException, "the reason from Sec1 is kept")
    }

    @Test
    fun malformedOrUnsupportedCertificatesAreRejected() {
        val valid = certificate(algorithm(TestCertificates.X25519), ByteArray(32), "x")
        val p256 = ecAlgorithm(TestCertificates.P_256)
        val invalid = listOf(
            valid + byteArrayOf(0), // trailing bytes
            valid.copyOf(valid.size - 1), // truncated
            certificate(algorithm("2b6571"), ByteArray(57), "x"), // Ed448: not supported
            certificate(algorithm(TestCertificates.EC_PUBLIC_KEY, "2b8104000a"), ByteArray(65), "x"), // secp256k1
            certificate(algorithm(TestCertificates.EC_PUBLIC_KEY), ByteArray(65), "x"), // no named curve
            certificate(algorithm(TestCertificates.X25519), ByteArray(31), "x"), // wrong key length
            certificate(p256, ByteArray(40), "x"),
            certificate(p256, ByteArray(65), "x"), // 65 bytes, but not 04 || x || y
            certificate(p256, ByteArray(33), "x"), // 33 bytes, but not 02 or 03 || x
            certificate(p256, ("02" + "ff".repeat(32)).hexToBytes(), "x"), // x not below p
            "3080".hexToBytes(), // indefinite length
            "1f00".hexToBytes(), // multi-byte tag
            "30840000000000".hexToBytes(), // four length bytes
            "30810100".hexToBytes(), // non-minimal length
            "3005".hexToBytes(), // length beyond the input
            "".hexToBytes(),
            "3081".hexToBytes(),
            "308200".hexToBytes(),
            TestCertificates.sequence(TestCertificates.sequence(), TestCertificates.sequence()), // missing fields
            TestCertificates.sequence(TestCertificates.sequence(valid)), // a certificate inside a SEQUENCE
        )
        for (der in invalid) {
            assertFailsWith<CertificateFormatException>(der.toHex()) { X509Certificate.parse(der) }
        }
        val unusedBits = valid.toHex().replace("032100" + "00".repeat(32), "032101" + "00".repeat(32)).hexToBytes()
        assertFailsWith<CertificateFormatException> { X509Certificate.parse(unusedBits) }
        val unsupported = assertFailsWith<CertificateFormatException> {
            X509Certificate.parse(certificate(algorithm("2b6571"), ByteArray(57), "x"))
        }
        assertEquals("Unsupported public key algorithm OID 2b6571", unsupported.message)
    }

    /**
     * Peer certificates arrive in EDHOC's `x5chain` and in DTLS: whatever the bytes, the parser either
     * returns or throws [CertificateFormatException], never [IllegalArgumentException] or anything else.
     */
    @Test
    fun mutatedCertificatesFailOnlyWithCertificateFormatException() {
        val seeds = listOf(
            Rfc9529Certificates.CRED_I.hexToBytes(),
            certificate(ecAlgorithm(TestCertificates.P_256), EcPoints.P256_2G_COMPRESSED.hexToBytes(), "p256"),
            certificate(ecAlgorithm(TestCertificates.P_384), EcPoints.P384_2G_UNCOMPRESSED.hexToBytes(), "p384"),
            certificate(algorithm(TestCertificates.ML_DSA_44), ByteArray(1312) { 3 }, "pq"),
        )
        val random = Random(9529)
        var parsed = 0
        repeat(FUZZ_ITERATIONS) {
            val input = ByteMutator.mutate(seeds[it % seeds.size], random)
            try {
                X509Certificate.parse(input)
                parsed++
            } catch (_: CertificateFormatException) {
            }
        }
        assertTrue(parsed in 1 until FUZZ_ITERATIONS, "some mutations still parse, most do not: $parsed")
    }

    private companion object {
        const val FUZZ_ITERATIONS = 4_000
    }
}
