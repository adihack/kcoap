package dev.kcoap.crypto

/**
 * The single seam between protocol code and cryptography, in the spirit of libedhoc's crypto vtable:
 * protocol modules never call a platform API directly, so a backend can be replaced by one for a
 * secure element, Android KeyStore / Secure Enclave keys, a post-quantum provider, or a deterministic
 * test double.
 *
 * All operations suspend, because some platforms (WebCrypto) only offer asynchronous cryptography.
 *
 * Only the symmetric primitives are required. Asymmetric operations default to throwing
 * [UnsupportedAlgorithmException], so an OSCORE-only deployment can use a minimal backend. Backends
 * override what they support; see `DefaultCryptoBackend` and `BouncyCastleCryptoBackend`.
 */
public interface CryptoBackend {
    public suspend fun randomBytes(size: Int): ByteArray

    /**
     * A digest of [HashAlgorithm.outputLength] bytes. Backends without a native SHAKE256 use the
     * common-code [Shake256].
     */
    public suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray

    /** HMAC with a SHA-2 hash. Not defined for SHAKE256, which uses KMAC instead. */
    public suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray

    /** Raw AES block encryption (16-byte blocks) with a 128, 192 or 256-bit key. */
    public suspend fun aesBlockCipher(key: ByteArray): AesBlockCipher

    /**
     * An AEAD for [algorithm]. AES-CCM is implemented in common code on top of [aesBlockCipher]
     * because CryptoKit, CommonCrypto, WebCrypto and the stock JDK do not offer it. Backends override
     * this for what their platform provides natively (AES-GCM, ChaCha20/Poly1305).
     */
    public suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
        if (!algorithm.isAesCcm) throw UnsupportedAlgorithmException(algorithm.name)
        require(key.size == algorithm.keyLength) {
            "$algorithm needs a ${algorithm.keyLength}-byte key, got ${key.size}"
        }
        return AesCcm(aesBlockCipher(key), tagLength = algorithm.tagLength, nonceLength = algorithm.nonceLength)
    }

    /** A fresh key pair. Used for EDHOC ephemeral keys and for provisioning static keys. */
    public suspend fun generateKeyPair(type: KeyType): KeyPair = throw UnsupportedAlgorithmException(type.name)

    /**
     * Wraps existing key material, for static keys from configuration and fixed keys in test vectors.
     * The default keeps the key in memory as a [SoftwarePrivateKey].
     */
    public suspend fun importKeyPair(type: KeyType, privateKey: ByteArray, publicKey: ByteArray): KeyPair {
        require(publicKey.size == type.publicKeyLength) {
            "$type public key must be ${type.publicKeyLength} bytes, got ${publicKey.size}"
        }
        return KeyPair(publicKey, SoftwarePrivateKey(type, privateKey))
    }

    /**
     * Diffie-Hellman: the shared secret of [privateKey] and [peerPublicKey]. For P-256 and P-384 the
     * peer key may also be a compressed SEC1 point; the result is the x-coordinate either way.
     */
    public suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
        throw UnsupportedAlgorithmException(privateKey.type.name)

    public suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray =
        throw UnsupportedAlgorithmException(algorithm.name)

    /** Returns false for a wrong signature; throws only for unusable input such as a malformed key. */
    public suspend fun verify(
        algorithm: SignatureAlgorithm,
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = throw UnsupportedAlgorithmException(algorithm.name)

    public suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation =
        throw UnsupportedAlgorithmException(algorithm.name)

    public suspend fun decapsulate(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray =
        throw UnsupportedAlgorithmException(privateKey.type.name)
}

/** Forward AES block permutation. CCM, CTR and CBC-MAC only ever need the encrypt direction. */
public fun interface AesBlockCipher {
    /** Encrypts exactly one 16-byte block and returns a new 16-byte array. */
    public suspend fun encryptBlock(block: ByteArray): ByteArray
}

/** AEAD with the COSE convention: ciphertext output is `ciphertext || tag`. */
public interface Aead {
    public val tagLength: Int
    public val nonceLength: Int

    public suspend fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** @throws AeadAuthenticationException when the tag does not verify; no plaintext is released. */
    public suspend fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

public class AeadAuthenticationException : Exception("AEAD authentication failed")

public class UnsupportedAlgorithmException(algorithm: String) : Exception("Unsupported algorithm: $algorithm")
