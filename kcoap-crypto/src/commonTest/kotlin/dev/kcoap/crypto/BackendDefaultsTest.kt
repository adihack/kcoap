package dev.kcoap.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A backend that implements only the required symmetric primitives, as an OSCORE-only deployment
 * might: everything else must fail clearly with [UnsupportedAlgorithmException].
 */
class BackendDefaultsTest {
    private val minimal = object : CryptoBackend {
        override suspend fun randomBytes(size: Int): ByteArray = ByteArray(size)
        override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray = ByteArray(algorithm.outputLength)
        override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray = ByteArray(algorithm.outputLength)
        override suspend fun aesBlockCipher(key: ByteArray): AesBlockCipher = AesBlockCipher { it.copyOf() }
    }

    @Test
    fun aeadDefaultsToCommonCodeCcm() = runTest {
        val ccm = minimal.aead(AeadAlgorithm.AES_CCM_16_64_128, ByteArray(16))
        assertEquals(8, ccm.tagLength)
        assertEquals(13, ccm.nonceLength)
        assertFailsWith<IllegalArgumentException> { minimal.aead(AeadAlgorithm.AES_CCM_16_64_128, ByteArray(15)) }
        assertFailsWith<UnsupportedAlgorithmException> { minimal.aead(AeadAlgorithm.A128GCM, ByteArray(16)) }
    }

    @Test
    fun asymmetricOperationsAreUnsupportedByDefault() = runTest {
        val key = SoftwarePrivateKey(KeyType.X25519, ByteArray(32))
        assertFailsWith<UnsupportedAlgorithmException> { minimal.generateKeyPair(KeyType.X25519) }
        assertFailsWith<UnsupportedAlgorithmException> { minimal.keyAgreement(key, ByteArray(32)) }
        assertFailsWith<UnsupportedAlgorithmException> { minimal.sign(SignatureAlgorithm.EDDSA, key, ByteArray(0)) }
        assertFailsWith<UnsupportedAlgorithmException> { minimal.verify(SignatureAlgorithm.EDDSA, ByteArray(32), ByteArray(0), ByteArray(64)) }
        assertFailsWith<UnsupportedAlgorithmException> { minimal.encapsulate(KemAlgorithm.ML_KEM_768, ByteArray(1184)) }
        assertFailsWith<UnsupportedAlgorithmException> { minimal.decapsulate(key, ByteArray(1088)) }
    }

    @Test
    fun importKeepsKeysInMemory() = runTest {
        val pair = minimal.importKeyPair(KeyType.ED25519, privateKey = ByteArray(32) { 1 }, publicKey = ByteArray(32) { 2 })
        assertEquals(KeyType.ED25519, pair.type)
        assertContentEquals(ByteArray(32) { 2 }, pair.publicKey)
        assertContentEquals(ByteArray(32) { 1 }, (pair.privateKey as SoftwarePrivateKey).encoded())
        assertFailsWith<IllegalArgumentException> { minimal.importKeyPair(KeyType.P256, ByteArray(32), ByteArray(64)) }
        val encapsulation = KemEncapsulation(ByteArray(768), ByteArray(32))
        assertEquals("KemEncapsulation(768-byte ciphertext)", encapsulation.toString())
    }

    @Test
    fun registryLookups() {
        assertEquals(HashAlgorithm.SHAKE256, HashAlgorithm.fromCoseId(-45))
        assertNull(HashAlgorithm.fromCoseId(0))
        assertEquals(EcdhCurve.X25519, EcdhCurve.fromCoseId(4))
        assertNull(EcdhCurve.fromCoseId(99))
        assertEquals(SignatureAlgorithm.ML_DSA_44, SignatureAlgorithm.fromCoseId(-48))
        assertNull(SignatureAlgorithm.fromCoseId(0))
        assertEquals(AeadAlgorithm.A256GCM, AeadAlgorithm.fromCoseId(3))
        assertNull(AeadAlgorithm.fromCoseId(-1))
        assertEquals(32, KemAlgorithm.ML_KEM_1024.sharedSecretLength)
        assertEquals(KeyType.P256, EcdhCurve.P_256.keyType)
        assertEquals("bad chain", CertificateValidationException("bad chain").message)
    }
}
