package dev.kcoap.crypto

import dev.kcoap.common.toHex
import org.bouncycastle.crypto.digests.SHAKEDigest
import org.bouncycastle.crypto.macs.KMAC
import org.bouncycastle.crypto.params.KeyParameter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** SHAKE256 and KMAC256 against Bouncy Castle for many input and output lengths. */
class KeccakDifferentialTest {
    private val random = Random(202)

    @Test
    fun shake256MatchesBouncyCastle() {
        for (length in listOf(0, 1, 135, 136, 137, 271, 272, 273, 1000)) {
            val data = random.nextBytes(length)
            val outputLength = random.nextInt(1, 400)
            val bc = SHAKEDigest(256).apply { update(data, 0, data.size) }
            val expected = ByteArray(outputLength).also { bc.doFinal(it, 0, outputLength) }
            assertEquals(expected.toHex(), Shake256.digest(data, outputLength).toHex(), "input $length, output $outputLength")
        }
    }

    @Test
    fun kmac256MatchesBouncyCastle() {
        for (keyLength in listOf(0, 16, 32, 64, 200)) {
            for (customization in listOf("", "EDHOC")) {
                val key = random.nextBytes(keyLength)
                val data = random.nextBytes(random.nextInt(0, 300))
                val outputLength = random.nextInt(1, 200)
                val s = customization.encodeToByteArray()
                val bc = KMAC(256, s).apply {
                    init(KeyParameter(key))
                    update(data, 0, data.size)
                }
                val expected = ByteArray(outputLength).also { bc.doFinal(it, 0, outputLength) }
                assertEquals(
                    expected.toHex(),
                    Kmac256.compute(key, data, outputLength, s).toHex(),
                    "key $keyLength, S '$customization'",
                )
            }
        }
    }
}
