package dev.kcoap.coap.stack

import dev.kcoap.log.Logger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Notices when [CoapEndpointConfig.timeSource] does not advance with the clock of the dispatcher the
 * endpoint runs on. Timers read "now" from the time source and wait with the dispatcher's delays, so the
 * two must be one clock (architecture rule 5). When they are not, such as `TimeSource.Monotonic` under
 * `runTest`, the timer loops spin through virtual time or stall, and retransmissions disagree with request
 * timeouts. A wait that ended by timeout but moved the time source by less than half of it, several times
 * in a row, gets one warning. Correctly configured timers never fire early, so it never warns then.
 *
 * Shared by the endpoint's timer loop and those of its connections; safe from any thread.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ClockCheck(private val log: Logger) {
    private val strikes = AtomicInt(0)
    private val warned = AtomicBoolean(false)

    /** A timer loop waited [waited] on the dispatcher and was not woken; the time source moved [elapsed]. */
    fun afterTimeout(waited: Duration, elapsed: Duration) {
        if (waited < MIN_WAIT) return
        if (elapsed * 2 >= waited) {
            strikes.store(0)
            return
        }
        if (strikes.addAndFetch(1) >= STRIKES && warned.compareAndSet(false, true)) {
            log.warn {
                "The endpoint's timeSource does not advance with its dispatcher's clock: a timer waited $waited, " +
                    "the time source moved $elapsed. Pass the dispatcher's time source in CoapEndpointConfig.timeSource; " +
                    "under runTest that is testScheduler.timeSource."
            }
        }
    }

    private companion object {
        const val STRIKES = 3

        /** Shorter waits are dominated by scheduling noise. */
        val MIN_WAIT = 10.milliseconds
    }
}
