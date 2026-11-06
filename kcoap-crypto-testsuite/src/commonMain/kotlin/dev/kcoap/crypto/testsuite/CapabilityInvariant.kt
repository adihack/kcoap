package dev.kcoap.crypto.testsuite

import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.AeadAuthenticationException
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.EcdhCurve
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.crypto.testsuite.vectors.ML_KEM_ENCAPSULATION_VECTORS
import kotlin.coroutines.cancellation.CancellationException

/**
 * The capability contract of `CryptoBackend` (docs/adr/0001-crypto-capabilities.md), checked on one backend:
 * `supports(x)` is false exactly when the operations on `x` throw `UnsupportedAlgorithmException`. So a
 * backend that answers yes runs those operations and gets them right, one that answers no throws that
 * exception from them and nothing else, and the answer does not change.
 *
 * The operations of a question are those the default `supports` members probe, checked further where a wrong
 * result would go unnoticed otherwise. Every algorithm reproduces a published known answer, so that a
 * look-alike that is consistent with itself does not pass:
 * - an AEAD: `aead`, then `encrypt` and `decrypt`, which round trip, reject a changed ciphertext body, a
 *   changed tag or changed associated data with `AeadAuthenticationException`, and reproduce RFC 3610 packet 1
 *   (AES-CCM-16-64-128), the cose-wg examples (the other AES-CCM variants of RFC 9053 §4.2), the GCM
 *   specification's test cases (AES-GCM) or RFC 8439 §2.8.2 (ChaCha20/Poly1305);
 * - a key type: `generateKeyPair`, whose public key has `publicKeyLength` bytes (P-256 and P-384 keys
 *   uncompressed), and for the Diffie-Hellman types `keyAgreement`, which agrees both ways in `keyLength`
 *   bytes and reproduces RFC 7748 §6.1 and §6.2 (X25519, X448) or RFC 5903 §8.1 and §8.2 (P-256, P-384, whose
 *   secret is the x-coordinate of the shared point);
 * - a signature algorithm: key generation, `sign` and `verify`: signatures of `signatureLength` bytes that
 *   verify, false for a changed message, a changed signature or another key, and the published verdicts on
 *   RFC 8032 TEST 1 (Ed25519), RFC 6979 §A.2.5 and §A.2.6 (ES256, ES384, as raw `r || s`) and NIST's ACVP
 *   signatures (ML-DSA);
 * - a KEM: key generation, `encapsulate` and `decapsulate`: the lengths of FIPS 203, the same secret on both
 *   sides, a different secret for a changed ciphertext (implicit rejection, FIPS 203 Algorithm 18), and
 *   NIST's ACVP shared secrets;
 * - a hash: `digest`, and for SHA-2 `hmac`: the FIPS 180-4 and FIPS 202 examples and RFC 4231 test case 1.
 *
 * Known answers that need an imported private key (X25519, X448, P-256, P-384, ML-KEM) are skipped when the
 * backend refuses `importKeyPair` with `UnsupportedAlgorithmException`, as a key store may. SHAKE256 must be
 * supported by every backend, since common code can always compute it (`CryptoBackend.digest`).
 *
 * The checks generate key pairs and destroy them again; on a backend that keeps keys in hardware they cost
 * what its probes would. A `PrivateKey.destroy` that throws breaks its contract and is a violation too, unless
 * an operation of the question failed before it: then that failure is the one reported.
 */
public object CapabilityInvariant {
    /** Every question the `supports` members take: each AEAD, key type, signature algorithm, KEM and hash. */
    public val questions: List<Enum<*>> =
        AeadAlgorithm.entries + KeyType.entries + SignatureAlgorithm.entries + KemAlgorithm.entries +
            HashAlgorithm.entries

    /** The violations of [backend] over all [questions], in their order; empty when the contract holds. */
    public suspend fun violations(backend: CryptoBackend): List<CapabilityViolation> =
        questions.mapNotNull { check(backend, it) }

