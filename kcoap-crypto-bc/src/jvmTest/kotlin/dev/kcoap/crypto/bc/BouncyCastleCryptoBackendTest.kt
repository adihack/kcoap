package dev.kcoap.crypto.bc

import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.AesBlockCipher
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.Shake256
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.bc.vectors.ML_DSA_KEYGEN_VECTORS
import dev.kcoap.crypto.bc.vectors.ML_KEM_KEYGEN_VECTORS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Round trips, input checks, delegation and key hygiene of [BouncyCastleCryptoBackend]. */
class BouncyCastleCryptoBackendTest {
    private val backend = BouncyCastleCryptoBackend()
    private val data = "EDHOC message_2".encodeToByteArray()
    private val mlDsa = listOf(SignatureAlgorithm.ML_DSA_44, SignatureAlgorithm.ML_DSA_65, SignatureAlgorithm.ML_DSA_87)

    @Test
    fun kemRoundTripsForEveryParameterSet() = runTest {
        for (algorithm in KemAlgorithm.entries) {
            val pair = backend.generateKeyPair(algorithm.keyType)

            val encapsulation = backend.encapsulate(algorithm, pair.publicKey)

            assertEquals(algorithm.keyType.publicKeyLength, pair.publicKey.size, algorithm.name)
            assertEquals(MlKem.SEED_LENGTH, pair.privateKey.encoded.size, algorithm.name)
            assertEquals(algorithm.ciphertextLength, encapsulation.ciphertext.size, algorithm.name)
            assertEquals(algorithm.sharedSecretLength, encapsulation.sharedSecret.size, algorithm.name)
            assertContentEquals(
                encapsulation.sharedSecret,
                backend.decapsulate(pair.privateKey, encapsulation.ciphertext),
                algorithm.name,
            )
        }
    }

    /**
     * FIPS 203 Algorithm 18, lines 7-10, checked against kcoap's own SHAKE256: a tampered ciphertext decapsulates
     * to `J(z || c) = SHAKE256(z || c, 32)` instead of failing, where `z` is the second half of the seed.
     */
    @Test
    fun tamperedCiphertextYieldsTheImplicitRejectionSecret() = runTest {
        for (algorithm in KemAlgorithm.entries) {
            val seed = ByteArray(MlKem.SEED_LENGTH) { it.toByte() }
            val z = seed.copyOfRange(32, 64)
            val pair = BouncyCastleCryptoBackend(ScriptedRandomBackend(seed)).generateKeyPair(algorithm.keyType)
            val encapsulation = backend.encapsulate(algorithm, pair.publicKey)

            for (index in listOf(0, algorithm.ciphertextLength - 1)) {
                val tampered = encapsulation.ciphertext.flipped(index)

                val secret = backend.decapsulate(pair.privateKey, tampered)

                assertContentEquals(Shake256.digest(z + tampered, algorithm.sharedSecretLength), secret, algorithm.name)
                assertFalse(secret.contentEquals(encapsulation.sharedSecret), algorithm.name)
            }
        }
    }

    @Test
    fun kemRejectsMalformedInput() = runTest {
        val pair = backend.generateKeyPair(KeyType.ML_KEM_512)
        val ciphertext = ByteArray(KemAlgorithm.ML_KEM_512.ciphertextLength)

        val shortKey = pair.publicKey.copyOf(799)
        assertFailsWith<IllegalArgumentException> { backend.encapsulate(KemAlgorithm.ML_KEM_512, shortKey) }
        assertFailsWith<IllegalArgumentException> { backend.encapsulate(KemAlgorithm.ML_KEM_768, pair.publicKey) }
        assertFailsWith<IllegalArgumentException> { backend.decapsulate(pair.privateKey, ciphertext.copyOf(767)) }
        assertFailsWith<IllegalArgumentException> { backend.decapsulate(pair.privateKey, ciphertext + byteArrayOf(0)) }
        val wrongLength = SoftwarePrivateKey(KeyType.ML_KEM_512, ByteArray(MlKem.SEED_LENGTH + 1))
        assertFailsWith<IllegalArgumentException> { backend.decapsulate(wrongLength, ciphertext) }
        val foreign = object : PrivateKey(KeyType.ML_KEM_512) {}
        assertFailsWith<IllegalArgumentException> { backend.decapsulate(foreign, ciphertext) }
    }

