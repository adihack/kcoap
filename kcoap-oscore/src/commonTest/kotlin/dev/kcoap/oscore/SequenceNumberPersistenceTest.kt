package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.common.readUnsigned
import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RFC 8613 Appendix B.1.1: every Sender Sequence Number is durable in the [SequenceNumberStore] before
 * its nonce is used, and a failed or cancelled write never lets a restarted context use a number twice.
 */
class SequenceNumberPersistenceTest {
    private val crypto = DefaultCryptoBackend()
    private val secret = ByteArray(16) { (it + 1).toByte() }
    private val parameters = SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2))
    private val peer = Endpoint.Udp("192.0.2.1", 5683)

    private class StorageFullException : Exception("disk full")

    /** A store whose writes fail on demand, before or after the value became durable. */
    private class FlakyStore : SequenceNumberStore {
        enum class Failure { BEFORE_WRITE, AFTER_WRITE }

        val durable = mutableMapOf<String, Long>()
        val writes = mutableListOf<Long>()
        var failNext: Failure? = null
        var failAlways = false

        override suspend fun load(contextName: String): Long? = durable[contextName]

        override suspend fun store(contextName: String, limit: Long) {
            writes += limit
            val failure = failNext.also { failNext = null } ?: if (failAlways) Failure.BEFORE_WRITE else null
            if (failure == Failure.BEFORE_WRITE) throw StorageFullException()
            durable[contextName] = limit
            if (failure == Failure.AFTER_WRITE) throw StorageFullException()
        }
    }

    /** A store whose next write suspends until it is cancelled, without becoming durable. */
    private class StuckStore : SequenceNumberStore {
        val durable = mutableMapOf<String, Long>()
        var stuck: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun load(contextName: String): Long? = durable[contextName]

        override suspend fun store(contextName: String, limit: Long) {
            stuck?.let {
                entered.complete(Unit)
                it.await()
            }
            durable[contextName] = limit
        }
    }

    private suspend fun SecurityContext.take(count: Int): List<Long> = List(count) { takeSequenceNumber() }

    /** A restart from what [store] holds: the next numbers must all be new. */
    private suspend fun assertRestartReusesNothing(store: SequenceNumberStore, handedOut: List<Long>, label: String) {
        val restarted = SecurityContext.derive(parameters, crypto, store, persistInterval = 5)
        val next = restarted.take(20)
        assertTrue(next.none { it in handedOut }, "$label: restarted at ${next.first()} after handing out $handedOut")
    }

    @Test
    fun everyPartialIvIsDurableBeforeItsNonceIsUsed() = runTest {
        val events = mutableListOf<String>()
        lateinit var commonIv: ByteArray
        val store = object : SequenceNumberStore {
            private val inner = EphemeralSequenceNumberStore()
            override suspend fun load(contextName: String): Long? = inner.load(contextName)
            override suspend fun store(contextName: String, limit: Long) {
                inner.store(contextName, limit)
                events += "store $limit"
            }
        }
        // Records the Partial IV inside each nonce at the moment it is used: nonce XOR Common IV ends in it (§5.2).
        val recording = object : CryptoBackend by crypto {
            override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead {
                val inner = crypto.aead(algorithm, key)
                return object : Aead by inner {
                    override suspend fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                        val plain = ByteArray(nonce.size) { (nonce[it].toInt() xor commonIv[it].toInt()).toByte() }
                        events += "encrypt ${plain.copyOfRange(plain.size - 5, plain.size).readUnsigned()}"
                        return inner.encrypt(nonce, plaintext, associatedData)
                    }
                }
            }
        }
        val context = SecurityContext.derive(parameters, recording, store, persistInterval = 3)
        commonIv = context.commonIv
        val client = OscoreClient(context)
        repeat(7) { client.protectRequest(CoapMessage.request(CoapCode.GET) { options { uriPath("x") } }, peer) }

        assertEquals(
            listOf(
                "store 0", // derive records the new context
                "store 3", "encrypt 0", "encrypt 1", "encrypt 2",
                "store 6", "encrypt 3", "encrypt 4", "encrypt 5",
                "store 9", "encrypt 6",
            ),
            events,
        )
    }

    @Test
    fun serverPartialIvsAreDurableBeforeUse() = runTest {
        val store = FlakyStore()
        val serverParameters = SecurityContextParameters(secret, byteArrayOf(2), byteArrayOf(1))
        val server = SecurityContext.derive(
            serverParameters,
            crypto,
            store,
            persistInterval = 2,
            freshness = FreshnessPolicy.ASSUME_FRESH,
        )
        val client = ephemeralContext(parameters, crypto)
        val registration = OscoreClient(client).protectRequest(CoapMessage.request(CoapCode.GET) { options { observe(0) } }, peer)!!
        val verified = OscoreServer(InMemoryContextRepository().apply { add(server) }).unprotectRequest(registration.message, peer)!!
        for (value in 0L until 5L) {
            val notification = verified.protectResponse(CoapMessage.response(verified.message, CoapCode.CONTENT) { options { observe(value) } })
            val piv = OscoreOptionValue.decode(notification.options.oscore!!).partialIv ?: continue
            assertTrue(piv.readUnsigned() < store.durable.getValue(serverParameters.name), "Partial IV ${piv.readUnsigned()} was persisted first")
        }
        assertEquals(listOf(0L, 2, 4), store.writes, "the first response reuses the request nonce; four notifications take 0..3")
    }

    @Test
    fun aFailedWriteHandsOutNoNumberAndIsRetried() = runTest {
        for (failure in FlakyStore.Failure.entries) {
            val store = FlakyStore()
            val context = SecurityContext.derive(parameters, crypto, store, persistInterval = 5)
            val handedOut = context.take(5).toMutableList()

            store.failNext = failure
            assertFailsWith<StorageFullException>("$failure") { context.takeSequenceNumber() }
            assertEquals(5, context.peekSequenceNumber(), "$failure: the number was not handed out")

            handedOut += context.take(3)
            assertEquals(listOf(0L, 5, 10, 10), store.writes, "$failure: the next take wrote the same limit again")
            assertEquals((0L..7L).toList(), handedOut)
            assertRestartReusesNothing(store, handedOut, "$failure")
        }
    }

    @Test
    fun aCancelledWriteHandsOutNoNumberAndIsRetried() = runTest {
        val store = StuckStore()
        val context = SecurityContext.derive(parameters, crypto, store, persistInterval = 5)
        val handedOut = context.take(5).toMutableList()

        store.stuck = CompletableDeferred()
        val take = launch { handedOut += context.takeSequenceNumber() }
        store.entered.await()
        take.cancelAndJoin()
        assertEquals(5, context.peekSequenceNumber(), "the cancelled take handed out nothing")
        assertEquals(5, store.durable[parameters.name], "the cancelled write did not become durable")

        store.stuck = null
        handedOut += context.take(3)
        assertEquals((0L..7L).toList(), handedOut)
        assertEquals(10, store.durable[parameters.name], "the next take wrote again")
        assertRestartReusesNothing(store, handedOut, "cancelled")
    }

    @Test
    fun aStoreThatAlwaysFailsStopsEveryTakeAtTheLimit() = runTest {
        val store = FlakyStore()
        val context = SecurityContext.derive(parameters, crypto, store, persistInterval = 5)
        val handedOut = context.take(5)

        store.failAlways = true
        repeat(4) { assertFailsWith<StorageFullException>("take ${it + 1} past the limit") { context.takeSequenceNumber() } }
        assertEquals(5, context.peekSequenceNumber())
        assertEquals(listOf(0L, 5, 10, 10, 10, 10), store.writes, "every take at the limit tries to write")

        store.failAlways = false
        assertEquals(5, context.takeSequenceNumber(), "once the store recovers, numbers continue")
        assertRestartReusesNothing(store, handedOut + 5, "recovered")
    }

    @Test
    fun derivationRecordsANewContextOnlyOnce() = runTest {
        val store = FlakyStore()
        SecurityContext.derive(parameters, crypto, store)
        SecurityContext.derive(parameters, crypto, store)
        assertEquals(listOf(0L), store.writes, "the first derivation records the context; the second restores it")
        assertEquals(0, SecurityContext.derive(parameters, crypto, store).peekSequenceNumber())
    }

    @Test
    fun derivationRejectsValuesOutsideThePartialIvRange() = runTest {
        for (value in listOf(-1L, SecurityContext.MAX_SEQUENCE_NUMBER + 2)) {
            val store = EphemeralSequenceNumberStore().apply { store(parameters.name, value) }
            assertFailsWith<IllegalArgumentException>("$value") { SecurityContext.derive(parameters, crypto, store) }
        }
        // 2^40 itself is a valid limit: the context is restored exhausted (§7.2.1).
        val exhausted = EphemeralSequenceNumberStore().apply { store(parameters.name, SecurityContext.MAX_SEQUENCE_NUMBER + 1) }
        val context = SecurityContext.derive(parameters, crypto, exhausted)
        assertEquals(OscoreError.SEQUENCE_EXHAUSTED, assertFailsWith<OscoreException> { context.takeSequenceNumber() }.error)
    }
}
