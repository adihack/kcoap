package dev.kcoap.edhoc

import dev.kcoap.common.toHex
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CertificateValidationException
import dev.kcoap.crypto.CertificateValidator
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.TestCertificates.algorithm
import dev.kcoap.edhoc.TestCertificates.certificate
import dev.kcoap.edhoc.vectors.Rfc9529Trace1
import dev.kcoap.edhoc.vectors.Rfc9529Trace2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Authentication credentials, the DER reader, and the trusted-credentials resolver. */
class CredentialTest {
    private val backend = DefaultCryptoBackend()

    /** RFC 9529 §2.8: the Ed25519 certificates of trace 1. */
    private fun ecAlgorithm(curveOid: String): ByteArray = algorithm(TestCertificates.EC_PUBLIC_KEY, curveOid)

    private fun attribute(oid: String, tag: Int, value: String): ByteArray =
        TestCertificates.sequence(TestCertificates.oid(oid), TestCertificates.tlv(tag, value.encodeToByteArray()))

    private fun ccs(type: KeyType, publicKey: ByteArray): AuthenticationCredential =
        AuthenticationCredential.ccs(CwtClaimsSet(null, Fixtures.coseKey(type, publicKey)))

    @Test
    fun traceCertificatesParse() {
        val initiator = X509Certificate.parse(Rfc9529Trace1.CRED_I.hex())
        assertEquals(KeyType.ED25519, initiator.keyType)
        assertEquals(Rfc9529Trace1.PK_I, initiator.publicKey.toHex())
        assertEquals("EDHOC Initiator Ed25519", initiator.subjectCommonName)
        assertEquals(Rfc9529Trace1.CRED_I, initiator.encoded.toHex())
        assertEquals("X509Certificate(ED25519, CN=EDHOC Initiator Ed25519)", initiator.toString())
        assertEquals(initiator, X509Certificate.parse(Rfc9529Trace1.CRED_I.hex()))
        assertEquals(initiator.hashCode(), X509Certificate.parse(Rfc9529Trace1.CRED_I.hex()).hashCode())
        assertNotEquals(initiator, X509Certificate.parse(Rfc9529Trace1.CRED_R.hex()))
        assertNotEquals<Any>(initiator, "certificate")
    }

