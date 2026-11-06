package dev.kcoap.crypto.bc

import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.Shake256
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.SoftwarePrivateKey
import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.signers.MLDSASigner
import java.security.SecureRandom

/**
 * The ML-DSA parameter sets (FIPS 204 §4) on Bouncy Castle's lightweight API: pure ML-DSA, not HashML-DSA, with
 * the empty context string, as COSE uses it (draft-ietf-cose-dilithium).
 *
 * Every function here is deterministic: the caller draws the randomness FIPS 204 asks for (`ξ` for key
 * generation, `rnd` for hedged signing) and passes it in, which is what lets NIST's vectors replay through the
 * public backend.
 *
 * Private keys come in the two encodings of [KeyType], told apart by their length:
 *  - the 32-byte seed `ξ` of ML-DSA.KeyGen (FIPS 204 Algorithm 1); ML-DSA.KeyGen_internal (Algorithm 6)
 *    expands it on every use;
 *  - the expanded private key `sk = ρ || K || tr || s1 || s2 || t0` of skEncode (Algorithm 24).
 */
internal enum class MlDsa(val algorithm: SignatureAlgorithm, private val parameters: MLDSAParameters) {
    ML_DSA_44(SignatureAlgorithm.ML_DSA_44, MLDSAParameters.ml_dsa_44),
    ML_DSA_65(SignatureAlgorithm.ML_DSA_65, MLDSAParameters.ml_dsa_65),
    ML_DSA_87(SignatureAlgorithm.ML_DSA_87, MLDSAParameters.ml_dsa_87),
    ;

    val keyType: KeyType get() = algorithm.keyType

    /** `ξ` (FIPS 204 Algorithm 1, line 1): [KeyType.seedLength]. */
    val seedLength: Int = checkNotNull(algorithm.keyType.seedLength) { "$algorithm has no seed length" }

    /** The expanded private key `sk` (FIPS 204 §4, Table 2): [KeyType.expandedPrivateKeyLength]. */
    val expandedPrivateKeyLength: Int =
        checkNotNull(algorithm.keyType.expandedPrivateKeyLength) { "$algorithm has no expanded key length" }

    /** `tr = H(pk, 64)` (FIPS 204 Algorithm 6, line 7): [SignatureAlgorithm.trLength]. */
    private val trLength: Int = checkNotNull(algorithm.trLength) { "$algorithm has no tr length" }

    /** ML-DSA.KeyGen (FIPS 204 Algorithm 1) for an already drawn [seed] `ξ`, which becomes the private key. */
    fun generateKeyPair(seed: ByteArray): KeyPair {
        val key = privateKey(seed)
        try {
            return KeyPair(key.publicKey, SoftwarePrivateKey(keyType, seed))
        } finally {
            key.destroy()
        }
    }

    /**
     * Wraps a seed or an expanded private key with its public key, after checking that both belong together:
     * `pk = ρ || t1` must be the key the private key derives, and `tr` must be `H(pk, 64)` (FIPS 204 Algorithm 6,
     * lines 6-7). Bouncy Castle recomputes `t1` from an expanded key but takes `tr` as stored, and a wrong `tr`
     * would only show up later, as signatures nobody can verify.
     */
    fun importKeyPair(privateKey: ByteArray, publicKey: ByteArray): KeyPair {
        requirePublicKeyLength(publicKey)
        val key = privateKey(privateKey)
        try {
            val belongs = key.publicKey.contentEquals(publicKey) &&
                key.tr.contentEquals(Shake256.digest(publicKey, trLength))
            require(belongs) { "$keyType private key does not belong to the public key" }
        } finally {
            key.destroy()
        }
        return KeyPair(publicKey, SoftwarePrivateKey(keyType, privateKey))
    }

    /**
     * ML-DSA.Sign (FIPS 204 Algorithm 2) with an empty context. [rnd] is the 32-byte randomness for
     * hedged signing, or null for the deterministic variant, which uses `rnd = {0}^32` (FIPS 204 §3.4).
     */
    fun sign(privateKey: ByteArray, data: ByteArray, rnd: ByteArray?): ByteArray {
        val key = privateKey(privateKey)
        try {
            val signer = MLDSASigner()
            // Without a SecureRandom, Bouncy Castle signs deterministically.
            signer.init(true, if (rnd == null) key else ParametersWithRandom(key, PresetRandom(rnd)))
            signer.update(data, 0, data.size)
            return signer.generateSignature()
        } finally {
            key.destroy()
        }
    }

    /**
     * ML-DSA.Verify (FIPS 204 Algorithm 3) with an empty context. Bouncy Castle returns false, never throws, for
     * a signature that is wrong or malformed (wrong length, hints out of order or too many, FIPS 204 Algorithm 21).
     */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        requirePublicKeyLength(publicKey)
        val verifier = MLDSASigner()
        verifier.init(false, MLDSAPublicKeyParameters(parameters, publicKey))
        verifier.update(data, 0, data.size)
        return verifier.verifySignature(signature)
    }

    private fun requirePublicKeyLength(publicKey: ByteArray) {
        require(publicKey.size == keyType.publicKeyLength) {
            "$keyType public key must be ${keyType.publicKeyLength} bytes, got ${publicKey.size}"
        }
    }

    /**
     * Bouncy Castle keeps a seed by reference and clears it on `destroy()`, so it gets its own copy; otherwise
     * destroying the parameters would wipe the caller's array.
     */
    private fun privateKey(encoded: ByteArray): MLDSAPrivateKeyParameters {
        require(encoded.size == seedLength || encoded.size == expandedPrivateKeyLength) {
            "$keyType private key must be $seedLength (seed) or $expandedPrivateKeyLength (expanded) bytes, " +
                "got ${encoded.size}"
        }
        return MLDSAPrivateKeyParameters(parameters, encoded.copyOf())
    }

    /**
     * Hands Bouncy Castle's signer the `rnd` the backend already drew, because its API only takes a [SecureRandom].
     * It serves exactly those bytes; the signer asks once per signature.
     */
    private class PresetRandom(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(output: ByteArray) {
            check(output.size == bytes.size) { "Expected a request for ${bytes.size} random bytes, got ${output.size}" }
            bytes.copyInto(output)
        }
    }

    companion object {
        /** `rnd` for hedged signing (FIPS 204 Algorithm 2). */
        const val RND_LENGTH: Int = 32

        fun of(type: KeyType): MlDsa? = entries.firstOrNull { it.keyType == type }

        fun of(algorithm: SignatureAlgorithm): MlDsa? = entries.firstOrNull { it.algorithm == algorithm }
    }
}
