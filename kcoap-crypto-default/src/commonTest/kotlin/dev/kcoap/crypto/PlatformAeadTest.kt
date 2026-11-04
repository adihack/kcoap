package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Round-trip and tamper checks for every AEAD the default backend offers, on every target. */
class PlatformAeadTest {
    private val backend = DefaultCryptoBackend()

    @Test
    fun everyAeadRoundTripsAndRejectsTampering() = runTest {
        for (algorithm in AeadAlgorithm.entries) {
            val key = backend.randomBytes(algorithm.keyLength)
            val nonce = backend.randomBytes(algorithm.nonceLength)
            val aad = "aad-${algorithm.name}".encodeToByteArray()
            val plaintext = ByteArray(37) { it.toByte() }
            val aead = backend.aead(algorithm, key)

            val ct = aead.encrypt(nonce, plaintext, aad)
            assertEquals(plaintext.size + algorithm.tagLength, ct.size, algorithm.name)
            assertContentEquals(plaintext, aead.decrypt(nonce, ct, aad), algorithm.name)

            ct[0] = (ct[0].toInt() xor 0x80).toByte()
            assertFailsWith<AeadAuthenticationException>(algorithm.name) { aead.decrypt(nonce, ct, aad) }
        }
    }
}
