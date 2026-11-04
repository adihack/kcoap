package dev.kcoap.edhoc

import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.EcdhCurve
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.SignatureAlgorithm

/**
 * EDHOC cipher suites, RFC 9528 §10.2 (IANA "EDHOC Cipher Suites").
 *
 * Tuple order: EDHOC AEAD, EDHOC hash, EDHOC MAC length, EDHOC key exchange, EDHOC signature,
 * application AEAD, application hash.
 */
public enum class CipherSuite(
    public val value: Int,
    public val edhocAead: AeadAlgorithm,
    /** `null` for SHAKE256 (suite 25), which no default-backend platform provides. */
    public val edhocHash: HashAlgorithm?,
    public val macLength: Int,
    public val keyExchange: EcdhCurve,
    public val signature: SignatureAlgorithm,
    public val appAead: AeadAlgorithm,
    public val appHash: HashAlgorithm?,
) {
    SUITE_0(0, AeadAlgorithm.AES_CCM_16_64_128, HashAlgorithm.SHA_256, 8, EcdhCurve.X25519, SignatureAlgorithm.EDDSA, AeadAlgorithm.AES_CCM_16_64_128, HashAlgorithm.SHA_256),
    SUITE_1(1, AeadAlgorithm.AES_CCM_16_128_128, HashAlgorithm.SHA_256, 16, EcdhCurve.X25519, SignatureAlgorithm.EDDSA, AeadAlgorithm.AES_CCM_16_64_128, HashAlgorithm.SHA_256),
    SUITE_2(2, AeadAlgorithm.AES_CCM_16_64_128, HashAlgorithm.SHA_256, 8, EcdhCurve.P_256, SignatureAlgorithm.ES256, AeadAlgorithm.AES_CCM_16_64_128, HashAlgorithm.SHA_256),
    SUITE_3(3, AeadAlgorithm.AES_CCM_16_128_128, HashAlgorithm.SHA_256, 16, EcdhCurve.P_256, SignatureAlgorithm.ES256, AeadAlgorithm.AES_CCM_16_64_128, HashAlgorithm.SHA_256),
    SUITE_4(4, AeadAlgorithm.CHACHA20_POLY1305, HashAlgorithm.SHA_256, 16, EcdhCurve.X25519, SignatureAlgorithm.EDDSA, AeadAlgorithm.CHACHA20_POLY1305, HashAlgorithm.SHA_256),
    SUITE_5(5, AeadAlgorithm.CHACHA20_POLY1305, HashAlgorithm.SHA_256, 16, EcdhCurve.P_256, SignatureAlgorithm.ES256, AeadAlgorithm.CHACHA20_POLY1305, HashAlgorithm.SHA_256),
    SUITE_6(6, AeadAlgorithm.A128GCM, HashAlgorithm.SHA_256, 16, EcdhCurve.X25519, SignatureAlgorithm.ES256, AeadAlgorithm.A128GCM, HashAlgorithm.SHA_256),
    SUITE_24(24, AeadAlgorithm.A256GCM, HashAlgorithm.SHA_384, 16, EcdhCurve.P_384, SignatureAlgorithm.ES384, AeadAlgorithm.A256GCM, HashAlgorithm.SHA_384),
    SUITE_25(25, AeadAlgorithm.CHACHA20_POLY1305, null, 16, EcdhCurve.X448, SignatureAlgorithm.EDDSA, AeadAlgorithm.CHACHA20_POLY1305, null),
    ;

    /** RFC 9528 §8: implementations MUST support cipher suites 2 and 3. */
    public val isMandatoryToImplement: Boolean get() = value == 2 || value == 3

    public companion object {
        public fun fromValue(value: Int): CipherSuite? = entries.firstOrNull { it.value == value }
    }
}
