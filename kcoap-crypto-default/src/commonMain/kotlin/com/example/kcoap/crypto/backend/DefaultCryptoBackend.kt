package com.example.kcoap.crypto.backend

import com.example.kcoap.crypto.Aead
import com.example.kcoap.crypto.AeadAlgorithm
import com.example.kcoap.crypto.AeadAuthenticationException
import com.example.kcoap.crypto.AesBlockCipher
import com.example.kcoap.crypto.CryptoBackend
import com.example.kcoap.crypto.HashAlgorithm
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA384
import dev.whyoleg.cryptography.operations.IvAuthenticatedCipher

/**
 * [CryptoBackend] on cryptography-kotlin.
 *
 * With the default provider this is JCA on JVM/Android and CryptoKit + CommonCrypto on Apple targets.
 * On Android, pass a JDK provider backed by a bundled BouncyCastle for X25519/Ed25519/ChaCha20 on older
 * API levels. AES-CCM always uses the common-code implementation on top of the platform's AES block.
 */
public class DefaultCryptoBackend(
    private val provider: CryptographyProvider = CryptographyProvider.Default,
) : CryptoBackend {

    override fun randomBytes(size: Int): ByteArray = CryptographySystem.getDefaultRandom().nextBytes(size)

    override fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
        provider.get(algorithm.digestId()).hasher().hashBlocking(data)

    override fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
        provider.get(HMAC)
            .keyDecoder(algorithm.digestId())
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignatureBlocking(data)

    // Raw AES-ECB on a single block is exactly what CCM's CBC-MAC and CTR steps need; it is never used
    // as a mode of operation on messages.
    @OptIn(DelicateCryptographyApi::class)
    override fun aesBlockCipher(key: ByteArray): AesBlockCipher {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key length: ${key.size}" }
        val cipher = provider.get(AES.ECB)
            .keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher(padding = false)
        return AesBlockCipher { block ->
            require(block.size == 16) { "AES block must be 16 bytes, got ${block.size}" }
            cipher.encryptBlocking(block)
        }
    }

    override fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
        if (algorithm.isAesCcm) return super.aead(algorithm, key)
        require(key.size == algorithm.keyLength) { "$algorithm needs a ${algorithm.keyLength}-byte key, got ${key.size}" }
        val cipher: IvAuthenticatedCipher = when (algorithm) {
            AeadAlgorithm.A128GCM, AeadAlgorithm.A192GCM, AeadAlgorithm.A256GCM ->
                provider.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
                    .cipher(tagSize = 128.bits)

            AeadAlgorithm.CHACHA20_POLY1305 ->
                provider.get(ChaCha20Poly1305).keyDecoder()
                    .decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key)
                    .cipher()

            else -> return super.aead(algorithm, key)
        }
        return PlatformAead(cipher, tagLength = algorithm.tagLength, nonceLength = algorithm.nonceLength)
    }

    // Caller-supplied nonces are required: OSCORE and EDHOC derive them deterministically (RFC 8613 §5.2,
    // RFC 9528 §5) and uniqueness comes from the sender sequence number, not from random IVs.
    @OptIn(DelicateCryptographyApi::class)
    private class PlatformAead(
        private val cipher: IvAuthenticatedCipher,
        override val tagLength: Int,
        override val nonceLength: Int,
    ) : Aead {
        override fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            require(nonce.size == nonceLength) { "Nonce must be $nonceLength bytes, got ${nonce.size}" }
            return cipher.encryptWithIvBlocking(nonce, plaintext, associatedData)
        }

        override fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            require(nonce.size == nonceLength) { "Nonce must be $nonceLength bytes, got ${nonce.size}" }
            if (ciphertext.size < tagLength) throw AeadAuthenticationException()
            return try {
                cipher.decryptWithIvBlocking(nonce, ciphertext, associatedData)
            } catch (e: Exception) {
                // Platform providers signal tag failure with provider-specific exception types.
                throw AeadAuthenticationException()
            }
        }
    }

    private fun HashAlgorithm.digestId(): CryptographyAlgorithmId<Digest> = when (this) {
        HashAlgorithm.SHA_256 -> SHA256
        HashAlgorithm.SHA_384 -> SHA384
    }
}