    /**
     * Asks [backend] about [question] twice and runs the question's operations.
     *
     * @return null when the answer holds, otherwise what is wrong.
     * @throws IllegalArgumentException when [question] is not one of [questions].
     */
    public suspend fun check(backend: CryptoBackend, question: Enum<*>): CapabilityViolation? {
        val answer = ask(backend, question)
        val again = ask(backend, question)
        if (again != answer) {
            return CapabilityViolation(question, answer, "it answered ${yesOrNo(again)} when asked again")
        }
        if (question == HashAlgorithm.SHAKE256 && !answer) {
            return CapabilityViolation(question, false, "every backend computes SHAKE256 (CryptoBackend.digest)")
        }
        val problem = try {
            exercise(backend, question)
            if (answer) null else "its operations ran instead of throwing UnsupportedAlgorithmException"
        } catch (e: Unsupported) {
            if (answer) "${e.operation} threw UnsupportedAlgorithmException" else null
        } catch (e: Wrong) {
            e.problem
        }
        return problem?.let { CapabilityViolation(question, answer, it) }
    }

    /** What [backend] answers to [question]; shared with [CapabilityReport]. */
    internal suspend fun ask(backend: CryptoBackend, question: Enum<*>): Boolean = when (question) {
        is AeadAlgorithm -> backend.supports(question)
        is KeyType -> backend.supports(question)
        is SignatureAlgorithm -> backend.supports(question)
        is KemAlgorithm -> backend.supports(question)
        is HashAlgorithm -> backend.supports(question)
        else -> throw IllegalArgumentException(
            "${question::class.simpleName}.${question.name} is not a capability question",
        )
    }

    private suspend fun exercise(backend: CryptoBackend, question: Enum<*>) {
        when (question) {
            is AeadAlgorithm -> aead(backend, question)
            is KeyType -> keyType(backend, question)
            is SignatureAlgorithm -> signature(backend, question)
            is KemAlgorithm -> kem(backend, question)
            is HashAlgorithm -> hash(backend, question)
        }
    }

    private suspend fun aead(backend: CryptoBackend, algorithm: AeadAlgorithm) {
        val known = KnownAnswers.sealings[algorithm]
        val key = known?.key ?: ByteArray(algorithm.keyLength) { (it + 1).toByte() }
        val aead = step("aead") { backend.aead(algorithm, key) }
        val tagLength = step("tagLength") { aead.tagLength }
        val nonceLength = step("nonceLength") { aead.nonceLength }
        expect(tagLength == algorithm.tagLength && nonceLength == algorithm.nonceLength) {
            "aead has a $tagLength-byte tag and a $nonceLength-byte nonce"
        }
        val associatedData = "kcoap".encodeToByteArray()
        for ((index, plaintext) in listOf(ByteArray(0), ByteArray(37) { it.toByte() }).withIndex()) {
            // A fresh nonce per encryption: a platform may refuse a nonce it saw under the same key (JCA's GCM does).
            val nonce = ByteArray(algorithm.nonceLength) { (0xA0 + it + index).toByte() }
            val sealed = step("encrypt") { aead.encrypt(nonce, plaintext, associatedData) }
            expect(sealed.size == plaintext.size + algorithm.tagLength) {
                "encrypt returned ${sealed.size} bytes for ${plaintext.size} bytes of plaintext"
            }
            val opened = step("decrypt") { aead.decrypt(nonce, sealed, associatedData) }
            expect(opened.contentEquals(plaintext)) { "decrypt does not return what was encrypted" }
            if (plaintext.isNotEmpty()) {
                rejects("a changed ciphertext body") { aead.decrypt(nonce, sealed.flipped(0), associatedData) }
            }
            rejects("a changed tag") { aead.decrypt(nonce, sealed.flipped(sealed.lastIndex), associatedData) }
            rejects("changed associated data") { aead.decrypt(nonce, sealed, associatedData.flipped(0)) }
        }
        if (known != null) {
            val sealed = step("encrypt") { aead.encrypt(known.nonce, known.plaintext, known.associatedData) }
            expect(sealed.contentEquals(known.sealed)) { "encrypt does not reproduce ${known.source}" }
            val opened = step("decrypt") { aead.decrypt(known.nonce, known.sealed, known.associatedData) }
            expect(opened.contentEquals(known.plaintext)) { "decrypt does not reproduce ${known.source}" }
        }
    }

