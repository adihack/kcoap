package dev.kcoap.crypto.backend

import dev.kcoap.crypto.AesBlockCipher
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES

/**
 * The single-block AES encryption that the common-code CCM runs on, built from whichever AES mode the
 * provider offers; the choice is a capability check, the same code on every platform.
 *
 * - **AES-ECB** when the provider has it (JCA, CommonCrypto, OpenSSL): one block in, one block out.
 * - **AES-CTR** when it does not (WebCrypto deliberately offers no ECB). CTR mode encrypts a block `P`
 *   as `P xor AES_K(counter)` (NIST SP 800-38A §6.5), so encrypting one all-zero block with the input
 *   block as the initial counter yields exactly `AES_K(input)`. Only one block is ever encrypted per
 *   call, so the counter is never incremented and the counter width the platform uses makes no
 *   difference.
 *
 * Either way the result is the raw AES permutation of FIPS 197, used one block at a time by CCM's
 * CBC-MAC and CTR steps (RFC 3610 §2), never as a mode of operation on messages. Where the platform's
 * cryptography is asynchronous (WebCrypto) every block is one asynchronous call: slower than a native
 * block, but fine for CoAP-sized messages.
 */
@OptIn(DelicateCryptographyApi::class)
internal object PlatformAesBlock {
    private const val BLOCK_SIZE = 16

    /** ECB when [provider] has it, otherwise the CTR construction. */
    suspend fun create(provider: CryptographyProvider, key: ByteArray): AesBlockCipher {
        val ecb = provider.getOrNull(AES.ECB)
        if (ecb != null) return fromEcb(ecb, key)
        val ctr = provider.getOrNull(AES.CTR) ?: throw UnsupportedAlgorithmException("AES")
        return fromCtr(ctr, key)
    }

    suspend fun fromEcb(ecb: AES.ECB, key: ByteArray): AesBlockCipher {
        val algorithm = checkKey(key)
        val cipher = capabilityCall(algorithm) {
            ecb.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher(padding = false)
        }
        return AesBlockCipher { block ->
            checkBlock(block)
            platformCall(algorithm) { cipher.encrypt(block) }
        }
    }

    suspend fun fromCtr(ctr: AES.CTR, key: ByteArray): AesBlockCipher {
        val algorithm = checkKey(key)
        val cipher = capabilityCall(algorithm) { ctr.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher() }
        return AesBlockCipher { block ->
            checkBlock(block)
            platformCall(algorithm) { cipher.encryptWithIv(iv = block, plaintext = ByteArray(BLOCK_SIZE)) }
        }
    }

    /** Checks the key length and names the algorithm after it, as AES-128, AES-192 or AES-256. */
    private fun checkKey(key: ByteArray): String {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key length: ${key.size}" }
        return "AES-${8 * key.size}"
    }

    private fun checkBlock(block: ByteArray) {
        require(block.size == BLOCK_SIZE) { "AES block must be $BLOCK_SIZE bytes, got ${block.size}" }
    }
}
