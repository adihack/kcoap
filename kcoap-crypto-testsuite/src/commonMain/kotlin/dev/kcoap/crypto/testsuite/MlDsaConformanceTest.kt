package dev.kcoap.crypto.testsuite

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.testsuite.vectors.ML_DSA_KEYGEN_VECTORS
import dev.kcoap.crypto.testsuite.vectors.ML_DSA_SIGGEN_VECTORS
import dev.kcoap.crypto.testsuite.vectors.ML_DSA_SIGVER_VECTORS
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FIPS 204 known-answer tests from NIST's ACVP vectors (github.com/usnistgov/ACVP-Server, commit 975de31e),
 * replayed through the public operations of any backend with ML-DSA: key generation from NIST's `ξ`, NIST's
 * signatures byte for byte, NIST's verdicts, and the checks on imported keys. ML-DSA here is pure ML-DSA with an
 * empty context, as COSE uses it (draft-ietf-cose-dilithium).
 *
 * A backend's tests extend it with two factories, as for [MlKemConformanceTest]. The suite signs NIST's
 * deterministic vectors with the hedged signer and a scripted `rnd` of 32 zero bytes, which is what the
 * deterministic variant is (FIPS 204 §3.4), so the backend needs no deterministic mode.
 *
 * The private keys are the seed or the expanded key of [KeyType] (`seedLength`, `expandedPrivateKeyLength`),
 * and the backend imports both with `importKeyPair`. A key pair whose parts do not belong together, `tr`
 * included ([SignatureAlgorithm.trLength]), is rejected with an exception other than
 * `UnsupportedAlgorithmException`. Parameter sets the backend does not support are skipped, but it must
 * support one.
 */
public abstract class MlDsaConformanceTest {
    /** A new instance of the backend under test, with its own randomness. */
    protected abstract fun createBackend(): CryptoBackend

    /**
     * A new instance of the backend under test that draws every random byte from [random], exactly as many as
     * FIPS 204 prescribes: the 32 bytes `ξ` for ML-DSA.KeyGen (Algorithm 1), the 32 bytes `rnd` for each hedged
     * ML-DSA.Sign (Algorithm 2).
     */
    protected abstract fun createBackend(random: ScriptedRandom): CryptoBackend

    /** FIPS 204 Algorithm 1 with NIST's `ξ`: the public key matches, and the private key is the seed or `sk`. */
    @Test
    public fun fips204KeyGenMatchesAcvpVectors(): TestResult = runTest {
        val supported = supportedParameterSets()
        for (vector in ML_DSA_KEYGEN_VECTORS.filter { it.algorithm in supported }) {
            val random = ScriptedRandom(vector.seed.hex())

            val pair = createBackend(random).generateKeyPair(vector.algorithm.keyType)

            assertHexEquals(vector.pk, pair.publicKey, vector.name)
            assertHoldsEncoding(pair.privateKey, vector.seed.hex(), vector.sk.hex(), vector.name)
            assertEquals(0, random.remaining, "${vector.name}: KeyGen draws exactly ξ")
        }
    }

    /**
     * The seed and NIST's expanded `sk` (FIPS 204 Algorithm 24) are the same key: their deterministic signatures
     * are identical, so `K`, `tr`, `s1`, `s2` and `t0` all agree.
     */
    @Test
    public fun fips204SeedAndExpandedPrivateKeyAgree(): TestResult = runTest {
        val supported = supportedParameterSets()
        for (vector in ML_DSA_KEYGEN_VECTORS.filter { it.algorithm in supported }) {
            val random = ScriptedRandom(DETERMINISTIC_RND, DETERMINISTIC_RND)
            val backend = createBackend(random)
            val type = vector.algorithm.keyType
            val fromSeed = backend.importKeyPair(type, vector.seed.hex(), vector.pk.hex())
            val expanded = backend.importKeyPair(type, vector.sk.hex(), vector.pk.hex())
            val message = vector.name.encodeToByteArray()

            val signature = backend.sign(vector.algorithm, fromSeed.privateKey, message)

            assertContentEquals(signature, backend.sign(vector.algorithm, expanded.privateKey, message), vector.name)
            assertEquals(0, random.remaining, "${vector.name}: each signature draws exactly rnd")
            assertTrue(backend.verify(vector.algorithm, vector.pk.hex(), message, signature), vector.name)
            assertHoldsEncoding(expanded.privateKey, null, vector.sk.hex(), vector.name)
        }
    }