    private suspend fun keyType(backend: CryptoBackend, type: KeyType) {
        withKeyPair(backend, type) { pair ->
            val curve = EcdhCurve.entries.firstOrNull { it.keyType == type } ?: return@withKeyPair
            withKeyPair(backend, type) { peer ->
                val mine = step("keyAgreement") { backend.keyAgreement(pair.privateKey, peer.publicKey) }
                val theirs = step("keyAgreement") { backend.keyAgreement(peer.privateKey, pair.publicKey) }
                expect(mine.contentEquals(theirs)) { "keyAgreement gives the two sides different secrets" }
                expect(mine.size == curve.keyLength) {
                    "keyAgreement returned ${mine.size} bytes, not ${curve.keyLength}"
                }
            }
            val known = KnownAnswers.agreements[type] ?: return@withKeyPair
            withImportedKeyPair(backend, type, known.privateKey, known.publicKey) { imported ->
                val secret = step("keyAgreement") { backend.keyAgreement(imported.privateKey, known.peerPublicKey) }
                expect(secret.contentEquals(known.sharedSecret)) { "keyAgreement does not reproduce ${known.source}" }
            }
        }
    }

    private suspend fun signature(backend: CryptoBackend, algorithm: SignatureAlgorithm) {
        val message = "kcoap conformance".encodeToByteArray()
        withKeyPair(backend, algorithm.keyType) { pair ->
            for (data in listOf(ByteArray(0), message)) {
                val signature = step("sign") { backend.sign(algorithm, pair.privateKey, data) }
                expect(signature.size == algorithm.signatureLength) {
                    "sign returned ${signature.size} bytes, not ${algorithm.signatureLength}"
                }
                val valid = step("verify") { backend.verify(algorithm, pair.publicKey, data, signature) }
                expect(valid) { "verify rejects a signature of the backend's own" }
            }
            val signature = step("sign") { backend.sign(algorithm, pair.privateKey, message) }
            val otherMessage = message.flipped(0)
            expect(!step("verify") { backend.verify(algorithm, pair.publicKey, otherMessage, signature) }) {
                "verify accepts a signature over another message"
            }
            val changed = signature.flipped(signature.size / 2)
            expect(!step("verify") { backend.verify(algorithm, pair.publicKey, message, changed) }) {
                "verify accepts a changed signature"
            }
            withKeyPair(backend, algorithm.keyType) { other ->
                expect(!step("verify") { backend.verify(algorithm, other.publicKey, message, signature) }) {
                    "verify accepts a signature under another key"
                }
            }
        }
        for (known in KnownAnswers.signatures(algorithm)) {
            val valid = step("verify") { backend.verify(algorithm, known.publicKey, known.message, known.signature) }
            expect(valid == known.isValid) { "verify does not reach the verdict of ${known.source}" }
        }
    }

    private suspend fun kem(backend: CryptoBackend, algorithm: KemAlgorithm) {
        withKeyPair(backend, algorithm.keyType) { pair ->
            val encapsulation = step("encapsulate") { backend.encapsulate(algorithm, pair.publicKey) }
            val secret = step("sharedSecret") { encapsulation.sharedSecret() }
            encapsulation.destroy()
            val ciphertext = encapsulation.ciphertext
            expect(ciphertext.size == algorithm.ciphertextLength) {
                "encapsulate returned a ${ciphertext.size}-byte ciphertext, not ${algorithm.ciphertextLength}"
            }
            expect(secret.size == algorithm.sharedSecretLength) {
                "encapsulate returned a ${secret.size}-byte secret, not ${algorithm.sharedSecretLength}"
            }
            val received = step("decapsulate") { backend.decapsulate(pair.privateKey, ciphertext) }
            expect(received.contentEquals(secret)) { "decapsulate does not return the secret encapsulate made" }
            val rejected = step("decapsulate") { backend.decapsulate(pair.privateKey, ciphertext.flipped(0)) }
            expect(rejected.size == algorithm.sharedSecretLength && !rejected.contentEquals(secret)) {
                "a changed ciphertext does not decapsulate to another secret " +
                    "(implicit rejection, FIPS 203 Algorithm 18)"
            }
        }
        for (known in ML_KEM_ENCAPSULATION_VECTORS.filter { it.algorithm == algorithm }) {
            withImportedKeyPair(backend, algorithm.keyType, known.dk.hexToBytes(), known.ek.hexToBytes()) { imported ->
                val secret = step("decapsulate") { backend.decapsulate(imported.privateKey, known.c.hexToBytes()) }
                expect(secret.contentEquals(known.k.hexToBytes())) { "decapsulate does not reproduce ${known.name}" }
            }
        }
    }