    @Test
    fun subjectPublicKeyAlgorithms() {
        val point = "04" + "11".repeat(32) + "22".repeat(32)
        val cases = listOf(
            ecAlgorithm(TestCertificates.P_256) to point to KeyType.P256,
            ecAlgorithm(TestCertificates.P_256) to ("02" + "11".repeat(32)) to KeyType.P256,
            ecAlgorithm(TestCertificates.P_384) to ("04" + "33".repeat(96)) to KeyType.P384,
            algorithm(TestCertificates.X25519) to "44".repeat(32) to KeyType.X25519,
            algorithm("2b656f") to "55".repeat(56) to KeyType.X448,
            algorithm(TestCertificates.ML_DSA_87) to "66".repeat(2592) to KeyType.ML_DSA_87,
            algorithm("608648016503040312") to "77".repeat(1952) to KeyType.ML_DSA_65,
        )
        for ((input, type) in cases) {
            val (algorithmIdentifier, keyHex) = input
            val parsed = X509Certificate.parse(certificate(algorithmIdentifier, keyHex.hex(), "subject"))
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

    @Test
    fun malformedCertificatesAreRejected() {
        val valid = certificate(algorithm(TestCertificates.X25519), ByteArray(32), "x")
        val invalid = listOf(
            valid + byteArrayOf(0), // trailing bytes
            valid.copyOf(valid.size - 1), // truncated
            certificate(algorithm("2b6571"), ByteArray(57), "x"), // Ed448: not supported
            certificate(algorithm(TestCertificates.EC_PUBLIC_KEY, "2b8104000a"), ByteArray(65), "x"), // secp256k1
            certificate(algorithm(TestCertificates.X25519), ByteArray(31), "x"), // wrong key length
            certificate(algorithm(TestCertificates.EC_PUBLIC_KEY, TestCertificates.P_256), ByteArray(40), "x"),
            "3080".hex(), // indefinite length
            "1f00".hex(), // multi-byte tag
            "30840000000000".hex(), // four length bytes
            "30810100".hex(), // non-minimal length
            "3005".hex(), // length beyond the input
            "".hex(),
            "3081".hex(),
            "308200".hex(),
            TestCertificates.sequence(TestCertificates.sequence(), TestCertificates.sequence()), // missing fields
        )
        for (der in invalid) {
            assertFailsWith<IllegalArgumentException>(der.toHex()) { X509Certificate.parse(der) }
        }
        val unusedBits = valid.toHex().replace("032100" + "00".repeat(32), "032101" + "00".repeat(32)).hex()
        assertFailsWith<IllegalArgumentException> { X509Certificate.parse(unusedBits) }
        val extra = TestCertificates.sequence(
            TestCertificates.sequence(*listOf(valid).toTypedArray()),
        )
        assertFailsWith<IllegalArgumentException> { X509Certificate.parse(extra) }
    }

    @Test
    fun ccsCredentials() {
        val credR = AuthenticationCredential.ccs(Rfc9529Trace2.CRED_R.hex())
        assertEquals(KeyType.P256, credR.keyType)
        assertEquals("32", credR.kid!!.toHex())
        assertEquals(Rfc9529Trace2.CRED_R, credR.encoded.toHex())
        assertEquals("example.edu", credR.claimsSet!!.subject)
        assertNull(credR.certificate)
        assertEquals("AuthenticationCredential(CCS, P256, kid=32)", credR.toString())
        assertEquals(credR, AuthenticationCredential.ccs(CwtClaimsSet.decode(Rfc9529Trace2.CRED_R.hex())))
        assertEquals(credR.hashCode(), AuthenticationCredential.ccs(Rfc9529Trace2.CRED_R.hex()).hashCode())
        assertNotEquals<Any>(credR, "credential")
        val x509 = AuthenticationCredential.x509(Rfc9529Trace1.CRED_R.hex(), kid = "07".hex())
        assertEquals("AuthenticationCredential(X.509, ED25519, kid=07)", x509.toString())
        assertNotEquals(x509, AuthenticationCredential.x509(Rfc9529Trace1.CRED_R.hex()))
        val withoutKid = AuthenticationCredential.x509(Rfc9529Trace1.CRED_R.hex())
        assertEquals("AuthenticationCredential(X.509, ED25519)", withoutKid.toString())
    }

    @Test
    fun localCredentialsCheckTheirKeys() = runTest {
        val ed = backend.generateKeyPair(KeyType.ED25519)
        val x = backend.generateKeyPair(KeyType.X25519)
        val edCredential = ccs(KeyType.ED25519, ed.publicKey)
        val xCredential = ccs(KeyType.X25519, x.publicKey)
        val id = CredentialIdentifier.KeyId("01".hex())
        assertFailsWith<IllegalArgumentException> { LocalCredential.signature(edCredential, id, x.privateKey) }
        assertFailsWith<IllegalArgumentException> { LocalCredential.signature(xCredential, id, x.privateKey) }
        assertFailsWith<IllegalArgumentException> {
            LocalCredential.staticDiffieHellman(edCredential, id, ed.privateKey)
        }
        val signer = LocalCredential.signature(edCredential, id, ed.privateKey)
        assertTrue(signer.isUsableFor(AuthenticationKind.SIGNATURE, CipherSuite.SUITE_0))
        assertTrue(!signer.isUsableFor(AuthenticationKind.STATIC_DH, CipherSuite.SUITE_0))
        assertTrue(!signer.isUsableFor(AuthenticationKind.SIGNATURE, CipherSuite.SUITE_2))
        val dh = LocalCredential.staticDiffieHellman(xCredential, id, x.privateKey)
        assertTrue(dh.isUsableFor(AuthenticationKind.STATIC_DH, CipherSuite.SUITE_6))
        assertTrue(!dh.isUsableFor(AuthenticationKind.STATIC_DH, CipherSuite.SUITE_PQC_1))
        assertTrue(dh.toString().startsWith("LocalCredential(STATIC_DH, AuthenticationCredential(CCS, X25519)"))
        assertEquals("PrivateKey(X25519)", (dh.privateKey as SoftwarePrivateKey).toString())
    }

    @Test
    fun trustedCredentialsResolveEveryIdentifierType() = runTest {
        val certificateR = AuthenticationCredential.x509(Rfc9529Trace1.CRED_R.hex())
        val certificateI = AuthenticationCredential.x509(Rfc9529Trace1.CRED_I.hex())
        val ccs = AuthenticationCredential.ccs(Rfc9529Trace2.CRED_R.hex())
        val resolver = TrustedCredentials(backend, listOf(certificateR, ccs))

        assertEquals(listOf(ccs), resolver.resolve(CredentialIdentifier.KeyId("32".hex())))
        assertEquals(emptyList(), resolver.resolve(CredentialIdentifier.KeyId("33".hex())))
        val thumbprintR = CredentialIdentifier.x509Thumbprint(backend, certificateR.certificate!!.encoded)
        val thumbprintI = CredentialIdentifier.x509Thumbprint(backend, certificateI.certificate!!.encoded)
        assertEquals(listOf(certificateR), resolver.resolve(thumbprintR))
        assertEquals(emptyList(), resolver.resolve(thumbprintI))
        assertEquals(listOf(ccs), resolver.resolve(CredentialIdentifier.Kccs(ccs.claimsSet!!)))
        val otherCcs = AuthenticationCredential.ccs(Rfc9529Trace2.CRED_I.hex())
        assertEquals(emptyList(), resolver.resolve(CredentialIdentifier.Kccs(otherCcs.claimsSet!!)))
        val chainR = CredentialIdentifier.X509Chain(listOf(Rfc9529Trace1.CRED_R.hex()))
        assertEquals(listOf(certificateR), resolver.resolve(chainR))
        assertEquals(emptyList(), resolver.resolve(CredentialIdentifier.X509Chain(listOf(Rfc9529Trace1.CRED_I.hex()))))
        assertEquals(emptyList(), resolver.resolve(CredentialIdentifier.Other(dev.kcoap.cbor.CborMap())))

        val root = "3000".hex()
        val validating = TrustedCredentials(
            backend, emptyList(),
            CertificateValidator { chain ->
                if (!chain.last().contentEquals(root)) throw CertificateValidationException("untrusted root")
            },
        )
        val accepted = validating.resolve(CredentialIdentifier.X509Chain(listOf(Rfc9529Trace1.CRED_I.hex(), root)))
        assertContentEquals(certificateI.encoded, accepted.single().encoded)
        val rejected = assertFailsWith<EdhocException> {
            validating.resolve(CredentialIdentifier.X509Chain(listOf(Rfc9529Trace1.CRED_I.hex(), "3001".hex())))
        }
        assertEquals("Certificate chain not trusted", rejected.message)
        assertEquals("Authentication failed", rejected.error.diagnostic, "the peer is not told why")
        assertTrue(rejected.cause is CertificateValidationException)
    }
}
