package dev.kcoap.coap.server

import kotlin.time.Duration
import kotlinx.coroutines.Job

/**
 * A Q-Block body of [QBlockServer] with one timer coroutine, which runs its state machine's next deadline:
 * [timer] fires at [timerAt]; [isDropped] once the body is gone, so a callback still on its way ignores it.
 * Guarded by [QBlockServer]'s lock.
 */
internal abstract class TimedBody {
    var timer: Job? = null
    var timerAt: Duration? = null
    var isDropped: Boolean = false
        private set

    /** Cancels the timer and marks the body gone. */
    fun drop() {
        isDropped = true
        timer?.cancel()
        timer = null
        timerAt = null
    }
}
