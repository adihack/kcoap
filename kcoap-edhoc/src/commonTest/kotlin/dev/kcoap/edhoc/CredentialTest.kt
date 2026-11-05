package dev.kcoap.edhoc

import dev.kcoap.common.toHex
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CertificateValidationException
import dev.kcoap.crypto.CertificateValidator
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.x509.CertificateFormatException
import dev.kcoap.crypto.x509.X509Certificate
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

/** Authentication credentials and the trusted-credentials resolver; X.509 parsing is tested in kcoap-crypto. */
class CredentialTest {
    private val backend = DefaultCryptoBackend()

    private fun ccs(type: KeyType, publicKey: ByteArray): AuthenticationCredential =
        AuthenticationCredential.ccs(CwtClaimsSet(null, Fixtures.coseKey(type, publicKey)))

    /**
     * X.509 credentials (RFC 9528 §3.5.2): CRED_x is the DER certificate as a CBOR byte string, and the key
     * comes from kcoap-crypto's parser, whose own tests cover the DER.
     */
    @Test
    fun x509CredentialsCarryTheCertificateAndItsKey() {
        val credential = AuthenticationCredential.x509(Rfc9529Trace1.CRED_I.hex())
        assertEquals(Rfc9529Trace1.CRED_I_CBOR, credential.encoded.toHex())
        assertEquals(KeyType.ED25519, credential.keyType)
        assertEquals(Rfc9529Trace1.PK_I, credential.publicKey.toHex())
        assertEquals(X509Certificate.parse(Rfc9529Trace1.CRED_I.hex()), credential.certificate)
        assertEquals("EDHOC Initiator Ed25519", credential.certificate!!.subjectCommonName)
        assertNull(credential.claimsSet)
    }

    /** A certificate kcoap cannot use fails with the parser's exception, not [IllegalArgumentException]. */
    @Test
    fun unusableCertificatesFailWithCertificateFormatException() = runTest {
        val truncated = Rfc9529Trace1.CRED_I.hex().let { it.copyOf(it.size - 1) }
        assertFailsWith<CertificateFormatException> { AuthenticationCredential.x509(truncated) }
        val ed448 = TestCertificates.certificate(TestCertificates.algorithm("2b6571"), ByteArray(57), "ed448")
        assertFailsWith<CertificateFormatException> { AuthenticationCredential.x509(ed448) }
        val resolver = TrustedCredentials(backend, listOf(AuthenticationCredential.x509(Rfc9529Trace1.CRED_R.hex())))
        assertFailsWith<CertificateFormatException> { resolver.resolve(CredentialIdentifier.X509Chain(listOf(truncated))) }
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
