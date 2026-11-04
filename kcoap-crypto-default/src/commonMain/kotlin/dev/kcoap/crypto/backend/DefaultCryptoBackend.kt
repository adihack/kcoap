package dev.kcoap.crypto.backend

import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.AeadAuthenticationException
import dev.kcoap.crypto.AesBlockCipher
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.Shake256
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.whyoleg.cryptography.BinarySize.Companion.bits
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
 * [CryptoBackend] on cryptography-kotlin: JCA on JVM/Android, CryptoKit and CommonCrypto on Apple.
 *
 * Supports everything the classical EDHOC suites and OSCORE need: SHA-2, SHAKE256 (common code),
 * HMAC, AES-CCM (common code on the platform AES block), AES-GCM, ChaCha20/Poly1305, X25519, Ed25519,
 * and P-256/P-384 for ECDH and ECDSA. Post-quantum algorithms come from `BouncyCastleCryptoBackend`.
 *
 * Private keys are [SoftwarePrivateKey]s holding raw key material in memory.
 */
public open class DefaultCryptoBackend(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
) : CryptoBackend {

    override suspend fun randomBytes(size: Int): ByteArray = CryptographySystem.getDefaultRandom().nextBytes(size)

    override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
        if (algorithm == HashAlgorithm.SHAKE256) {
            Shake256.digest(data, algorithm.outputLength)
        } else {
            provider.get(algorithm.sha2Id()).hasher().hash(data)
        }

    override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
        provider.get(HMAC)
            .keyDecoder(algorithm.sha2Id())
            .decodeFromByteArray(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignature(data)

    // Raw AES-ECB on single blocks is exactly what CCM's CBC-MAC and CTR steps need; it is never used
    // as a mode of operation on messages.
    @OptIn(DelicateCryptographyApi::class)
    override suspend fun aesBlockCipher(key: ByteArray): AesBlockCipher {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key length: ${key.size}" }
        val cipher = provider.get(AES.ECB)
            .keyDecoder()
            .decodeFromByteArray(AES.Key.Format.RAW, key)
            .cipher(padding = false)
        return AesBlockCipher { block ->
            require(block.size == 16) { "AES block must be 16 bytes, got ${block.size}" }
            cipher.encrypt(block)
        }
    }

    override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
        if (algorithm.isAesCcm) return super.aead(algorithm, key)
        require(key.size == algorithm.keyLength) { "$algorithm needs a ${algorithm.keyLength}-byte key, got ${key.size}" }
        val cipher: IvAuthenticatedCipher = when (algorithm) {
            AeadAlgorithm.A128GCM, AeadAlgorithm.A192GCM, AeadAlgorithm.A256GCM ->
                provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
                    .cipher(tagSize = 128.bits)

            AeadAlgorithm.CHACHA20_POLY1305 ->
                provider.get(ChaCha20Poly1305).keyDecoder()
                    .decodeFromByteArray(ChaCha20Poly1305.Key.Format.RAW, key)
                    .cipher()

            else -> throw UnsupportedAlgorithmException(algorithm.name)
        }
        return PlatformAead(cipher, tagLength = algorithm.tagLength, nonceLength = algorithm.nonceLength)
    }

    override suspend fun generateKeyPair(type: KeyType): KeyPair = when (type) {
        KeyType.X25519, KeyType.X448 -> {
            val pair = provider.get(XDH).keyPairGenerator(type.xdhCurve()).generateKey()
            KeyPair(
                pair.publicKey.encodeToByteArray(XDH.PublicKey.Format.RAW),
                SoftwarePrivateKey(type, pair.privateKey.encodeToByteArray(XDH.PrivateKey.Format.RAW)),
            )
        }

        KeyType.ED25519 -> {
            val pair = provider.get(EdDSA).keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
            KeyPair(
                pair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.RAW),
                SoftwarePrivateKey(type, pair.privateKey.encodeToByteArray(EdDSA.PrivateKey.Format.RAW)),
            )
        }

        KeyType.P256, KeyType.P384 -> {
            val pair = provider.get(ECDH).keyPairGenerator(type.ecCurve()).generateKey()
            KeyPair(
                pair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW),
                SoftwarePrivateKey(type, pair.privateKey.encodeToByteArray(EC.PrivateKey.Format.RAW)),
            )
        }

        else -> throw UnsupportedAlgorithmException(type.name)
    }

    override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
        val raw = privateKey.softwareKey().encoded()
        return when (val type = privateKey.type) {
            KeyType.X25519, KeyType.X448 -> {
                val xdh = provider.get(XDH)
                val own = xdh.privateKeyDecoder(type.xdhCurve()).decodeFromByteArray(XDH.PrivateKey.Format.RAW, raw)
                val peer = xdh.publicKeyDecoder(type.xdhCurve()).decodeFromByteArray(XDH.PublicKey.Format.RAW, peerPublicKey)
                own.sharedSecretGenerator().generateSharedSecretToByteArray(peer)
            }

            KeyType.P256, KeyType.P384 -> {
                val ecdh = provider.get(ECDH)
                val own = ecdh.privateKeyDecoder(type.ecCurve()).decodeFromByteArray(EC.PrivateKey.Format.RAW, raw)
                val peer = ecdh.publicKeyDecoder(type.ecCurve()).decodeFromByteArray(peerPublicKey.ecPublicKeyFormat(), peerPublicKey)
                own.sharedSecretGenerator().generateSharedSecretToByteArray(peer)
            }

            else -> throw UnsupportedAlgorithmException(type.name)
        }
    }

    override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray {
        require(privateKey.type == algorithm.keyType) { "$algorithm needs a ${algorithm.keyType} key, got ${privateKey.type}" }
        val raw = privateKey.softwareKey().encoded()
        return when (algorithm) {
            SignatureAlgorithm.EDDSA ->
                provider.get(EdDSA).privateKeyDecoder(EdDSA.Curve.Ed25519)
                    .decodeFromByteArray(EdDSA.PrivateKey.Format.RAW, raw)
                    .signatureGenerator()
                    .generateSignature(data)

            SignatureAlgorithm.ES256, SignatureAlgorithm.ES384 ->
                provider.get(ECDSA).privateKeyDecoder(algorithm.keyType.ecCurve())
                    .decodeFromByteArray(EC.PrivateKey.Format.RAW, raw)
                    .signatureGenerator(algorithm.ecdsaDigest(), ECDSA.SignatureFormat.RAW)
                    .generateSignature(data)

            else -> throw UnsupportedAlgorithmException(algorithm.name)
        }
    }

    override suspend fun verify(
        algorithm: SignatureAlgorithm,
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        // Decoding the key stays outside the try: a malformed key is a caller error, not a bad signature.
        val verifier: SignatureVerifier = when (algorithm) {
            SignatureAlgorithm.EDDSA ->
                provider.get(EdDSA).publicKeyDecoder(EdDSA.Curve.Ed25519)
                    .decodeFromByteArray(EdDSA.PublicKey.Format.RAW, publicKey)
                    .signatureVerifier()

            SignatureAlgorithm.ES256, SignatureAlgorithm.ES384 ->
                provider.get(ECDSA).publicKeyDecoder(algorithm.keyType.ecCurve())
                    .decodeFromByteArray(publicKey.ecPublicKeyFormat(), publicKey)
                    .signatureVerifier(algorithm.ecdsaDigest(), ECDSA.SignatureFormat.RAW)

            else -> throw UnsupportedAlgorithmException(algorithm.name)
        }
        if (signature.size != algorithm.signatureLength) return false
        return try {
            verifier.tryVerifySignature(data, signature)
        } catch (_: Exception) {
            // Some platforms report a structurally invalid signature (say, R not on the curve) by throwing.
            false
        }
    }

    /** The key material of a [SoftwarePrivateKey]; any other handle belongs to a different backend. */
    protected fun PrivateKey.softwareKey(): SoftwarePrivateKey =
        this as? SoftwarePrivateKey
            ?: throw IllegalArgumentException("${this::class.simpleName} was not created by a software backend")

    private class PlatformAead(
        private val cipher: IvAuthenticatedCipher,
        override val tagLength: Int,
        override val nonceLength: Int,
    ) : Aead {
        // Caller-supplied nonces are required: OSCORE and EDHOC derive them deterministically
        // (RFC 8613 §5.2, RFC 9528 §5), and uniqueness comes from sequence numbers, not random IVs.
        @OptIn(DelicateCryptographyApi::class)
        override suspend fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            require(nonce.size == nonceLength) { "Nonce must be $nonceLength bytes, got ${nonce.size}" }
            return cipher.encryptWithIv(nonce, plaintext, associatedData)
        }

        @OptIn(DelicateCryptographyApi::class)
        override suspend fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            require(nonce.size == nonceLength) { "Nonce must be $nonceLength bytes, got ${nonce.size}" }
            if (ciphertext.size < tagLength) throw AeadAuthenticationException()
            return try {
                cipher.decryptWithIv(nonce, ciphertext, associatedData)
            } catch (_: Exception) {
                // Platform providers signal tag failure with provider-specific exception types.
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

        /** SEC1 points: `04 || x || y` uncompressed, `02 || x` or `03 || x` compressed. */
        fun ByteArray.ecPublicKeyFormat(): EC.PublicKey.Format =
            if (isNotEmpty() && (this[0].toInt() == 2 || this[0].toInt() == 3)) {
                EC.PublicKey.Format.RAW.Compressed
            } else {
                EC.PublicKey.Format.RAW
            }
    }
}
