package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.testkit.Stress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Key agreement and signatures of the default backend, on every target. */
class AsymmetricTest {
    private val backend = DefaultCryptoBackend()

    /** RFC 7748 §6.1. */
    @Test
    fun x25519MatchesRfc7748() = runTest {
        val alice = backend.importKeyPair(
            KeyType.X25519,
            privateKey = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hex(),
            publicKey = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".hex(),
        )
        val bobPublic = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hex()
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            backend.keyAgreement(alice.privateKey, bobPublic).hex(),
        )
    }

    /**
     * RFC 8032 §7.1, test 1 (empty message). The RFC's signature must verify everywhere. RFC 8032 signing
     * is deterministic, so where the backend signs alike twice its signature must be the RFC's; a backend
     * may instead hedge it with randomness (Apple's CryptoKit does), and then its signature must verify.
     */
    @Test
    fun ed25519MatchesRfc8032() = runTest {
        val key = backend.importKeyPair(
            KeyType.ED25519,
            privateKey = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hex(),
            publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hex(),
        )
        val expected = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        assertTrue(backend.verify(SignatureAlgorithm.EDDSA, key.publicKey, ByteArray(0), expected.hex()))
        assertFalse(backend.verify(SignatureAlgorithm.EDDSA, key.publicKey, byteArrayOf(1), expected.hex()))

        val signature = backend.sign(SignatureAlgorithm.EDDSA, key.privateKey, ByteArray(0))
        assertTrue(backend.verify(SignatureAlgorithm.EDDSA, key.publicKey, ByteArray(0), signature))
        if (signature contentEquals backend.sign(SignatureAlgorithm.EDDSA, key.privateKey, ByteArray(0))) {
            assertEquals(expected, signature.hex())
        }
    }

    @Test
    fun generatedKeysAgreeForEveryCurve() = runTest {
        for (type in listOf(KeyType.X25519, KeyType.P256, KeyType.P384)) {
            val a = backend.generateKeyPair(type)
            val b = backend.generateKeyPair(type)
            assertEquals(type.publicKeyLength, a.publicKey.size, type.name)
            assertContentEquals(
                backend.keyAgreement(a.privateKey, b.publicKey),
                backend.keyAgreement(b.privateKey, a.publicKey),
                type.name,
            )
        }
    }

    /** EDHOC sends only the x-coordinate of P-256 keys; ECDH must work from a compressed point. */
    @Test
    fun p256AgreementFromCompressedPoint() = runTest {
        val a = backend.generateKeyPair(KeyType.P256)
        val b = backend.generateKeyPair(KeyType.P256)
        val bX = b.publicKey.copyOfRange(1, 33)
        for (prefix in listOf(0x02, 0x03)) {
            assertContentEquals(
                backend.keyAgreement(b.privateKey, a.publicKey),
                backend.keyAgreement(a.privateKey, byteArrayOf(prefix.toByte()) + bX),
                "prefix $prefix",
            )
        }
    }

    /**
     * Protocol code turns a backend's exceptions into protocol errors (RFC 9528 §9.8), so unusable peer
     * keys must fail with an [Exception] on every platform, also where WebCrypto rejects them with
     * something that is not one. Verification may instead just return false.
     */
    @Test
    fun unusablePeerKeysFailWithAnException() = runTest {
        val p256 = backend.generateKeyPair(KeyType.P256)
        val notOnTheCurve = byteArrayOf(4) + ByteArray(64) { 1 }
        assertFailsWith<Exception> { backend.keyAgreement(p256.privateKey, notOnTheCurve) }
        val xNotOnTheCurve = byteArrayOf(2) + ByteArray(31) + byteArrayOf(1)
        assertFailsWith<IllegalArgumentException> { backend.keyAgreement(p256.privateKey, xNotOnTheCurve) }
        val x25519 = backend.generateKeyPair(KeyType.X25519)
        assertFailsWith<Exception> { backend.keyAgreement(x25519.privateKey, ByteArray(31)) }

        suspend fun rejects(algorithm: SignatureAlgorithm, publicKey: ByteArray): Boolean = try {
            !backend.verify(algorithm, publicKey, ByteArray(1), ByteArray(algorithm.signatureLength))
        } catch (_: Exception) {
            true
        }
        assertTrue(rejects(SignatureAlgorithm.ES256, notOnTheCurve))
        assertTrue(rejects(SignatureAlgorithm.EDDSA, ByteArray(31)))
    }

    @Test
    fun signaturesRoundTripAndRejectTampering() = runTest {
        for (algorithm in listOf(SignatureAlgorithm.EDDSA, SignatureAlgorithm.ES256, SignatureAlgorithm.ES384)) {
            val key = backend.generateKeyPair(algorithm.keyType)
            val data = "signed by ${algorithm.name}".encodeToByteArray()
            val signature = backend.sign(algorithm, key.privateKey, data)
            assertEquals(algorithm.signatureLength, signature.size, algorithm.name)
            assertTrue(backend.verify(algorithm, key.publicKey, data, signature), algorithm.name)
            signature[3] = (signature[3].toInt() xor 1).toByte()
            assertFalse(backend.verify(algorithm, key.publicKey, data, signature), algorithm.name)
            assertFalse(backend.verify(algorithm, key.publicKey, data, signature.copyOf(10)), algorithm.name)
        }
    }

    @Test
    fun rejectsMismatchedOrForeignKeys() = runTest {
        val ed = backend.generateKeyPair(KeyType.ED25519)
        assertFailsWith<IllegalArgumentException> { backend.sign(SignatureAlgorithm.ES256, ed.privateKey, ByteArray(1)) }
        val foreign = object : PrivateKey(KeyType.X25519) {}
        assertFailsWith<IllegalArgumentException> { backend.keyAgreement(foreign, ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(KeyType.X25519, ByteArray(32), ByteArray(31)) }
    }

    @Test
    fun postQuantumIsNotInTheDefaultBackend() = runTest {
        assertFailsWith<UnsupportedAlgorithmException> { backend.generateKeyPair(KeyType.ML_KEM_512) }
        assertFailsWith<UnsupportedAlgorithmException> { backend.encapsulate(KemAlgorithm.ML_KEM_512, ByteArray(800)) }
        val kemKey = SoftwarePrivateKey(KeyType.ML_KEM_512, ByteArray(1632))
        assertFailsWith<UnsupportedAlgorithmException> { backend.decapsulate(kemKey, ByteArray(768)) }
        assertFailsWith<UnsupportedAlgorithmException> { backend.keyAgreement(kemKey, ByteArray(800)) }
        val dsaKey = SoftwarePrivateKey(KeyType.ML_DSA_44, ByteArray(32))
        assertFailsWith<UnsupportedAlgorithmException> { backend.sign(SignatureAlgorithm.ML_DSA_44, dsaKey, ByteArray(1)) }
        assertFailsWith<UnsupportedAlgorithmException> {
            backend.verify(SignatureAlgorithm.ML_DSA_44, ByteArray(1312), ByteArray(1), ByteArray(2420))
        }
    }

    @Test
    fun keysNeverPrintMaterial() = runTest {
        val pair = backend.generateKeyPair(KeyType.X25519)
        assertEquals("KeyPair(X25519)", pair.toString())
        assertEquals("PrivateKey(X25519)", pair.privateKey.toString())
    }

    /** A destroyed key fails closed: its zeroed bytes would be a valid, publicly known key. */
    @Test
    fun destroyedKeysCannotBeUsed() = runTest {
        val pair = backend.generateKeyPair(KeyType.X25519)
        val peer = backend.generateKeyPair(KeyType.X25519)
        val software = pair.privateKey as SoftwarePrivateKey
        software.destroy()
        software.destroy()
        assertFailsWith<IllegalStateException> { software.encoded() }
        assertFailsWith<IllegalStateException> { backend.keyAgreement(software, peer.publicKey) }
    }

    /**
     * A shutdown racing a handshake: a destroy() on another thread while encoded() copies the key never
     * hands out the zeroed bytes. Either the whole key comes back, or the call throws.
     */
    @Test
    fun aKeyDestroyedWhileItIsReadIsNeverReadAsZeros() = runTest {
        val material = ByteArray(32) { (it + 1).toByte() }
        Stress.run(iterations = 200) {
            val key = SoftwarePrivateKey(KeyType.X25519, material)
            // Each reader reads until the key is destroyed and returns what it read before.
            val reader = suspend {
                val reads = mutableListOf<ByteArray>()
                try {
                    repeat(50) { reads += key.encoded() }
                } catch (_: IllegalStateException) {
                }
                reads
            }
            val results = Stress.race(reader, reader, reader, suspend { emptyList<ByteArray>().also { key.destroy() } })
            for (read in results.flatMap { it.getOrThrow() }) assertContentEquals(material, read)
            assertFailsWith<IllegalStateException> { key.encoded() }
        }
    }

    @Test
    fun shake256DigestAndHmacRestrictions() = runTest {
        assertEquals(64, backend.digest(HashAlgorithm.SHAKE256, ByteArray(0)).size)
        assertEquals(32, backend.digest(HashAlgorithm.SHA_256, ByteArray(0)).size)
        assertFailsWith<UnsupportedAlgorithmException> { backend.hmac(HashAlgorithm.SHAKE256, ByteArray(16), ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { backend.aesBlockCipher(ByteArray(15)) }
    }
}
