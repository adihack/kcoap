package dev.kcoap.crypto.testsuite

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.testsuite.vectors.ML_KEM_512_INVALID_DK
import dev.kcoap.crypto.testsuite.vectors.ML_KEM_512_INVALID_DK_EK
import dev.kcoap.crypto.testsuite.vectors.ML_KEM_512_INVALID_EK
import dev.kcoap.crypto.testsuite.vectors.ML_KEM_ENCAPSULATION_VECTORS
import dev.kcoap.crypto.testsuite.vectors.ML_KEM_IMPLICIT_REJECTION_VECTORS
import dev.kcoap.crypto.testsuite.vectors.ML_KEM_KEYGEN_VECTORS
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FIPS 203 known-answer tests from NIST's ACVP vectors (github.com/usnistgov/ACVP-Server, commit 975de31e),
 * replayed through the public operations of any backend with ML-KEM: key generation from NIST's `d || z`,
 * encapsulation with NIST's `m`, decapsulation with NIST's keys, implicit rejection, and the input checks of
 * FIPS 203 §7.2 and §7.3.
 *
 * A backend's tests extend it with two factories, the second drawing every random byte from a script:
 *
 * ```
 * class MyMlKemTest : MlKemConformanceTest() {
 *     override fun createBackend(): CryptoBackend = MyBackend(DefaultCryptoBackend())
 *     override fun createBackend(random: ScriptedRandom): CryptoBackend =
 *         MyBackend(object : CryptoBackend by DefaultCryptoBackend() {
 *             override suspend fun randomBytes(size: Int): ByteArray = random.next(size)
 *         })
 * }
 * ```
 *
 * The private keys are the seed or the expanded decapsulation key of [KeyType] (`seedLength`,
 * `expandedPrivateKeyLength`), and the backend imports both with `importKeyPair`. Input that fails the FIPS 203
 * checks is rejected with an exception other than `UnsupportedAlgorithmException`. Parameter sets the backend
 * does not support (`supports(KemAlgorithm)`) are skipped, but it must support one.
 */
public abstract class MlKemConformanceTest {
    /** A new instance of the backend under test, with its own randomness. */
    protected abstract fun createBackend(): CryptoBackend

    /**
     * A new instance of the backend under test that draws every random byte from [random], exactly as many as
     * FIPS 203 prescribes: the 64 bytes `d || z` for ML-KEM.KeyGen (Algorithm 19), the 32 bytes `m` for
     * ML-KEM.Encaps (Algorithm 20).
     */
    protected abstract fun createBackend(random: ScriptedRandom): CryptoBackend

    /** FIPS 203 Algorithm 19 with NIST's `d` and `z`: the encapsulation key matches; the key is the seed or `dk`. */
    @Test
    public fun fips203KeyGenMatchesAcvpVectors(): TestResult = runTest {
        val supported = supportedParameterSets()
        for (vector in ML_KEM_KEYGEN_VECTORS.filter { algorithmOf(it.type) in supported }) {
            val seed = vector.d.hex() + vector.z.hex()
            val random = ScriptedRandom(seed)

            val pair = createBackend(random).generateKeyPair(vector.type)

            assertHexEquals(vector.ek, pair.publicKey, vector.name)
            assertHoldsEncoding(pair.privateKey, seed, vector.dk.hex(), vector.name)
            assertEquals(0, random.remaining, "${vector.name}: KeyGen draws exactly d || z")
        }
    }

    /**
     * The seed and NIST's expanded `dk` (FIPS 203 Algorithm 16) are the same key: both decapsulate a valid and a
     * modified ciphertext to the same secrets, so `s`, `H(ek)` and `z` all agree.
     */
    @Test
    public fun fips203SeedAndExpandedDecapsulationKeyAgree(): TestResult = runTest {
        val backend = createBackend()
        val supported = supportedParameterSets()
        for (vector in ML_KEM_KEYGEN_VECTORS.filter { algorithmOf(it.type) in supported }) {
            val seed = vector.d.hex() + vector.z.hex()
            val dk = vector.dk.hex()
            val fromSeed = backend.importKeyPair(vector.type, seed, vector.ek.hex())
            val expanded = backend.importKeyPair(vector.type, dk, vector.ek.hex())
            val encapsulation = backend.encapsulate(algorithmOf(vector.type), vector.ek.hex())

            for (ciphertext in listOf(encapsulation.ciphertext, encapsulation.ciphertext.flipped(0))) {
                assertContentEquals(
                    backend.decapsulate(fromSeed.privateKey, ciphertext),
                    backend.decapsulate(expanded.privateKey, ciphertext),
                    vector.name,
                )
            }
            val secret = backend.decapsulate(expanded.privateKey, encapsulation.ciphertext)
            assertContentEquals(encapsulation.sharedSecret(), secret, vector.name)
            assertHoldsEncoding(expanded.privateKey, null, vector.dk.hex(), vector.name)
            assertHexEquals(vector.d + vector.z, seed, "${vector.name}: import leaves the caller's seed alone")
            assertHexEquals(vector.dk, dk, "${vector.name}: import leaves the caller's dk alone")
        }
    }

