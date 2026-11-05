package dev.kcoap.testkit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Concurrency stress tests on real threads. Everything runs on [Dispatchers.Default]: a thread pool on the
 * JVM and Kotlin/Native, the single event loop on JS and Wasm. The same common test therefore races
 * threads where the platform has them, and interleaves at suspension points where it does not.
 *
 * Every run is bounded: a fixed number of iterations and a timeout in real time, so a deadlock or a lost
 * wakeup fails the test instead of hanging the build. Call it from `runTest`. It leaves virtual time for
 * its duration, so endpoints under stress must use the real clock (`TimeSource.Monotonic`, the default),
 * not `testScheduler.timeSource`. Uncaught exceptions in [StressScope.background] still reach the test's
 * exception handler and fail the test.
 *
 * ```
 * @Test
 * fun closeRacesRequests() = runTest {
 *     Stress.run(iterations = 50) {
 *         val client = CoapClient(network.attach(), background)
 *         Stress.race({ client.get(uri) }, { client.close() })
 *     }
 * }
 * ```
 */
public object Stress {
    /** Iterations of [run] unless the test asks for others. */
    public const val DEFAULT_ITERATIONS: Int = 100

    /** Iterations of [run] in progress at the same time unless the test asks for another number. */
    public const val DEFAULT_PARALLELISM: Int = 4

    /** How long a whole [run] may take in real time unless the test allows another limit. */
    public val DEFAULT_TIMEOUT: Duration = 30.seconds

    /**
     * Runs [body] [iterations] times on [Dispatchers.Default], [parallelism] iterations at a time. Each
     * iteration gets a [StressScope] with its number and a [StressScope.background] scope that is
     * cancelled, and joined, when the iteration ends.
     *
     * An iteration that ends with a [CancellationException] of its own, such as the
     * `TimeoutCancellationException` of a `withTimeout` in [body], fails the run: a cancelled coroutine does
     * not fail its parent, so otherwise the run would succeed with that iteration's checks never made.
     *
     * @throws AssertionError when the iterations do not finish within [timeout], or an iteration was
     *   cancelled from inside.
     * @throws IllegalArgumentException when a count is not positive or [timeout] is not positive.
     */
    public suspend fun run(
        iterations: Int = DEFAULT_ITERATIONS,
        parallelism: Int = DEFAULT_PARALLELISM,
        timeout: Duration = DEFAULT_TIMEOUT,
        body: suspend StressScope.() -> Unit,
    ) {
        require(iterations > 0) { "Iterations must be positive: $iterations" }
        require(parallelism > 0) { "Parallelism must be positive: $parallelism" }
        require(timeout.isPositive()) { "Timeout must be positive: $timeout" }
        withContext(Dispatchers.Default) {
            val finished = withTimeoutOrNull(timeout) {
                val indices = Channel<Int>(Channel.UNLIMITED)
                repeat(iterations) { indices.trySend(it) }
                indices.close()
                coroutineScope {
                    repeat(minOf(parallelism, iterations)) {
                        launch { for (index in indices) iteration(index, body) }
                    }
                }
            }
            if (finished == null) throw AssertionError("Stress run of $iterations iterations did not finish within $timeout")
        }
    }

    /**
     * Starts every action at once on [Dispatchers.Default] and waits for all of them. The result of each
     * action is in the same position of the returned list, a failure as [Result.failure], so a test can
     * check which side of a race won and that the loser failed the way it should.
     */
    public suspend fun <T> race(vararg actions: suspend () -> T): List<Result<T>> = coroutineScope {
        val start = CompletableDeferred<Unit>()
        val running = actions.map { action ->
            async(Dispatchers.Default) {
                start.await()
                try {
                    Result.success(action())
                } catch (e: Throwable) {
                    // A failure of the action is a result; the cancellation of the race itself is not.
                    currentCoroutineContext().ensureActive()
                    Result.failure(e)
                }
            }
        }
        start.complete(Unit)
        running.awaitAll()
    }

    private suspend fun iteration(index: Int, body: suspend StressScope.() -> Unit) {
        val context = currentCoroutineContext()
        val backgroundJob = SupervisorJob(context[Job])
        try {
            coroutineScope { StressScope(index, this, CoroutineScope(context + backgroundJob)).body() }
        } catch (e: CancellationException) {
            // The run itself is still going, so the cancellation came from inside the iteration.
            if (currentCoroutineContext().isActive) throw AssertionError("Iteration $index was cancelled: ${e.message}", e)
            throw e
        } finally {
            withContext(NonCancellable) { backgroundJob.cancelAndJoin() }
        }
    }
}

/**
 * One iteration of [Stress.run]: a [CoroutineScope] whose coroutines the iteration waits for, the
 * iteration's number, and a [background] scope for coroutines that outlive the body, such as those of an
 * endpoint, like `runTest`'s `backgroundScope`.
 */
public class StressScope internal constructor(
    /** The iteration, counted from 0. */
    public val iteration: Int,
    scope: CoroutineScope,
    /** Cancelled, and joined, when the iteration ends; a failure in it does not cancel the iteration. */
    public val background: CoroutineScope,
) : CoroutineScope by scope
