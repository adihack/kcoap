package dev.kcoap.edhoc

import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.EcdhCurve
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CipherSuiteTest {
    /**
     * The "Array" column of RFC 9528 §10.2 as COSE ids:
     * [EDHOC AEAD, EDHOC hash, MAC length, key exchange, signature, app AEAD, app hash].
     */
    private val registry = mapOf(
        0 to listOf(10, -16, 8, 4, -8, 10, -16),
        1 to listOf(30, -16, 16, 4, -8, 10, -16),
        2 to listOf(10, -16, 8, 1, -7, 10, -16),
        3 to listOf(30, -16, 16, 1, -7, 10, -16),
        4 to listOf(24, -16, 16, 4, -8, 24, -16),
        5 to listOf(24, -16, 16, 1, -7, 24, -16),
        6 to listOf(1, -16, 16, 4, -7, 1, -16),
        24 to listOf(3, -43, 16, 2, -35, 3, -43),
        25 to listOf(24, -45, 16, 5, -8, 24, -45),
    )

    @Test
    fun suitesMatchIanaRegistry() {
        assertEquals(registry.keys, CipherSuite.registered.map { it.value }.toSet())
        for (suite in CipherSuite.registered) {
            val curve = (suite.keyExchange as KeyExchangeAlgorithm.DiffieHellman).curve
            val actual = listOf(
                suite.edhocAead.coseId,
                suite.edhocHash.coseId,
                suite.macLength,
                curve.coseId,
                suite.signature.coseId,
                suite.appAead.coseId,
                suite.appHash.coseId,
            )
            assertEquals(registry.getValue(suite.value), actual, "suite ${suite.value}")
            assertEquals(suite, CipherSuite.fromValue(suite.value))
            assertTrue(suite.keyExchange.supportsStaticDiffieHellman)
            assertFalse(suite.isPrivateUse)
        }
    }

    @Test
    fun mandatorySuitesAreTwoAndThree() {
        assertEquals(setOf(2, 3), CipherSuite.registered.filter { it.isMandatoryToImplement }.map { it.value }.toSet())
    }

    /** The mandatory suites run on every platform; the default backend runs no post-quantum suite. */
    @Test
    fun theDefaultBackendRunsTheMandatorySuitesEverywhere() = runTest {
        val backend = DefaultCryptoBackend()
        assertTrue(CipherSuite.SUITE_2.isSupportedBy(backend))
        assertTrue(CipherSuite.SUITE_3.isSupportedBy(backend))
        assertFalse(CipherSuite.SUITE_PQC_1.isSupportedBy(backend), "no ML-KEM or ML-DSA")
        assertFalse(CipherSuite.SUITE_25.isSupportedBy(backend), "not implemented")
    }

    /** A platform without ChaCha20/Poly1305, as WebCrypto is, loses exactly suites 4 and 5. */
    @Test
    fun aSuiteNeedsEveryOneOfItsAlgorithms() = runTest {
        val default = DefaultCryptoBackend()
        val withoutChaCha = object : CryptoBackend by default {
            override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead =
                if (algorithm == AeadAlgorithm.CHACHA20_POLY1305) {
                    throw UnsupportedAlgorithmException(algorithm.name)
                } else {
                    default.aead(algorithm, key)
                }
        }
        val supported = CipherSuite.registered.filter { it.isSupportedBy(withoutChaCha) }.map { it.value }
        assertEquals(listOf(0, 1, 2, 3, 6, 24), supported)
    }

    /** RFC 9528 Table 6 lists suite 25, but its EdDSA uses Ed448, which no backend has. */
    @Test
    fun suite25IsRegisteredButNotImplemented() {
        assertFalse(CipherSuite.SUITE_25.isImplemented)
        assertTrue(CipherSuite.registered.filter { it != CipherSuite.SUITE_25 }.all { it.isImplemented })
    }

    /**
     * libedhoc `EDHOC_CIPHER_SUITE_PQC_1`: value -24, no static DH, G_X 800 bytes (ML-KEM-512 encapsulation
     * key), G_Y 768 bytes (ciphertext), ML-DSA-44 signatures of 2420 bytes, AES-CCM key 16 / tag 16 / IV 13,
     * SHAKE256 hash length 64, MAC length 16. The EDHOC algorithms equal draft-ietf-lake-pqsuites-02 TBD1:
     * [30, -45, 16, ML-KEM-512, -48, 10, -16].
     */
    @Test
    fun postQuantumSuiteMatchesLibedhoc() {
        val suite = CipherSuite.SUITE_PQC_1
        assertEquals(-24, suite.value)
        assertTrue(suite.isPrivateUse)
        assertEquals(KeyExchangeAlgorithm.Kem(KemAlgorithm.ML_KEM_512), suite.keyExchange)
        assertFalse(suite.keyExchange.supportsStaticDiffieHellman)
        assertEquals(KeyType.ML_KEM_512, suite.keyExchange.ephemeralKeyType)
        assertEquals(800, suite.keyExchange.initiatorShareLength)
        assertEquals(768, suite.keyExchange.responderShareLength)
        assertEquals(2420, suite.signature.signatureLength)
        val aead = suite.edhocAead
        assertEquals(listOf(16, 16, 13), listOf(aead.keyLength, aead.tagLength, aead.nonceLength))
        assertEquals(64, suite.edhocHash.outputLength)
        assertEquals(16, suite.macLength)
        assertEquals(
            listOf(30, -45, 16, -48, 10, -16),
            listOf(
                aead.coseId, suite.edhocHash.coseId, suite.macLength, suite.signature.coseId, suite.appAead.coseId,
                suite.appHash.coseId,
            ),
        )
        assertEquals(suite, CipherSuite.fromValue(-24))
        assertNull(CipherSuite.fromValue(-23))
        assertEquals("ML_KEM_512", suite.keyExchange.toString())
        assertTrue(
            suite.toString().startsWith("CipherSuite(-24: AES_CCM_16_128_128, SHAKE256, 16, ML_KEM_512, ML_DSA_44"),
        )
    }

    @Test
    fun diffieHellmanShareLengthsAreCoordinateLengths() {
        assertEquals(32, CipherSuite.SUITE_2.keyExchange.initiatorShareLength)
        assertEquals(48, CipherSuite.SUITE_24.keyExchange.responderShareLength)
        assertEquals(KeyType.X25519, CipherSuite.SUITE_0.keyExchange.ephemeralKeyType)
        assertEquals("P_256", CipherSuite.SUITE_2.keyExchange.toString())
    }

    /** RFC 9528 §9.3: MAC length and AEAD tag at least 8 bytes, hash output at least 256 bits. */
    @Test
    fun privateSuitesAreValidated() {
        val p256 = KeyExchangeAlgorithm.DiffieHellman(EcdhCurve.P_256)
        fun private(macLength: Int) = CipherSuite(
            -22, AeadAlgorithm.A128GCM, HashAlgorithm.SHA_256, macLength, p256,
            SignatureAlgorithm.ES256, AeadAlgorithm.A128GCM, HashAlgorithm.SHA_256,
        )
        val custom = private(16)
        assertTrue(custom.isImplemented)
        assertTrue(custom.isPrivateUse)
        assertEquals(custom, private(16))
        assertEquals(custom.hashCode(), private(16).hashCode())
        assertNotEquals(custom, private(8))
        assertNotEquals<Any>(custom, "suite")
        assertFailsWith<IllegalArgumentException> { private(4) }
    }

    @Test
    fun methodsAndCompatibility() {
        assertEquals(listOf(0, 1, 2, 3), EdhocMethod.entries.map { it.value })
        assertEquals(EdhocMethod.STATIC_DH_SIGNATURE, EdhocMethod.fromValue(2))
        assertNull(EdhocMethod.fromValue(4))
        assertFalse(EdhocMethod.SIGNATURE_SIGNATURE.requiresDiffieHellman)
        assertTrue(EdhocMethod.SIGNATURE_STATIC_DH.requiresDiffieHellman)
        assertTrue(EdhocMethod.SIGNATURE_SIGNATURE.isCompatibleWith(CipherSuite.SUITE_PQC_1))
        for (method in EdhocMethod.entries.drop(1)) {
            assertFalse(method.isCompatibleWith(CipherSuite.SUITE_PQC_1), method.name)
            assertTrue(method.isCompatibleWith(CipherSuite.SUITE_2), method.name)
        }
        val method2 = EdhocMethod.STATIC_DH_SIGNATURE
        assertEquals(AuthenticationKind.STATIC_DH, method2.authenticationOf(EdhocRole.INITIATOR))
        assertEquals(AuthenticationKind.SIGNATURE, method2.authenticationOf(EdhocRole.RESPONDER))
    }
}
