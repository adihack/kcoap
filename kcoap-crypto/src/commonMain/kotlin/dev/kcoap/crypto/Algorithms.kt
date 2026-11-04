package dev.kcoap.crypto

/** Hash functions used by OSCORE and EDHOC. COSE ids from the IANA "COSE Algorithms" registry. */
public enum class HashAlgorithm(
    public val coseId: Int,
    /** Output length in bytes; for SHAKE256 the length EDHOC uses (RFC 9528 §3.6). */
    public val outputLength: Int,
    /** SHAKE256 is an extendable-output function: EDHOC derives keys from it with KMAC, not HMAC. */
    public val isXof: Boolean = false,
) {
    SHA_256(coseId = -16, outputLength = 32),
    SHA_384(coseId = -43, outputLength = 48),
    SHAKE256(coseId = -45, outputLength = 64, isXof = true),
    ;

    public companion object {
        public fun fromCoseId(id: Int): HashAlgorithm? = entries.firstOrNull { it.coseId == id }
    }
}

/**
 * AEAD algorithms (RFC 9053 §4, RFC 8439 for ChaCha20/Poly1305).
 *
 * AES-CCM naming in COSE is `AES-CCM-<L bits>-<tag bits>-<key bits>`: L=16 bits means a 13-byte nonce,
 * L=64 bits means a 7-byte nonce.
 */
public enum class AeadAlgorithm(
    public val coseId: Int,
    public val keyLength: Int,
    public val nonceLength: Int,
    public val tagLength: Int,
) {
    AES_CCM_16_64_128(coseId = 10, keyLength = 16, nonceLength = 13, tagLength = 8),
    AES_CCM_16_64_256(coseId = 11, keyLength = 32, nonceLength = 13, tagLength = 8),
    AES_CCM_64_64_128(coseId = 12, keyLength = 16, nonceLength = 7, tagLength = 8),
    AES_CCM_64_64_256(coseId = 13, keyLength = 32, nonceLength = 7, tagLength = 8),
    AES_CCM_16_128_128(coseId = 30, keyLength = 16, nonceLength = 13, tagLength = 16),
    AES_CCM_16_128_256(coseId = 31, keyLength = 32, nonceLength = 13, tagLength = 16),
    AES_CCM_64_128_128(coseId = 32, keyLength = 16, nonceLength = 7, tagLength = 16),
    AES_CCM_64_128_256(coseId = 33, keyLength = 32, nonceLength = 7, tagLength = 16),
    A128GCM(coseId = 1, keyLength = 16, nonceLength = 12, tagLength = 16),
    A192GCM(coseId = 2, keyLength = 24, nonceLength = 12, tagLength = 16),
    A256GCM(coseId = 3, keyLength = 32, nonceLength = 12, tagLength = 16),
    CHACHA20_POLY1305(coseId = 24, keyLength = 32, nonceLength = 12, tagLength = 16),
    ;

    public val isAesCcm: Boolean get() = coseId in 10..13 || coseId in 30..33

    public companion object {
        public fun fromCoseId(id: Int): AeadAlgorithm? = entries.firstOrNull { it.coseId == id }
    }
}

/**
 * Kinds of asymmetric keys, with their raw encodings. Every public key crossing the [CryptoBackend]
 * boundary uses the encoding documented here; protocol code converts to and from wire formats.
 */
public enum class KeyType(
    /** Raw public key length in bytes. */
    public val publicKeyLength: Int,
) {
    /** RFC 7748; 32-byte u-coordinate. */
    X25519(32),

    /** RFC 7748; 56-byte u-coordinate. */
    X448(56),

    /** secp256r1 for ECDH and ECDSA; public keys are uncompressed SEC1 points `04 || x || y`. */
    P256(65),

    /** secp384r1; uncompressed SEC1 points. */
    P384(97),

    /** RFC 8032; 32-byte public key. */
    ED25519(32),

    /** FIPS 203 encapsulation keys. */
    ML_KEM_512(800),
    ML_KEM_768(1184),
    ML_KEM_1024(1568),

    /** FIPS 204 public keys. */
    ML_DSA_44(1312),
    ML_DSA_65(1952),
    ML_DSA_87(2592),
}

/** Curves for ephemeral and static Diffie-Hellman (COSE "Elliptic Curves" registry). */
public enum class EcdhCurve(public val coseId: Int, public val keyLength: Int, public val keyType: KeyType) {
    P_256(coseId = 1, keyLength = 32, keyType = KeyType.P256),
    P_384(coseId = 2, keyLength = 48, keyType = KeyType.P384),
    X25519(coseId = 4, keyLength = 32, keyType = KeyType.X25519),
    X448(coseId = 5, keyLength = 56, keyType = KeyType.X448),
    ;

    public companion object {
        public fun fromCoseId(id: Int): EcdhCurve? = entries.firstOrNull { it.coseId == id }
    }
}

/** Signature algorithms (COSE "Algorithms" registry). ML-DSA ids from draft-ietf-cose-dilithium. */
public enum class SignatureAlgorithm(
    public val coseId: Int,
    public val keyType: KeyType,
    /** Signature length in bytes. */
    public val signatureLength: Int,
) {
    /** ECDSA P-256 with SHA-256; signatures are raw `r || s` as in COSE. */
    ES256(coseId = -7, keyType = KeyType.P256, signatureLength = 64),

    /** ECDSA P-384 with SHA-384; raw `r || s`. */
    ES384(coseId = -35, keyType = KeyType.P384, signatureLength = 96),

    /** Ed25519 (COSE "EdDSA" with an OKP Ed25519 key). */
    EDDSA(coseId = -8, keyType = KeyType.ED25519, signatureLength = 64),

    ML_DSA_44(coseId = -48, keyType = KeyType.ML_DSA_44, signatureLength = 2420),
    ML_DSA_65(coseId = -49, keyType = KeyType.ML_DSA_65, signatureLength = 3309),
    ML_DSA_87(coseId = -50, keyType = KeyType.ML_DSA_87, signatureLength = 4627),
    ;

    public companion object {
        public fun fromCoseId(id: Int): SignatureAlgorithm? = entries.firstOrNull { it.coseId == id }
    }
}

/** Key-encapsulation mechanisms (FIPS 203), used by the post-quantum EDHOC cipher suites. */
public enum class KemAlgorithm(public val keyType: KeyType, public val ciphertextLength: Int) {
    ML_KEM_512(KeyType.ML_KEM_512, ciphertextLength = 768),
    ML_KEM_768(KeyType.ML_KEM_768, ciphertextLength = 1088),
    ML_KEM_1024(KeyType.ML_KEM_1024, ciphertextLength = 1568),
    ;

    /** All ML-KEM parameter sets produce a 32-byte shared secret. */
    public val sharedSecretLength: Int get() = 32
}
