package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OscorePartsTest {
    private val secret = ByteArray(16) { it.toByte() }

    @Test
    fun optionValueEncodings() {
        // RFC 8613 Appendix C.4, C.5 and C.6 option values.
        assertEquals("0914", OscoreOptionValue(byteArrayOf(0x14), ByteArray(0)).encode().toHex())
        assertEquals("091400", OscoreOptionValue(byteArrayOf(0x14), byteArrayOf(0)).encode().toHex())
        assertEquals("19140837cbf3210017a2d3", OscoreOptionValue(byteArrayOf(0x14), ByteArray(0), "37cbf3210017a2d3".hexToBytes()).encode().toHex())
        assertEquals("", OscoreOptionValue().encode().toHex(), "C.7: nothing present, empty value")
        assertEquals("0100", OscoreOptionValue(partialIv = byteArrayOf(0)).encode().toHex(), "C.8")

        val decoded = OscoreOptionValue.decode("19140837cbf3210017a2d3aa".hexToBytes())
        assertEquals("14", decoded.partialIv?.toHex())
        assertEquals("37cbf3210017a2d3", decoded.kidContext?.toHex())
        assertEquals("aa", decoded.kid?.toHex())
        assertEquals("OscoreOptionValue(piv=14, kid=aa, kidContext=37cbf3210017a2d3)", decoded.toString())
        val empty = OscoreOptionValue.decode(ByteArray(0))
        assertNull(empty.partialIv)
        assertNull(empty.kid)
        assertNull(empty.kidContext)
    }

    @Test
    fun malformedOptionValues() {
        val bad = listOf(
            "20" to "reserved flag",
            "06" to "reserved Partial IV length",
            "02 01" to "truncated Partial IV",
            "10" to "kid context flag without length",
            "10 05 01" to "truncated kid context",
            "01 01 ff" to "trailing bytes without kid",
        )
        for ((hex, reason) in bad) {
            val error = assertFailsWith<OscoreException>(reason) { OscoreOptionValue.decode(hex.replace(" ", "").hexToBytes()) }
            assertEquals(OscoreError.DECODE_FAILED, error.error)
        }
        assertFailsWith<IllegalArgumentException> { OscoreOptionValue(partialIv = ByteArray(6)) }
        assertFailsWith<IllegalArgumentException> { OscoreOptionValue(kidContext = ByteArray(256)) }
    }

    @Test
    fun replayWindowFollowsRfc6347() {
        val window = ReplayWindow(32)
        assertTrue(window.accept(5))
        assertFalse(window.accept(5), "duplicate")
        assertTrue(window.accept(3), "older but inside the window")
        assertTrue(window.accept(40))
        assertTrue(window.isReplay(5), "fell out of the window")
        assertFalse(window.isReplay(20))
        assertTrue(window.accept(20))
        assertTrue(window.isReplay(20))
        assertTrue(window.accept(200), "a jump clears the history")
        assertFalse(window.isReplay(199))
        assertEquals(200, window.highest)
        assertFailsWith<IllegalArgumentException> { ReplayWindow(65) }
    }

    @Test
    fun partialIvEncoding() {
        assertEquals("00", OscoreProtection.encodePartialIv(0).toHex())
        assertEquals("14", OscoreProtection.encodePartialIv(20).toHex())
        assertEquals("0100", OscoreProtection.encodePartialIv(256).toHex())
        assertEquals("ffffffffff", OscoreProtection.encodePartialIv(SecurityContext.MAX_SEQUENCE_NUMBER).toHex())
    }

    /** RFC 8613 Appendix B.1.1: a restart continues after the persisted block, never reusing a number. */
    @Test
    fun sequenceNumbersSurviveRestarts() = runTest {
        val store = EphemeralSequenceNumberStore()
        val parameters = SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2))
        val first = SecurityContext.derive(parameters, DefaultCryptoBackend(), store, persistInterval = 10)
        repeat(3) { first.takeSequenceNumber() }
        assertEquals(3, first.peekSequenceNumber())
        assertEquals(10, store.load(parameters.name))

        val restarted = SecurityContext.derive(parameters, DefaultCryptoBackend(), store, persistInterval = 10)
        assertEquals(10, restarted.takeSequenceNumber())
        assertEquals(20, store.load(parameters.name))
        assertFailsWith<IllegalArgumentException> { SecurityContext.derive(parameters, DefaultCryptoBackend(), store, persistInterval = 0) }
        assertFailsWith<IllegalArgumentException>("more than the Partial IV range") {
            SecurityContext.derive(parameters, DefaultCryptoBackend(), store, persistInterval = SecurityContext.MAX_SEQUENCE_NUMBER + 2)
        }
    }

    @Test
    fun exhaustedSequenceNumbersStopTheContext() = runTest {
        val store = EphemeralSequenceNumberStore()
        val parameters = SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2))
        store.store(parameters.name, SecurityContext.MAX_SEQUENCE_NUMBER)
        val context = SecurityContext.derive(parameters, DefaultCryptoBackend(), store)
        assertEquals(SecurityContext.MAX_SEQUENCE_NUMBER, context.takeSequenceNumber())
        val error = assertFailsWith<OscoreException> { context.takeSequenceNumber() }
        assertEquals(OscoreError.SEQUENCE_EXHAUSTED, error.error)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, error.toMessageSecurityException().responseCode)
    }

    @Test
    fun parameterValidation() {
        assertFailsWith<IllegalArgumentException>("empty secret") { SecurityContextParameters(ByteArray(0), byteArrayOf(1), byteArrayOf(2)) }
        assertFailsWith<IllegalArgumentException>("Sender ID too long") { SecurityContextParameters(secret, ByteArray(8), byteArrayOf(2)) }
        assertFailsWith<IllegalArgumentException>("Recipient ID too long") { SecurityContextParameters(secret, byteArrayOf(1), ByteArray(8)) }
        assertFailsWith<IllegalArgumentException>("equal IDs") { SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException>("SHAKE HKDF") {
            SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2), hkdfAlgorithm = HashAlgorithm.SHAKE256)
        }
        assertFailsWith<IllegalArgumentException>("window") { SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2), replayWindowSize = 0) }
        assertFailsWith<IllegalArgumentException>("kid context without ID Context") {
            SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2), includeIdContext = true)
        }
        // AES-CCM-16-64-128 has a 13-byte nonce, so IDs may be 7 bytes; GCM's 12-byte nonce allows 6.
        SecurityContextParameters(secret, ByteArray(7), byteArrayOf(2))
        assertFailsWith<IllegalArgumentException> {
            SecurityContextParameters(secret, ByteArray(7), byteArrayOf(2), aeadAlgorithm = AeadAlgorithm.A128GCM)
        }
    }

    @Test
    fun descriptionsNeverShowSecrets() = runTest {
        val parameters = SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2), idContext = byteArrayOf(3))
        assertEquals("SecurityContextParameters(sid=01,rid=02,idc=03, AES_CCM_16_64_128)", parameters.toString())
        val context = ephemeralContext(parameters, DefaultCryptoBackend())
        assertEquals("SecurityContext(sid=01,rid=02,idc=03)", context.toString())
        assertEquals("sid=01,rid=02,idc=03", context.name)
        assertEquals(AeadAlgorithm.AES_CCM_16_64_128, context.aeadAlgorithm)
        assertContentEquals(secret, parameters.masterSecret())
        assertContentEquals(ByteArray(0), parameters.masterSalt())
        assertContentEquals(byteArrayOf(3), context.idContext)
        assertEquals("Decryption failed: tag", OscoreException(OscoreError.DECRYPTION_FAILED, "tag").message)
    }

    /** RFC 8613 §3.2.1 runs on copies of the Master Secret and Salt; they and the PRK are wiped afterwards. */
    @Test
    fun derivationWipesItsCopiesOfTheSecrets() = runTest {
        val default = DefaultCryptoBackend()
        val calls = mutableListOf<Pair<ByteArray, ByteArray>>()
        val recording = object : CryptoBackend by default {
            override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray {
                calls += key to data
                return default.hmac(algorithm, key, data)
            }
        }
        val salt = byteArrayOf(9, 8, 7)
        val parameters = SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2), masterSalt = salt)
        ephemeralContext(parameters, recording)

        val (saltCopy, secretCopy) = calls.first()
        val prk = calls[1].first
        assertTrue(saltCopy.all { it == 0.toByte() } && saltCopy.size == salt.size, "Master Salt copy wiped")
        assertTrue(secretCopy.all { it == 0.toByte() } && secretCopy.size == secret.size, "Master Secret copy wiped")
        assertTrue(prk.all { it == 0.toByte() }, "PRK wiped")
        assertTrue(calls.drop(1).all { (key, _) -> key === prk }, "every Expand step used the same PRK")
        assertContentEquals(secret, parameters.masterSecret(), "the parameters keep their own copy")
        assertContentEquals(salt, parameters.masterSalt())
    }

    /** The keys the backend got for AEADs it then could not build belong to no context: they are wiped. */
    @Test
    fun aBackendThatCannotBuildTheAeadGetsItsKeysWiped() = runTest {
        val default = DefaultCryptoBackend()
        val keys = mutableListOf<ByteArray>()
        // The Sender AEAD is built, the Recipient AEAD fails, as when a platform lacks the algorithm.
        val failing = object : CryptoBackend by default {
            override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
                keys += key
                if (keys.size == 2) throw UnsupportedAlgorithmException(algorithm.name)
                return default.aead(algorithm, key)
            }
        }
        val parameters = SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2))
        assertFailsWith<UnsupportedAlgorithmException> { ephemeralContext(parameters, failing) }

        val expected = SecurityContext.deriveKeys(parameters, default)
        assertEquals(listOf(expected.senderKey.size, expected.recipientKey.size), keys.map { it.size })
        assertFalse(expected.senderKey.all { it == 0.toByte() }, "the real keys are not zero")
        assertTrue(keys.all { key -> key.all { it == 0.toByte() } }, "Sender and Recipient Key wiped")
    }

    /** A backend failing during HKDF-Expand (§3.2.1) fails the derivation with its own exception. */
    @Test
    fun aFailingKeyExpansionReachesTheCaller() = runTest {
        val default = DefaultCryptoBackend()
        val parameters = SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2))
        // HMAC call 1 is the Extract; 2, 3 and 4 expand the Sender Key, the Recipient Key and the Common IV.
        for (failingCall in 2..4) {
            var calls = 0
            var aeads = 0
            val failing = object : CryptoBackend by default {
                override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray {
                    if (++calls == failingCall) throw IllegalStateException("HMAC $failingCall failed")
                    return default.hmac(algorithm, key, data)
                }

                override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
                    aeads++
                    return default.aead(algorithm, key)
                }
            }
            val error = assertFailsWith<IllegalStateException> { ephemeralContext(parameters, failing) }
            assertEquals("HMAC $failingCall failed", error.message)
            assertEquals(0, aeads, "no AEAD was built after a failed expansion")
        }
    }

    /** A GCM context works end to end too; only the nonce length differs. */
    @Test
    fun otherAeadAlgorithms() = runTest {
        val crypto = DefaultCryptoBackend()
        // ChaCha20/Poly1305 where the platform has it (WebCrypto does not).
        val candidates = listOf(AeadAlgorithm.A128GCM, AeadAlgorithm.CHACHA20_POLY1305, AeadAlgorithm.AES_CCM_16_128_128)
        for (algorithm in candidates.filter { crypto.supports(it) }) {
            val client = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2), aeadAlgorithm = algorithm), crypto)
            val server = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(2), byteArrayOf(1), aeadAlgorithm = algorithm), crypto)
            assertEquals(algorithm, client.aeadAlgorithm)
            val request = dev.kcoap.coap.CoapMessage.request(CoapCode.GET) { options { uriPath("x") } }
            val (protected, _) = OscoreProtection.protectRequest(client, request)
            val decrypted = OscoreProtection.unprotectRequest(protected) { _, _ -> server }.message
            assertEquals(request, decrypted, algorithm.name)
        }
    }
}
