package dev.kcoap.crypto.testsuite

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.AeadAuthenticationException
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.EcdhCurve
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KemEncapsulation
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.Shake256
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.testsuite.vectors.ML_KEM_ENCAPSULATION_VECTORS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The capability contract as [CapabilityInvariant] checks it (ADR 0001), on decorators of the default backend
 * that break it one way each: a yes for what throws, a no for what runs, a no with the wrong exception, a
 * changing answer, and wrong results that only a round trip or a published known answer shows. Checks of an
 * algorithm the platform lacks (X448 on Apple's CryptoKit, ChaCha20/Poly1305 on WebCrypto) are skipped, since
 * the default backend answers no there.
 */
class CapabilityInvariantTest {
    private val base = DefaultCryptoBackend()

    @Test
    fun aYesForAnOperationThatThrowsUnsupportedAlgorithmExceptionIsAViolation() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun supports(type: KeyType): Boolean = true
            override suspend fun generateKeyPair(type: KeyType): KeyPair =
                throw UnsupportedAlgorithmException(type.name)
        }

        assertViolation(backend, KeyType.X25519, answer = true, "generateKeyPair threw UnsupportedAlgorithmException")
    }

    /** What a decorator that adds an algorithm without overriding `supports` does: its delegate denies it. */
    @Test
    fun aNoForOperationsThatRunIsAViolation() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun supports(algorithm: HashAlgorithm): Boolean = algorithm != HashAlgorithm.SHA_384
        }

        val violations = CapabilityInvariant.violations(backend)

        assertEquals(listOf<Enum<*>>(HashAlgorithm.SHA_384), violations.map { it.question })
        assertEquals(
            "HashAlgorithm.SHA_384: supports answered no, but its operations ran instead of throwing " +
                "UnsupportedAlgorithmException",
            violations.single().toString(),
        )
    }

    @Test
    fun aNoMustComeWithUnsupportedAlgorithmExceptionAndNothingElse() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun supports(type: KeyType): Boolean = type != KeyType.ED25519 && base.supports(type)
            override suspend fun generateKeyPair(type: KeyType): KeyPair =
                if (type == KeyType.ED25519) error("device busy") else base.generateKeyPair(type)
        }

        assertViolation(
            backend, KeyType.ED25519, answer = false,
            "generateKeyPair threw IllegalStateException (device busy)",
        )
    }

    @Test
    fun aNoBackedByUnsupportedAlgorithmExceptionHolds() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun supports(algorithm: AeadAlgorithm): Boolean = algorithm != AeadAlgorithm.A128GCM
            override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead =
                if (algorithm == AeadAlgorithm.A128GCM) {
                    throw UnsupportedAlgorithmException("A128GCM")
                } else {
                    base.aead(algorithm, key)
                }

            override suspend fun supports(type: KeyType): Boolean = type != KeyType.X448
            override suspend fun generateKeyPair(type: KeyType): KeyPair =
                if (type == KeyType.X448) throw UnsupportedAlgorithmException("X448") else base.generateKeyPair(type)
        }

        assertNull(CapabilityInvariant.check(backend, AeadAlgorithm.A128GCM))
        assertNull(CapabilityInvariant.check(backend, KeyType.X448))
    }

    @Test
    fun anAnswerThatChangesIsAViolation() = runTest {
        var asked = 0
        val backend = object : CryptoBackend by base {
            override suspend fun supports(algorithm: HashAlgorithm): Boolean = asked++ % 2 == 0
        }

        assertViolation(backend, HashAlgorithm.SHA_256, answer = true, "it answered no when asked again")
    }

    /** `CryptoBackend.digest` promises SHAKE256 everywhere, since common code computes it. */
    @Test
    fun everyBackendSupportsShake256() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun supports(algorithm: HashAlgorithm): Boolean = algorithm != HashAlgorithm.SHAKE256
            override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
                if (algorithm.isXof) throw UnsupportedAlgorithmException("SHAKE256") else base.digest(algorithm, data)
        }

        assertViolation(backend, HashAlgorithm.SHAKE256, answer = false, "every backend computes SHAKE256")
    }

    @Test
    fun aDigestOrMacThatDiffersFromTheStandardIsAViolation() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
                if (algorithm == HashAlgorithm.SHA_256) ByteArray(32) else base.digest(algorithm, data)

            override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
                base.hmac(algorithm, if (algorithm == HashAlgorithm.SHA_384) key.flipped(0) else key, data)
        }

        assertViolation(
            backend, HashAlgorithm.SHA_256, answer = true,
            "digest does not reproduce the FIPS 180-4 SHA-256 example",
        )
        assertViolation(backend, HashAlgorithm.SHA_384, answer = true, "hmac does not reproduce RFC 4231 test case 1")
    }

    @Test
    fun anAeadThatDoesNotAuthenticateOrIsAnotherAlgorithmIsAViolation() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
                val real = base.aead(algorithm, key)
                return when (algorithm) {
                    // Encrypts nothing and appends a zero tag: it round trips, and takes any tag.
                    AeadAlgorithm.AES_CCM_16_64_128 -> object : Aead by real {
                        override suspend fun encrypt(
                            nonce: ByteArray,
                            plaintext: ByteArray,
                            associatedData: ByteArray,
                        ) = plaintext + ByteArray(tagLength)

                        override suspend fun decrypt(
                            nonce: ByteArray,
                            ciphertext: ByteArray,
                            associatedData: ByteArray,
                        ) = ciphertext.copyOf(ciphertext.size - tagLength)
                    }
                    // AES-GCM under another key: it round trips and authenticates, but is not the GCM test case.
                    AeadAlgorithm.A128GCM -> base.aead(algorithm, key.flipped(0))
                    AeadAlgorithm.A256GCM -> object : Aead by real {
                        override val tagLength: Int = 8
                    }
                    AeadAlgorithm.AES_CCM_16_128_128 -> object : Aead by real {
                        override suspend fun decrypt(
                            nonce: ByteArray,
                            ciphertext: ByteArray,
                            associatedData: ByteArray,
                        ) = try {
                            real.decrypt(nonce, ciphertext, associatedData)
                        } catch (_: AeadAuthenticationException) {
                            error("tag mismatch")
                        }
                    }
                    else -> real
                }
            }
        }

        assertViolation(backend, AeadAlgorithm.AES_CCM_16_64_128, answer = true, "decrypt accepts a changed tag")
        assertViolation(
            backend, AeadAlgorithm.A128GCM, answer = true,
            "encrypt does not reproduce the GCM specification's test case 2",
        )
        assertViolation(backend, AeadAlgorithm.A256GCM, answer = true, "aead has a 8-byte tag and a 12-byte nonce")
        assertViolation(
            backend, AeadAlgorithm.AES_CCM_16_128_128, answer = true,
            "decrypt of a changed tag threw IllegalStateException (tag mismatch) instead of " +
                "AeadAuthenticationException",
        )

        // Checks the tag of a ciphertext it made without the body: a changed tag or changed associated data is
        // rejected, and it reproduces the known answer, but a forged body passes.
        val bodyBlind = object : CryptoBackend by base {
            override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
                val real = base.aead(algorithm, key)
                val madeByTag = HashMap<String, ByteArray>()
                return object : Aead by real {
                    override suspend fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray) =
                        real.encrypt(nonce, plaintext, associatedData).also { madeByTag[tagOf(it)] = it }

                    override suspend fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray) =
                        real.decrypt(nonce, madeByTag[tagOf(ciphertext)] ?: ciphertext, associatedData)

                    private fun tagOf(sealed: ByteArray) =
                        sealed.copyOfRange(sealed.size - tagLength, sealed.size).toHex()
                }
            }
        }
        assertViolation(bodyBlind, AeadAlgorithm.A128GCM, answer = true, "decrypt accepts a changed ciphertext body")
    }

    /** A property of the `Aead` that throws is reported like an operation that throws, not passed on. */
    @Test
    fun anAeadWhosePropertiesThrowIsAViolation() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
                val real = base.aead(algorithm, key)
                return object : Aead by real {
                    override val nonceLength: Int get() = error("session closed")
                }
            }
        }

        assertViolation(
            backend, AeadAlgorithm.A256GCM, answer = true,
            "nonceLength threw IllegalStateException (session closed)",
        )
    }

    /**
     * Another key gives an AEAD that round trips and authenticates, like an AES-CCM whose CBC-MAC is formatted
     * the same wrong way on both sides: only the published known answer of each algorithm tells it apart.
     */
    @Test
    fun everyAeadMustReproduceItsKnownAnswer() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead =
                base.aead(algorithm, key.flipped(0))
        }

        for (algorithm in AeadAlgorithm.entries.filter { base.supports(it) }) {
            val source = KnownAnswers.sealings.getValue(algorithm).source
            assertViolation(backend, algorithm, answer = true, "encrypt does not reproduce $source")
        }
    }

    @Test
    fun aKeyAgreementThatDisagreesOrDiffersFromTheStandardIsAViolation() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
                val secret = base.keyAgreement(privateKey, peerPublicKey)
                return when (privateKey.type) {
                    KeyType.P256 -> base.randomBytes(secret.size)
                    KeyType.X25519 -> secret.flipped(0)
                    KeyType.P384 -> secret.copyOf(32)
                    else -> secret
                }
            }
        }

        assertViolation(backend, KeyType.P256, answer = true, "keyAgreement gives the two sides different secrets")
        assertViolation(backend, KeyType.X25519, answer = true, "keyAgreement does not reproduce RFC 7748 §6.1")
        assertViolation(backend, KeyType.P384, answer = true, "keyAgreement returned 32 bytes, not 48")
    }

    /**
     * A hash of the shared secret agrees on both sides and has the right length, like an ECDH that returns the
     * y-coordinate or a KDF of x: only the published known answer of each curve tells it apart.
     */
    @Test
    fun everyKeyAgreementMustReproduceItsKnownAnswer() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
                val secret = base.keyAgreement(privateKey, peerPublicKey)
                return Shake256.digest(secret, secret.size)
            }
        }

        val types = EcdhCurve.entries.map { it.keyType }.filter { base.supports(it) }
        assertTrue(KeyType.P256 in types && KeyType.P384 in types && KeyType.X25519 in types, "$types")
        for (type in types) {
            val source = KnownAnswers.agreements.getValue(type).source
            assertViolation(backend, type, answer = true, "keyAgreement does not reproduce $source")
        }
    }

    /** A key store may import no keys; the known answers that need one are skipped, but other failures are not. */
    @Test
    fun aKnownAnswerIsSkippedOnlyWhenImportIsUnsupported() = runTest {
        val importsNothing = object : CryptoBackend by base {
            override suspend fun importKeyPair(type: KeyType, privateKey: ByteArray, publicKey: ByteArray): KeyPair =
                throw UnsupportedAlgorithmException("import")
        }
        val locked = object : CryptoBackend by base {
            override suspend fun importKeyPair(type: KeyType, privateKey: ByteArray, publicKey: ByteArray): KeyPair =
                error("store locked")
        }

        assertNull(CapabilityInvariant.check(importsNothing, KeyType.X25519))
        assertViolation(
            locked, KeyType.X25519, answer = true,
            "importKeyPair threw IllegalStateException (store locked)",
        )
    }

    @Test
    fun aSignatureThatIsMisshapenAcceptsTooMuchOrRejectsTheStandardIsAViolation() = runTest {
        val rfc8032PublicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToBytes()
        val backend = object : CryptoBackend by base {
            override suspend fun sign(
                algorithm: SignatureAlgorithm,
                privateKey: PrivateKey,
                data: ByteArray,
            ): ByteArray {
                val signature = base.sign(algorithm, privateKey, data)
                return if (algorithm == SignatureAlgorithm.ES256) signature.copyOf(63) else signature
            }

            override suspend fun verify(
                algorithm: SignatureAlgorithm,
                publicKey: ByteArray,
                data: ByteArray,
                signature: ByteArray,
            ): Boolean = when (algorithm) {
                SignatureAlgorithm.ES384 -> true
                SignatureAlgorithm.EDDSA ->
                    base.verify(algorithm, publicKey, data, signature) && !publicKey.contentEquals(rfc8032PublicKey)
                else -> base.verify(algorithm, publicKey, data, signature)
            }
        }
        val rejectsItsOwn = object : CryptoBackend by base {
            override suspend fun verify(
                algorithm: SignatureAlgorithm,
                publicKey: ByteArray,
                data: ByteArray,
                signature: ByteArray,
            ) = false
        }

        assertViolation(backend, SignatureAlgorithm.ES256, answer = true, "sign returned 63 bytes, not 64")
        assertViolation(
            backend, SignatureAlgorithm.ES384, answer = true,
            "verify accepts a signature over another message",
        )
        assertViolation(
            backend, SignatureAlgorithm.EDDSA, answer = true,
            "verify does not reach the verdict of RFC 8032 §7.1 TEST 1",
        )
        assertViolation(
            rejectsItsOwn, SignatureAlgorithm.EDDSA, answer = true,
            "verify rejects a signature of the backend's own",
        )
    }

    /**
     * Signing and verifying a hash of the message is consistent with itself, like an ES256 that hashes with
     * SHA-384: only the published verdict of each algorithm tells it apart.
     */
    @Test
    fun everySignatureAlgorithmMustReachThePublishedVerdict() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun sign(
                algorithm: SignatureAlgorithm,
                privateKey: PrivateKey,
                data: ByteArray,
            ): ByteArray = base.sign(algorithm, privateKey, Shake256.digest(data, 32))

            override suspend fun verify(
                algorithm: SignatureAlgorithm,
                publicKey: ByteArray,
                data: ByteArray,
                signature: ByteArray,
            ): Boolean = base.verify(algorithm, publicKey, Shake256.digest(data, 32), signature)
        }

        val algorithms = SignatureAlgorithm.entries.filter { base.supports(it) }
        assertEquals(listOf(SignatureAlgorithm.ES256, SignatureAlgorithm.ES384, SignatureAlgorithm.EDDSA), algorithms)
        for (algorithm in algorithms) {
            val source = KnownAnswers.signatures(algorithm).single().source
            assertViolation(backend, algorithm, answer = true, "verify does not reach the verdict of $source")
        }
    }

    /** What the suite's documentation promises: a published known answer for every algorithm a backend may support. */
    @Test
    fun everyAlgorithmHasAKnownAnswer() {
        assertEquals(AeadAlgorithm.entries.toSet(), KnownAnswers.sealings.keys)
        assertEquals(EcdhCurve.entries.map { it.keyType }.toSet(), KnownAnswers.agreements.keys)
        for (algorithm in SignatureAlgorithm.entries) {
            assertTrue(KnownAnswers.signatures(algorithm).any { it.isValid }, algorithm.name)
        }
        assertEquals(KemAlgorithm.entries.toSet(), ML_KEM_ENCAPSULATION_VECTORS.map { it.algorithm }.toSet())
        assertEquals(HashAlgorithm.entries.toSet(), KnownAnswers.digests.keys)
        assertEquals(HashAlgorithm.entries.filter { !it.isXof }.toSet(), KnownAnswers.hmacs.keys)
    }

    /**
     * `PrivateKey.destroy` must not throw (kcoap-crypto's `Keys.kt`): one that does is a violation, but not in
     * place of an operation that failed before it, which is the failure reported.
     */
    @Test
    fun aKeyThatThrowsOnDestroyIsAViolationThatHidesNoOther() = runTest {
        val backend = object : CryptoBackend by base {
            override suspend fun generateKeyPair(type: KeyType): KeyPair =
                base.generateKeyPair(type).let { KeyPair(it.publicKey, StuckKey(it.privateKey)) }

            override suspend fun sign(
                algorithm: SignatureAlgorithm,
                privateKey: PrivateKey,
                data: ByteArray,
            ): ByteArray = error("signing failed")
        }

        assertViolation(
            backend, KeyType.ED25519, answer = true,
            "destroy threw IllegalStateException (entry not deleted)",
        )
        assertViolation(
            backend, SignatureAlgorithm.ES256, answer = true,
            "sign threw IllegalStateException (signing failed)",
        )
    }

    /** A key-store handle whose entry cannot be deleted. */
    private class StuckKey(private val key: PrivateKey) : PrivateKey(key.type) {
        override fun destroy() {
            key.destroy()
            error("entry not deleted")
        }
    }

    @Test
    fun anEncapsulationWhoseSecretIsAlreadyDestroyedIsAViolation() = runTest {
        val kem = ToyKem(base)
        val backend = object : CryptoBackend by kem {
            override suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation =
                kem.encapsulate(algorithm, publicKey).also { it.destroy() }
        }

        assertViolation(
            backend, KemAlgorithm.ML_KEM_512, answer = true,
            "sharedSecret threw IllegalStateException (The shared secret was destroyed)",
        )
    }

    /**
     * A KEM that is not ML-KEM gets through the round trip and the length checks, but not NIST's known answers;
     * one without implicit rejection or with the wrong ciphertext length does not get that far.
     */
    @Test
    fun aKemThatIsNotMlKemIsAViolation() = runTest {
        assertViolation(
            ToyKem(base), KemAlgorithm.ML_KEM_512, answer = true,
            "decapsulate does not reproduce ML-KEM-encapDecap-FIPS203",
        )
        assertNull(CapabilityInvariant.check(ToyKem(base, importsKeys = false), KemAlgorithm.ML_KEM_512))
        assertViolation(
            ToyKem(base, rejectsImplicitly = false), KemAlgorithm.ML_KEM_512, answer = true,
            "a changed ciphertext does not decapsulate to another secret",
        )
        assertViolation(
            ToyKem(base, ciphertextLength = 767), KemAlgorithm.ML_KEM_512, answer = true,
            "encapsulate returned a 767-byte ciphertext, not 768",
        )
    }

    @Test
    fun onlyCapabilityQuestionsCanBeChecked() = runTest {
        assertFailsWith<IllegalArgumentException> { CapabilityInvariant.check(base, NotAQuestion.COLOUR) }
    }

    @Test
    fun theQuestionsAreEveryAlgorithmAndKeyType() {
        val expected = AeadAlgorithm.entries.size + KeyType.entries.size + SignatureAlgorithm.entries.size +
            KemAlgorithm.entries.size + HashAlgorithm.entries.size
        assertEquals(expected, CapabilityInvariant.questions.toSet().size)
    }

    private enum class NotAQuestion { COLOUR }

    private suspend fun assertViolation(backend: CryptoBackend, question: Enum<*>, answer: Boolean, problem: String) {
        val violation = assertNotNull(CapabilityInvariant.check(backend, question), "$question holds")
        assertEquals(question, violation.question)
        assertEquals(answer, violation.answer, violation.toString())
        assertContains(violation.problem, problem)
    }
}
