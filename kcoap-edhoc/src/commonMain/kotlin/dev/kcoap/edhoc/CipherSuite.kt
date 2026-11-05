package dev.kcoap.edhoc

import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.AeadAlgorithm.A128GCM
import dev.kcoap.crypto.AeadAlgorithm.A256GCM
import dev.kcoap.crypto.AeadAlgorithm.AES_CCM_16_128_128
import dev.kcoap.crypto.AeadAlgorithm.AES_CCM_16_64_128
import dev.kcoap.crypto.AeadAlgorithm.CHACHA20_POLY1305
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.EcdhCurve
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.HashAlgorithm.SHAKE256
import dev.kcoap.crypto.HashAlgorithm.SHA_256
import dev.kcoap.crypto.HashAlgorithm.SHA_384
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm

/**
 * The EDHOC key exchange algorithm of a cipher suite (RFC 9528 §3.6).
 *
 * RFC 9528 specifies the key exchange as elliptic-curve Diffie-Hellman, and notes that method 0 also works
 * with a key-encapsulation mechanism (RFC 9528 §9.4). draft-ietf-lake-pqsuites §3 makes that precise:
 * the Initiator sends its encapsulation key as G_X, the Responder encapsulates and sends the ciphertext
 * as G_Y, and G_XY is the KEM shared secret. KEMs cannot replace static Diffie-Hellman authentication,
 * so a KEM suite only works with [EdhocMethod.SIGNATURE_SIGNATURE].
 */
public sealed class KeyExchangeAlgorithm {
    /** The type of the ephemeral key pair the Initiator generates for G_X. */
    public abstract val ephemeralKeyType: KeyType

    /** Length of G_X in message_1, in bytes. */
    public abstract val initiatorShareLength: Int

    /** Length of G_Y at the start of G_Y_CIPHERTEXT_2 in message_2, in bytes. */
    public abstract val responderShareLength: Int

    /**
     * Whether the algorithm is a Diffie-Hellman function that static-DH authentication (methods 1–3)
     * can use: the "Supports DH/NIKE" column that draft-ietf-lake-pqsuites §6.4 adds to the registry.
     */
    public abstract val supportsStaticDiffieHellman: Boolean

    /**
     * Ephemeral-ephemeral ECDH on [curve]. G_X and G_Y are the x-coordinates of the public keys in the
     * compact representation of RFC 9528 Appendix B, so both have the curve's coordinate length.
     */
    public data class DiffieHellman(public val curve: EcdhCurve) : KeyExchangeAlgorithm() {
        override val ephemeralKeyType: KeyType get() = curve.keyType
        override val initiatorShareLength: Int get() = curve.keyLength
        override val responderShareLength: Int get() = curve.keyLength
        override val supportsStaticDiffieHellman: Boolean get() = true

        override fun toString(): String = curve.name
    }

    /**
     * A key-encapsulation mechanism (draft-ietf-lake-pqsuites §3): G_X is the encapsulation key, G_Y the
     * ciphertext, with the lengths of draft-ietf-lake-pqsuites Table 1.
     */
    public data class Kem(public val algorithm: KemAlgorithm) : KeyExchangeAlgorithm() {
        override val ephemeralKeyType: KeyType get() = algorithm.keyType
        override val initiatorShareLength: Int get() = algorithm.keyType.publicKeyLength
        override val responderShareLength: Int get() = algorithm.ciphertextLength
        override val supportsStaticDiffieHellman: Boolean get() = false

        override fun toString(): String = algorithm.name
    }
}

