package dev.kcoap.crypto.testsuite

import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.testsuite.vectors.ML_DSA_SIGGEN_VECTORS
import dev.kcoap.crypto.testsuite.vectors.ML_DSA_SIGVER_VECTORS

/**
 * Published known answers that tell an algorithm from a look-alike: a backend that says it supports an
 * algorithm must reproduce them ([CapabilityInvariant]). One or two per algorithm, from the standard that
 * defines it; the complete vector sets of a primitive stay with its implementation (kcoap-crypto-default's
 * RFC 3610 and RFC 5869 tests, for instance).
 */
internal object KnownAnswers {
    /** The message of the FIPS 180-4 examples. */
    val abc: ByteArray = "abc".encodeToByteArray()

    /** The digest of [abc] (SHA-2) or of the empty message (SHAKE256, its first 64 bytes). */
    class Digest(val message: ByteArray, val digest: ByteArray, val source: String)

    val digests: Map<HashAlgorithm, Digest> = mapOf(
        HashAlgorithm.SHA_256 to Digest(
            abc,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToBytes(),
            "the FIPS 180-4 SHA-256 example",
        ),
        HashAlgorithm.SHA_384 to Digest(
            abc,
            ("cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed" +
                "8086072ba1e7cc2358baeca134c825a7").hexToBytes(),
            "the FIPS 180-4 SHA-384 example",
        ),
        HashAlgorithm.SHAKE256 to Digest(
            ByteArray(0),
            ("46b9dd2b0ba88d13233b3feb743eeb243fcd52ea62b81b82b50c27646ed5762f" +
                "d75dc4ddd8c0f200cb05019d67b592f6fc821c49479ab48640292eacb3b7c4be").hexToBytes(),
            "the FIPS 202 SHAKE256 example for the empty message",
        ),
    )

    /** RFC 4231 §4.2, test case 1: key `0x0b` × 20, data "Hi There". */
    val hmacKey: ByteArray = ByteArray(20) { 0x0b }
    val hmacData: ByteArray = "Hi There".encodeToByteArray()
    val hmacs: Map<HashAlgorithm, ByteArray> = mapOf(
        HashAlgorithm.SHA_256 to "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7".hexToBytes(),
        HashAlgorithm.SHA_384 to ("afd03944d84895626b0825f4ab46907f15f9dadbe4101ec682aa034c7cebc59c" +
            "faea9ea9076ede7f4af152e8b2fa9cb6").hexToBytes(),
    )

    /** An AEAD encryption whose output (ciphertext and tag) a standard publishes. */
    class Sealing(
        val key: ByteArray,
        val nonce: ByteArray,
        val associatedData: ByteArray,
        val plaintext: ByteArray,
        val sealed: ByteArray,
        val source: String,
    )

    private val zeroBlock = ByteArray(16)

    val sealings: Map<AeadAlgorithm, Sealing> = mapOf(
        AeadAlgorithm.AES_CCM_16_64_128 to Sealing(
            key = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf".hexToBytes(),
            nonce = "00000003020100a0a1a2a3a4a5".hexToBytes(),
            associatedData = "0001020304050607".hexToBytes(),
            plaintext = "08090a0b0c0d0e0f101112131415161718191a1b1c1d1e".hexToBytes(),
            sealed = "588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0".hexToBytes(),
            source = "RFC 3610 §8 packet vector #1",
        ),
        AeadAlgorithm.A128GCM to Sealing(
            key = ByteArray(16),
            nonce = ByteArray(12),
            associatedData = ByteArray(0),
            plaintext = zeroBlock,
            sealed = "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf".hexToBytes(),
            source = "the GCM specification's test case 2 (McGrew and Viega)",
        ),
        AeadAlgorithm.A192GCM to Sealing(
            key = ByteArray(24),
            nonce = ByteArray(12),
            associatedData = ByteArray(0),
            plaintext = zeroBlock,
            sealed = "98e7247c07f0fe411c267e4384b0f6002ff58d80033927ab8ef4d4587514f0fb".hexToBytes(),
            source = "the GCM specification's test case 8 (McGrew and Viega)",
        ),
        AeadAlgorithm.A256GCM to Sealing(
            key = ByteArray(32),
            nonce = ByteArray(12),
            associatedData = ByteArray(0),
            plaintext = zeroBlock,
            sealed = "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919".hexToBytes(),
            source = "the GCM specification's test case 14 (McGrew and Viega)",
        ),
        AeadAlgorithm.CHACHA20_POLY1305 to Sealing(
            key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".hexToBytes(),
            nonce = "070000004041424344454647".hexToBytes(),
            associatedData = "50515253c0c1c2c3c4c5c6c7".hexToBytes(),
            plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the " +
                "future, sunscreen would be it.").encodeToByteArray(),
            sealed = """
                d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b
                1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc
                3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691
            """.hexToBytes(),
            source = "RFC 8439 §2.8.2",
        ),
    )

    /** A Diffie-Hellman agreement from a standard: own private and public key, the peer's public key, the secret. */
    class Agreement(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
        val peerPublicKey: ByteArray,
        val sharedSecret: ByteArray,
        val source: String,
    )

    val agreements: Map<KeyType, Agreement> = mapOf(
        KeyType.X25519 to Agreement(
            privateKey = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes(),
            publicKey = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".hexToBytes(),
            peerPublicKey = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hexToBytes(),
            sharedSecret = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".hexToBytes(),
            source = "RFC 7748 §6.1",
        ),
    )

    /**
     * A signature a standard publishes, with whether it is valid. Only verified, never reproduced: Ed25519 and
     * ECDSA signers may hedge (Apple's CryptoKit does), and ML-DSA signs hedged by default.
     */
    class Signature(
        val publicKey: ByteArray,
        val message: ByteArray,
        val signature: ByteArray,
        val isValid: Boolean,
        val source: String,
    )

    fun signatures(algorithm: SignatureAlgorithm): List<Signature> = when (algorithm) {
        SignatureAlgorithm.EDDSA -> listOf(
            Signature(
                publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToBytes(),
                message = ByteArray(0),
                signature = ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b").hexToBytes(),
                isValid = true,
                source = "RFC 8032 §7.1 TEST 1",
            ),
        )
        else ->
            ML_DSA_SIGGEN_VECTORS.filter { it.algorithm == algorithm }.map {
                Signature(it.pk.hexToBytes(), it.message.hexToBytes(), it.signature.hexToBytes(), true, it.name)
            } + ML_DSA_SIGVER_VECTORS.filter { it.algorithm == algorithm }.map {
                Signature(it.pk.hexToBytes(), it.message.hexToBytes(), it.signature.hexToBytes(), it.isValid, it.name)
            }
    }
}
