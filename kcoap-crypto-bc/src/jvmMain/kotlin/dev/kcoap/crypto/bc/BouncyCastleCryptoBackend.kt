package dev.kcoap.crypto.bc

import dev.kcoap.common.wipe
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KemEncapsulation
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend

/**
 * [CryptoBackend] with the post-quantum algorithms of the EDHOC PQ cipher suites (draft-ietf-lake-pqsuites):
 * ML-KEM-512/768/1024 (FIPS 203) for the ephemeral key exchange and ML-DSA-44/65/87 (FIPS 204) for signatures,
 * on Bouncy Castle's lightweight API. libedhoc's private suite -24 uses ML-KEM-512 and ML-DSA-44. JVM and
 * Android only.
 *
 * Everything else (hashes, HMAC, AEADs, ECDH, EdDSA, ECDSA, randomness) is delegated to [classical]. The ML-KEM
 * and ML-DSA randomness also comes from `classical.randomBytes`, so a backend with fixed random bytes replays
 * NIST's known-answer vectors through this class.
 *
 * Encodings, chosen so keys move unchanged to and from other implementations such as libedhoc:
 *  - Public keys are the FIPS 203 encapsulation key `ek` and the FIPS 204 public key `pk`.
 *  - Generated private keys are [SoftwarePrivateKey]s holding the seed: the 64 bytes `d || z` of ML-KEM.KeyGen
 *    (FIPS 203 Algorithm 19) or the 32 bytes `ξ` of ML-DSA.KeyGen (FIPS 204 Algorithm 1). Seeds are small and
 *    determine the whole key; the expanded key is re-derived from the seed for every operation.
 *  - [importKeyPair] accepts the seed or the expanded FIPS encoding (`dk` of 768k + 96 bytes, `sk` of 2560, 4032
 *    or 4896 bytes), told apart by length, and keeps the private key in the encoding it was given.
 *
 * ML-DSA is pure ML-DSA (not HashML-DSA) with an empty context string, as COSE uses it (draft-ietf-cose-dilithium).
 *
 * The `supports` members answer for ML-KEM and ML-DSA without probing, since Bouncy Castle always has them,
 * and ask [classical] about everything else (docs/adr/0001-crypto-capabilities.md).
 *
 * @param classical the backend for every algorithm that is not ML-KEM or ML-DSA, and for randomness.
 * @param isSigningDeterministic whether ML-DSA signs with the deterministic variant (`rnd = {0}^32`, FIPS 204
 *   §3.4) instead of the default hedged one. Deterministic signatures are reproducible, which known-answer
 *   tests need; hedged ones resist fault and side-channel attacks better.
 */
