package dev.kcoap.crypto.backend

import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.AeadAuthenticationException
import dev.kcoap.crypto.AesBlockCipher
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.Shake256
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyAlgorithm
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA384
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.operations.IvAuthenticatedCipher
import dev.whyoleg.cryptography.operations.SignatureVerifier

/**
 * [CryptoBackend] on cryptography-kotlin: JCA on JVM/Android, CryptoKit and CommonCrypto on Apple,
 * OpenSSL on Linux, WebCrypto in browsers and Node.js.
 *
 * Offers everything the classical EDHOC suites and OSCORE need: SHA-2, SHAKE256 (common code),
 * HMAC, AES-CCM (common code on the platform AES block), AES-GCM, ChaCha20/Poly1305, X25519, Ed25519,
 * and P-256/P-384 for ECDH and ECDSA, as far as the platform has them. Post-quantum algorithms come
 * from `BouncyCastleCryptoBackend`.
 *
 * The code is the same on every platform; what differs is only what the provider offers, and the
 * backend adapts by capability, never by platform:
 * - The AES block under CCM comes from AES-ECB when the provider has it and from AES-CTR otherwise
 *   (WebCrypto has no ECB); see [PlatformAesBlock].
 * - Compressed P-256 and P-384 points are expanded in common code before any provider sees them
 *   ([EcPointDecompression]), so ECDH from an x-only EDHOC key works whether or not the platform
 *   accepts compressed points.
 * - What the platform lacks is reported as [UnsupportedAlgorithmException] when it is asked for, and
 *   the `supports` members ask in advance: ChaCha20/Poly1305 on WebCrypto, 192-bit AES keys in
 *   Chromium, X25519 and Ed25519 in browsers that predate them.
 * - Other platform failures leave as exceptions, also where the platform's own are not Kotlin
 *   exceptions (WebCrypto), so protocol code can turn them into protocol errors.
 *
 * The `supports` members probe once per instance and memoize the answer (docs/adr/0001-crypto-capabilities.md):
 * the first question about an algorithm runs its operations on throwaway keys, and every later one is a
 * lookup, however many callers ask at once. Probes call this backend's own operations, so a subclass that
 * overrides an operation is asked about its own; those operations must not ask `supports` in turn.
 *
 * Private keys are [SoftwarePrivateKey]s holding raw key material in memory.
 */
