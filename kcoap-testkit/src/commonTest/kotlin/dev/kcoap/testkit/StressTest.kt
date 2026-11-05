package dev.kcoap.testkit

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

@OptIn(ExperimentalAtomicApi::class)
class StressTest {
    @Test
    fun everyIterationRunsExactlyOnce() = runTest {
        val lock = Mutex()
        val seen = mutableListOf<Int>()
        Stress.run(iterations = 50, parallelism = 8) {
            yield()
            lock.withLock { seen += iteration }
        }
        assertEquals((0 until 50).toList(), seen.sorted())
    }

    @Test
    fun itRunsOnRealTimeNotOnTheTestScheduler() = runTest {
        Stress.run(iterations = 2) { delay(5.milliseconds) }
        assertEquals(0, testScheduler.currentTime, "virtual time did not move")
    }

    @Test
    fun aFailingIterationFailsTheRun() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            Stress.run(iterations = 10) { check(iteration != 3) { "iteration 3 failed" } }
        }
        assertEquals("iteration 3 failed", error.message)
    }

    @Test
    fun aHangingIterationFailsWithinTheTimeout() = runTest {
        val error = assertFailsWith<AssertionError> {
            Stress.run(iterations = 3, timeout = 50.milliseconds) { awaitCancellation() }
        }
        assertTrue("did not finish within" in error.message!!)
    }

    @Test
    fun anIterationThatTimesOutFailsTheRun() = runTest {
        // A TimeoutCancellationException is a CancellationException, which would end only its worker quietly.
        val error = assertFailsWith<AssertionError> {
            Stress.run(iterations = 3) { withTimeout(1.milliseconds) { awaitCancellation() } }
        }
        assertTrue("was cancelled" in error.message!!, error.message)
    }

    @Test
    fun theBackgroundScopeEndsWithItsIteration() = runTest {
        // Counted without a lock: a cancelled coroutine cannot take a contended Mutex in its finally.
        val cancelled = AtomicInt(0)
        Stress.run(iterations = 5, parallelism = 2) {
            val started = CompletableDeferred<Unit>()
            background.launch {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.addAndFetch(1)
                }
            }
            started.await()
        }
        assertEquals(5, cancelled.load())
    }

    @Test
    fun aRaceStartsEveryActionAndKeepsEachOutcome() = runTest {
        val results = Stress.race(
            { "first" },
            { error("second failed") },
            {
                delay(1.milliseconds)
                "third"
            },
        )
        assertEquals("first", results[0].getOrThrow())
        assertEquals("second failed", results[1].exceptionOrNull()?.message)
        assertEquals("third", results[2].getOrThrow())
    }

    @Test
    fun invalidBoundsAreRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { Stress.run(iterations = 0) {} }
        assertFailsWith<IllegalArgumentException> { Stress.run(parallelism = 0) {} }
        assertFailsWith<IllegalArgumentException> { Stress.run(timeout = Duration.ZERO) {} }
    }
}
