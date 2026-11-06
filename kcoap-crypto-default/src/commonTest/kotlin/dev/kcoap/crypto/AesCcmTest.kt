package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.vectors.CcmVector
import dev.kcoap.crypto.vectors.RFC3610_VECTORS
import dev.kcoap.crypto.vectors.RFC8613_AEAD_VECTORS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AesCcmTest {
    private val backend = DefaultCryptoBackend()

    private suspend fun ccm(v: CcmVector) =
        AesCcm(backend.aesBlockCipher(v.key.hex()), tagLength = v.tagLength, nonceLength = v.nonce.length / 2)

    @Test
    fun rfc3610PacketVectors() = runTest {
        assertEquals(24, RFC3610_VECTORS.size)
        RFC3610_VECTORS.forEach { v -> checkVector(v, ccm(v)) }
    }

    @Test
    fun rfc8613OscoreVectorsThroughBackendAead() = runTest {
        RFC8613_AEAD_VECTORS.forEach { v ->
            checkVector(v, backend.aead(AeadAlgorithm.AES_CCM_16_64_128, v.key.hex()))
        }
    }

    @Test
    fun rejectsTamperedTagCiphertextAndAad() = runTest {
        val v = RFC8613_AEAD_VECTORS.first()
        val aead = ccm(v)
        val ct = v.ciphertext.hex()
        for (index in ct.indices) {
            val tampered = ct.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertFailsWith<AeadAuthenticationException>("byte $index") {
                aead.decrypt(v.nonce.hex(), tampered, v.aad.hex())
            }
        }
        assertFailsWith<AeadAuthenticationException> {
            aead.decrypt(v.nonce.hex(), ct, v.aad.hex() + byteArrayOf(0))
        }
        assertFailsWith<AeadAuthenticationException> {
            aead.decrypt(v.nonce.hex(), ct.copyOf(v.tagLength - 1), v.aad.hex())
        }
    }

    @Test
    fun emptyPlaintextAndEmptyAad() = runTest {
        val aead = backend.aead(AeadAlgorithm.AES_CCM_16_128_128, ByteArray(16) { it.toByte() })
        val nonce = ByteArray(13) { (0xA0 + it).toByte() }
        val ct = aead.encrypt(nonce, ByteArray(0), ByteArray(0))
        assertEquals(16, ct.size)
        assertEquals(0, aead.decrypt(nonce, ct, ByteArray(0)).size)
    }

    private suspend fun checkVector(v: CcmVector, aead: Aead) {
        assertEquals(v.ciphertext, aead.encrypt(v.nonce.hex(), v.plaintext.hex(), v.aad.hex()).hex(), v.name)
        assertEquals(v.plaintext, aead.decrypt(v.nonce.hex(), v.ciphertext.hex(), v.aad.hex()).hex(), v.name)
    }
}
