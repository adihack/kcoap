package dev.kcoap.crypto

import dev.kcoap.common.wipe
import kotlin.coroutines.cancellation.CancellationException

/**
 * The default answers of the `supports` members of [CryptoBackend]: each probe runs the operations once
 * on throwaway keys and inputs, and answers no exactly when one of them throws
 * [UnsupportedAlgorithmException] (docs/adr/0001-crypto-capabilities.md).
 *
 * Any other failure means the algorithm is there: an operation that rejects a probe's input, or a device
 * that is busy, fails the same way when it is used for real, and that failure is not a missing
 * capability. Cancellation propagates. Probes cost key pairs, so backends memoize or override them.
 */
internal object Probe {
    private val diffieHellmanTypes: Set<KeyType> = EcdhCurve.entries.map { it.keyType }.toSet()

    suspend fun aead(crypto: CryptoBackend, algorithm: AeadAlgorithm): Boolean = answer {
        crypto.aead(algorithm, ByteArray(algorithm.keyLength))
            .encrypt(ByteArray(algorithm.nonceLength), ByteArray(0), ByteArray(0))
    }

    suspend fun keyType(crypto: CryptoBackend, type: KeyType): Boolean = answer {
        withKeyPair(crypto, type) { pair ->
            if (type in diffieHellmanTypes) crypto.keyAgreement(pair.privateKey, pair.publicKey).wipe()
        }
    }

    suspend fun signature(crypto: CryptoBackend, algorithm: SignatureAlgorithm): Boolean = answer {
        withKeyPair(crypto, algorithm.keyType) { pair ->
            val signature = crypto.sign(algorithm, pair.privateKey, ByteArray(0))
            crypto.verify(algorithm, pair.publicKey, ByteArray(0), signature)
        }
    }

    suspend fun kem(crypto: CryptoBackend, algorithm: KemAlgorithm): Boolean = answer {
        withKeyPair(crypto, algorithm.keyType) { pair ->
            val encapsulation = crypto.encapsulate(algorithm, pair.publicKey)
            encapsulation.sharedSecret.wipe()
            crypto.decapsulate(pair.privateKey, encapsulation.ciphertext).wipe()
        }
    }

    suspend fun hash(crypto: CryptoBackend, algorithm: HashAlgorithm): Boolean = answer {
        crypto.digest(algorithm, ByteArray(0))
        if (!algorithm.isXof) crypto.hmac(algorithm, ByteArray(algorithm.outputLength), ByteArray(0))
    }

    private inline fun answer(probe: () -> Unit): Boolean = try {
        probe()
        true
    } catch (_: UnsupportedAlgorithmException) {
        false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        true
    }

    private suspend inline fun withKeyPair(crypto: CryptoBackend, type: KeyType, use: (KeyPair) -> Unit) {
        val pair = crypto.generateKeyPair(type)
        try {
            use(pair)
        } finally {
            (pair.privateKey as? SoftwarePrivateKey)?.destroy()
        }
    }
}
