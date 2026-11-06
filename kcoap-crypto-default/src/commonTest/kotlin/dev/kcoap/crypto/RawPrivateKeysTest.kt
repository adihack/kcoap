package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.backend.softwareKeyOf
import dev.kcoap.crypto.backend.withRawKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The copies of a private key that the default backend hands to the platform, or takes from it, are wiped
 * once the platform is done with them; the key itself keeps its material.
 */
class RawPrivateKeysTest {
    private val material = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun theCopyHandedToThePlatformIsWipedAfterUse() {
        val key = SoftwarePrivateKey(KeyType.X25519, material)
        var handed: ByteArray? = null
        val result = key.withRawKey { raw ->
            assertContentEquals(material, raw)
            handed = raw
            "done"
        }
        assertEquals("done", result)
        assertTrue(assertNotNull(handed).all { it == 0.toByte() })
        assertContentEquals(material, key.encoded())
    }

    @Test
    fun theCopyIsWipedWhenThePlatformFails() {
        val key = SoftwarePrivateKey(KeyType.P256, material)
        var handed: ByteArray? = null
        assertFailsWith<IllegalArgumentException> {
            key.withRawKey { raw ->
                handed = raw
                throw IllegalArgumentException("rejected by the platform")
            }
        }
        assertTrue(assertNotNull(handed).all { it == 0.toByte() })
        assertContentEquals(material, key.encoded())
    }

    @Test
    fun aHandleOfAnotherBackendHasNoRawKey() {
        val foreign = object : PrivateKey(KeyType.X25519) {}
        var ran = false
        assertFailsWith<IllegalArgumentException> { foreign.withRawKey { ran = true } }
        assertEquals(false, ran)
    }

    @Test
    fun aDestroyedKeyHandsOutNoCopy() {
        val key = SoftwarePrivateKey(KeyType.ED25519, material)
        key.destroy()
        var handed: ByteArray? = null
        assertFailsWith<IllegalStateException> { key.withRawKey { handed = it } }
        assertNull(handed)
    }

    @Test
    fun theEncodingOfAGeneratedKeyIsWipedOnceTheKeyHoldsItsOwnCopy() {
        val encoding = material.copyOf()
        val key = softwareKeyOf(KeyType.ED25519, encoding)
        assertTrue(encoding.all { it == 0.toByte() })
        assertEquals(KeyType.ED25519, key.type)
        assertContentEquals(material, key.encoded())
    }

    /** Wiping the copies leaves the key usable: it signs and agrees again and again, with the same results. */
    @Test
    fun keysStayUsableAfterTheBackendWipesItsCopies() = runTest {
        val backend = DefaultCryptoBackend()
        for (type in listOf(KeyType.X25519, KeyType.P256, KeyType.P384)) {
            val own = backend.generateKeyPair(type)
            val peer = backend.generateKeyPair(type)
            val first = backend.keyAgreement(own.privateKey, peer.publicKey)
            assertContentEquals(first, backend.keyAgreement(own.privateKey, peer.publicKey), type.name)
            assertContentEquals(first, backend.keyAgreement(peer.privateKey, own.publicKey), type.name)
        }
        for (algorithm in listOf(SignatureAlgorithm.EDDSA, SignatureAlgorithm.ES256, SignatureAlgorithm.ES384)) {
            val key = backend.generateKeyPair(algorithm.keyType)
            val data = "signed twice".encodeToByteArray()
            repeat(2) {
                val signature = backend.sign(algorithm, key.privateKey, data)
                assertTrue(backend.verify(algorithm, key.publicKey, data, signature), algorithm.name)
            }
        }
    }
}
