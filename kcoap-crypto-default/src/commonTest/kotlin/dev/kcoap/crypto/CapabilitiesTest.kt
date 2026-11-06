package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.backend.capabilityCall
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the default backend reports it can do, which must be the same answer on every platform. */
class CapabilitiesTest {
    private val backend = DefaultCryptoBackend()

    /** The key types and signatures of EDHOC suites 0 to 3, 6 and 24 (RFC 9528 Table 6). */
    @Test
    fun theClassicalKeyTypesAndSignaturesAreSupportedEverywhere() = runTest {
        for (type in listOf(KeyType.X25519, KeyType.ED25519, KeyType.P256, KeyType.P384)) {
            assertTrue(backend.supports(type), type.name)
        }
        for (algorithm in listOf(SignatureAlgorithm.EDDSA, SignatureAlgorithm.ES256, SignatureAlgorithm.ES384)) {
            assertTrue(backend.supports(algorithm), algorithm.name)
        }
    }

    /** The hashes of EDHOC and OSCORE: SHA-2 with its HMAC on the platform, SHAKE256 in common code. */
    @Test
    fun everyHashIsSupportedEverywhere() = runTest {
        for (algorithm in HashAlgorithm.entries) assertTrue(backend.supports(algorithm), algorithm.name)
    }

    @Test
    fun postQuantumAlgorithmsAreNotInTheDefaultBackend() = runTest {
        assertFalse(backend.supports(KeyType.ML_KEM_512))
        assertFalse(backend.supports(SignatureAlgorithm.ML_DSA_44))
        for (algorithm in KemAlgorithm.entries) assertFalse(backend.supports(algorithm), algorithm.name)
    }

    /**
     * Whatever a platform answers for a curve it does not know (JCA and OpenSSL throw their own
     * exceptions, WebCrypto rejects with NotSupportedError), the backend reports it the same way.
     */
    @Test
    fun aKeyTypeThePlatformLacksIsReportedAsUnsupported() = runTest {
        val ecdh = CryptographyProvider.Default.get(ECDH)
        assertFailsWith<UnsupportedAlgorithmException> {
            capabilityCall("no-such-curve") { ecdh.keyPairGenerator(EC.Curve("kcoap-no-such-curve")).generateKey() }
        }
    }
}
