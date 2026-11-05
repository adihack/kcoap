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
 *
 * ## Capabilities
 *
 * Platforms differ in what their cryptography offers (WebCrypto has no ChaCha20/Poly1305, a secure
 * element may have only P-256), so protocol code asks before it commits: the `supports` members say
 * whether an operation would run, and EDHOC offers only the cipher suites they allow. The contract
 * (docs/adr/0001-crypto-capabilities.md): `supports(x)` is false exactly when the operations on `x`
 * would throw [UnsupportedAlgorithmException]. Other failures, such as malformed input, are not a
 * missing capability.
 *
 * The defaults find out by trying: they run each operation once on throwaway keys and count only
 * [UnsupportedAlgorithmException] as a no. They remember nothing, so every call probes again and costs key
 * pairs. A software backend therefore memoizes the answers per instance, as `DefaultCryptoBackend` does,
 * and a hardware or secure-element backend overrides the members with static answers, because a probe
 * there could create a persistent key or prompt for a biometric. An answer must not change during the
 * backend's lifetime. Callers cannot rely on the memo: they ask once and keep the answers, as EDHOC keeps
 * them per configuration and backend instead of asking per session.
 *
 * A decorator (`CryptoBackend by delegate`) forwards `supports` to its delegate, so one that adds
 * algorithms, as `BouncyCastleCryptoBackend` adds ML-KEM and ML-DSA, must also override the matching
 * `supports` members.
 */
public interface CryptoBackend {
    public suspend fun randomBytes(size: Int): ByteArray

    /**
     * A digest of [HashAlgorithm.outputLength] bytes.
     *
     * For [HashAlgorithm.SHAKE256] that is the first 64 bytes of SHAKE256 (FIPS 202 §6.2), the hash of EDHOC
     * suite 25 and of the post-quantum suites (RFC 9528 §3.6, draft-ietf-lake-pqsuites). Every backend
     * answers it, since common code always can: one without a native SHAKE256 returns
     * `Shake256.digest(data, algorithm.outputLength)` ([Shake256]) rather than throwing
     * [UnsupportedAlgorithmException]. Its MAC is not here: KMAC256 (NIST SP 800-185), which EDHOC derives
     * keys with for SHAKE256 suites (RFC 9528 §4.1), is common code on Keccak ([Kmac256]) and never goes
     * through the backend, just as [Hkdf] is common code on [hmac].
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
     * Diffie-Hellman: the shared secret of [privateKey] and [peerPublicKey], in the raw encoding of the
     * key's [KeyType]. For P-256 and P-384 that is an uncompressed SEC1 point `04 || x || y` of
     * [KeyType.publicKeyLength] bytes, and the result is the x-coordinate of the shared point (SEC 1 v2
     * §3.3.1). Callers expand compressed points with [Sec1] first; a backend may accept them too, as
     * `DefaultCryptoBackend` does, but need not.
     */
    public suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
        throw UnsupportedAlgorithmException(privateKey.type.name)

    public suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray =
        throw UnsupportedAlgorithmException(algorithm.name)

    /**
     * Whether [signature] over [data] verifies under [publicKey], in the raw encoding of
     * [SignatureAlgorithm.keyType]: an uncompressed SEC1 point for ES256 and ES384, as for [keyAgreement].
     * Returns false for a wrong signature; throws only for unusable input such as a malformed key.
     */
    public suspend fun verify(
        algorithm: SignatureAlgorithm,
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = throw UnsupportedAlgorithmException(algorithm.name)

    /**
     * Encapsulates a fresh shared secret to the encapsulation key [publicKey] (FIPS 203 §7.2). The caller
     * owns the result and [destroys][KemEncapsulation.destroy] it once it has the secret.
     */
    public suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation =
        throw UnsupportedAlgorithmException(algorithm.name)

    public suspend fun decapsulate(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray =
        throw UnsupportedAlgorithmException(privateKey.type.name)

    /**
     * Whether [aead] runs [algorithm]. The default asks by building it under an all-zero key and
     * encrypting an empty message.
     */
    public suspend fun supports(algorithm: AeadAlgorithm): Boolean = Probe.aead(this, algorithm)

    /**
     * Whether this backend generates and uses key pairs of [type]: [generateKeyPair], and for the
     * Diffie-Hellman types (X25519, X448, P-256, P-384) also [keyAgreement]. Signing is asked for separately,
     * with `supports(SignatureAlgorithm)`. The default asks by generating a key pair and, for a
     * Diffie-Hellman type, agreeing on a secret with the pair's own public key.
     */
    public suspend fun supports(type: KeyType): Boolean = Probe.keyType(this, type)

    /**
     * Whether this backend [sign]s and [verify]s with [algorithm]. The default asks by generating a key
     * pair, signing an empty message and verifying the signature.
     */
    public suspend fun supports(algorithm: SignatureAlgorithm): Boolean = Probe.signature(this, algorithm)

    /**
     * Whether this backend [encapsulate]s and [decapsulate]s with [algorithm] (FIPS 203). The default asks
     * by generating a key pair, encapsulating to it and decapsulating the ciphertext.
     */
    public suspend fun supports(algorithm: KemAlgorithm): Boolean = Probe.kem(this, algorithm)

    /**
     * Whether this backend computes the [digest] of [algorithm], and for a SHA-2 hash also its [hmac],
     * which HKDF (RFC 5869) needs. The default asks by hashing and authenticating an empty message.
     */
    public suspend fun supports(algorithm: HashAlgorithm): Boolean = Probe.hash(this, algorithm)
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

/**
 * The backend does not provide [algorithm] where it runs. A backend reports a missing capability this
 * way and no other, and the `supports` members of [CryptoBackend] answer false exactly where an
 * operation would throw it.
 */
public class UnsupportedAlgorithmException(
    /** The missing algorithm, key type or curve, as the backend names it. */
    public val algorithm: String,
) : Exception("Unsupported algorithm: $algorithm")
