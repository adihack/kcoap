package dev.kcoap.crypto

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The default `supports` answers (docs/adr/0001-crypto-capabilities.md): no exactly when an operation the
 * capability stands for throws [UnsupportedAlgorithmException].
 */
class ProbeTest {
    @Test
    fun aSignatureNeedsKeyGenerationSigningAndVerification() = runTest {
        assertTrue(Stub("generate", "sign", "verify").supports(SignatureAlgorithm.ES256))
        assertFalse(Stub("sign", "verify").supports(SignatureAlgorithm.ES256))
        assertFalse(Stub("generate", "verify").supports(SignatureAlgorithm.ES256))
        assertFalse(Stub("generate", "sign").supports(SignatureAlgorithm.ES256))
    }

    /** A Diffie-Hellman key type is used for key agreement; the others only need key generation. */
    @Test
    fun diffieHellmanKeyTypesAlsoNeedKeyAgreement() = runTest {
        val generateOnly = Stub("generate")
        for (type in listOf(KeyType.X25519, KeyType.X448, KeyType.P256, KeyType.P384)) {
            assertFalse(generateOnly.supports(type), type.name)
            assertTrue(Stub("generate", "agree").supports(type), type.name)
        }
        for (type in listOf(KeyType.ED25519, KeyType.ML_KEM_512, KeyType.ML_DSA_44)) {
            assertTrue(generateOnly.supports(type), type.name)
        }
        assertFalse(Stub("agree").supports(KeyType.X25519))
    }

    @Test
    fun aKemNeedsKeyGenerationEncapsulationAndDecapsulation() = runTest {
        assertTrue(Stub("generate", "encapsulate", "decapsulate").supports(KemAlgorithm.ML_KEM_768))
        assertFalse(Stub("generate", "encapsulate").supports(KemAlgorithm.ML_KEM_768))
        assertFalse(Stub("generate", "decapsulate").supports(KemAlgorithm.ML_KEM_768))
        assertFalse(Stub("encapsulate", "decapsulate").supports(KemAlgorithm.ML_KEM_768))
    }

    /** HKDF needs the HMAC of a SHA-2 hash; SHAKE256 is used through KMAC, which is common code. */
    @Test
    fun aSha2HashNeedsItsHmacAndShake256DoesNot() = runTest {
        assertTrue(Stub("digest", "hmac").supports(HashAlgorithm.SHA_384))
        assertFalse(Stub("digest").supports(HashAlgorithm.SHA_384))
        assertTrue(Stub("digest").supports(HashAlgorithm.SHAKE256))
        assertFalse(Stub("hmac").supports(HashAlgorithm.SHAKE256))
    }

    @Test
    fun anAeadNeedsToBeBuiltAndToEncrypt() = runTest {
        assertTrue(Stub("aead", "encrypt").supports(AeadAlgorithm.A256GCM))
        assertFalse(Stub("encrypt").supports(AeadAlgorithm.A256GCM))
        assertFalse(Stub("aead").supports(AeadAlgorithm.A256GCM))
    }

    /**
     * Only [UnsupportedAlgorithmException] means no: an operation that rejects the probe's input, or a
     * device that is busy, would fail the same way in use, and the algorithm is there.
     */
    @Test
    fun otherFailuresMeanTheAlgorithmIsThere() = runTest {
        val busy = object : CryptoBackend by Stub("generate", "agree", "verify") {
            override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray) =
                throw IllegalStateException("secure element busy")

            override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray) =
                throw IllegalArgumentException("not a valid peer key")
        }
        assertTrue(Probe.signature(busy, SignatureAlgorithm.EDDSA))
        assertTrue(Probe.keyType(busy, KeyType.X25519))
    }

    @Test
    fun cancellationLeavesTheProbe() = runTest {
        val cancelled = object : CryptoBackend by Stub() {
            override suspend fun generateKeyPair(type: KeyType): KeyPair = throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { Probe.keyType(cancelled, KeyType.P256) }
    }

    @Test
    fun probesDestroyTheirSoftwareKeys() = runTest {
        val stub = Stub("generate", "agree", "sign", "verify", "encapsulate", "decapsulate")
        stub.supports(KeyType.X25519)
        stub.supports(SignatureAlgorithm.EDDSA)
        stub.supports(KemAlgorithm.ML_KEM_512)
        assertEquals(3, stub.generated.size)
        for (key in stub.generated) assertFailsWith<IllegalStateException>(key.toString()) { key.encoded() }
    }

    /**
     * `CryptoBackend by delegate` forwards `supports` too, so a decorator that adds an algorithm must
     * answer for it itself.
     */
    @Test
    fun aDecoratorAnswersWhatItsDelegateAnswers() = runTest {
        val delegate = Stub()
        val adding = object : CryptoBackend by delegate {
            override suspend fun generateKeyPair(type: KeyType): KeyPair =
                KeyPair(ByteArray(type.publicKeyLength), SoftwarePrivateKey(type, ByteArray(32)))
        }
        assertFalse(adding.supports(KeyType.ED25519), "asked of the delegate")
        val answering = object : CryptoBackend by adding {
            override suspend fun supports(type: KeyType): Boolean = type == KeyType.ED25519 || adding.supports(type)
        }
        assertTrue(answering.supports(KeyType.ED25519))
    }

    /** A backend that implements exactly the named operations, on placeholder values. */
    private class Stub(vararg operations: String) : CryptoBackend {
        private val available = operations.toSet()
        val generated: MutableList<SoftwarePrivateKey> = mutableListOf()

        private fun needs(operation: String) {
            if (operation !in available) throw UnsupportedAlgorithmException(operation)
        }

        override suspend fun randomBytes(size: Int): ByteArray = ByteArray(size)

        override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray {
            needs("digest")
            return ByteArray(algorithm.outputLength)
        }

        override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray {
            needs("hmac")
            return ByteArray(algorithm.outputLength)
        }

        override suspend fun aesBlockCipher(key: ByteArray): AesBlockCipher = throw UnsupportedAlgorithmException("AES")

        override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
            needs("aead")
            return object : Aead {
                override val tagLength: Int get() = algorithm.tagLength
                override val nonceLength: Int get() = algorithm.nonceLength

                override suspend fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray) =
                    ByteArray(tagLength).also { needs("encrypt") }

                override suspend fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray) =
                    ByteArray(0)
            }
        }

        override suspend fun generateKeyPair(type: KeyType): KeyPair {
            needs("generate")
            val key = SoftwarePrivateKey(type, ByteArray(32) { 1 })
            generated += key
            return KeyPair(ByteArray(type.publicKeyLength), key)
        }

        override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
            needs("agree")
            return ByteArray(32) { 2 }
        }

        override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray {
            needs("sign")
            return ByteArray(algorithm.signatureLength)
        }

        override suspend fun verify(
            algorithm: SignatureAlgorithm,
            publicKey: ByteArray,
            data: ByteArray,
            signature: ByteArray,
        ): Boolean {
            needs("verify")
            return true
        }

        override suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation {
            needs("encapsulate")
            return KemEncapsulation(ByteArray(algorithm.ciphertextLength), ByteArray(32) { 3 })
        }

        override suspend fun decapsulate(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray {
            needs("decapsulate")
            return ByteArray(32) { 3 }
        }
    }
}
