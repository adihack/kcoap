package com.example.kcoap.crypto

import com.example.kcoap.crypto.backend.DefaultCryptoBackend
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.AEADCipher
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Differential test against BouncyCastle's independent implementations. Covers what the RFC 3610 vectors
 * do not: 16-byte tags (AES-CCM-16-128-128, EDHOC suites 1 and 3), 7-byte nonces, 256-bit keys, the
 * 6-byte AAD length encoding (AAD >= 0xFF00 bytes), and the platform GCM/ChaCha20 wiring.
 */
class BouncyCastleDifferentialTest {
    private val backend = DefaultCryptoBackend()
    private val random = Random(0x0C0A9)

    @Test
    fun aesCcmMatchesBouncyCastleForAllParameterCombinations() {
        for (keyLength in listOf(16, 32)) {
            for (nonceLength in 7..13) {
                for (tagLength in 4..16 step 2) {
                    repeat(5) {
                        val k = random.nextBytes(keyLength)
                        compare(
                            key = k,
                            ours = AesCcm(backend.aesBlockCipher(k), tagLength, nonceLength),
                            theirs = CCMBlockCipher.newInstance(AESEngine.newInstance()),
                            nonceLength = nonceLength,
                            tagLength = tagLength,
                            plaintextLength = random.nextInt(0, 100),
                            aadLength = random.nextInt(0, 60),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aesCcmLongAadUsesSixByteLengthEncoding() {
        for (aadLength in listOf(0xFEFF, 0xFF00, 0x1_0000 + 3)) {
            val k = random.nextBytes(16)
            compare(
                key = k,
                ours = AesCcm(backend.aesBlockCipher(k), tagLength = 16, nonceLength = 13),
                theirs = CCMBlockCipher.newInstance(AESEngine.newInstance()),
                nonceLength = 13, tagLength = 16, plaintextLength = 33, aadLength = aadLength,
            )
        }
    }

    @Test
    fun platformGcmAndChaCha20MatchBouncyCastle() {
        val cases = mapOf(
            AeadAlgorithm.A128GCM to { GCMBlockCipher.newInstance(AESEngine.newInstance()) as AEADCipher },
            AeadAlgorithm.A256GCM to { GCMBlockCipher.newInstance(AESEngine.newInstance()) as AEADCipher },
            AeadAlgorithm.CHACHA20_POLY1305 to { ChaCha20Poly1305() as AEADCipher },
        )
        for ((algorithm, theirs) in cases) {
            repeat(10) {
                val k = random.nextBytes(algorithm.keyLength)
                compare(
                    key = k,
                    ours = backend.aead(algorithm, k),
                    theirs = theirs(),
                    nonceLength = algorithm.nonceLength,
                    tagLength = algorithm.tagLength,
                    plaintextLength = random.nextInt(0, 100),
                    aadLength = random.nextInt(0, 60),
                )
            }
        }
    }

    private fun compare(
        key: ByteArray,
        ours: Aead,
        theirs: AEADCipher,
        nonceLength: Int,
        tagLength: Int,
        plaintextLength: Int,
        aadLength: Int,
    ) {
        val nonce = random.nextBytes(nonceLength)
        val aad = random.nextBytes(aadLength)
        val plaintext = random.nextBytes(plaintextLength)

        theirs.init(true, AEADParameters(KeyParameter(key), tagLength * 8, nonce, aad))
        val expected = ByteArray(theirs.getOutputSize(plaintext.size))
        val written = theirs.processBytes(plaintext, 0, plaintext.size, expected, 0)
        theirs.doFinal(expected, written)

        val label = "${theirs.algorithmName} key=${key.size} nonce=$nonceLength tag=$tagLength " +
            "pt=$plaintextLength aad=$aadLength"
        assertEquals(expected.hex(), ours.encrypt(nonce, plaintext, aad).hex(), label)
        assertEquals(plaintext.hex(), ours.decrypt(nonce, expected, aad).hex(), label)
    }
}
