package dev.kcoap.oscore

import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every test vector of RFC 8613 Appendix C, byte for byte. */
class Rfc8613VectorsTest {
    private val crypto = DefaultCryptoBackend()
    private val masterSecret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
    private val masterSalt = "9e7ca92223786340".hexToBytes()
    private val idContext = "37cbf3210017a2d3".hexToBytes()

    /** A store that makes a new context start at [sequence], as the vectors' Sender Sequence Number 20. */
    private fun startingAt(sequence: Long) = object : SequenceNumberStore {
        override suspend fun load(contextName: String): Long = sequence
        override suspend fun store(contextName: String, limit: Long) {}
    }

    private suspend fun context(
        senderId: String,
        recipientId: String,
        salt: Boolean,
        withIdContext: Boolean = false,
        sequence: Long = 0,
    ) = SecurityContext.derive(
        SecurityContextParameters(
            masterSecret = masterSecret,
            senderId = senderId.hexToBytes(),
            recipientId = recipientId.hexToBytes(),
            masterSalt = if (salt) masterSalt else ByteArray(0),
            idContext = if (withIdContext) idContext else null,
            includeIdContext = withIdContext,
        ),
        crypto,
        startingAt(sequence),
    )

    private class Derived(val senderKey: String, val recipientKey: String, val commonIv: String, val senderNonce: String, val recipientNonce: String)

    private suspend fun assertDerivation(context: SecurityContext, expected: Derived, label: String) {
        assertEquals(expected.senderKey, context.senderKey.toHex(), "$label Sender Key")
        assertEquals(expected.recipientKey, context.recipientKey.toHex(), "$label Recipient Key")
        assertEquals(expected.commonIv, context.commonIv.toHex(), "$label Common IV")
        assertEquals(expected.senderNonce, context.nonce(context.senderId, byteArrayOf(0)).toHex(), "$label sender nonce")
        assertEquals(expected.recipientNonce, context.nonce(context.recipientId, byteArrayOf(0)).toHex(), "$label recipient nonce")
    }

    /** C.1: key derivation with Master Salt. */
    @Test
    fun c1KeyDerivationWithMasterSalt() = runTest {
        val client = context(senderId = "", recipientId = "01", salt = true)
        assertEquals("8540f60a634b657910", SecurityContext.info(client.parameters, client.senderId, "Key", 16).toHex())
        assertEquals("854101f60a634b657910", SecurityContext.info(client.parameters, client.recipientId, "Key", 16).toHex())
        assertEquals("8540f60a6249560d", SecurityContext.info(client.parameters, ByteArray(0), "IV", 13).toHex())
        assertDerivation(
            client,
            Derived("f0910ed7295e6ad4b54fc793154302ff", "ffb14e093c94c9cac9471648b4f98710", "4622d4dd6d944168eefb54987c", "4622d4dd6d944168eefb54987c", "4722d4dd6d944169eefb54987c"),
            "C.1.1",
        )
        assertDerivation(
            context(senderId = "01", recipientId = "", salt = true),
            Derived("ffb14e093c94c9cac9471648b4f98710", "f0910ed7295e6ad4b54fc793154302ff", "4622d4dd6d944168eefb54987c", "4722d4dd6d944169eefb54987c", "4622d4dd6d944168eefb54987c"),
            "C.1.2",
        )
    }

    /** C.2: key derivation without Master Salt. */
    @Test
    fun c2KeyDerivationWithoutMasterSalt() = runTest {
        assertDerivation(
            context(senderId = "00", recipientId = "01", salt = false),
            Derived("321b26943253c7ffb6003b0b64d74041", "e57b5635815177cd679ab4bcec9d7dda", "be35ae297d2dace910c52e99f9", "bf35ae297d2dace910c52e99f9", "bf35ae297d2dace810c52e99f9"),
            "C.2.1",
        )
        assertDerivation(
            context(senderId = "01", recipientId = "00", salt = false),
            Derived("e57b5635815177cd679ab4bcec9d7dda", "321b26943253c7ffb6003b0b64d74041", "be35ae297d2dace910c52e99f9", "bf35ae297d2dace810c52e99f9", "bf35ae297d2dace910c52e99f9"),
            "C.2.2",
        )
    }

    /** C.3: key derivation with ID Context. */
    @Test
    fun c3KeyDerivationWithIdContext() = runTest {
        val client = context(senderId = "", recipientId = "01", salt = true, withIdContext = true)
        assertEquals("85404837cbf3210017a2d30a634b657910", SecurityContext.info(client.parameters, client.senderId, "Key", 16).toHex())
        assertEquals("85404837cbf3210017a2d30a6249560d", SecurityContext.info(client.parameters, ByteArray(0), "IV", 13).toHex())
        assertDerivation(
            client,
            Derived("af2a1300a5e95788b356336eeecd2b92", "e39a0c7c77b43f03b4b39ab9a268699f", "2ca58fb85ff1b81c0b7181b85e", "2ca58fb85ff1b81c0b7181b85e", "2da58fb85ff1b81d0b7181b85e"),
            "C.3.1",
        )
        assertDerivation(
            context(senderId = "01", recipientId = "", salt = true, withIdContext = true),
            Derived("e39a0c7c77b43f03b4b39ab9a268699f", "af2a1300a5e95788b356336eeecd2b92", "2ca58fb85ff1b81c0b7181b85e", "2da58fb85ff1b81d0b7181b85e", "2ca58fb85ff1b81c0b7181b85e"),
            "C.3.2",
        )
    }

