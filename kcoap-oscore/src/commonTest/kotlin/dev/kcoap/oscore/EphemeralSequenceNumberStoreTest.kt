package dev.kcoap.oscore

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.testkit.Stress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EphemeralSequenceNumberStore]: values per context name that never go back, and safe to share between
 * contexts that run in parallel. The stress tests run [WORKERS] workers at once with kcoap-testkit's [Stress],
 * on `Dispatchers.Default`, which is multithreaded on the JVM and Native and single-threaded on JS and Wasm,
 * within a bound in real time, so a deadlock fails the test instead of hanging it.
 */
class EphemeralSequenceNumberStoreTest {
    private val crypto = DefaultCryptoBackend()
    private val secret = ByteArray(16) { (it + 7).toByte() }

    /** Runs [block] once per worker, all [WORKERS] of them at the same time. */
    private suspend fun everyWorker(block: suspend (worker: Int) -> Unit) =
        Stress.run(iterations = WORKERS, parallelism = WORKERS) { block(iteration) }

    @Test
    fun keepsTheHighestValuePerContext() = runTest {
        val store = EphemeralSequenceNumberStore()
        assertNull(store.load("a"))
        store.store("a", 10)
        store.store("b", 3)
        store.store("a", 4)
        assertEquals(10, store.load("a"), "a value never goes back")
        assertEquals(3, store.load("b"))
        store.store("a", 11)
        assertEquals(11, store.load("a"))
        assertEquals("EphemeralSequenceNumberStore", store.toString())
    }

    @Test
    fun parallelWritersNeverLoseAValue() = runTest {
        val store = EphemeralSequenceNumberStore()
        everyWorker { worker ->
            for (limit in 1L..ROUNDS) {
                store.store("own-$worker", limit)
                assertEquals(limit, store.load("own-$worker"), "own-$worker")
                // Every worker also raises one shared name, so the check-and-raise races on the same entry.
                val shared = limit * WORKERS + worker
                store.store("shared", shared)
                assertTrue(store.load("shared")!! >= shared, "the shared value went back below $shared")
            }
        }
        repeat(WORKERS) { assertEquals(ROUNDS, store.load("own-$it"), "context own-$it") }
        assertEquals(ROUNDS * WORKERS + WORKERS - 1, store.load("shared"))
    }

    @Test
    fun contextsSharingTheStoreHandOutUniqueDurableNumbers() = runTest {
        val store = EphemeralSequenceNumberStore()
        val parameters = List(8) { SecurityContextParameters(secret, byteArrayOf(it.toByte()), byteArrayOf(0x7f)) }
        val contexts = parameters.map { SecurityContext.derive(it, crypto, store, persistInterval = 3) }
        val lock = Mutex()
        val taken = mutableListOf<Pair<Int, Long>>()
        everyWorker { worker ->
            val index = worker % contexts.size
            val numbers = List(TAKES) {
                val number = contexts[index].takeSequenceNumber()
                assertTrue(number < store.load(parameters[index].name)!!, "$number of ${parameters[index].name} was durable before use")
                worker to number
            }
            lock.withLock { taken += numbers }
        }
        for ((index, context) in contexts.withIndex()) {
            val numbers = taken.filter { (worker, _) -> worker % contexts.size == index }.map { it.second }.sorted()
            assertEquals((0L until TAKES * 4L).toList(), numbers, "${parameters[index].name}: every number once")
            assertEquals(TAKES * 4L, context.peekSequenceNumber())
        }
    }

    private companion object {
        const val WORKERS = 32
        const val ROUNDS = 500L
        const val TAKES = 100
    }
}
