package dev.kcoap.edhoc

import dev.kcoap.cbor.CborMap
import dev.kcoap.cbor.toCbor
import dev.kcoap.common.toHex
import dev.kcoap.cose.CoseKey
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The contract of `CryptoBackend.keyAgreement` and `verify`: P-256 and P-384 public keys reach the backend as
 * uncompressed points `04 || x || y`, however EDHOC got them: as x-only shares G_X and G_Y (RFC 9528
 * Appendix B), from a certificate that carries a compressed point (RFC 5480 §2.2), or from a COSE_Key whose
 * y is a sign bit (RFC 9053 §7.1.1). [UncompressedOnlyBackend] refuses anything else, as a platform that
 * cannot decompress points would, where the default backend would take a compressed point.
 */
class UncompressedPointsTest {
    private val backend = UncompressedOnlyBackend()

    /** The instrument works: a compressed point or a bare x-coordinate is refused and recorded. */
    @Test
    fun theStrictBackendRefusesCompressedPoints() = runTest {
        val pair = backend.generateKeyPair(KeyType.P256)
        val compressed = compress(pair.publicKey)
        val xOnly = compressed.copyOfRange(1, 33)
        assertFailsWith<IllegalArgumentException> { backend.keyAgreement(pair.privateKey, compressed) }
        assertFailsWith<IllegalArgumentException> { backend.keyAgreement(pair.privateKey, xOnly) }
        val signature = backend.sign(SignatureAlgorithm.ES256, pair.privateKey, ByteArray(1))
        assertFailsWith<IllegalArgumentException> {
            backend.verify(SignatureAlgorithm.ES256, compressed, ByteArray(1), signature)
        }
        assertEquals(3, backend.refused.size)
        assertTrue(backend.verify(SignatureAlgorithm.ES256, pair.publicKey, ByteArray(1), signature))
    }

    /**
     * Every method on the P-256 suites 2 and 3 and the P-384 suite 24: G_XY on both sides, G_RX and G_IY for
     * each static DH side (RFC 9528 §4.1.1), and an ECDSA verification for each signature side.
     */
    @Test
    fun everyMethodOnTheNistSuites() = runTest {
        var handshakes = 0
        for (suite in listOf(CipherSuite.SUITE_2, CipherSuite.SUITE_3, CipherSuite.SUITE_24)) {
            for (method in EdhocMethod.entries.filter { suite.isSupportedBy(backend, it) }) {
                assertHandshake(Fixtures.configs(backend, method, suite), method, "suite ${suite.value}")
                handshakes++
            }
        }
        assertTrue(handshakes >= 4, "suite 24 runs on every platform")
    }

    /** X.509 credentials whose SubjectPublicKeyInfo holds a compressed P-256 point, sent by value in x5chain. */
    @Test
    fun certificatesWithCompressedKeys() = runTest {
        for (method in listOf(EdhocMethod.SIGNATURE_SIGNATURE, EdhocMethod.STATIC_DH_STATIC_DH)) {
            val initiator = compressedCertificate(method.initiatorAuthentication, "initiator")
            val responder = compressedCertificate(method.responderAuthentication, "responder")
            assertHandshake(Fixtures.configs(method, CipherSuite.SUITE_2, initiator, responder), method, "X.509")
        }
    }

    /** CCS credentials whose COSE_Key gives y as its sign bit, identified by kid. */
    @Test
    fun coseKeysWithASignBit() = runTest {
        for (method in listOf(EdhocMethod.SIGNATURE_SIGNATURE, EdhocMethod.STATIC_DH_STATIC_DH)) {
            val initiator = signBitCredential(method.initiatorAuthentication, 0x2b)
            val responder = signBitCredential(method.responderAuthentication, 0x32)
            assertHandshake(Fixtures.configs(method, CipherSuite.SUITE_2, initiator, responder), method, "sign bit")
        }
    }

    /**
     * One handshake with [method] on [backend], which must have checked a NIST key for each key agreement and
     * verification: G_XY on both sides, G_RX or G_IY on both sides for each static DH side, and one
     * verification for each signature side.
     */
    private suspend fun assertHandshake(
        configs: Pair<EdhocInitiatorConfig, EdhocResponderConfig>,
        method: EdhocMethod,
        what: String,
    ) {
        val label = "method ${method.value}, $what"
        val staticSides = listOf(method.initiatorAuthentication, method.responderAuthentication)
            .count { it == AuthenticationKind.STATIC_DH }
        val agreementsBefore = backend.keyAgreements
        val verificationsBefore = backend.verifications
        val handshake = Fixtures.handshake(backend, configs)
        assertEquals(emptyList<String>(), backend.refused, label)
        assertContentEquals(handshake.initiatorSession.prkOut(), handshake.responderSession.prkOut(), label)
        assertEquals(2 + 2 * staticSides, backend.keyAgreements - agreementsBefore, "key agreements, $label")
        assertEquals(2 - staticSides, backend.verifications - verificationsBefore, "verifications, $label")
    }

