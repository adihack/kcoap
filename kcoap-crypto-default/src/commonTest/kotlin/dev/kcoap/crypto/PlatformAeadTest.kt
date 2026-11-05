package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Round-trip and tamper checks for every AEAD the default backend offers, on every target. */
class PlatformAeadTest {
    private val backend = DefaultCryptoBackend()

    /** What the platform lacks is reported as unsupported and is nothing else; what it has works. */
    @Test
    fun everyAeadRoundTripsAndRejectsTamperingOrIsUnsupported() = runTest {
        for (algorithm in AeadAlgorithm.entries) {
            val key = backend.randomBytes(algorithm.keyLength)
            val nonce = backend.randomBytes(algorithm.nonceLength)
            val aad = "aad-${algorithm.name}".encodeToByteArray()
            val plaintext = ByteArray(37) { it.toByte() }
            val aead = try {
                backend.aead(algorithm, key)
            } catch (_: UnsupportedAlgorithmException) {
                assertFalse(backend.supports(algorithm), algorithm.name)
                continue
            }
            assertTrue(backend.supports(algorithm), algorithm.name)

            val ct = aead.encrypt(nonce, plaintext, aad)
            assertEquals(plaintext.size + algorithm.tagLength, ct.size, algorithm.name)
            assertContentEquals(plaintext, aead.decrypt(nonce, ct, aad), algorithm.name)

            ct[0] = (ct[0].toInt() xor 0x80).toByte()
            assertFailsWith<AeadAuthenticationException>(algorithm.name) { aead.decrypt(nonce, ct, aad) }
        }
    }

    /**
     * Every platform must run OSCORE's default AEAD (RFC 8613 §3.2), the AEADs of EDHOC's mandatory
     * suites 2 and 3 (RFC 9528 §8), and AES-GCM, which all platform providers have.
     */
    @Test
    fun theMandatoryAeadsAreSupportedEverywhere() = runTest {
        val mandatory = listOf(
            AeadAlgorithm.AES_CCM_16_64_128,
            AeadAlgorithm.AES_CCM_16_128_128,
            AeadAlgorithm.A128GCM,
            AeadAlgorithm.A256GCM,
        )
        for (algorithm in mandatory) assertTrue(backend.supports(algorithm), algorithm.name)
    }
}
