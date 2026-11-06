package dev.kcoap.crypto.testsuite

import dev.kcoap.common.toHex
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KemEncapsulation
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.Shake256
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.UnsupportedAlgorithmException

/**
 * A key-encapsulation mechanism for ML-KEM-512 that is not ML-KEM: keys and ciphertexts of the right lengths,
 * random secrets it remembers per ciphertext, and SHAKE256 of a ciphertext it does not know as its implicit
 * rejection. Each flag breaks one more thing.
 *
 * @param importsKeys whether `importKeyPair` takes ML-KEM keys or refuses them as unsupported, as a key store may.
 * @param rejectsImplicitly whether an unknown ciphertext gets its own secret or the last one handed out.
 * @param ciphertextLength the length of the ciphertexts it makes.
 */
internal class ToyKem(
    private val base: CryptoBackend,
    private val importsKeys: Boolean = true,
    private val rejectsImplicitly: Boolean = true,
    private val ciphertextLength: Int = KemAlgorithm.ML_KEM_512.ciphertextLength,
) : CryptoBackend by base {
    private val secrets = HashMap<String, ByteArray>()
    private var last = ByteArray(KemAlgorithm.ML_KEM_512.sharedSecretLength)

    override suspend fun supports(algorithm: KemAlgorithm): Boolean = algorithm == KemAlgorithm.ML_KEM_512

    override suspend fun generateKeyPair(type: KeyType): KeyPair =
        if (type != KeyType.ML_KEM_512) {
            base.generateKeyPair(type)
        } else {
            KeyPair(base.randomBytes(type.publicKeyLength), SoftwarePrivateKey(type, base.randomBytes(64)))
        }

    override suspend fun importKeyPair(type: KeyType, privateKey: ByteArray, publicKey: ByteArray): KeyPair =
        if (type == KeyType.ML_KEM_512 && !importsKeys) {
            throw UnsupportedAlgorithmException("import of ${type.name}")
        } else {
            base.importKeyPair(type, privateKey, publicKey)
        }

    override suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation {
        if (algorithm != KemAlgorithm.ML_KEM_512) throw UnsupportedAlgorithmException(algorithm.name)
        val ciphertext = base.randomBytes(ciphertextLength)
        val secret = base.randomBytes(algorithm.sharedSecretLength)
        secrets[ciphertext.toHex()] = secret.copyOf()
        last = secret.copyOf()
        return KemEncapsulation(ciphertext, secret)
    }

    override suspend fun decapsulate(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray {
        if (privateKey.type != KeyType.ML_KEM_512) throw UnsupportedAlgorithmException(privateKey.type.name)
        secrets[ciphertext.toHex()]?.let { return it.copyOf() }
        return if (rejectsImplicitly) Shake256.digest(ciphertext, last.size) else last.copyOf()
    }
}
