package dev.kcoap.crypto.bc

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.bc.vectors.ML_DSA_KEYGEN_VECTORS
import dev.kcoap.crypto.bc.vectors.ML_DSA_SIGGEN_VECTORS
import dev.kcoap.crypto.bc.vectors.ML_DSA_SIGVER_VECTORS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FIPS 204 known-answer tests from NIST's ACVP vectors, replayed through the public backend. */
class MlDsaKnownAnswerTest {
    private val deterministic = BouncyCastleCryptoBackend(DefaultCryptoBackend(), isSigningDeterministic = true)

    /** FIPS 204 Algorithm 1 with NIST's `ξ`: the public key matches, the seed is the private key. */
    @Test
    fun fips204KeyGenMatchesAcvpVectors() = runTest {
        for (vector in ML_DSA_KEYGEN_VECTORS) {
            val random = ScriptedRandomBackend(vector.seed.hex())

            val pair = BouncyCastleCryptoBackend(random).generateKeyPair(vector.algorithm.keyType)

            assertHexEquals(vector.pk, pair.publicKey, vector.name)
            assertContentEquals(vector.seed.hex(), pair.privateKey.encoded, vector.name)
            assertEquals(0, random.remaining, vector.name)
        }
    }

    /**
     * The seed and NIST's expanded `sk` (FIPS 204 Algorithm 24) are the same key: their deterministic signatures are
     * identical, so `K`, `tr`, `s1`, `s2` and `t0` all agree.
     */
    @Test
    fun fips204SeedAndExpandedPrivateKeyAgree() = runTest {
        for (vector in ML_DSA_KEYGEN_VECTORS) {
            val type = vector.algorithm.keyType
            val fromSeed = deterministic.importKeyPair(type, vector.seed.hex(), vector.pk.hex())
            val expanded = deterministic.importKeyPair(type, vector.sk.hex(), vector.pk.hex())
            val message = vector.name.encodeToByteArray()

            val signature = deterministic.sign(vector.algorithm, fromSeed.privateKey, message)

            val fromExpanded = deterministic.sign(vector.algorithm, expanded.privateKey, message)
            assertContentEquals(signature, fromExpanded, vector.name)
            assertTrue(deterministic.verify(vector.algorithm, vector.pk.hex(), message, signature), vector.name)
            assertContentEquals(vector.sk.hex(), expanded.privateKey.encoded, vector.name)
        }
    }

    /**
     * FIPS 204 Algorithm 2, pure with an empty context: NIST's deterministic signatures (`rnd = {0}^32`) and hedged
     * signature (with NIST's `rnd`), byte for byte. Each one also verifies (Algorithm 3).
     */
    @Test
    fun fips204SigGenMatchesAcvpVectors() = runTest {
        for (vector in ML_DSA_SIGGEN_VECTORS) {
            val rnd = vector.rnd?.hex() ?: ByteArray(0)
            val random = ScriptedRandomBackend(rnd)
            val backend = BouncyCastleCryptoBackend(random, isSigningDeterministic = vector.rnd == null)
            val key = backend.importKeyPair(vector.algorithm.keyType, vector.sk.hex(), vector.pk.hex())

            val signature = backend.sign(vector.algorithm, key.privateKey, vector.message.hex())

            assertHexEquals(vector.signature, signature, vector.name)
            assertEquals(0, random.remaining, vector.name)
            assertTrue(backend.verify(vector.algorithm, vector.pk.hex(), vector.message.hex(), signature), vector.name)
        }
    }

    /** FIPS 204 Algorithm 3 reaches NIST's verdict. */
    @Test
    fun fips204SigVerMatchesAcvpVectors() = runTest {
        for (vector in ML_DSA_SIGVER_VECTORS) {
            val isValid = deterministic.verify(
                vector.algorithm,
                publicKey = vector.pk.hex(),
                data = vector.message.hex(),
                signature = vector.signature.hex(),
            )
            assertEquals(vector.isValid, isValid, vector.name)
        }
    }

    /**
     * Changes to NIST's valid signatures are rejected with false, never an exception: the commitment `c~`, the
     * response `z`, an extra or a missing byte, and a hint encoding that HintBitUnpack (FIPS 204 Algorithm 21)
     * refuses because its last index count exceeds ω.
     */
    @Test
    fun fips204VerifyRejectsModifiedAndMalformedSignatures() = runTest {
        for (vector in ML_DSA_SIGGEN_VECTORS) {
            val pk = vector.pk.hex()
            val message = vector.message.hex()
            val signature = vector.signature.hex()
            val malformedHints = signature.copyOf().also { it[it.lastIndex] = 0xFF.toByte() }

            for (forged in listOf(
                signature.flipped(0),
                signature.flipped(signature.size / 2),
                signature + byteArrayOf(0),
                signature.copyOf(signature.size - 1),
                malformedHints,
            )) {
                assertFalse(deterministic.verify(vector.algorithm, pk, message, forged), vector.name)
            }
            assertFalse(deterministic.verify(vector.algorithm, pk, message.flipped(0), signature), vector.name)
        }
    }
}
