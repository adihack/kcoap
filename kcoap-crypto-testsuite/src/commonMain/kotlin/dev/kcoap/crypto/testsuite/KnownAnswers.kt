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
 * algorithm must reproduce them ([CapabilityInvariant]). There is one for every AEAD, Diffie-Hellman key type,
 * signature algorithm, KEM and hash, from the standard that defines it, or for the COSE AES-CCM variants that
 * RFC 3610 does not cover (other key and tag lengths, 7-byte nonces) from the COSE Working Group's examples
 * (github.com/cose-wg/Examples, commit 53c9d634, `aes-ccm-examples`). The complete vector sets of a primitive
 * stay with its implementation (kcoap-crypto-default's RFC 3610 and RFC 5869 tests, for instance).
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

    /**
     * An AEAD encryption whose output (ciphertext and tag) a standard publishes. The cose-wg examples encrypt
     * [coseContent] with the `Enc_structure` of a COSE_Encrypt0 as associated data (RFC 9052 §5.3).
     */
    class Sealing(
        val key: ByteArray,
        val nonce: ByteArray,
        val associatedData: ByteArray,
        val plaintext: ByteArray,
        val sealed: ByteArray,
        val source: String,
    )

    private val zeroBlock = ByteArray(16)

    /** The plaintext of the cose-wg examples. */
    private val coseContent = "This is the content.".encodeToByteArray()

    val sealings: Map<AeadAlgorithm, Sealing> = mapOf(
        AeadAlgorithm.AES_CCM_16_64_128 to Sealing(
            key = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf".hexToBytes(),
            nonce = "00000003020100a0a1a2a3a4a5".hexToBytes(),
            associatedData = "0001020304050607".hexToBytes(),
            plaintext = "08090a0b0c0d0e0f101112131415161718191a1b1c1d1e".hexToBytes(),
            sealed = "588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0".hexToBytes(),
            source = "RFC 3610 §8 packet vector #1",
        ),
        AeadAlgorithm.AES_CCM_16_128_128 to Sealing(
            key = "849b57219dae48de646d07dbb533566e".hexToBytes(),
            nonce = "89f52f65a1c580933b5261a72f".hexToBytes(),
            associatedData = "8368456e63727970743044a101181e40".hexToBytes(),
            plaintext = coseContent,
            sealed = ("6899da0a132bd2d2b9b10915743ee1f7b92a4680903f2c00d37e14d4ebdc7ef2" +
                "c03cf5a9").hexToBytes(),
            source = "the cose-wg Examples' aes-ccm-enc-02",
        ),
        AeadAlgorithm.AES_CCM_64_64_128 to Sealing(
            key = "849b57219dae48de646d07dbb533566e".hexToBytes(),
            nonce = "89f52f65a1c580".hexToBytes(),
            associatedData = "8368456e63727970743043a1010c40".hexToBytes(),
            plaintext = coseContent,
            sealed = "191bd858dec79fc11da3428bdfa446ac240d591ffcf91eeb8035f87a".hexToBytes(),
            source = "the cose-wg Examples' aes-ccm-enc-03",
        ),
        AeadAlgorithm.AES_CCM_64_128_128 to Sealing(
            key = "849b57219dae48de646d07dbb533566e".hexToBytes(),
            nonce = "89f52f65a1c580".hexToBytes(),
            associatedData = "8368456e63727970743044a101182040".hexToBytes(),
            plaintext = coseContent,
            sealed = ("191bd858dec79fc11da3428bdfa446ac240d591f3965fa7ca156fe666bc26280" +
                "7df0ee99").hexToBytes(),
            source = "the cose-wg Examples' aes-ccm-enc-04",
        ),
        AeadAlgorithm.AES_CCM_16_64_256 to Sealing(
            key = "0f1e2d3c4b5a69788796a5b4c3d2e1f01f2e3d4c5b6a798897a6b5c4d3e2f100".hexToBytes(),
            nonce = "89f52f65a1c580933b5261a72f".hexToBytes(),
            associatedData = "8368456e63727970743043a1010b40".hexToBytes(),
            plaintext = coseContent,
            sealed = "28b3bddff844a736c5f0ee0f8c691fd0b7adf9173140cb621df47c2f".hexToBytes(),
            source = "the cose-wg Examples' aes-ccm-enc-05",
        ),
        AeadAlgorithm.AES_CCM_16_128_256 to Sealing(
            key = "0f1e2d3c4b5a69788796a5b4c3d2e1f01f2e3d4c5b6a798897a6b5c4d3e2f100".hexToBytes(),
            nonce = "89f52f65a1c580933b5261a72f".hexToBytes(),
            associatedData = "8368456e63727970743044a101181f40".hexToBytes(),
            plaintext = coseContent,
            sealed = ("28b3bddff844a736c5f0ee0f8c691fd0b7adf917b0cfa0d187c769a4ba100372" +
                "a585bccc").hexToBytes(),
            source = "the cose-wg Examples' aes-ccm-enc-06",
        ),
        AeadAlgorithm.AES_CCM_64_64_256 to Sealing(
            key = "0f1e2d3c4b5a69788796a5b4c3d2e1f01f2e3d4c5b6a798897a6b5c4d3e2f100".hexToBytes(),
            nonce = "89f52f65a1c580".hexToBytes(),
            associatedData = "8368456e63727970743043a1010d40".hexToBytes(),
            plaintext = coseContent,
            sealed = "721908d60812806f2660054238e931adb575771e9bc42ff530baeb00".hexToBytes(),
            source = "the cose-wg Examples' aes-ccm-enc-07",
        ),
        AeadAlgorithm.AES_CCM_64_128_256 to Sealing(
            key = "0f1e2d3c4b5a69788796a5b4c3d2e1f01f2e3d4c5b6a798897a6b5c4d3e2f100".hexToBytes(),
            nonce = "89f52f65a1c580".hexToBytes(),
            associatedData = "8368456e63727970743044a101182140".hexToBytes(),
            plaintext = coseContent,
            sealed = ("721908d60812806f2660054238e931adb575771e723c6ffd415a07cdb9fa9cee" +
                "cc6c81fc").hexToBytes(),
            source = "the cose-wg Examples' aes-ccm-enc-08",
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

    /**
     * A Diffie-Hellman agreement from a standard: own private and public key, the peer's public key, the secret.
     * P-256 and P-384 keys are uncompressed points and the secret is the x-coordinate of the shared point, as
     * `CryptoBackend.keyAgreement` returns it (RFC 5903 §7, SEC 1 v2 §3.3.1).
     */
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
        KeyType.X448 to Agreement(
            privateKey = ("9a8f4925d1519f5775cf46b04b5800d4ee9ee8bae8bc5565d498c28dd9c9baf5" +
                "74a9419744897391006382a6f127ab1d9ac2d8c0a598726b").hexToBytes(),
            publicKey = ("9b08f7cc31b7e3e67d22d5aea121074a273bd2b83de09c63faa73d2c22c5d9bb" +
                "c836647241d953d40c5b12da88120d53177f80e532c41fa0").hexToBytes(),
            peerPublicKey = ("3eb7a829b0cd20f5bcfc0b599b6feccf6da4627107bdb0d4f345b43027d8b972" +
                "fc3e34fb4232a13ca706dcb57aec3dae07bdc1c67bf33609").hexToBytes(),
            sharedSecret = ("07fff4181ac6cc95ec1c16a94a0f74d12da232ce40a77552281d282bb60c0b56" +
                "fd2464c335543936521c24403085d59a449a5037514a879d").hexToBytes(),
            source = "RFC 7748 §6.2",
        ),
        KeyType.P256 to Agreement(
            privateKey = "c88f01f510d9ac3f70a292daa2316de544e9aab8afe84049c62a9c57862d1433".hexToBytes(),
            publicKey = ("04" +
                "dad0b65394221cf9b051e1feca5787d098dfe637fc90b9ef945d0c3772581180" +
                "5271a0461cdb8252d61f1c456fa3e59ab1f45b33accf5f58389e0577b8990bb3").hexToBytes(),
            peerPublicKey = ("04" +
                "d12dfb5289c8d4f81208b70270398c342296970a0bccb74c736fc7554494bf63" +
                "56fbf3ca366cc23e8157854c13c58d6aac23f046ada30f8353e74f33039872ab").hexToBytes(),
            sharedSecret = "d6840f6b42f6edafd13116e0e12565202fef8e9ece7dce03812464d04b9442de".hexToBytes(),
            source = "RFC 5903 §8.1",
        ),
        KeyType.P384 to Agreement(
            privateKey = ("099f3c7034d4a2c699884d73a375a67f7624ef7c6b3c0f160647b67414dce655" +
                "e35b538041e649ee3faef896783ab194").hexToBytes(),
            publicKey = ("04" +
                "667842d7d180ac2cde6f74f37551f55755c7645c20ef73e31634fe72b4c55ee6" +
                "de3ac808acb4bdb4c88732aee95f41aa" +
                "9482ed1fc0eeb9cafc4984625ccfc23f65032149e0e144ada024181535a0f38e" +
                "eb9fcff3c2c947dae69b4c634573a81c").hexToBytes(),
            peerPublicKey = ("04" +
                "e558dbef53eecde3d3fccfc1aea08a89a987475d12fd950d83cfa41732bc509d" +
                "0d1ac43a0336def96fda41d0774a3571" +
                "dcfbec7aacf3196472169e838430367f66eebe3c6e70c416dd5f0c68759dd1ff" +
                "f83fa40142209dff5eaad96db9e6386c").hexToBytes(),
            sharedSecret = ("11187331c279962d93d604243fd592cb9d0a926f422e47187521287e7156c5c4" +
                "d603135569b9e9d09cf5d4a270f59746").hexToBytes(),
            source = "RFC 5903 §8.2",
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
        SignatureAlgorithm.ES256 -> listOf(
            Signature(
                publicKey = ("04" +
                    "60fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6" +
                    "7903fe1008b8bc99a41ae9e95628bc64f2f1b20c2d7e9f5177a3c294d4462299").hexToBytes(),
                message = "sample".encodeToByteArray(),
                signature = ("efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716" +
                    "f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8").hexToBytes(),
                isValid = true,
                source = "RFC 6979 §A.2.5 (SHA-256, \"sample\")",
            ),
        )
        SignatureAlgorithm.ES384 -> listOf(
            Signature(
                publicKey = ("04" +
                    "ec3a4e415b4e19a4568618029f427fa5da9a8bc4ae92e02e06aae5286b300c64" +
                    "def8f0ea9055866064a254515480bc13" +
                    "8015d9b72d7d57244ea8ef9ac0c621896708a59367f9dfb9f54ca84b3f1c9db1" +
                    "288b231c3ae0d4fe7344fd2533264720").hexToBytes(),
                message = "sample".encodeToByteArray(),
                signature = ("94edbb92a5ecb8aad4736e56c691916b3f88140666ce9fa73d64c4ea95ad133c" +
                    "81a648152e44acf96e36dd1e80fabe46" +
                    "99ef4aeb15f178cea1fe40db2603138f130e740a19624526203b6351d0a3a94f" +
                    "a329c145786e679e7b82c71a38628ac8").hexToBytes(),
                isValid = true,
                source = "RFC 6979 §A.2.6 (SHA-384, \"sample\")",
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
