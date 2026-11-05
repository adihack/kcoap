package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.backend.PlatformAesBlock
import dev.kcoap.crypto.vectors.RFC3610_VECTORS
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The raw AES block under the common-code CCM, through both constructions: AES-ECB where the platform
 * has it, and AES-CTR on a zero block with the input as the counter, which WebCrypto uses.
 */
@OptIn(DelicateCryptographyApi::class)
class AesBlockTest {
    private val provider = CryptographyProvider.Default

    /** FIPS 197 Appendix C.1 to C.3: one block under 128, 192 and 256-bit keys. */
    private val fips197 = listOf(
        "000102030405060708090a0b0c0d0e0f" to "69c4e0d86a7b0430d8cdb78070b4c55a",
        "000102030405060708090a0b0c0d0e0f1011121314151617" to "dda97ca4864cdfe06eaf70a0ec0d7191",
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" to "8ea2b7ca516745bfeafc49904b496089",
    )
    private val fips197Plaintext = "00112233445566778899aabbccddeeff"

    @Test
    fun fips197AppendixCThroughTheBackendBlock() = runTest {
        for ((key, ciphertext) in fips197) {
            val block = DefaultCryptoBackend().aesBlockCipher(key.hex())
            assertEquals(ciphertext, block.encryptBlock(fips197Plaintext.hex()).hex(), "${key.length * 4}-bit key")
        }
    }

    @Test
    fun fips197AppendixCThroughTheCtrConstruction() = runTest {
        for ((key, ciphertext) in fips197) {
            val block = PlatformAesBlock.fromCtr(provider.get(AES.CTR), key.hex())
            assertEquals(ciphertext, block.encryptBlock(fips197Plaintext.hex()).hex(), "${key.length * 4}-bit key")
        }
    }

    /**
     * Wherever the provider has ECB, both constructions agree. The whole input block is the counter, so
     * the edges of the counter range are included.
     */
    @Test
    fun ctrConstructionAgreesWithEcbOnRandomBlocks() = runTest {
        val ecbMode = provider.getOrNull(AES.ECB) ?: return@runTest // no ECB to compare with (WebCrypto)
        val random = Random(3610)
        for (keyLength in listOf(16, 24, 32)) {
            val key = random.nextBytes(keyLength)
            val ecb = PlatformAesBlock.fromEcb(ecbMode, key)
            val ctr = PlatformAesBlock.fromCtr(provider.get(AES.CTR), key)
            val blocks = List(32) { random.nextBytes(16) } + listOf(ByteArray(16), ByteArray(16) { -1 })
            for (block in blocks) {
                assertEquals(ecb.encryptBlock(block).hex(), ctr.encryptBlock(block).hex(), "$keyLength-byte key, ${block.hex()}")
            }
        }
    }

    @Test
    fun rfc3610VectorsThroughTheCtrConstruction() = runTest {
        for (v in RFC3610_VECTORS) {
            val block = PlatformAesBlock.fromCtr(provider.get(AES.CTR), v.key.hex())
            val ccm = AesCcm(block, tagLength = v.tagLength, nonceLength = v.nonce.length / 2)
            assertEquals(v.ciphertext, ccm.encrypt(v.nonce.hex(), v.plaintext.hex(), v.aad.hex()).hex(), v.name)
            assertEquals(v.plaintext, ccm.decrypt(v.nonce.hex(), v.ciphertext.hex(), v.aad.hex()).hex(), v.name)
        }
    }

    @Test
    fun rejectsWrongKeyAndBlockSizes() = runTest {
        assertFailsWith<IllegalArgumentException> { PlatformAesBlock.fromCtr(provider.get(AES.CTR), ByteArray(20)) }
        val block = PlatformAesBlock.create(provider, ByteArray(16))
        assertFailsWith<IllegalArgumentException> { block.encryptBlock(ByteArray(15)) }
        val ctr = PlatformAesBlock.fromCtr(provider.get(AES.CTR), ByteArray(16))
        assertFailsWith<IllegalArgumentException> { ctr.encryptBlock(ByteArray(17)) }
    }
}