    private suspend fun hash(backend: CryptoBackend, algorithm: HashAlgorithm) {
        val known = KnownAnswers.digests.getValue(algorithm)
        val digest = step("digest") { backend.digest(algorithm, known.message) }
        expect(digest.contentEquals(known.digest)) { "digest does not reproduce ${known.source}" }
        val other = step("digest") { backend.digest(algorithm, KnownAnswers.abc + known.message) }
        expect(other.size == algorithm.outputLength) {
            "digest returned ${other.size} bytes, not ${algorithm.outputLength}"
        }
        if (algorithm.isXof) return
        val mac = step("hmac") { backend.hmac(algorithm, KnownAnswers.hmacKey, KnownAnswers.hmacData) }
        expect(mac.contentEquals(KnownAnswers.hmacs.getValue(algorithm))) {
            "hmac does not reproduce RFC 4231 test case 1"
        }
    }

    /** A generated key pair of [type], checked and destroyed after [use]. */
    private suspend inline fun withKeyPair(backend: CryptoBackend, type: KeyType, use: (KeyPair) -> Unit) {
        val pair = step("generateKeyPair") { backend.generateKeyPair(type) }
        destroyingAfter(pair) {
            expect(pair.type == type) { "generateKeyPair($type) returned a ${pair.type} key" }
            expect(pair.publicKey.size == type.publicKeyLength) {
                "generateKeyPair returned a ${pair.publicKey.size}-byte public key, not ${type.publicKeyLength}"
            }
            if (type == KeyType.P256 || type == KeyType.P384) {
                expect(pair.publicKey[0] == UNCOMPRESSED) { "generateKeyPair returned a compressed public key" }
            }
            use(pair)
        }
    }

    /** An imported key pair for a known answer, destroyed after [use]; skipped if the backend imports no keys. */
    private suspend inline fun withImportedKeyPair(
        backend: CryptoBackend,
        type: KeyType,
        privateKey: ByteArray,
        publicKey: ByteArray,
        use: (KeyPair) -> Unit,
    ) {
        val pair = try {
            step("importKeyPair") { backend.importKeyPair(type, privateKey, publicKey) }
        } catch (_: Unsupported) {
            return
        }
        destroyingAfter(pair) { use(pair) }
    }

    /**
     * Runs [block], then destroys [pair]'s private key. A `destroy` that throws is a [Wrong] when [block]
     * completed; when [block] failed, its failure is the one that propagates, and the other is dropped.
     */
    private inline fun destroyingAfter(pair: KeyPair, block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (e: Throwable) {
            failed = true
            throw e
        } finally {
            try {
                pair.privateKey.destroy()
            } catch (e: Throwable) {
                if (!failed) throw if (e is CancellationException) e else Wrong("destroy threw ${describe(e)}")
            }
        }
    }

    /**
     * Runs one operation: [UnsupportedAlgorithmException] becomes [Unsupported], any other failure [Wrong], so
     * the question's exercise stops at the first operation that does not run.
     */
    private inline fun <T> step(operation: String, block: () -> T): T = try {
        block()
    } catch (e: UnsupportedAlgorithmException) {
        throw Unsupported(operation)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Wrong) {
        throw e
    } catch (e: Unsupported) {
        throw e
    } catch (e: Throwable) {
        throw Wrong("$operation threw ${describe(e)}")
    }

    /** Runs a decryption that must fail with [AeadAuthenticationException]. */
    private inline fun rejects(what: String, decrypt: () -> ByteArray) {
        try {
            decrypt()
        } catch (_: AeadAuthenticationException) {
            return
        } catch (e: UnsupportedAlgorithmException) {
            throw Unsupported("decrypt")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw Wrong("decrypt of $what threw ${describe(e)} instead of AeadAuthenticationException")
        }
        throw Wrong("decrypt accepts $what")
    }

    private inline fun expect(condition: Boolean, problem: () -> String) {
        if (!condition) throw Wrong(problem())
    }

    private fun describe(e: Throwable): String = "${e::class.simpleName ?: "an exception"} (${e.message})"

    private fun yesOrNo(answer: Boolean): String = if (answer) "yes" else "no"

    private const val UNCOMPRESSED: Byte = 0x04

    /** An operation of the question threw `UnsupportedAlgorithmException`. */
    private class Unsupported(val operation: String) : Exception(operation)

    /** An operation of the question failed otherwise, or gave a wrong result. */
    private class Wrong(val problem: String) : Exception(problem)
}

/** A copy with bit 0 of byte [index] flipped. */
internal fun ByteArray.flipped(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