    /** FIPS 203 Algorithm 20 with NIST's `m` gives NIST's `c` and `k`, and `dk` decapsulates `c` back to `k`. */
    @Test
    public fun fips203EncapsulationMatchesAcvpVectors(): TestResult = runTest {
        val backend = createBackend()
        val supported = supportedParameterSets()
        for (vector in ML_KEM_ENCAPSULATION_VECTORS.filter { it.algorithm in supported }) {
            val random = ScriptedRandom(vector.m.hex())

            val encapsulation = createBackend(random).encapsulate(vector.algorithm, vector.ek.hex())

            assertHexEquals(vector.c, encapsulation.ciphertext, vector.name)
            assertHexEquals(vector.k, encapsulation.sharedSecret(), vector.name)
            assertEquals(0, random.remaining, "${vector.name}: Encaps draws exactly m")
            val pair = backend.importKeyPair(vector.algorithm.keyType, vector.dk.hex(), vector.ek.hex())
            assertHexEquals(vector.k, backend.decapsulate(pair.privateKey, vector.c.hex()), vector.name)
        }
    }

    /** FIPS 203 Algorithm 18, lines 7-10: NIST's modified ciphertext decapsulates to `J(z || c)`, without an error. */
    @Test
    public fun fips203ImplicitRejectionMatchesAcvpVector(): TestResult = runTest {
        val backend = createBackend()
        val supported = supportedParameterSets()
        for (vector in ML_KEM_IMPLICIT_REJECTION_VECTORS.filter { it.algorithm in supported }) {
            val pair = backend.importKeyPair(vector.algorithm.keyType, vector.dk.hex(), vector.ek.hex())

            assertHexEquals(vector.k, backend.decapsulate(pair.privateKey, vector.c.hex()), vector.name)
            val other = backend.decapsulate(pair.privateKey, vector.c.hex().flipped(5))
            assertFalse(vector.k.hex().contentEquals(other), vector.name)
        }
    }

    /** FIPS 203 §7.2: an encapsulation key with coefficients of q or more fails the modulus check. */
    @Test
    public fun fips203ModulusCheckRejectsAcvpEncapsulationKey(): TestResult = runTest {
        if (KemAlgorithm.ML_KEM_512 !in supportedParameterSets()) return@runTest
        val backend = createBackend()
        assertRejects("encapsulating to an encapsulation key that fails the modulus check") {
            backend.encapsulate(KemAlgorithm.ML_KEM_512, ML_KEM_512_INVALID_EK.hex())
        }
    }

    /** FIPS 203 §7.3: a decapsulation key whose `H(ek)` was modified fails the hash check, on import and on use. */
    @Test
    public fun fips203HashCheckRejectsAcvpDecapsulationKey(): TestResult = runTest {
        if (KemAlgorithm.ML_KEM_512 !in supportedParameterSets()) return@runTest
        val backend = createBackend()
        assertRejects("importing a decapsulation key that fails the hash check") {
            backend.importKeyPair(KeyType.ML_KEM_512, ML_KEM_512_INVALID_DK.hex(), ML_KEM_512_INVALID_DK_EK.hex())
        }
        val stored = SoftwarePrivateKey(KeyType.ML_KEM_512, ML_KEM_512_INVALID_DK.hex())
        assertRejects("decapsulating with a stored key that fails the hash check") {
            backend.decapsulate(stored, ByteArray(KemAlgorithm.ML_KEM_512.ciphertextLength))
        }
    }

    /** Import refuses NIST's expanded `dk` with an encapsulation key it does not belong to. */
    @Test
    public fun importRejectsADecapsulationKeyOfAnotherEncapsulationKey(): TestResult = runTest {
        val backend = createBackend()
        val supported = supportedParameterSets()
        for (vector in ML_KEM_KEYGEN_VECTORS.filter { algorithmOf(it.type) in supported }) {
            val other = backend.generateKeyPair(vector.type)
            assertRejects("${vector.name}: importing dk with another encapsulation key") {
                backend.importKeyPair(vector.type, vector.dk.hex(), other.publicKey)
            }
            assertRejects("${vector.name}: importing the seed with another encapsulation key") {
                backend.importKeyPair(vector.type, vector.d.hex() + vector.z.hex(), other.publicKey)
            }
            other.privateKey.destroy()
        }
    }

    /** The parameter sets the backend supports; a backend that extends this suite supports at least one. */
    private suspend fun supportedParameterSets(): Set<KemAlgorithm> {
        val backend = createBackend()
        val supported = KemAlgorithm.entries.filter { backend.supports(it) }.toSet()
        assertTrue(supported.isNotEmpty(), "the backend supports no ML-KEM parameter set")
        return supported
    }

    private fun algorithmOf(type: KeyType): KemAlgorithm = KemAlgorithm.entries.single { it.keyType == type }
}