public class BouncyCastleCryptoBackend(
    private val classical: CryptoBackend = DefaultCryptoBackend(),
    private val isSigningDeterministic: Boolean = false,
) : CryptoBackend by classical {

    /** True for the ML-KEM and ML-DSA key types; [classical]'s answer for the others. */
    override suspend fun supports(type: KeyType): Boolean =
        MlKem.of(type) != null || MlDsa.of(type) != null || classical.supports(type)

    /** True for ML-DSA-44/65/87; [classical]'s answer for the others. */
    override suspend fun supports(algorithm: SignatureAlgorithm): Boolean =
        MlDsa.of(algorithm) != null || classical.supports(algorithm)

    /** True for ML-KEM-512/768/1024; [classical]'s answer for any other KEM. */
    override suspend fun supports(algorithm: KemAlgorithm): Boolean =
        MlKem.of(algorithm) != null || classical.supports(algorithm)

    /** ML-KEM.KeyGen (FIPS 203 Algorithm 19) or ML-DSA.KeyGen (FIPS 204 Algorithm 1), with the seed as private key. */
    override suspend fun generateKeyPair(type: KeyType): KeyPair {
        MlKem.of(type)?.let { kem -> return withRandomBytes(MlKem.SEED_LENGTH) { seed -> kem.generateKeyPair(seed) } }
        MlDsa.of(type)?.let { dsa -> return withRandomBytes(MlDsa.SEED_LENGTH) { seed -> dsa.generateKeyPair(seed) } }
        return classical.generateKeyPair(type)
    }

    /**
     * For ML-KEM and ML-DSA, [privateKey] is either the seed or the expanded FIPS encoding (see the class
     * documentation), and must belong to [publicKey].
     *
     * @throws IllegalArgumentException for a wrong length, an encapsulation key that fails the modulus check
     *   (FIPS 203 §7.2), a decapsulation key that fails the hash check (FIPS 203 §7.3), or a private key that
     *   does not belong to [publicKey].
     */
    override suspend fun importKeyPair(type: KeyType, privateKey: ByteArray, publicKey: ByteArray): KeyPair {
        MlKem.of(type)?.let { kem -> return kem.importKeyPair(privateKey, publicKey) }
        MlDsa.of(type)?.let { dsa -> return dsa.importKeyPair(privateKey, publicKey) }
        return classical.importKeyPair(type, privateKey, publicKey)
    }

    /** ML-DSA.Sign (FIPS 204 Algorithm 2), hedged unless the backend was built with [isSigningDeterministic]. */
    override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray {
        val dsa = MlDsa.of(algorithm) ?: return classical.sign(algorithm, privateKey, data)
        require(privateKey.type == algorithm.keyType) {
            "$algorithm needs a ${algorithm.keyType} key, got ${privateKey.type}"
        }
        return privateKey.withMaterial { material ->
            if (isSigningDeterministic) {
                dsa.sign(material, data, rnd = null)
            } else {
                withRandomBytes(MlDsa.RND_LENGTH) { rnd -> dsa.sign(material, data, rnd) }
            }
        }
    }

    /**
     * ML-DSA.Verify (FIPS 204 Algorithm 3). Returns false for a wrong or malformed signature.
     *
     * @throws IllegalArgumentException when [publicKey] does not have the length of the algorithm's key type.
     */
    override suspend fun verify(
        algorithm: SignatureAlgorithm,
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val dsa = MlDsa.of(algorithm) ?: return classical.verify(algorithm, publicKey, data, signature)
        return dsa.verify(publicKey, data, signature)
    }

    /**
     * ML-KEM.Encaps (FIPS 203 Algorithm 20).
     *
     * @throws IllegalArgumentException when [publicKey] fails the type or modulus check (FIPS 203 §7.2).
     */
    override suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation {
        val kem = MlKem.of(algorithm) ?: return classical.encapsulate(algorithm, publicKey)
        return withRandomBytes(MlKem.MESSAGE_LENGTH) { m -> kem.encapsulate(publicKey, m) }
    }

    /**
     * ML-KEM.Decaps (FIPS 203 Algorithm 21). A ciphertext that was tampered with yields a different, pseudorandom
     * secret rather than an error (implicit rejection), so the failure surfaces when the derived keys are used.
     *
     * @throws IllegalArgumentException when [ciphertext] has the wrong length or the key fails the checks of
     *   FIPS 203 §7.3.
     */
    override suspend fun decapsulate(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray {
        val kem = MlKem.of(privateKey.type) ?: return classical.decapsulate(privateKey, ciphertext)
        return privateKey.withMaterial { material -> kem.decapsulate(material, ciphertext) }
    }

    /**
     * Draws [size] bytes from [classical] for [block] and wipes them afterwards. A random source that returns
     * fewer or more bytes than asked is broken, and no key or signature may come from it.
     */
    private suspend inline fun <T> withRandomBytes(size: Int, block: (ByteArray) -> T): T {
        val bytes = classical.randomBytes(size)
        try {
            check(bytes.size == size) { "The random source returned ${bytes.size} bytes instead of $size" }
            return block(bytes)
        } finally {
            bytes.wipe()
        }
    }

    /** Runs [block] on a copy of the raw key and wipes the copy afterwards. */
    private inline fun <T> PrivateKey.withMaterial(block: (ByteArray) -> T): T {
        val software = this as? SoftwarePrivateKey
            ?: throw IllegalArgumentException("${this::class.simpleName} was not created by a software backend")
        val material = software.encoded()
        try {
            return block(material)
        } finally {
            material.wipe()
        }
    }
}
