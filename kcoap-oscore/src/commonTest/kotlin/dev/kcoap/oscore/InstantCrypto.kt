package dev.kcoap.oscore

import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.AesBlockCipher
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * [delegate] with what OSCORE uses taking no virtual time under `runTest`, for tests that run real
 * cryptography under CoAP timers (as kcoap-edhoc-oscore's tests do). Where cryptography really suspends
 * (WebCrypto answers through promises), the test scheduler would find nothing to run while an answer is
 * pending and jump to the next timer, so retransmissions and exchange timeouts would fire. Each operation
 * here keeps one coroutine runnable on the test dispatcher until it completes, so virtual time stands still
 * meanwhile. Tests using it drive time with `delay`, not `advanceTimeBy`.
 */
internal class InstantCrypto(private val delegate: CryptoBackend) : CryptoBackend by delegate {
    override suspend fun randomBytes(size: Int): ByteArray = instant { delegate.randomBytes(size) }

    override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray = instant { delegate.digest(algorithm, data) }

    override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
        instant { delegate.hmac(algorithm, key, data) }

    override suspend fun aesBlockCipher(key: ByteArray): AesBlockCipher {
        val block = instant { delegate.aesBlockCipher(key) }
        return AesBlockCipher { input -> instant { block.encryptBlock(input) } }
    }

    override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
        val aead = instant { delegate.aead(algorithm, key) }
        return object : Aead by aead {
            override suspend fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray) =
                instant { aead.encrypt(nonce, plaintext, associatedData) }

            override suspend fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray) =
                instant { aead.decrypt(nonce, ciphertext, associatedData) }
        }
    }

    private suspend fun <T> instant(operation: suspend () -> T): T = coroutineScope {
        val keepRunnable = launch {
            while (true) yield()
        }
        try {
            operation()
        } finally {
            keepRunnable.cancel()
        }
    }
}
