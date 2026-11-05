package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 8613 Appendix B.1.2: which contexts require the Echo freshness proof before they trust their replay
 * window, under each [FreshnessPolicy].
 */
class FreshnessPolicyTest {
    private val crypto = DefaultCryptoBackend()
    private val peer = Endpoint.Udp("192.0.2.1", 5683)
    private val secret = ByteArray(16) { (it * 3).toByte() }
    private val clientParameters = SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b))
    private val serverParameters = SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a))

    /** A store that stands in for a durable one, such as a file: any store but the ephemeral one. */
    private class DurableStore : SequenceNumberStore {
        private val values = mutableMapOf<String, Long>()
        override suspend fun load(contextName: String): Long? = values[contextName]
        override suspend fun store(contextName: String, limit: Long) {
            values[contextName] = limit
        }
    }

    /** Whether [server] answers a client's first request with a challenge instead of accepting it. */
    private suspend fun challengesFirstRequest(server: SecurityContext): Boolean {
        val client = OscoreClient(ephemeralContext(clientParameters, crypto))
        val request = client.protectRequest(CoapMessage.request(CoapCode.GET) { options { uriPath("x") } }, peer)!!
        val verified = OscoreServer(InMemoryContextRepository().apply { add(server) }).unprotectRequest(request.message, peer)!!
        return verified.challenge != null
    }

    @Test
    fun autoTrustsANewContextWithTheEphemeralStore() = runTest {
        val server = SecurityContext.derive(serverParameters, crypto, EphemeralSequenceNumberStore())
        assertFalse(challengesFirstRequest(server), "keys new in this process run: nothing can be a replay")
    }

    @Test
    fun autoChallengesAContextRestoredFromItsStore() = runTest {
        val store = EphemeralSequenceNumberStore().apply { store(serverParameters.name, 128) }
        val server = SecurityContext.derive(serverParameters, crypto, store)
        assertEquals(128, server.peekSequenceNumber(), "resumes from the stored value (Appendix B.1.1)")
        assertTrue(challengesFirstRequest(server), "the replay window was not restored")
    }

    @Test
    fun autoChallengesAContextDerivedAgainFromTheSameStore() = runTest {
        val store = EphemeralSequenceNumberStore()
        assertFalse(challengesFirstRequest(SecurityContext.derive(serverParameters, crypto, store)))
        // The first context took no sequence number (its response reused the request nonce), yet the
        // second one starts with an empty window that would accept that request again.
        assertTrue(challengesFirstRequest(SecurityContext.derive(serverParameters, crypto, store)))
    }

    @Test
    fun autoChallengesAProvisionedContextWithADurableStore() = runTest {
        val server = SecurityContext.derive(serverParameters, crypto, DurableStore())
        assertEquals(0, server.peekSequenceNumber())
        assertTrue(challengesFirstRequest(server), "an earlier run may have accepted requests without persisting anything")
    }

    @Test
    fun requireProofChallengesEvenANewContext() = runTest {
        val server = SecurityContext.derive(serverParameters, crypto, EphemeralSequenceNumberStore(), freshness = FreshnessPolicy.REQUIRE_PROOF)
        assertTrue(challengesFirstRequest(server))
    }

    @Test
    fun assumeFreshTrustsEvenARestoredContext() = runTest {
        val store = DurableStore().apply { store(serverParameters.name, 64) }
        val server = SecurityContext.derive(serverParameters, crypto, store, freshness = FreshnessPolicy.ASSUME_FRESH)
        assertEquals(64, server.peekSequenceNumber())
        assertFalse(challengesFirstRequest(server))
    }
}