    private suspend fun compressedCertificate(kind: AuthenticationKind, name: String): LocalCredential {
        val pair = backend.generateKeyPair(KeyType.P256)
        val ecP256 = TestCertificates.algorithm(TestCertificates.EC_PUBLIC_KEY, TestCertificates.P_256)
        val der = TestCertificates.certificate(ecP256, compress(pair.publicKey), name)
        val credential = AuthenticationCredential.x509(der)
        assertContentEquals(pair.publicKey, credential.publicKey, "the parser expands the point")
        return LocalCredential(credential, CredentialIdentifier.X509Chain(listOf(der)), pair.privateKey, kind)
    }

    private suspend fun signBitCredential(kind: AuthenticationKind, kid: Int): LocalCredential {
        val pair = backend.generateKeyPair(KeyType.P256)
        val publicKey = pair.publicKey
        val coseKey = CborMap(
            CoseKey.LABEL_KTY.toCbor() to 2.toCbor(),
            CoseKey.LABEL_KID.toCbor() to byteArrayOf(kid.toByte()).toCbor(),
            CoseKey.LABEL_CRV.toCbor() to 1.toCbor(),
            CoseKey.LABEL_X.toCbor() to publicKey.copyOfRange(1, 33).toCbor(),
            CoseKey.LABEL_Y.toCbor() to (publicKey.last().toInt() and 1 == 1).toCbor(),
        )
        val claims = CborMap(
            CwtClaimsSet.CLAIM_SUBJECT.toCbor() to "sign bit".toCbor(),
            CwtClaimsSet.CLAIM_CNF.toCbor() to CborMap(CwtClaimsSet.CNF_COSE_KEY.toCbor() to coseKey),
        )
        val credential = AuthenticationCredential.ccs(claims.encode())
        assertContentEquals(publicKey, credential.publicKey, "the COSE_Key expands the point")
        val identifier = CredentialIdentifier.KeyId(byteArrayOf(kid.toByte()))
        return LocalCredential(credential, identifier, pair.privateKey, kind)
    }

    /** `02 || x` or `03 || x` for the uncompressed point [point] (SEC 1 v2 §2.3.3). */
    private fun compress(point: ByteArray): ByteArray {
        val length = (point.size - 1) / 2
        val prefix = if (point.last().toInt() and 1 == 1) 0x03 else 0x02
        return byteArrayOf(prefix.toByte()) + point.copyOfRange(1, 1 + length)
    }
}

/**
 * The default backend, holding P-256 and P-384 public keys to the letter of the `CryptoBackend` contract: it
 * refuses, and records in [refused], any that is not `04 || x || y` of the key's length. It counts the NIST
 * keys it checked.
 */
private class UncompressedOnlyBackend(private val inner: CryptoBackend = DefaultCryptoBackend()) :
    CryptoBackend by inner {
    val refused = mutableListOf<String>()

    var keyAgreements = 0
        private set

    var verifications = 0
        private set

    override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
        if (isNist(privateKey.type)) {
            keyAgreements++
            requireUncompressed("keyAgreement", privateKey.type, peerPublicKey)
        }
        return inner.keyAgreement(privateKey, peerPublicKey)
    }

    override suspend fun verify(
        algorithm: SignatureAlgorithm,
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (isNist(algorithm.keyType)) {
            verifications++
            requireUncompressed("verify", algorithm.keyType, publicKey)
        }
        return inner.verify(algorithm, publicKey, data, signature)
    }

    private fun isNist(type: KeyType) = type == KeyType.P256 || type == KeyType.P384

    private fun requireUncompressed(operation: String, type: KeyType, key: ByteArray) {
        if (key.size != type.publicKeyLength || key.firstOrNull() != UNCOMPRESSED) {
            refused += "$operation got a ${key.size}-byte $type key ${key.toHex()}"
            throw IllegalArgumentException("Not an uncompressed $type point")
        }
    }

    private companion object {
        const val UNCOMPRESSED: Byte = 0x04
    }
}
