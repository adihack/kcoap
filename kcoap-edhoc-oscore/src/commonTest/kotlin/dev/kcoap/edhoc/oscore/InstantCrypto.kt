package dev.kcoap.edhoc.oscore

import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.AesBlockCipher
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KemEncapsulation
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SignatureAlgorithm
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * [delegate] with every operation taking no virtual time under `runTest`, for tests that run real
 * cryptography under CoAP timers.
 *
 * When cryptography completes without suspending (JCA, OpenSSL, CommonCrypto), virtual time measures
 * protocol time only. When it really suspends (WebCrypto answers through promises; a remote key store
 * would too), the test scheduler finds nothing to run while an answer is pending and jumps to the next
 * timer: a handshake would see its retransmissions and exchange timeouts fire. Each operation here
 * keeps one coroutine runnable on the test dispatcher until it completes, so virtual time stands still
 * meanwhile; the answer still arrives, because coroutine dispatchers yield to the event loop every few
 * tasks.
 *
 * Tests using it drive time with `delay`, not with `advanceTimeBy`, which would spin synchronously on
 * the runnable coroutine and never let the promise resolve.
 */
internal class InstantCrypto(private val delegate: CryptoBackend) : CryptoBackend {
    override suspend fun randomBytes(size: Int): ByteArray = instant { delegate.randomBytes(size) }

    override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
        instant { delegate.digest(algorithm, data) }

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

    override suspend fun generateKeyPair(type: KeyType): KeyPair = instant { delegate.generateKeyPair(type) }

    override suspend fun importKeyPair(type: KeyType, privateKey: ByteArray, publicKey: ByteArray): KeyPair =
        instant { delegate.importKeyPair(type, privateKey, publicKey) }

    override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
        instant { delegate.keyAgreement(privateKey, peerPublicKey) }

    override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray =
        instant { delegate.sign(algorithm, privateKey, data) }

    override suspend fun verify(algorithm: SignatureAlgorithm, publicKey: ByteArray, data: ByteArray, signature: ByteArray) =
        instant { delegate.verify(algorithm, publicKey, data, signature) }

    override suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation =
        instant { delegate.encapsulate(algorithm, publicKey) }

    override suspend fun decapsulate(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray =
        instant { delegate.decapsulate(privateKey, ciphertext) }

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
