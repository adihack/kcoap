package dev.kcoap.coap.server

import dev.kcoap.coap.stack.BodyMode
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * What a [ServerObservation] keeps of its latest Q-Block2 body, the registration response or a notification
 * (RFC 9177 §4.5): how it goes ([mode]), when it started ([startedAt]) and whether it is done ([isDone]). Never
 * the body itself: [QBlockServer] holds a body only while it sends or is cached for the client's follow-ups,
 * so the observers of a server keep no representation beyond [QBlockServerConfig.maxCachedBodies] (RFC 7959
 * §7.1), however long they last.
 */
internal class OutstandingBody(val mode: BodyMode, val startedAt: Duration) {
    /** Whether every block went out (Confirmable ones acknowledged), or the body stopped; set once. */
    @Volatile
    var isDone: Boolean = false
        private set

    fun markDone() {
        isDone = true
    }

    /**
     * Whether the body is no longer the observation's outstanding notification: it [isDone] and, if
     * Non-confirmable, is at least [interval] old, as a single Non-confirmable notification keeps its NSTART slot
     * for [CoapServerConfig.nonConfirmableNotificationInterval] (RFC 7641 §4.5.1).
     */
    fun isSettled(now: Duration, interval: Duration): Boolean =
        isDone && (mode != BodyMode.NON || now >= startedAt + interval)

    override fun toString(): String = "OutstandingBody($mode, startedAt=$startedAt, done=$isDone)"
}