public open class DefaultCryptoBackend(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
) : CryptoBackend {
    private val capabilities = CapabilityMemo()

    /** Probed once per instance: an AEAD built under a zero key encrypts an empty message. */
    override suspend fun supports(algorithm: AeadAlgorithm): Boolean =
        capabilities.answer(algorithm) { probe(algorithm) }

    /** Probed once per instance: a generated key pair, and key agreement for the Diffie-Hellman types. */
    override suspend fun supports(type: KeyType): Boolean = capabilities.answer(type) { probe(type) }

    /** Probed once per instance: a generated key pair signs and verifies an empty message. */
    override suspend fun supports(algorithm: SignatureAlgorithm): Boolean =
        capabilities.answer(algorithm) { probe(algorithm) }

    /** Probed once per instance; this backend has no KEM, so the answer is false unless a subclass adds one. */
    override suspend fun supports(algorithm: KemAlgorithm): Boolean =
        capabilities.answer(algorithm) { probe(algorithm) }

    /** Probed once per instance: the digest, and the HMAC of a SHA-2 hash. */
    override suspend fun supports(algorithm: HashAlgorithm): Boolean =
        capabilities.answer(algorithm) { probe(algorithm) }

    // The interface's probes, which call this instance's operations.
    private suspend fun probe(algorithm: AeadAlgorithm): Boolean = super.supports(algorithm)
    private suspend fun probe(type: KeyType): Boolean = super.supports(type)
    private suspend fun probe(algorithm: SignatureAlgorithm): Boolean = super.supports(algorithm)
    private suspend fun probe(algorithm: KemAlgorithm): Boolean = super.supports(algorithm)
    private suspend fun probe(algorithm: HashAlgorithm): Boolean = super.supports(algorithm)

    override suspend fun randomBytes(size: Int): ByteArray = CryptographySystem.getDefaultRandom().nextBytes(size)

    override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
        if (algorithm == HashAlgorithm.SHAKE256) {
            Shake256.digest(data, algorithm.outputLength)
        } else {
            platformCall(algorithm.name) { platform(algorithm.sha2Id()).hasher().hash(data) }
        }

    override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
        platformCall("HMAC-${algorithm.name}") {
            platform(HMAC)
                .keyDecoder(algorithm.sha2Id())
                .decodeFromByteArray(HMAC.Key.Format.RAW, key)
                .signatureGenerator()
                .generateSignature(data)
        }

    /** AES-ECB on single blocks where the provider has it, otherwise AES-CTR; see [PlatformAesBlock]. */
    override suspend fun aesBlockCipher(key: ByteArray): AesBlockCipher = PlatformAesBlock.create(provider, key)

    override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
        if (algorithm.isAesCcm) return super.aead(algorithm, key)
        require(key.size == algorithm.keyLength) { "$algorithm needs a ${algorithm.keyLength}-byte key, got ${key.size}" }
        val cipher: IvAuthenticatedCipher = capabilityCall(algorithm.name) {
            when (algorithm) {
                AeadAlgorithm.A128GCM, AeadAlgorithm.A192GCM, AeadAlgorithm.A256GCM ->
                    platform(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
                        .cipher(tagSize = 128.bits)

                AeadAlgorithm.CHACHA20_POLY1305 ->
                    platform(ChaCha20Poly1305).keyDecoder()
                        .decodeFromByteArray(ChaCha20Poly1305.Key.Format.RAW, key)
                        .cipher()

                else -> throw UnsupportedAlgorithmException(algorithm.name)
            }
        }
        return PlatformAead(algorithm.name, cipher, tagLength = algorithm.tagLength, nonceLength = algorithm.nonceLength)
    }

    override suspend fun generateKeyPair(type: KeyType): KeyPair = platformKeyPair(type)

    /** [generateKeyPair] on the provider, which no subclass replaces. */
    private suspend fun platformKeyPair(type: KeyType): KeyPair = capabilityCall(type.name) {
        when (type) {
            KeyType.X25519, KeyType.X448 -> {
                val pair = platform(XDH).keyPairGenerator(type.xdhCurve()).generateKey()
                KeyPair(
                    pair.publicKey.encodeToByteArray(XDH.PublicKey.Format.RAW),
                    SoftwarePrivateKey(type, pair.privateKey.encodeToByteArray(XDH.PrivateKey.Format.RAW)),
                )
            }

            KeyType.ED25519 -> {
                val pair = platform(EdDSA).keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
                KeyPair(
                    pair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW),
                    SoftwarePrivateKey(type, pair.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.RAW)),
                )
            }

            KeyType.P256, KeyType.P384 -> {
                val pair = platform(ECDH).keyPairGenerator(type.ecCurve()).generateKey()
                KeyPair(
                    pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW),
                    SoftwarePrivateKey(type, pair.privateKey.encodeToByteArray(EC.PrivateKey.Format.RAW)),
                )
            }

            else -> throw UnsupportedAlgorithmException(type.name)
        }
    }

    override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
        val raw = privateKey.softwareKey().encoded()
        val type = privateKey.type
        return withKeysOf(type, type.name) {
            when (type) {
                KeyType.X25519, KeyType.X448 -> {
                    val xdh = platform(XDH)
                    val own = xdh.privateKeyDecoder(type.xdhCurve()).decodeFromByteArray(XDH.PrivateKey.Format.RAW, raw)
                    val peer = xdh.publicKeyDecoder(type.xdhCurve()).decodeFromByteArray(XDH.PublicKey.Format.RAW, peerPublicKey)
                    own.sharedSecretGenerator().generateSharedSecretToByteArray(peer)
                }

                KeyType.P256, KeyType.P384 -> {
                    val ecdh = platform(ECDH)
                    val own = ecdh.privateKeyDecoder(type.ecCurve()).decodeFromByteArray(EC.PrivateKey.Format.RAW, raw)
                    val peer = ecdh.publicKeyDecoder(type.ecCurve())
                        .decodeFromByteArray(EC.PublicKey.Format.RAW, uncompressedPoint(type, peerPublicKey))
                    own.sharedSecretGenerator().generateSharedSecretToByteArray(peer)
                }

                else -> throw UnsupportedAlgorithmException(type.name)
            }
        }
    }

    override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray {
        require(privateKey.type == algorithm.keyType) { "$algorithm needs a ${algorithm.keyType} key, got ${privateKey.type}" }
        val raw = privateKey.softwareKey().encoded()
        return withKeysOf(algorithm.keyType, algorithm.name) {
            when (algorithm) {
                SignatureAlgorithm.EDDSA ->
                    platform(EdDSA).privateKeyDecoder(EdDSA.Curve.Ed25519)
                        .decodeFromByteArray(EdDSA.PrivateKey.Format.RAW, raw)
                        .signatureGenerator()
                        .generateSignature(data)

                SignatureAlgorithm.ES256, SignatureAlgorithm.ES384 ->
                    platform(ECDSA).privateKeyDecoder(algorithm.keyType.ecCurve())
                        .decodeFromByteArray(EC.PrivateKey.Format.RAW, raw)
                        .signatureGenerator(algorithm.ecdsaDigest(), ECDSA.SignatureFormat.RAW)
                        .generateSignature(data)

                else -> throw UnsupportedAlgorithmException(algorithm.name)
            }
        }
    }

    override suspend fun verify(
        algorithm: SignatureAlgorithm,
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        // Decoding the key stays outside the try: a malformed key is a caller error, not a bad signature.
        val verifier: SignatureVerifier = withKeysOf(algorithm.keyType, algorithm.name) {
            when (algorithm) {
                SignatureAlgorithm.EDDSA ->
                    platform(EdDSA).publicKeyDecoder(EdDSA.Curve.Ed25519)
                        .decodeFromByteArray(EdDSA.PublicKey.Format.RAW, publicKey)
                        .signatureVerifier()

                SignatureAlgorithm.ES256, SignatureAlgorithm.ES384 ->
                    platform(ECDSA).publicKeyDecoder(algorithm.keyType.ecCurve())
                        .decodeFromByteArray(EC.PublicKey.Format.RAW, uncompressedPoint(algorithm.keyType, publicKey))
                        .signatureVerifier(algorithm.ecdsaDigest(), ECDSA.SignatureFormat.RAW)

                else -> throw UnsupportedAlgorithmException(algorithm.name)
            }
        }
        if (signature.size != algorithm.signatureLength) return false
        return try {
            verifier.tryVerifySignature(data, signature)
        } catch (e: Throwable) {
            // Some platforms report a structurally invalid signature (say, R not on the curve) by throwing.
            if (!isPlatformFailure(e)) throw e
            false
        }
    }

    /**
     * Runs [block], a platform call on keys of [type] given by the caller or a peer. When it fails, the
     * cause is either the input or the platform not handling [type] at all (X25519 in a browser that
     * predates it): the second becomes [UnsupportedAlgorithmException], the first an exception about the
     * input ([platformCall]).
     */
    private suspend inline fun <T> withKeysOf(type: KeyType, algorithm: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (e is UnsupportedAlgorithmException || !isPlatformFailure(e)) throw e
        if (!generatesKeysOf(type)) throw UnsupportedAlgorithmException(algorithm)
        throw asException(e, algorithm)
    }

    /**
     * Whether the provider generates key pairs of [type], asked once. Not `supports(type)`: that probes
     * key agreement too, which would come back here when it fails.
     */
    private suspend fun generatesKeysOf(type: KeyType): Boolean = capabilities.answer(KeyGeneration(type)) {
        try {
            platformKeyPair(type).privateKey.destroy()
            true
        } catch (_: UnsupportedAlgorithmException) {
            false
        }
    }

    /** The memo's question for [generatesKeysOf], apart from `supports(type)`. */
    private data class KeyGeneration(val type: KeyType)

    /**
     * The provider's implementation of [id], or [UnsupportedAlgorithmException] when the platform has
     * none, as WebCrypto has no ChaCha20/Poly1305.
     */
    private fun <A : CryptographyAlgorithm> platform(id: CryptographyAlgorithmId<A>): A =
        provider.getOrNull(id) ?: throw UnsupportedAlgorithmException(id.name)

    /** The key material of a [SoftwarePrivateKey]; any other handle belongs to a different backend. */
    protected fun PrivateKey.softwareKey(): SoftwarePrivateKey =
        this as? SoftwarePrivateKey
            ?: throw IllegalArgumentException("${this::class.simpleName} was not created by a software backend")

    private class PlatformAead(
        private val algorithm: String,
        private val cipher: IvAuthenticatedCipher,
        override val tagLength: Int,
        override val nonceLength: Int,
    ) : Aead {
        // Caller-supplied nonces are required: OSCORE and EDHOC derive them deterministically
        // (RFC 8613 §5.2, RFC 9528 §5), and uniqueness comes from sequence numbers, not random IVs.
        @OptIn(DelicateCryptographyApi::class)
        override suspend fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            require(nonce.size == nonceLength) { "Nonce must be $nonceLength bytes, got ${nonce.size}" }
            return platformCall(algorithm) { cipher.encryptWithIv(nonce, plaintext, associatedData) }
        }

        @OptIn(DelicateCryptographyApi::class)
        override suspend fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            require(nonce.size == nonceLength) { "Nonce must be $nonceLength bytes, got ${nonce.size}" }
            if (ciphertext.size < tagLength) throw AeadAuthenticationException()
            return try {
                cipher.decryptWithIv(nonce, ciphertext, associatedData)
            } catch (e: Throwable) {
                // Platform providers signal tag failure with provider-specific exception types.
                if (!isPlatformFailure(e)) throw e
                throw AeadAuthenticationException()
            }
        }
    }

    private companion object {
        fun HashAlgorithm.sha2Id(): CryptographyAlgorithmId<Digest> = when (this) {
            HashAlgorithm.SHA_256 -> SHA256
            HashAlgorithm.SHA_384 -> SHA384
            HashAlgorithm.SHAKE256 -> throw UnsupportedAlgorithmException("HMAC-SHAKE256")
        }

        fun SignatureAlgorithm.ecdsaDigest(): CryptographyAlgorithmId<Digest> =
            if (this == SignatureAlgorithm.ES384) SHA384 else SHA256

        fun KeyType.xdhCurve(): XDH.Curve = if (this == KeyType.X448) XDH.Curve.X448 else XDH.Curve.X25519

        fun KeyType.ecCurve(): EC.Curve = when (this) {
            KeyType.P256 -> EC.Curve.P256
            KeyType.P384 -> EC.Curve.P384
            else -> throw UnsupportedAlgorithmException(name)
        }

        /** A SEC1 point as `04 || x || y`: compressed points (`02 || x`, `03 || x`) are expanded first. */
        fun uncompressedPoint(type: KeyType, point: ByteArray): ByteArray =
            if (EcPointDecompression.isCompressed(point)) EcPointDecompression.decompress(type, point) else point
    }
}
