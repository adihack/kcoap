package com.example.kcoap.crypto

/**
 * The single seam between protocol code and platform cryptography, in the spirit of libedhoc's crypto
 * vtable: protocol modules never call a platform API directly, so a backend can be swapped for a secure
 * element, Android KeyStore / Secure Enclave keys, or a deterministic test double.
 *
 * Key agreement and signatures are added together with the EDHOC module.
 */
public interface CryptoBackend {
    public fun randomBytes(size: Int): ByteArray

    public fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray

    public fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray

    /** Raw AES block encryption (16-byte blocks) with the given 128/192/256-bit key. */
    public fun aesBlockCipher(key: ByteArray): AesBlockCipher

    /**
     * Returns an AEAD for [algorithm]. AES-CCM is implemented in common code on top of [aesBlockCipher]
     * because CryptoKit, CommonCrypto and the stock JDK/Android providers do not offer it. Backends override
     * this for the algorithms their platform provides natively (AES-GCM, ChaCha20/Poly1305).
     */
    public fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
        if (algorithm.isAesCcm) {
            require(key.size == algorithm.keyLength) {
                "$algorithm needs a ${algorithm.keyLength}-byte key, got ${key.size}"
            }
            return AesCcm(aesBlockCipher(key), tagLength = algorithm.tagLength, nonceLength = algorithm.nonceLength)
        }
        throw UnsupportedAlgorithmException(algorithm.name)
    }
}

/** Forward AES block permutation. CCM, CTR and CBC-MAC only ever need the encrypt direction. */
public fun interface AesBlockCipher {
    /** Encrypts exactly one 16-byte block and returns a new 16-byte array. */
    public fun encryptBlock(block: ByteArray): ByteArray
}

/** AEAD with the COSE convention: ciphertext output is `ciphertext || tag`. */
public interface Aead {
    public val tagLength: Int
    public val nonceLength: Int

    public fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** @throws AeadAuthenticationException when the tag does not verify; no plaintext is released. */
    public fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

public class AeadAuthenticationException : Exception("AEAD authentication failed")

public class UnsupportedAlgorithmException(algorithm: String) : Exception("Unsupported algorithm: $algorithm")
