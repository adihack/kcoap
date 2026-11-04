package com.example.kcoap.crypto

/** Hash functions used by OSCORE and EDHOC. COSE ids from the IANA "COSE Algorithms" registry. */
public enum class HashAlgorithm(public val coseId: Int, public val outputLength: Int) {
    SHA_256(coseId = -16, outputLength = 32),
    SHA_384(coseId = -43, outputLength = 48),

    // SHAKE256 (-45, EDHOC suite 25) is intentionally absent: no default-backend platform exposes it.
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

/** Curves for ephemeral and static Diffie-Hellman (COSE "Elliptic Curves" registry). */
public enum class EcdhCurve(public val coseId: Int, public val keyLength: Int) {
    P_256(coseId = 1, keyLength = 32),
    P_384(coseId = 2, keyLength = 48),
    X25519(coseId = 4, keyLength = 32),
    X448(coseId = 5, keyLength = 56),
}

/** Signature algorithms (COSE "Algorithms" registry). */
public enum class SignatureAlgorithm(public val coseId: Int) {
    ES256(coseId = -7),
    ES384(coseId = -35),
    EDDSA(coseId = -8),
}
