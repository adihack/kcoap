package dev.kcoap.crypto.bc

import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KemEncapsulation
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SoftwarePrivateKey
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters

/**
 * The ML-KEM parameter sets (FIPS 203 §8) on Bouncy Castle's lightweight API.
 *
 * Every function here is deterministic: the caller draws the randomness FIPS 203 asks for (`d || z` for key
 * generation, `m` for encapsulation) and passes it in, which is what lets NIST's vectors replay through the
 * public backend.
 *
 * Private keys come in the two encodings of [KeyType], told apart by their length:
 *  - the 64-byte seed `d || z` of ML-KEM.KeyGen (FIPS 203 Algorithm 19); ML-KEM.KeyGen_internal (Algorithm 16)
 *    expands it on every use;
 *  - the expanded decapsulation key `dk = dk_PKE || ek || H(ek) || z` of Algorithm 16, 768k + 96 bytes.
 */
internal enum class MlKem(val algorithm: KemAlgorithm, private val parameters: MLKEMParameters) {
    ML_KEM_512(KemAlgorithm.ML_KEM_512, MLKEMParameters.ml_kem_512),
    ML_KEM_768(KemAlgorithm.ML_KEM_768, MLKEMParameters.ml_kem_768),
    ML_KEM_1024(KemAlgorithm.ML_KEM_1024, MLKEMParameters.ml_kem_1024),
    ;

    val keyType: KeyType get() = algorithm.keyType

    /** `d || z`, two 32-byte random values (FIPS 203 Algorithm 19, lines 1-2): [KeyType.seedLength]. */
    val seedLength: Int = checkNotNull(algorithm.keyType.seedLength) { "$algorithm has no seed length" }

    /** The expanded decapsulation key `dk` (FIPS 203 §8, Table 3): [KeyType.expandedPrivateKeyLength]. */
    val decapsulationKeyLength: Int =
        checkNotNull(algorithm.keyType.expandedPrivateKeyLength) { "$algorithm has no expanded key length" }

    /** ML-KEM.KeyGen (FIPS 203 Algorithm 19) for an already drawn [seed] `d || z`, which becomes the private key. */
    fun generateKeyPair(seed: ByteArray): KeyPair {
        val key = privateKey(seed)
        try {
            return KeyPair(key.publicKey, SoftwarePrivateKey(keyType, seed))
        } finally {
            key.destroy()
        }
    }

    /**
     * Wraps a seed or an expanded decapsulation key with its encapsulation key, after the input checks of FIPS 203
     * §7.2 (modulus check of `ek`) and §7.3 (hash check of `dk`), and a check that both belong together.
     */
    fun importKeyPair(privateKey: ByteArray, publicKey: ByteArray): KeyPair {
        privateKey(privateKey, publicKey(publicKey)).destroy()
        return KeyPair(publicKey, SoftwarePrivateKey(keyType, privateKey))
    }

    /** ML-KEM.Encaps (FIPS 203 Algorithm 20) for an already drawn 32-byte [m]: ML-KEM.Encaps_internal (Algorithm 17). */
    fun encapsulate(publicKey: ByteArray, m: ByteArray): KemEncapsulation {
        val result = MLKEMGenerator.internalGenerateEncapsulated(publicKey(publicKey), m)
        try {
            return KemEncapsulation(ciphertext = result.encapsulation, sharedSecret = result.secret)
        } finally {
            result.destroy()
        }
    }

    /**
     * ML-KEM.Decaps (FIPS 203 Algorithm 21). A ciphertext of the right length that was tampered with does not fail:
     * implicit rejection (Algorithm 18, lines 7-10) returns the pseudorandom `J(z || c)` instead.
     */
    fun decapsulate(privateKey: ByteArray, ciphertext: ByteArray): ByteArray {
        // FIPS 203 §7.3, ciphertext type check.
        require(ciphertext.size == algorithm.ciphertextLength) {
            "$algorithm ciphertext must be ${algorithm.ciphertextLength} bytes, got ${ciphertext.size}"
        }
        val key = privateKey(privateKey)
        try {
            return MLKEMExtractor(key).extractSecret(ciphertext)
        } finally {
            key.destroy()
        }
    }

    /** Bouncy Castle performs the modulus check of FIPS 203 §7.2 when it decodes the key. */
    private fun publicKey(encoded: ByteArray): MLKEMPublicKeyParameters {
        require(encoded.size == keyType.publicKeyLength) {
            "$keyType encapsulation key must be ${keyType.publicKeyLength} bytes, got ${encoded.size}"
        }
        return MLKEMPublicKeyParameters(parameters, encoded)
    }

    /**
     * Bouncy Castle expands a seed, performs the hash check of FIPS 203 §7.3 on the expanded key, and, given
     * [publicKey], rejects a private key that belongs to a different encapsulation key. It gets its own copy of
     * the encoding, because `destroy()` clears the arrays the parameters hold.
     */
    private fun privateKey(encoded: ByteArray, publicKey: MLKEMPublicKeyParameters? = null): MLKEMPrivateKeyParameters {
        require(encoded.size == seedLength || encoded.size == decapsulationKeyLength) {
            "$keyType private key must be $seedLength (seed) or $decapsulationKeyLength (expanded) bytes, " +
                "got ${encoded.size}"
        }
        return MLKEMPrivateKeyParameters(parameters, encoded.copyOf(), publicKey)
    }

    companion object {
        /** `m`, the encapsulation randomness (FIPS 203 Algorithm 20, line 1). */
        const val MESSAGE_LENGTH: Int = 32

        fun of(type: KeyType): MlKem? = entries.firstOrNull { it.keyType == type }

        fun of(algorithm: KemAlgorithm): MlKem? = entries.firstOrNull { it.algorithm == algorithm }
    }
}
