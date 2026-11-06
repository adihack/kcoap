package dev.kcoap.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The private-key encodings of the post-quantum key types and the length of ML-DSA's `tr`, which every
 * backend and key format read from [KeyType] and [SignatureAlgorithm] instead of keeping their own.
 */
class PostQuantumKeyEncodingsTest {
    /**
     * FIPS 203 §8, Tables 2 and 3: `ek` is 384k + 32 bytes, `dk` 768k + 96 and the ciphertext 32(du·k + dv); the
     * seed `d || z` of Algorithm 19 is 64 bytes for every parameter set.
     */
    @Test
    fun fips203Table3() {
        // (k, du, dv) of ML-KEM-512, -768 and -1024 (FIPS 203 §8, Table 2).
        val shapes = mapOf(
            KemAlgorithm.ML_KEM_512 to Triple(2, 10, 4),
            KemAlgorithm.ML_KEM_768 to Triple(3, 10, 4),
            KemAlgorithm.ML_KEM_1024 to Triple(4, 11, 5),
        )
        for ((algorithm, shape) in shapes) {
            val (rank, du, dv) = shape
            val type = algorithm.keyType
            assertEquals(32 * (du * rank + dv), algorithm.ciphertextLength, algorithm.name)
            assertEquals(384 * rank + 32, type.publicKeyLength, algorithm.name)
            assertEquals(768 * rank + 96, type.expandedPrivateKeyLength, algorithm.name)
            assertEquals(64, type.seedLength, algorithm.name)
        }
        assertEquals(KemAlgorithm.entries.map { it.keyType }, KeyType.entries.filter { it.name.startsWith("ML_KEM") })
    }

    /**
     * FIPS 204 §4, Table 2: the sizes of `pk`, `sk` and the signature; the seed `ξ` of Algorithm 1 is 32 bytes and
     * `tr` (Algorithm 6, line 7) 64 for every parameter set. `sk = ρ || K || tr || s1 || s2 || t0` is
     * 32 + 32 + 64 + 32((ℓ + k)·bitlen(2η) + 13k) bytes (Algorithm 24).
     */
    @Test
    fun fips204Table2() {
        // (k, ℓ, bitlen(2η)) of ML-DSA-44, -65 and -87 (FIPS 204 §4, Table 1).
        val shapes = mapOf(
            SignatureAlgorithm.ML_DSA_44 to Triple(4, 4, 3),
            SignatureAlgorithm.ML_DSA_65 to Triple(6, 5, 4),
            SignatureAlgorithm.ML_DSA_87 to Triple(8, 7, 3),
        )
        val sizes = mapOf(
            SignatureAlgorithm.ML_DSA_44 to Triple(1312, 2560, 2420),
            SignatureAlgorithm.ML_DSA_65 to Triple(1952, 4032, 3309),
            SignatureAlgorithm.ML_DSA_87 to Triple(2592, 4896, 4627),
        )
        for ((algorithm, size) in sizes) {
            val (k, l, etaBits) = shapes.getValue(algorithm)
            val type = algorithm.keyType
            assertEquals(size.first, type.publicKeyLength, algorithm.name)
            assertEquals(size.second, type.expandedPrivateKeyLength, algorithm.name)
            assertEquals(32 + 32 + 64 + 32 * ((l + k) * etaBits + 13 * k), type.expandedPrivateKeyLength, algorithm.name)
            assertEquals(size.third, algorithm.signatureLength, algorithm.name)
            assertEquals(32, type.seedLength, algorithm.name)
            assertEquals(64, algorithm.trLength, algorithm.name)
        }
    }

    /** The classical key types have one private-key encoding, and only ML-DSA has a `tr`. */
    @Test
    fun onlyThePostQuantumTypesHaveSeedsAndExpandedKeys() {
        val postQuantum = KemAlgorithm.entries.map { it.keyType } +
            SignatureAlgorithm.entries.filter { it.trLength != null }.map { it.keyType }
        for (type in KeyType.entries - postQuantum.toSet()) {
            assertNull(type.seedLength, type.name)
            assertNull(type.expandedPrivateKeyLength, type.name)
        }
        assertEquals(
            listOf(SignatureAlgorithm.ML_DSA_44, SignatureAlgorithm.ML_DSA_65, SignatureAlgorithm.ML_DSA_87),
            SignatureAlgorithm.entries.filter { it.trLength != null },
        )
        assertEquals(6, KeyType.entries.count { it.seedLength != null })
    }
}