    private suspend fun assertRequestVector(client: SecurityContext, unprotected: String, aad: String, protected: String, label: String) {
        val request = UdpMessageCodec.decode(unprotected.hexToBytes())
        assertEquals(aad, OscoreProtection.additionalData(client, client.senderId, byteArrayOf(0x14)).toHex(), "$label AAD")
        val (message, binding) = OscoreProtection.protectRequest(client, request)
        assertEquals("14", binding.requestPiv.toHex(), "$label Partial IV")
        assertEquals(protected, UdpMessageCodec.encode(message).toHex(), "$label protected request")
    }

    /** C.4: request with the C.1 context, Sender Sequence Number 20. */
    @Test
    fun c4ProtectRequest() = runTest {
        assertRequestVector(
            context(senderId = "", recipientId = "01", salt = true, sequence = 20),
            unprotected = "44015d1f00003974396c6f63616c686f737483747631",
            aad = "8368456e63727970743040488501810a40411440",
            protected = "44025d1f00003974396c6f63616c686f7374620914ff612f1092f1776f1c1668b3825e",
            label = "C.4",
        )
    }

    /** C.5: request with the C.2 context. */
    @Test
    fun c5ProtectRequest() = runTest {
        assertRequestVector(
            context(senderId = "00", recipientId = "01", salt = false, sequence = 20),
            unprotected = "440171c30000b932396c6f63616c686f737483747631",
            aad = "8368456e63727970743040498501810a4100411440",
            protected = "440271c30000b932396c6f63616c686f737463091400ff4ed339a5a379b0b8bc731fffb0",
            label = "C.5",
        )
    }

    /** C.6: request with the C.3 context, carrying the ID Context as 'kid context'. */
    @Test
    fun c6ProtectRequestWithKidContext() = runTest {
        assertRequestVector(
            context(senderId = "", recipientId = "01", salt = true, withIdContext = true, sequence = 20),
            unprotected = "44012f8eef9bbf7a396c6f63616c686f737483747631",
            aad = "8368456e63727970743040488501810a40411440",
            protected = "44022f8eef9bbf7a396c6f63616c686f73746b19140837cbf3210017a2d3ff72cd7273fd331ac45cffbe55c3",
            label = "C.6",
        )
    }

    /** The server side of C.4 and C.6: the protected requests decrypt to the original ones. */
    @Test
    fun serverVerifiesTheVectorRequests() = runTest {
        val cases = listOf(
            Triple(false, "44025d1f00003974396c6f63616c686f7374620914ff612f1092f1776f1c1668b3825e", "44015d1f00003974396c6f63616c686f737483747631"),
            Triple(true, "44022f8eef9bbf7a396c6f63616c686f73746b19140837cbf3210017a2d3ff72cd7273fd331ac45cffbe55c3", "44012f8eef9bbf7a396c6f63616c686f737483747631"),
        )
        for ((withIdContext, protected, unprotected) in cases) {
            val server = context(senderId = "01", recipientId = "", salt = true, withIdContext = withIdContext)
            val (decrypted, binding) = OscoreProtection.unprotectRequest(UdpMessageCodec.decode(protected.hexToBytes())) { _, _ -> server }
            assertEquals(unprotected, UdpMessageCodec.encode(decrypted).toHex())
            assertEquals("14", binding.requestPiv.toHex())
        }
    }

    /** C.7 (response reusing the request nonce) and C.8 (response with its own Partial IV 0). */
    @Test
    fun c7AndC8ProtectResponses() = runTest {
        val request = "44025d1f00003974396c6f63616c686f7374620914ff612f1092f1776f1c1668b3825e"
        val response = UdpMessageCodec.decode("64455d1f00003974ff48656c6c6f20576f726c6421".hexToBytes())
        val expected = mapOf(
            false to "64445d1f0000397490ffdbaad1e9a7e7b2a813d3c31524378303cdafae119106",
            true to "64445d1f00003974920100ff4d4c13669384b67354b2b6175ff4b8658c666a6cf88e",
        )
        for ((newPartialIv, protected) in expected) {
            val server = context(senderId = "01", recipientId = "", salt = true)
            val (_, binding) = OscoreProtection.unprotectRequest(UdpMessageCodec.decode(request.hexToBytes())) { _, _ -> server }
            val message = OscoreProtection.protectResponse(binding, response, newPartialIv)
            assertEquals(protected, UdpMessageCodec.encode(message).toHex(), if (newPartialIv) "C.8" else "C.7")

            // The client side of the same exchange.
            val client = context(senderId = "", recipientId = "01", salt = true, sequence = 20)
            val (_, clientBinding) = OscoreProtection.protectRequest(client, UdpMessageCodec.decode("44015d1f00003974396c6f63616c686f737483747631".hexToBytes()))
            val (decrypted, piv) = OscoreProtection.unprotectResponse(clientBinding, UdpMessageCodec.decode(protected.hexToBytes()))
            assertEquals("64455d1f00003974ff48656c6c6f20576f726c6421", UdpMessageCodec.encode(decrypted).toHex())
            if (newPartialIv) assertEquals("00", piv?.toHex()) else assertNull(piv)
        }
    }
}