    @Test
    fun kemImportKeepsTheEncodingAndChecksThatTheKeysBelongTogether() = runTest {
        val type = KeyType.ML_KEM_768
        val a = backend.generateKeyPair(type)
        val b = backend.generateKeyPair(type)
        val seed = a.privateKey.encoded

        val imported = backend.importKeyPair(type, seed, a.publicKey)

        assertContentEquals(a.publicKey, imported.publicKey)
        assertContentEquals(a.privateKey.encoded, imported.privateKey.encoded)
        assertContentEquals(a.privateKey.encoded, seed, "the caller's array is left untouched")
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, a.privateKey.encoded, b.publicKey) }
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, ByteArray(63), a.publicKey) }
        val shortKey = a.publicKey.copyOf(1183)
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, a.privateKey.encoded, shortKey) }
        val expanded = ML_KEM_KEYGEN_VECTORS.single { it.type == KeyType.ML_KEM_512 }.dk.hex()
        val other512 = backend.generateKeyPair(KeyType.ML_KEM_512).publicKey
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(KeyType.ML_KEM_512, expanded, other512) }
    }

    @Test
    fun signaturesRoundTripForEveryParameterSet() = runTest {
        for (algorithm in mlDsa) {
            val pair = backend.generateKeyPair(algorithm.keyType)
            val other = backend.generateKeyPair(algorithm.keyType)

            val signature = backend.sign(algorithm, pair.privateKey, data)

            assertEquals(algorithm.keyType.publicKeyLength, pair.publicKey.size, algorithm.name)
            assertEquals(MlDsa.SEED_LENGTH, pair.privateKey.encoded.size, algorithm.name)
            assertEquals(algorithm.signatureLength, signature.size, algorithm.name)
            assertTrue(backend.verify(algorithm, pair.publicKey, data, signature), algorithm.name)
            assertFalse(backend.verify(algorithm, pair.publicKey, data.flipped(0), signature), algorithm.name)
            assertFalse(backend.verify(algorithm, pair.publicKey, data, signature.flipped(7)), algorithm.name)
            assertFalse(backend.verify(algorithm, other.publicKey, data, signature), algorithm.name)
            val empty = backend.sign(algorithm, pair.privateKey, ByteArray(0))
            assertTrue(backend.verify(algorithm, pair.publicKey, ByteArray(0), empty), algorithm.name)
        }
    }

    /** FIPS 204 §3.4: hedged signing draws a fresh `rnd` for every signature; the deterministic variant repeats. */
    @Test
    fun hedgedSignaturesDifferAndDeterministicOnesRepeat() = runTest {
        val deterministic = BouncyCastleCryptoBackend(isSigningDeterministic = true)
        val key = backend.generateKeyPair(KeyType.ML_DSA_44).privateKey
        val algorithm = SignatureAlgorithm.ML_DSA_44

        assertFalse(backend.sign(algorithm, key, data).contentEquals(backend.sign(algorithm, key, data)))
        assertContentEquals(deterministic.sign(algorithm, key, data), deterministic.sign(algorithm, key, data))
    }

    /** The deterministic variant is hedged signing with `rnd = {0}^32` (FIPS 204 Algorithm 2). */
    @Test
    fun deterministicSigningIsHedgedSigningWithZeroRnd() = runTest {
        val random = ScriptedRandomBackend(ByteArray(MlDsa.RND_LENGTH))
        val hedged = BouncyCastleCryptoBackend(random)
        val deterministic = BouncyCastleCryptoBackend(isSigningDeterministic = true)
        val key = backend.generateKeyPair(KeyType.ML_DSA_65).privateKey

        val signature = hedged.sign(SignatureAlgorithm.ML_DSA_65, key, data)

        assertContentEquals(deterministic.sign(SignatureAlgorithm.ML_DSA_65, key, data), signature)
        assertEquals(0, random.remaining)
    }

    /**
     * Import checks that the private key derives the given `pk` and that its `tr` is `H(pk, 64)`. Flipping a byte
     * of `tr` (offset 64 of `sk`) leaves the derived public key unchanged, so only the `tr` check catches it.
     * The caller's arrays are left untouched.
     */
    @Test
    fun dsaImportKeepsTheEncodingAndChecksThatTheKeysBelongTogether() = runTest {
        val vector = ML_DSA_KEYGEN_VECTORS.single { it.algorithm == SignatureAlgorithm.ML_DSA_44 }
        val type = KeyType.ML_DSA_44
        val (seed, pk, sk) = Triple(vector.seed.hex(), vector.pk.hex(), vector.sk.hex())

        assertHexEquals(vector.seed, backend.importKeyPair(type, seed, pk).privateKey.encoded, "seed")
        assertHexEquals(vector.sk, backend.importKeyPair(type, sk, pk).privateKey.encoded, "expanded")
        assertHexEquals(vector.seed, seed, "caller's seed")
        assertHexEquals(vector.sk, sk, "caller's expanded key")
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, ByteArray(31), pk) }
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, sk.copyOf(2559), pk) }
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, seed, pk.copyOf(1311)) }
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, seed.flipped(0), pk) }
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, seed, pk.flipped(40)) }
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, sk.flipped(0), pk) }
        assertFailsWith<IllegalArgumentException> { backend.importKeyPair(type, sk.flipped(64), pk) }
    }

    @Test
    fun dsaRejectsKeysOfAnotherTypeOrBackend() = runTest {
        val algorithm = SignatureAlgorithm.ML_DSA_44
        val kem = backend.generateKeyPair(KeyType.ML_KEM_512)
        val dsa65 = backend.generateKeyPair(KeyType.ML_DSA_65)

        assertFailsWith<IllegalArgumentException> { backend.sign(algorithm, kem.privateKey, data) }
        assertFailsWith<IllegalArgumentException> { backend.sign(algorithm, dsa65.privateKey, data) }
        val foreign = object : PrivateKey(KeyType.ML_DSA_44) {}
        assertFailsWith<IllegalArgumentException> { backend.sign(algorithm, foreign, data) }
        val wrongLength = SoftwarePrivateKey(KeyType.ML_DSA_44, ByteArray(MlDsa.SEED_LENGTH + 1))
        assertFailsWith<IllegalArgumentException> { backend.sign(algorithm, wrongLength, data) }
        assertFailsWith<IllegalArgumentException> { backend.verify(algorithm, dsa65.publicKey, data, ByteArray(2420)) }
        assertFailsWith<IllegalArgumentException> { backend.verify(algorithm, ByteArray(0), data, ByteArray(2420)) }
    }

    /** RFC 7748 §6.1 and RFC 8032 §7.1 test 1 through the post-quantum backend, plus the symmetric primitives. */
    @Test
    fun classicalAlgorithmsAreDelegated() = runTest {
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
        val a = backend.generateKeyPair(KeyType.X25519)
        val b = backend.generateKeyPair(KeyType.X25519)
        assertContentEquals(
            backend.keyAgreement(a.privateKey, b.publicKey),
            backend.keyAgreement(b.privateKey, a.publicKey),
        )

        val ed = backend.importKeyPair(
            KeyType.ED25519,
            privateKey = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hex(),
            publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hex(),
        )
        val signature = backend.sign(SignatureAlgorithm.EDDSA, ed.privateKey, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555" +
                "fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            signature.hex(),
        )
        assertTrue(backend.verify(SignatureAlgorithm.EDDSA, ed.publicKey, ByteArray(0), signature))
        assertFailsWith<IllegalArgumentException> {
            backend.importKeyPair(KeyType.X25519, ByteArray(32), ByteArray(31))
        }

        // FIPS 180-4 "abc", RFC 4231 test case 1, FIPS 197 Appendix C.1.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            backend.digest(HashAlgorithm.SHA_256, "abc".encodeToByteArray()).hex(),
        )
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            backend.hmac(HashAlgorithm.SHA_256, ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).hex(),
        )
        val aes: AesBlockCipher = backend.aesBlockCipher("000102030405060708090a0b0c0d0e0f".hex())
        val block = aes.encryptBlock("00112233445566778899aabbccddeeff".hex())
        assertHexEquals("69c4e0d86a7b0430d8cdb78070b4c55a", block, "FIPS 197 C.1")
        val aead = backend.aead(AeadAlgorithm.AES_CCM_16_64_128, ByteArray(16))
        val nonce = ByteArray(13)
        assertContentEquals(data, aead.decrypt(nonce, aead.encrypt(nonce, data, ByteArray(0)), ByteArray(0)))
        assertEquals(16, backend.randomBytes(16).size)
    }

    @Test
    fun unsupportedCombinationsStayUnsupported() = runTest {
        val kem = backend.generateKeyPair(KeyType.ML_KEM_512)
        val dsa = backend.generateKeyPair(KeyType.ML_DSA_44)
        val x25519 = backend.generateKeyPair(KeyType.X25519)

        assertFailsWith<UnsupportedAlgorithmException> { backend.keyAgreement(kem.privateKey, kem.publicKey) }
        assertFailsWith<UnsupportedAlgorithmException> { backend.keyAgreement(dsa.privateKey, x25519.publicKey) }
        assertFailsWith<UnsupportedAlgorithmException> { backend.decapsulate(x25519.privateKey, ByteArray(32)) }
        assertFailsWith<UnsupportedAlgorithmException> { backend.decapsulate(dsa.privateKey, ByteArray(768)) }
        assertFailsWith<IllegalArgumentException> { backend.sign(SignatureAlgorithm.EDDSA, dsa.privateKey, data) }
    }

    /**
     * ML-KEM and ML-DSA need nothing from the classical backend but randomness, so they work on top of a minimal
     * one; the classical asymmetric operations it lacks stay unsupported.
     */
    @Test
    fun postQuantumWorksOnAMinimalClassicalBackend() = runTest {
        val minimal = BouncyCastleCryptoBackend(SymmetricOnlyBackend())
        val kem = minimal.generateKeyPair(KeyType.ML_KEM_512)
        val encapsulation = minimal.encapsulate(KemAlgorithm.ML_KEM_512, kem.publicKey)
        assertContentEquals(encapsulation.sharedSecret, minimal.decapsulate(kem.privateKey, encapsulation.ciphertext))
        val dsa = minimal.generateKeyPair(KeyType.ML_DSA_44)
        val signature = minimal.sign(SignatureAlgorithm.ML_DSA_44, dsa.privateKey, data)
        assertTrue(minimal.verify(SignatureAlgorithm.ML_DSA_44, dsa.publicKey, data, signature))

        assertFailsWith<UnsupportedAlgorithmException> { minimal.generateKeyPair(KeyType.X25519) }
        val x25519 = minimal.importKeyPair(KeyType.X25519, ByteArray(32), ByteArray(32))
        assertFailsWith<UnsupportedAlgorithmException> { minimal.keyAgreement(x25519.privateKey, ByteArray(32)) }
        assertFailsWith<UnsupportedAlgorithmException> { minimal.decapsulate(x25519.privateKey, ByteArray(32)) }
        assertFailsWith<UnsupportedAlgorithmException> {
            minimal.sign(SignatureAlgorithm.EDDSA, x25519.privateKey, data)
        }
        assertFailsWith<UnsupportedAlgorithmException> {
            minimal.verify(SignatureAlgorithm.ES256, ByteArray(65), data, ByteArray(64))
        }
    }

    /** Random bytes drawn for seeds, `m` and `rnd` are wiped once used; the key keeps its own copy of the seed. */
    @Test
    fun drawnRandomnessIsWipedAfterUse() = runTest {
        val handedOut = mutableListOf<ByteArray>()
        val recording = object : CryptoBackend by DefaultCryptoBackend() {
            override suspend fun randomBytes(size: Int): ByteArray = ByteArray(size) { 7 }.also { handedOut += it }
        }
        val pq = BouncyCastleCryptoBackend(recording)

        val dsa = pq.generateKeyPair(KeyType.ML_DSA_44)
        pq.sign(SignatureAlgorithm.ML_DSA_44, dsa.privateKey, data)
        pq.encapsulate(KemAlgorithm.ML_KEM_512, pq.generateKeyPair(KeyType.ML_KEM_512).publicKey)

        assertEquals(listOf(32, 32, 64, 32), handedOut.map { it.size })
        assertTrue(handedOut.all { bytes -> bytes.all { it == 0.toByte() } })
        assertContentEquals(ByteArray(32) { 7 }, dsa.privateKey.encoded)
    }

    /** A random source that hands out fewer bytes than asked is caught before a key or signature depends on it. */
    @Test
    fun shortRandomnessIsRejected() = runTest {
        val short = object : CryptoBackend by DefaultCryptoBackend() {
            override suspend fun randomBytes(size: Int): ByteArray = ByteArray(size - 1)
        }
        val pq = BouncyCastleCryptoBackend(short)
        val kem = backend.generateKeyPair(KeyType.ML_KEM_512)
        val dsa = backend.generateKeyPair(KeyType.ML_DSA_44)

        assertFailsWith<IllegalStateException> { pq.generateKeyPair(KeyType.ML_KEM_512) }
        assertFailsWith<IllegalStateException> { pq.generateKeyPair(KeyType.ML_DSA_44) }
        assertFailsWith<IllegalStateException> { pq.encapsulate(KemAlgorithm.ML_KEM_512, kem.publicKey) }
        assertFailsWith<IllegalStateException> { pq.sign(SignatureAlgorithm.ML_DSA_44, dsa.privateKey, data) }
    }

    /**
     * ML-KEM and ML-DSA are answered without probing anything; every other question is the classical
     * backend's to answer, also where it lacks what the default backend has (docs/adr/0001-crypto-capabilities.md).
     */
    @Test
    fun supportsAnswersPostQuantumItselfAndAsksTheClassicalBackendTheRest() = runTest {
        val asked = mutableListOf<Any>()
        val classical = object : CryptoBackend by SymmetricOnlyBackend() {
            override suspend fun supports(algorithm: AeadAlgorithm) =
                (algorithm != AeadAlgorithm.CHACHA20_POLY1305).also { asked += algorithm }

            override suspend fun supports(type: KeyType) = false.also { asked += type }
            override suspend fun supports(algorithm: SignatureAlgorithm) = false.also { asked += algorithm }
            override suspend fun supports(algorithm: KemAlgorithm) = false.also { asked += algorithm }
            override suspend fun supports(algorithm: HashAlgorithm) = true.also { asked += algorithm }
        }
        val pq = BouncyCastleCryptoBackend(classical)

        for (algorithm in KemAlgorithm.entries) {
            assertTrue(pq.supports(algorithm), algorithm.name)
            assertTrue(pq.supports(algorithm.keyType), algorithm.name)
        }
        for (algorithm in mlDsa) {
            assertTrue(pq.supports(algorithm), algorithm.name)
            assertTrue(pq.supports(algorithm.keyType), algorithm.name)
        }
        assertEquals(emptyList(), asked, "the post-quantum answers ask nobody")

        assertFalse(pq.supports(KeyType.X25519))
        assertFalse(pq.supports(SignatureAlgorithm.EDDSA))
        assertFalse(pq.supports(AeadAlgorithm.CHACHA20_POLY1305))
        assertTrue(pq.supports(HashAlgorithm.SHA_256))
        val expected = listOf<Any>(
            KeyType.X25519, SignatureAlgorithm.EDDSA, AeadAlgorithm.CHACHA20_POLY1305, HashAlgorithm.SHA_256,
        )
        assertEquals(expected, asked)
        assertTrue(backend.supports(KeyType.P256), "the default classical backend has P-256")
    }

    @Test
    fun keysNeverPrintMaterial() = runTest {
        val pair = backend.generateKeyPair(KeyType.ML_KEM_512)
        val encapsulation = backend.encapsulate(KemAlgorithm.ML_KEM_512, pair.publicKey)

        assertEquals("KeyPair(ML_KEM_512)", pair.toString())
        assertEquals("PrivateKey(ML_KEM_512)", pair.privateKey.toString())
        assertEquals("KemEncapsulation(768-byte ciphertext)", encapsulation.toString())
    }

    /** Only the primitives every backend must have, delegated to the default backend. */
    private class SymmetricOnlyBackend : CryptoBackend {
        private val default = DefaultCryptoBackend()

        override suspend fun randomBytes(size: Int): ByteArray = default.randomBytes(size)

        override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
            default.digest(algorithm, data)

        override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
            default.hmac(algorithm, key, data)

        override suspend fun aesBlockCipher(key: ByteArray): AesBlockCipher = default.aesBlockCipher(key)
    }
}