/**
 * An EDHOC cipher suite, RFC 9528 §3.6 and §10.2 (IANA "EDHOC Cipher Suites").
 *
 * The parameters follow the order of the registry's "Array" column: EDHOC AEAD, EDHOC hash, EDHOC MAC
 * length, EDHOC key exchange, EDHOC signature, application AEAD, application hash.
 *
 * @property edhocAead protects message_3 and message_4 (RFC 9528 §5.4.2, §5.5.2).
 * @property edhocHash computes the transcript hashes and selects HKDF or KMAC (RFC 9528 §4.1).
 * @property keyExchange the ephemeral key exchange, and with Diffie-Hellman also static-DH authentication.
 * @property signature signs Signature_or_MAC_2/3 in the signature methods.
 * @property appAead the AEAD for the application, for example OSCORE (RFC 9528 Appendix A.1).
 * @property appHash the hash for the application's HKDF, for example OSCORE.
 *
 * The companion holds the registered suites and libedhoc's private post-quantum suite [SUITE_PQC_1].
 * Other private suites (values -24..-21, RFC 9528 §3.6) can be declared with the public constructor; the
 * constructor enforces the minimum strengths of RFC 9528 §9.3.
 */
public class CipherSuite private constructor(
    /** The suite's integer label in SUITES_I and SUITES_R. */
    public val value: Int,
    public val edhocAead: AeadAlgorithm,
    public val edhocHash: HashAlgorithm,
    /** EDHOC MAC length in bytes, used by static-DH authentication (RFC 9528 §5.3.2). */
    public val macLength: Int,
    public val keyExchange: KeyExchangeAlgorithm,
    public val signature: SignatureAlgorithm,
    public val appAead: AeadAlgorithm,
    public val appHash: HashAlgorithm,
    /**
     * Whether kcoap can run this suite. False only for [SUITE_25], whose EdDSA signatures use Ed448, a key
     * type kcoap's crypto layer does not model and no backend provides.
     */
    public val isImplemented: Boolean,
) {
    /** A suite from its parameters, for private-use suites; [isImplemented] is true. */
    public constructor(
        value: Int,
        edhocAead: AeadAlgorithm,
        edhocHash: HashAlgorithm,
        macLength: Int,
        keyExchange: KeyExchangeAlgorithm,
        signature: SignatureAlgorithm,
        appAead: AeadAlgorithm,
        appHash: HashAlgorithm,
    ) : this(value, edhocAead, edhocHash, macLength, keyExchange, signature, appAead, appHash, isImplemented = true)

    init {
        // RFC 9528 §9.3: MAC length and AEAD tag at least 64 bits, hash output at least 256 bits.
        require(macLength >= 8) { "The EDHOC MAC length must be at least 8 bytes, got $macLength" }
        require(edhocAead.tagLength >= 8) { "The EDHOC AEAD tag must be at least 8 bytes" }
        require(edhocHash.outputLength >= 32) { "The EDHOC hash output must be at least 256 bits" }
    }

    /** RFC 9528 §8: implementations MUST support cipher suites 2 and 3. */
    public val isMandatoryToImplement: Boolean get() = value == 2 || value == 3

    /**
     * Whether a session with [method] on this suite runs on [crypto] where it is: the suite is
     * [implemented][isImplemented] and fits the method ([EdhocMethod.isCompatibleWith]), and the backend
     * supports
     * - the ephemeral key exchange: key agreement on the curve, which static-DH authentication uses too
     *   (RFC 9528 §4.1.1), or the KEM (draft-ietf-lake-pqsuites §3);
     * - the signature algorithm, if either side of [method] signs (RFC 9528 §5.3.2);
     * - the EDHOC and application AEADs, and the EDHOC and application hashes with HMAC for SHA-2
     *   (RFC 9528 §3.6, Appendix A.1).
     *
     * Platforms differ (WebCrypto has no ChaCha20/Poly1305, so suites 4 and 5 do not run there).
     * [EdhocInitiator] and [EdhocResponder] offer and accept only the suites for which this is true, and
     * it is true exactly when a handshake between them on this backend succeeds. Each question goes to
     * [CryptoBackend.supports], which backends answer from memory after the first time
     * (docs/adr/0001-crypto-capabilities.md).
     */
    public suspend fun isSupportedBy(crypto: CryptoBackend, method: EdhocMethod): Boolean =
        missingFor(crypto, method) == null

    /**
     * Why a session with [method] on this suite cannot run on [crypto], naming the first thing missing
     * ("needs P_256"), or null when it can.
     */
    internal suspend fun missingFor(crypto: CryptoBackend, method: EdhocMethod): String? {
        if (!isImplemented) return "is not implemented"
        if (!method.isCompatibleWith(this)) return "has no static Diffie-Hellman for method ${method.value}"
        val signs = method.initiatorAuthentication == AuthenticationKind.SIGNATURE ||
            method.responderAuthentication == AuthenticationKind.SIGNATURE
        val missing = when {
            !keyExchange.isSupportedBy(crypto) -> keyExchange.toString()
            signs && !crypto.supports(signature) -> signature.name
            !crypto.supports(edhocAead) -> edhocAead.name
            !crypto.supports(appAead) -> appAead.name
            !crypto.supports(edhocHash) -> edhocHash.name
            !crypto.supports(appHash) -> appHash.name
            else -> return null
        }
        return "needs $missing"
    }

    private suspend fun KeyExchangeAlgorithm.isSupportedBy(crypto: CryptoBackend): Boolean = when (this) {
        is KeyExchangeAlgorithm.DiffieHellman -> crypto.supports(curve.keyType)
        is KeyExchangeAlgorithm.Kem -> crypto.supports(algorithm)
    }

    /** Whether the value is in the private-use range -24..-21 of RFC 9528 §10.2. */
    public val isPrivateUse: Boolean get() = value in -24..-21

    override fun equals(other: Any?): Boolean =
        other is CipherSuite && value == other.value && edhocAead == other.edhocAead && edhocHash == other.edhocHash &&
            macLength == other.macLength && keyExchange == other.keyExchange && signature == other.signature &&
            appAead == other.appAead && appHash == other.appHash && isImplemented == other.isImplemented

    override fun hashCode(): Int = 31 * value + keyExchange.hashCode()

    override fun toString(): String =
        "CipherSuite($value: $edhocAead, $edhocHash, $macLength, $keyExchange, $signature, $appAead, $appHash)"

    public companion object {
        /** AES-CCM-16-64-128, SHA-256, 8, X25519, EdDSA, AES-CCM-16-64-128, SHA-256. */
        public val SUITE_0: CipherSuite = registered(
            0, AES_CCM_16_64_128, SHA_256, 8, EcdhCurve.X25519, SignatureAlgorithm.EDDSA, AES_CCM_16_64_128, SHA_256,
        )
        /** AES-CCM-16-128-128, SHA-256, 16, X25519, EdDSA, AES-CCM-16-64-128, SHA-256. */
        public val SUITE_1: CipherSuite = registered(
            1, AES_CCM_16_128_128, SHA_256, 16, EcdhCurve.X25519, SignatureAlgorithm.EDDSA, AES_CCM_16_64_128, SHA_256,
        )
        /** AES-CCM-16-64-128, SHA-256, 8, P-256, ES256, AES-CCM-16-64-128, SHA-256; mandatory (RFC 9528 §8). */
        public val SUITE_2: CipherSuite = registered(
            2, AES_CCM_16_64_128, SHA_256, 8, EcdhCurve.P_256, SignatureAlgorithm.ES256, AES_CCM_16_64_128, SHA_256,
        )
        /** AES-CCM-16-128-128, SHA-256, 16, P-256, ES256, AES-CCM-16-64-128, SHA-256; mandatory (RFC 9528 §8). */
        public val SUITE_3: CipherSuite = registered(
            3, AES_CCM_16_128_128, SHA_256, 16, EcdhCurve.P_256, SignatureAlgorithm.ES256, AES_CCM_16_64_128, SHA_256,
        )
        /** ChaCha20/Poly1305, SHA-256, 16, X25519, EdDSA, ChaCha20/Poly1305, SHA-256. */
        public val SUITE_4: CipherSuite = registered(
            4, CHACHA20_POLY1305, SHA_256, 16, EcdhCurve.X25519, SignatureAlgorithm.EDDSA, CHACHA20_POLY1305, SHA_256,
        )
        /** ChaCha20/Poly1305, SHA-256, 16, P-256, ES256, ChaCha20/Poly1305, SHA-256. */
        public val SUITE_5: CipherSuite = registered(
            5, CHACHA20_POLY1305, SHA_256, 16, EcdhCurve.P_256, SignatureAlgorithm.ES256, CHACHA20_POLY1305, SHA_256,
        )
        /** A128GCM, SHA-256, 16, X25519, ES256, A128GCM, SHA-256. */
        public val SUITE_6: CipherSuite = registered(
            6, A128GCM, SHA_256, 16, EcdhCurve.X25519, SignatureAlgorithm.ES256, A128GCM, SHA_256,
        )
        /** A256GCM, SHA-384, 16, P-384, ES384, A256GCM, SHA-384 (CNSA 1.0). */
        public val SUITE_24: CipherSuite = registered(
            24, A256GCM, SHA_384, 16, EcdhCurve.P_384, SignatureAlgorithm.ES384, A256GCM, SHA_384,
        )

        /** ChaCha20/Poly1305, SHAKE256, X448, EdDSA with Ed448: registered, not implemented ([isImplemented]). */
        public val SUITE_25: CipherSuite = CipherSuite(
            25, CHACHA20_POLY1305, SHAKE256, 16, KeyExchangeAlgorithm.DiffieHellman(EcdhCurve.X448),
            SignatureAlgorithm.EDDSA, CHACHA20_POLY1305, SHAKE256, isImplemented = false,
        )

        /**
         * libedhoc's experimental post-quantum suite `EDHOC_CIPHER_SUITE_PQC_1` on the private-use value -24:
         * AES-CCM-16-128-128, SHAKE256, MAC length 16, ML-KEM-512, ML-DSA-44. These are the EDHOC algorithms
         * of suite TBD1 in draft-ietf-lake-pqsuites-02 §6.4, whose application algorithms
         * (AES-CCM-16-64-128, SHA-256) this suite takes as well; libedhoc leaves those to the application.
         * With SHAKE256 as EDHOC hash, EDHOC_Extract and EDHOC_Expand are KMAC256 (RFC 9528 §4.1).
         */
        public val SUITE_PQC_1: CipherSuite = CipherSuite(
            -24, AES_CCM_16_128_128, SHAKE256, 16, KeyExchangeAlgorithm.Kem(KemAlgorithm.ML_KEM_512),
            SignatureAlgorithm.ML_DSA_44, AES_CCM_16_64_128, SHA_256,
        )

        /** The suites of the IANA registry (RFC 9528 Table 6), in value order. */
        public val registered: List<CipherSuite> =
            listOf(SUITE_0, SUITE_1, SUITE_2, SUITE_3, SUITE_4, SUITE_5, SUITE_6, SUITE_24, SUITE_25)

        /** A registered suite or [SUITE_PQC_1] by value; null for anything else. */
        public fun fromValue(value: Int): CipherSuite? =
            registered.firstOrNull { it.value == value } ?: SUITE_PQC_1.takeIf { it.value == value }

        @Suppress("LongParameterList")
        private fun registered(
            value: Int,
            edhocAead: AeadAlgorithm,
            edhocHash: HashAlgorithm,
            macLength: Int,
            curve: EcdhCurve,
            signature: SignatureAlgorithm,
            appAead: AeadAlgorithm,
            appHash: HashAlgorithm,
        ): CipherSuite {
            val keyExchange = KeyExchangeAlgorithm.DiffieHellman(curve)
            return CipherSuite(value, edhocAead, edhocHash, macLength, keyExchange, signature, appAead, appHash)
        }
    }
}
