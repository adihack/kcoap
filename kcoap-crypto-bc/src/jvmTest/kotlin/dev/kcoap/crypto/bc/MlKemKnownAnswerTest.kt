package dev.kcoap.crypto.bc

import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.bc.vectors.ML_KEM_512_INVALID_DK
import dev.kcoap.crypto.bc.vectors.ML_KEM_512_INVALID_DK_EK
import dev.kcoap.crypto.bc.vectors.ML_KEM_512_INVALID_EK
import dev.kcoap.crypto.bc.vectors.ML_KEM_ENCAPSULATION_VECTORS
import dev.kcoap.crypto.bc.vectors.ML_KEM_IMPLICIT_REJECTION_VECTORS
import dev.kcoap.crypto.bc.vectors.ML_KEM_KEYGEN_VECTORS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** FIPS 203 known-answer tests from NIST's ACVP vectors, replayed through the public backend. */
class MlKemKnownAnswerTest {
    private val backend = BouncyCastleCryptoBackend(DefaultCryptoBackend())

    /** FIPS 203 Algorithm 19 with NIST's `d` and `z`: the encapsulation key matches, the seed is the private key. */
    @Test
    fun fips203KeyGenMatchesAcvpVectors() = runTest {
        for (vector in ML_KEM_KEYGEN_VECTORS) {
            val seed = vector.d.hex() + vector.z.hex()
            val random = ScriptedRandomBackend(seed)

            val pair = BouncyCastleCryptoBackend(random).generateKeyPair(vector.type)

            assertHexEquals(vector.ek, pair.publicKey, vector.name)
            assertContentEquals(seed, pair.privateKey.encoded, vector.name)
            assertEquals(0, random.remaining, vector.name)
        }
    }

    /**
     * The seed and NIST's expanded `dk` (FIPS 203 Algorithm 16) are the same key: both decapsulate a valid and a
     * modified ciphertext to the same secrets, so `s`, `H(ek)` and `z` all agree.
     */
    @Test
    fun fips203SeedAndExpandedDecapsulationKeyAgree() = runTest {
        for (vector in ML_KEM_KEYGEN_VECTORS) {
            val algorithm = KemAlgorithm.entries.single { it.keyType == vector.type }
            val fromSeed = backend.importKeyPair(vector.type, vector.d.hex() + vector.z.hex(), vector.ek.hex())
            val expanded = backend.importKeyPair(vector.type, vector.dk.hex(), vector.ek.hex())
            val encapsulation = backend.encapsulate(algorithm, vector.ek.hex())

            for (ciphertext in listOf(encapsulation.ciphertext, encapsulation.ciphertext.flipped(0))) {
                assertContentEquals(
                    backend.decapsulate(fromSeed.privateKey, ciphertext),
                    backend.decapsulate(expanded.privateKey, ciphertext),
                    vector.name,
                )
            }
            val secret = backend.decapsulate(expanded.privateKey, encapsulation.ciphertext)
            assertContentEquals(encapsulation.sharedSecret(), secret, vector.name)
            assertContentEquals(vector.dk.hex(), expanded.privateKey.encoded, vector.name)
        }
    }

    /** FIPS 203 Algorithm 20 with NIST's `m` gives NIST's `c` and `k`, and `dk` decapsulates `c` back to `k`. */
    @Test
    fun fips203EncapsulationMatchesAcvpVectors() = runTest {
        for (vector in ML_KEM_ENCAPSULATION_VECTORS) {
            val random = ScriptedRandomBackend(vector.m.hex())
            val scripted = BouncyCastleCryptoBackend(random)

            val encapsulation = scripted.encapsulate(vector.algorithm, vector.ek.hex())

            assertHexEquals(vector.c, encapsulation.ciphertext, vector.name)
            assertHexEquals(vector.k, encapsulation.sharedSecret(), vector.name)
            assertEquals(0, random.remaining, vector.name)
            val pair = backend.importKeyPair(vector.algorithm.keyType, vector.dk.hex(), vector.ek.hex())
            assertHexEquals(vector.k, backend.decapsulate(pair.privateKey, vector.c.hex()), vector.name)
        }
    }

    /** FIPS 203 Algorithm 18, lines 7-10: NIST's modified ciphertext decapsulates to `J(z || c)`, without an error. */
    @Test
    fun fips203ImplicitRejectionMatchesAcvpVector() = runTest {
        for (vector in ML_KEM_IMPLICIT_REJECTION_VECTORS) {
            val pair = backend.importKeyPair(vector.algorithm.keyType, vector.dk.hex(), vector.ek.hex())

            assertHexEquals(vector.k, backend.decapsulate(pair.privateKey, vector.c.hex()), vector.name)
            assertFalse(vector.k.hex().contentEquals(backend.decapsulate(pair.privateKey, vector.c.hex().flipped(5))))
        }
    }

    /** FIPS 203 §7.2: an encapsulation key with coefficients of q or more fails the modulus check. */
    @Test
    fun fips203ModulusCheckRejectsAcvpEncapsulationKey() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backend.encapsulate(KemAlgorithm.ML_KEM_512, ML_KEM_512_INVALID_EK.hex())
        }
    }

    /** FIPS 203 §7.3: a decapsulation key whose `H(ek)` was modified fails the hash check, on import and on use. */
    @Test
    fun fips203HashCheckRejectsAcvpDecapsulationKey() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backend.importKeyPair(KeyType.ML_KEM_512, ML_KEM_512_INVALID_DK.hex(), ML_KEM_512_INVALID_DK_EK.hex())
        }
        val stored = SoftwarePrivateKey(KeyType.ML_KEM_512, ML_KEM_512_INVALID_DK.hex())
        assertFailsWith<IllegalArgumentException> {
            backend.decapsulate(stored, ByteArray(KemAlgorithm.ML_KEM_512.ciphertextLength))
        }
    }
}
