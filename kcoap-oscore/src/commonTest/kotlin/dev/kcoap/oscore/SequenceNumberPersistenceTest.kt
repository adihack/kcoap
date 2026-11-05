package dev.kcoap.oscore

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RFC 8613 Appendix B.1.1: a failed or cancelled write to the [SequenceNumberStore] never lets a
 * restarted context use a number twice.
 */
class SequenceNumberPersistenceTest {
    private val crypto = DefaultCryptoBackend()
    private val secret = ByteArray(16) { (it + 1).toByte() }
    private val parameters = SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2))

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
    fun aFailedWriteHandsOutNoNumberAndIsRetried() = runTest {
        for (failure in FlakyStore.Failure.entries) {
            val store = FlakyStore()
            val context = SecurityContext.derive(parameters, crypto, store, persistInterval = 5)
            val handedOut = context.take(5).toMutableList()

            store.failNext = failure
            assertFailsWith<StorageFullException>("$failure") { context.takeSequenceNumber() }
            assertEquals(5, context.peekSequenceNumber(), "$failure: the number was not handed out")

            handedOut += context.take(3)
            assertEquals(listOf(5L, 10, 10), store.writes, "$failure: the next take wrote the same limit again")
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
        assertEquals(listOf(5L, 10, 10, 10, 10), store.writes, "every take at the limit tries to write")

        store.failAlways = false
        assertEquals(5, context.takeSequenceNumber(), "once the store recovers, numbers continue")
        assertRestartReusesNothing(store, handedOut + 5, "recovered")
    }
}