    /**
     * FIPS 204 Algorithm 2, pure with an empty context: NIST's deterministic signatures (`rnd = {0}^32`) and its
     * hedged signature (with NIST's `rnd`), byte for byte. Each one also verifies (Algorithm 3).
     */
    @Test
    public fun fips204SigGenMatchesAcvpVectors(): TestResult = runTest {
        val supported = supportedParameterSets()
        for (vector in ML_DSA_SIGGEN_VECTORS.filter { it.algorithm in supported }) {
            val random = ScriptedRandom(vector.rnd?.hex() ?: DETERMINISTIC_RND)
            val backend = createBackend(random)
            val key = backend.importKeyPair(vector.algorithm.keyType, vector.sk.hex(), vector.pk.hex())

            val signature = backend.sign(vector.algorithm, key.privateKey, vector.message.hex())

            assertHexEquals(vector.signature, signature, vector.name)
            assertEquals(0, random.remaining, "${vector.name}: Sign draws exactly rnd")
            assertTrue(backend.verify(vector.algorithm, vector.pk.hex(), vector.message.hex(), signature), vector.name)
        }
    }

    /** FIPS 204 Algorithm 3 reaches NIST's verdict. */
    @Test
    public fun fips204SigVerMatchesAcvpVectors(): TestResult = runTest {
        val backend = createBackend()
        val supported = supportedParameterSets()
        for (vector in ML_DSA_SIGVER_VECTORS.filter { it.algorithm in supported }) {
            val isValid = backend.verify(
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
    public fun fips204VerifyRejectsModifiedAndMalformedSignatures(): TestResult = runTest {
        val backend = createBackend()
        val supported = supportedParameterSets()
        for (vector in ML_DSA_SIGGEN_VECTORS.filter { it.algorithm in supported }) {
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
                assertFalse(backend.verify(vector.algorithm, pk, message, forged), vector.name)
            }
            assertFalse(backend.verify(vector.algorithm, pk, message.flipped(0), signature), vector.name)
        }
    }

    /**
     * Import takes NIST's seed and `sk` with their `pk`, leaves the caller's arrays alone, and refuses parts that
     * do not belong together: wrong lengths, a changed seed, `pk` or `sk`, and a changed `tr` (FIPS 204 Algorithm
     * 6, line 7), which leaves the public key the expanded key derives unchanged, so only a `tr` check catches it.
     */
    @Test
    public fun importChecksThatThePrivateKeyBelongsToThePublicKey(): TestResult = runTest {
        val backend = createBackend()
        val supported = supportedParameterSets()
        for (vector in ML_DSA_KEYGEN_VECTORS.filter { it.algorithm in supported }) {
            val type = vector.algorithm.keyType
            val (seed, pk, sk) = Triple(vector.seed.hex(), vector.pk.hex(), vector.sk.hex())
            // sk = ρ || K || tr || ...: tr starts after the 32-byte ρ and the 32-byte K (FIPS 204 Algorithm 24).
            val trOffset = 64
            val trLength = checkNotNull(vector.algorithm.trLength)

            backend.importKeyPair(type, seed, pk).privateKey.destroy()
            backend.importKeyPair(type, sk, pk).privateKey.destroy()

            assertHexEquals(vector.seed, seed, "${vector.name}: the caller's seed")
            assertHexEquals(vector.sk, sk, "${vector.name}: the caller's sk")
            val seedLength = checkNotNull(type.seedLength)
            val expandedLength = checkNotNull(type.expandedPrivateKeyLength)
            val refused = mapOf(
                "a short seed" to (ByteArray(seedLength - 1) to pk),
                "a short sk" to (sk.copyOf(expandedLength - 1) to pk),
                "a short pk" to (seed to pk.copyOf(type.publicKeyLength - 1)),
                "a changed seed" to (seed.flipped(0) to pk),
                "a changed pk" to (seed to pk.flipped(40)),
                "a changed sk" to (sk.flipped(0) to pk),
                "a changed tr" to (sk.flipped(trOffset + trLength - 1) to pk),
            )
            for ((what, keys) in refused) {
                assertRejects("${vector.name}: importing $what") {
                    backend.importKeyPair(type, keys.first, keys.second)
                }
            }
        }
    }

    /** The parameter sets the backend supports; a backend that extends this suite supports at least one. */
    private suspend fun supportedParameterSets(): Set<SignatureAlgorithm> {
        val backend = createBackend()
        val supported = ML_DSA.filter { backend.supports(it) }.toSet()
        assertTrue(supported.isNotEmpty(), "the backend supports no ML-DSA parameter set")
        return supported
    }

    private companion object {
        val ML_DSA = SignatureAlgorithm.entries.filter { it.trLength != null }

        /** `rnd = {0}^32`: hedged signing with it is the deterministic variant (FIPS 204 §3.4). */
        val DETERMINISTIC_RND = ByteArray(32)
    }
}
